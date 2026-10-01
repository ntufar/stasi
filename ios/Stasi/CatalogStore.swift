import Foundation
import Combine

// MARK: - Persistent lines/routes/stops catalog (port of Android Room cache:
// cached_lines / cached_routes / cached_stops / route_stops + incremental
// catalog sync throttled to once per 24h). Stored as one JSON file in
// Application Support so search and cached route maps work offline.

struct CatalogStop: Codable, Hashable {
    let descr: String
    let lat: Double?
    let lng: Double?
}

private struct CatalogData: Codable {
    var lines: [BusLine] = []
    var routes: [String: CatalogRoute] = [:]
    var stops: [String: CatalogStop] = [:]
    var routeStops: [String: [RouteStop]] = [:]
    var linesSyncedAt: Date?
    var fullSyncAt: Date?
}

enum LinesCatalogState: Equatable {
    case loading, ready, unavailable
}

@MainActor
final class CatalogStore: ObservableObject {
    static let shared = CatalogStore()

    private static let syncInterval: TimeInterval = 24 * 3600
    private static let maxSyncLines = 80
    private static let maxRoutesPerLine = 4

    @Published private(set) var lines: [BusLine] = []
    @Published private(set) var linesState: LinesCatalogState = .loading
    @Published private(set) var isSyncing = false
    /// Bumped whenever stops change so search can re-run.
    @Published private(set) var stopsRevision = 0

    private var data = CatalogData()
    private var lineNorms: [String: String] = [:]
    private var stopIndex: [(code: String, name: String, norm: String)]?
    private var saveTask: Task<Void, Never>?
    private var warmTask: Task<Bool, Never>?

    private let fileURL: URL = {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("catalog_v1.json")
    }()

    private init() {
        if let raw = try? Data(contentsOf: fileURL),
           let decoded = try? JSONDecoder().decode(CatalogData.self, from: raw) {
            data = decoded
        }
        publishLines()
        if !lines.isEmpty { linesState = .ready }
    }

    // MARK: Lookups

    func line(code: String) -> BusLine? { lines.first { $0.lineCode == code } }

    /// Lines whose internal code or public number equals `input` (case-insensitive).
    func linesByExactCodeOrId(_ input: String) -> [BusLine] {
        let q = input.trimmed
        guard !q.isEmpty else { return [] }
        return lines.filter {
            $0.lineCode.caseInsensitiveCompare(q) == .orderedSame
                || $0.lineId.caseInsensitiveCompare(q) == .orderedSame
        }
    }

    func route(code: String) -> CatalogRoute? { data.routes[code.trimmed] }

    func routes(forLine lineCode: String) -> [CatalogRoute] {
        data.routes.values.filter { $0.lineCode == lineCode }
            .sorted { ($0.type == 0 ? Int.max : $0.type, $0.routeCode) < ($1.type == 0 ? Int.max : $1.type, $1.routeCode) }
    }

    func stop(code: String) -> CatalogStop? { data.stops[code.trimmed] }

    func routeStops(_ routeCode: String) -> [RouteStop] { data.routeStops[routeCode.trimmed] ?? [] }

    // MARK: Writes

    func upsertLines(_ new: [BusLine]) {
        guard !new.isEmpty else { return }
        var byCode = Dictionary(lines.map { ($0.lineCode, $0) }, uniquingKeysWith: { a, _ in a })
        for l in new { byCode[l.lineCode] = l }
        data.lines = byCode.values.sorted { lineSortKey($0) < lineSortKey($1) }
        publishLines()
        scheduleSave()
    }

    func upsertRoutes(_ routes: [CatalogRoute]) {
        guard !routes.isEmpty else { return }
        for r in routes {
            // Keep a known RouteType when the new row (e.g. from webRoutesForStop) lacks one.
            let type = r.type != 0 ? r.type : (data.routes[r.routeCode]?.type ?? 0)
            let descr = r.descr.isEmpty ? (data.routes[r.routeCode]?.descr ?? "") : r.descr
            data.routes[r.routeCode] = CatalogRoute(routeCode: r.routeCode, lineCode: r.lineCode, descr: descr, type: type)
        }
        scheduleSave()
    }

    func storeRouteStops(_ routeCode: String, _ stops: [RouteStop]) {
        guard !stops.isEmpty else { return }
        data.routeStops[routeCode] = stops
        for s in stops {
            data.stops[s.stopCode] = CatalogStop(descr: s.description, lat: s.lat, lng: s.lng)
        }
        stopIndex = nil
        stopsRevision += 1
        scheduleSave()
    }

    /// Remember a stop name learned from getStopNameAndXY / getClosestStops.
    func rememberStop(code: String, descr: String, lat: Double? = nil, lng: Double? = nil) {
        let c = code.trimmed
        guard !c.isEmpty, !descr.trimmed.isEmpty else { return }
        let old = data.stops[c]
        if old?.descr == descr, old?.lat != nil || lat == nil { return }
        data.stops[c] = CatalogStop(descr: descr, lat: lat ?? old?.lat, lng: lng ?? old?.lng)
        stopIndex = nil
        scheduleSave()
    }

    // MARK: Search (Greek accent-insensitive + Greeklish)

    func searchLines(_ query: String, limit: Int = 30) -> [BusLine] {
        let q = query.trimmed
        guard q.count >= 2 else { return [] }
        let hits = lines.filter { matchesGreekQuery(haystackNorm: lineNorms[$0.lineCode] ?? "", query: q) }
        // Exact public number first, then prefix, then the rest in catalog order.
        func rank(_ l: BusLine) -> Int {
            if l.lineId.caseInsensitiveCompare(q) == .orderedSame { return 0 }
            if l.lineId.lowercased().hasPrefix(q.lowercased()) { return 1 }
            return 2
        }
        return Array(hits.enumerated().sorted { (rank($0.element), $0.offset) < (rank($1.element), $1.offset) }
            .map(\.element).prefix(limit))
    }

    func searchStops(_ query: String, limit: Int = 50) -> [(code: String, name: String)] {
        let q = query.trimmed
        guard q.count >= 2 else { return [] }
        if stopIndex == nil {
            stopIndex = data.stops.map { code, s in
                (code, s.descr, stopSearchNorm(stopCode: code, descr: s.descr))
            }.sorted { $0.name < $1.name }
        }
        return stopIndex!.lazy
            .filter { matchesGreekQuery(haystackNorm: $0.norm, query: q) }
            .prefix(limit)
            .map { ($0.code, $0.name) }
    }

    // MARK: Sync

    /// One lightweight webGetLines when the catalog has no lines (search / map resolution).
    @discardableResult
    func warmLinesIfEmpty() async -> Bool {
        if !lines.isEmpty {
            linesState = .ready
            return true
        }
        if let t = warmTask { return await t.value }
        linesState = .loading
        let t = Task { @MainActor () -> Bool in
            defer { warmTask = nil }
            if let fresh = try? await fetchLines(), !fresh.isEmpty {
                upsertLines(fresh)
                data.linesSyncedAt = Date()
            }
            linesState = lines.isEmpty ? .unavailable : .ready
            return !lines.isEmpty
        }
        warmTask = t
        return await t.value
    }

    /// webGetLines → routes → webGetStops for the first lines, at most once per 24h.
    func syncIncremental() async {
        if let at = data.fullSyncAt, Date().timeIntervalSince(at) < Self.syncInterval, !lines.isEmpty { return }
        guard !isSyncing else { return }
        isSyncing = true
        defer { isSyncing = false }
        guard let fresh = try? await fetchLines(), !fresh.isEmpty else { return }
        upsertLines(fresh)
        let now = Date()
        data.linesSyncedAt = now
        linesState = .ready
        for line in fresh.prefix(Self.maxSyncLines) {
            if Task.isCancelled { return }
            guard let rows = try? await OasaAPI.shared.webGetRoutes(lineCode: line.lineCode) else { continue }
            let routes = rows.compactMap { catalogRoute($0, fallbackLine: line.lineCode) }
            upsertRoutes(routes)
            for r in routes.prefix(Self.maxRoutesPerLine) {
                guard let stopRows = try? await OasaAPI.shared.webGetStops(routeCode: r.routeCode) else { continue }
                storeRouteStops(r.routeCode, mapWebStops(stopRows))
            }
        }
        data.fullSyncAt = now
        scheduleSave()
    }

    private func fetchLines() async throws -> [BusLine] {
        try await OasaAPI.shared.webGetLines().compactMap { j in
            let code = j.s("LineCode")
            guard !code.isEmpty else { return nil }
            return BusLine(lineCode: code, lineId: j.s("LineID"), lineDescr: j.s("LineDescr"))
        }
    }

    // MARK: Persistence

    private func publishLines() {
        lines = data.lines
        lineNorms = Dictionary(
            lines.map { ($0.lineCode, lineSearchNorm(lineId: $0.lineId, lineCode: $0.lineCode, descr: $0.lineDescr)) },
            uniquingKeysWith: { a, _ in a })
    }

    private func scheduleSave() {
        saveTask?.cancel()
        saveTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            guard !Task.isCancelled else { return }
            let snapshot = data
            let url = fileURL
            await Task.detached(priority: .utility) {
                if let encoded = try? JSONEncoder().encode(snapshot) {
                    try? encoded.write(to: url, options: .atomic)
                }
            }.value
        }
    }
}

private func lineSortKey(_ l: BusLine) -> (Int, String) {
    // Numeric public numbers in numeric order ("2" < "10"), then the rest.
    let digits = l.lineId.prefix { $0.isNumber }
    return (Int(digits) ?? Int.max, l.lineId)
}

func catalogRoute(_ j: JSONRow, fallbackLine: String) -> CatalogRoute? {
    let rc = j.s("RouteCode")
    guard !rc.isEmpty else { return nil }
    return CatalogRoute(
        routeCode: rc, lineCode: j.s("LineCode").nilIfBlank ?? fallbackLine,
        descr: j.s("RouteDescr"), type: j.int("RouteType") ?? 0)
}

func mapWebStops(_ rows: [JSONRow]) -> [RouteStop] {
    rows.compactMap { j in
        let code = j.s("StopCode")
        guard !code.isEmpty, let lat = j.double("StopLat"), let lng = j.double("StopLng") else { return nil }
        return RouteStop(stopCode: code, description: j.s("StopDescr"), lat: lat, lng: lng,
                         order: j.int("RouteStopOrder") ?? 0)
    }.sorted { $0.order < $1.order }
}
