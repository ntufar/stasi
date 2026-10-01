import Foundation

// MARK: - Repository (port of Android OasaRepository.kt)
// All OASA access goes through here. In-memory TTL caches match Android:
// timetable 6h, routes-for-stop / route stops / line-route info 24h, bus
// positions 15s, closest stops 5 min, arrivals 20s (dedupe only; the Arrivals
// screen always forces a refresh). Concurrent requests for the same key share
// one in-flight task.

struct RouteAtStop {
    let routeCode: String
    let lineCode: String
    let lineId: String
    let lineDescr: String
    let routeDescr: String
}

@MainActor
final class OasaRepository {
    static let shared = OasaRepository()

    private let api = OasaAPI.shared
    private var catalog: CatalogStore { CatalogStore.shared }

    private struct Entry<T> {
        let value: T
        let at: Date
        func fresh(_ ttl: TimeInterval) -> Bool { Date().timeIntervalSince(at) < ttl }
    }

    private static let timetableTTL: TimeInterval = 6 * 3600
    private static let dayTTL: TimeInterval = 24 * 3600
    private static let busTTL: TimeInterval = 15
    private static let closestTTL: TimeInterval = 5 * 60
    private static let arrivalsTTL: TimeInterval = 20

    private var timetableCache: [String: Entry<RouteDailyTimetable>] = [:]
    private var routesForStopCache: [String: Entry<[RouteAtStop]>] = [:]
    private var routeStopsCache: [String: Entry<[RouteStop]>] = [:]
    private var busCache: [String: Entry<[BusOnRoute]>] = [:]
    private var closestCache: [String: Entry<[NearbyStop]>] = [:]
    private var lineRouteInfoCache: [String: Entry<LineRouteInfo>] = [:]
    private var arrivalsCache: [String: Entry<[ArrivalDetail]>] = [:]
    private var originStopCache: [String: String] = [:]
    private var stopLabelCache: [String: String] = [:]
    private var inflight: [String: Task<Any, Error>] = [:]

    /// Share one in-flight task per key (Android uses a Mutex per key).
    private func coalesced<T>(_ key: String, _ op: @escaping @MainActor () async throws -> T) async throws -> T {
        if let running = inflight[key] {
            return try await running.value as! T
        }
        let task = Task<Any, Error> { @MainActor in try await op() }
        inflight[key] = task
        defer { inflight[key] = nil }
        return try await task.value as! T
    }

    // MARK: Stops

    func getRoutesForStop(_ stopCode: String) async throws -> [RouteAtStop] {
        let sc = stopCode.trimmed
        guard !sc.isEmpty else { return [] }
        if let e = routesForStopCache[sc], e.fresh(Self.dayTTL) { return e.value }
        return try await coalesced("rfs:\(sc)") { [self] in
            let rows = try await api.webRoutesForStop(stopCode: sc)
            let mapped = rows.compactMap { j -> RouteAtStop? in
                let rc = j.s("RouteCode")
                guard !rc.isEmpty else { return nil }
                return RouteAtStop(routeCode: rc, lineCode: j.s("LineCode"), lineId: j.s("LineID"),
                                   lineDescr: j.s("LineDescr"), routeDescr: j.s("RouteDescr"))
            }
            if !mapped.isEmpty {
                routesForStopCache[sc] = Entry(value: mapped, at: Date())
                // Teach the catalog these routes/lines so line codes resolve for enrichment.
                catalog.upsertRoutes(mapped.filter { !$0.lineCode.isEmpty }.map {
                    CatalogRoute(routeCode: $0.routeCode, lineCode: $0.lineCode, descr: $0.routeDescr, type: 0)
                })
                let unknownLines = mapped.filter { !$0.lineCode.isEmpty && catalog.line(code: $0.lineCode) == nil }
                catalog.upsertLines(unknownLines.map {
                    BusLine(lineCode: $0.lineCode, lineId: $0.lineId, lineDescr: $0.lineDescr)
                })
            }
            return mapped
        }
    }

    /// Stop name: catalog → memory → getStopNameAndXY → the code itself.
    func getStopLabel(_ stopCode: String) async -> String {
        let sc = stopCode.trimmed
        if let s = catalog.stop(code: sc)?.descr.nilIfBlank { return s }
        if let s = stopLabelCache[sc] { return s }
        let rows = (try? await coalesced("xy:\(sc)") { [self] in
            try await api.getStopNameAndXY(stopCode: sc)
        }) ?? []
        guard let row = rows.first, let name = (row.s("stop_descr").nilIfBlank ?? row.s("StopDescr").nilIfBlank) else {
            return sc
        }
        stopLabelCache[sc] = name
        catalog.rememberStop(
            code: sc, descr: name,
            lat: row.double("stop_lat") ?? row.double("StopLat"),
            lng: row.double("stop_lng") ?? row.double("StopLng"))
        return name
    }

    func cachedStopLabel(_ stopCode: String) -> String? {
        catalog.stop(code: stopCode)?.descr.nilIfBlank ?? stopLabelCache[stopCode.trimmed]
    }

    // MARK: Arrivals

    func getStopArrivalsSnapshot(_ stopCode: String, forceRefresh: Bool = false) async -> ArrivalSnapshot {
        let sc = stopCode.trimmed
        if !forceRefresh, let e = arrivalsCache[sc], e.fresh(Self.arrivalsTTL) {
            return ArrivalSnapshot(arrivals: e.value, fetchedAt: e.at)
        }
        do {
            return try await coalesced("arr:\(sc)") { [self] in
                async let routesTask = try? getRoutesForStop(sc)
                let rows = try await api.getStopArrivals(stopCode: sc)
                let routesAtStop = Dictionary(
                    (await routesTask ?? []).map { ($0.routeCode, $0) }, uniquingKeysWith: { a, _ in a })
                var seen = Set<String>()
                let mapped = rows.compactMap { mapArrival($0, routesAtStop: routesAtStop) }
                    .filter { seen.insert("\($0.routeCode)\u{0}\($0.vehCode)").inserted }
                    .sorted { $0.minutes < $1.minutes }
                let now = Date()
                arrivalsCache[sc] = Entry(value: mapped, at: now)
                return ArrivalSnapshot(arrivals: mapped, fetchedAt: now)
            }
        } catch {
            let stale = arrivalsCache[sc]
            return ArrivalSnapshot(arrivals: stale?.value ?? [], fetchedAt: stale?.at, isStale: true)
        }
    }

    /// Last snapshot in memory, for progressive display while a forced fetch runs.
    func cachedArrivals(_ stopCode: String) -> ArrivalSnapshot? {
        arrivalsCache[stopCode.trimmed].map { ArrivalSnapshot(arrivals: $0.value, fetchedAt: $0.at) }
    }

    private func mapArrival(_ j: JSONRow, routesAtStop: [String: RouteAtStop]) -> ArrivalDetail? {
        let route = j.s("route_code")
        guard !route.isEmpty else { return nil }
        let veh = j.s("veh_code").nilIfBlank ?? "?"
        let meta = routesAtStop[route]
        let routeRow = catalog.route(code: route)
        let dest = j.s("route_descr").nilIfBlank ?? routeRow?.descr.nilIfBlank ?? meta?.routeDescr.nilIfBlank ?? route
        let lineCode = j.s("line_code").nilIfBlank ?? routeRow?.lineCode.nilIfBlank ?? meta?.lineCode ?? ""
        let lineRow = lineCode.isEmpty ? nil : catalog.line(code: lineCode)
        let direction = routeRow?.descr.nilIfBlank ?? meta?.routeDescr ?? ""

        func label(_ num: String, _ name: String) -> String {
            switch (num.isEmpty, name.isEmpty) {
            case (false, false): return "\(num) · \(name)"
            case (false, true): return num
            case (true, false): return name
            default: return route
            }
        }
        let lineLabel: String
        if let lineRow {
            lineLabel = label(lineRow.lineId.nilIfBlank ?? lineRow.lineCode, direction.nilIfBlank ?? lineRow.lineDescr)
        } else if let meta {
            lineLabel = label(meta.lineId.nilIfBlank ?? meta.lineCode, direction.nilIfBlank ?? meta.lineDescr)
        } else {
            lineLabel = lineCode.nilIfBlank ?? route
        }
        return ArrivalDetail(routeCode: route, vehCode: veh, minutes: parseArrivalMinutes(j.s("btime2")),
                             destinationLabel: dest, lineLabel: lineLabel)
    }

    /// Origin hints, last-bus warnings and schedule-only rows (second pass after live minutes show).
    func enrichStopArrivals(_ stopCode: String, _ arrivals: [ArrivalDetail], routeHint: String?) async -> [ArrivalDetail] {
        if arrivals.isEmpty {
            return await addScheduleOnlyDepartures(stopCode, arrivals, routeHint: routeHint)
        }
        let withOrigin = await enrichArrivalsWithOrigin(stopCode, arrivals)
        let withWarning = await enrichArrivalsWithLastBusWarning(withOrigin)
        return await addScheduleOnlyDepartures(stopCode, withWarning, routeHint: routeHint)
    }

    func getRouteOriginStopCode(_ routeCode: String) async -> String? {
        let rc = routeCode.trimmed
        guard !rc.isEmpty else { return nil }
        if let o = originStopCache[rc] { return o }
        let cached = catalog.routeStops(rc).min { $0.order < $1.order }?.stopCode
        let origin: String?
        if let cached {
            origin = cached
        } else {
            origin = (try? await getRouteStops(rc))?.stops.min { $0.order < $1.order }?.stopCode
        }
        if let origin { originStopCache[rc] = origin }
        return origin
    }

    private func originRoutesNotStartingAtStop(_ stopCode: String, _ arrivals: [ArrivalDetail]) async -> [String: String] {
        let routes = Array(Set(arrivals.map(\.routeCode).filter { !$0.isEmpty }))
        return await withTaskGroup(of: (String, String?).self) { group in
            for r in routes {
                group.addTask { await (r, self.getRouteOriginStopCode(r)) }
            }
            var out: [String: String] = [:]
            for await (route, origin) in group {
                if let origin, origin != stopCode { out[route] = origin }
            }
            return out
        }
    }

    private func timetables(for lineCodes: [String]) async -> [String: RouteDailyTimetable] {
        await withTaskGroup(of: (String, RouteDailyTimetable).self) { group in
            for lc in Set(lineCodes) {
                group.addTask { await (lc, self.getRouteDailyTimetable(lc)) }
            }
            var out: [String: RouteDailyTimetable] = [:]
            for await (lc, tt) in group { out[lc] = tt }
            return out
        }
    }

    func enrichArrivalsWithOrigin(_ stopCode: String, _ arrivals: [ArrivalDetail]) async -> [ArrivalDetail] {
        guard !arrivals.isEmpty else { return arrivals }
        let originByRoute = await originRoutesNotStartingAtStop(stopCode, arrivals)
        guard !originByRoute.isEmpty else { return arrivals }
        let now = Date()

        // Phase 1: schedule-based origin departures.
        let lineByRoute = originByRoute.keys.reduce(into: [String: String]()) { acc, r in
            if let lc = lineCodeForRoute(r) { acc[r] = lc }
        }
        let tts = await timetables(for: Array(lineByRoute.values))
        var scheduleHint: [String: (clock: String, mins: Int)] = [:]
        for route in originByRoute.keys {
            guard let lc = lineByRoute[route], let tt = tts[lc],
                  let next = nextOriginScheduleStart(tt, now: now) else { continue }
            let mins = minutesUntilClock(now: now, minuteOfDay: next.minuteOfDay, nextDay: next.nextDay)
            guard mins >= 0, mins < arrivalMinutesUnknown else { continue }
            scheduleHint[route] = (formatMinuteOfDay(next.minuteOfDay), mins)
        }

        // Phase 2: live boardings at the origin stop for routes without schedule data.
        let unresolved = originByRoute.filter { scheduleHint[$0.key] == nil }
        let arrivalsAtOrigin = await withTaskGroup(of: (String, [ArrivalDetail]).self) { group in
            for origin in Set(unresolved.values) {
                group.addTask { await (origin, self.getStopArrivalsSnapshot(origin).arrivals) }
            }
            var out: [String: [ArrivalDetail]] = [:]
            for await (o, list) in group { out[o] = list }
            return out
        }

        var labels: [String: String] = [:]
        for origin in Set(originByRoute.values) {
            labels[origin] = await getStopLabel(origin)
        }
        return arrivals.map { arr in
            guard let origin = originByRoute[arr.routeCode] else { return arr }
            var a = arr
            if let hint = scheduleHint[arr.routeCode] {
                a.originDepartureMinutes = hint.mins
                a.originScheduleClock = hint.clock
                a.originStopDescription = labels[origin]
            } else if let mins = arrivalsAtOrigin[origin]?.filter({ $0.routeCode == arr.routeCode }).map(\.minutes).min() {
                a.originDepartureMinutes = mins
                a.originStopDescription = labels[origin]
            }
            return a
        }
    }

    func enrichArrivalsWithLastBusWarning(_ arrivals: [ArrivalDetail]) async -> [ArrivalDetail] {
        guard !arrivals.isEmpty else { return arrivals }
        let lineByRoute = Set(arrivals.map(\.routeCode)).reduce(into: [String: String]()) { acc, r in
            if let lc = lineCodeForRoute(r) { acc[r] = lc }
        }
        guard !lineByRoute.isEmpty else { return arrivals }
        let tts = await timetables(for: Array(lineByRoute.values))
        let now = Date()
        return arrivals.map { arr in
            guard let lc = lineByRoute[arr.routeCode], let tt = tts[lc], isLastBusApproaching(tt, now: now) else { return arr }
            var a = arr
            a.isLastBusWarning = true
            return a
        }
    }

    /// For routes serving the stop with no live bus, append a schedule-only row
    /// with the next origin departure (one per line; the hinted direction wins).
    func addScheduleOnlyDepartures(_ stopCode: String, _ arrivals: [ArrivalDetail], routeHint: String?) async -> [ArrivalDetail] {
        let liveRoutes = Set(arrivals.map(\.routeCode).filter { !$0.isEmpty })
        guard let routesAtStop = try? await getRoutesForStop(stopCode) else { return arrivals }
        let hint = routeHint?.nilIfBlank
        let ordered = hint == nil ? routesAtStop
            : routesAtStop.filter { $0.routeCode == hint } + routesAtStop.filter { $0.routeCode != hint }
        let candidates = ordered.filter { !liveRoutes.contains($0.routeCode) && !$0.lineCode.isEmpty }
        guard !candidates.isEmpty else { return arrivals }

        let tts = await timetables(for: candidates.map(\.lineCode))
        let originByRoute = await withTaskGroup(of: (String, String?).self) { group in
            for rc in Set(candidates.map(\.routeCode)) {
                group.addTask { await (rc, self.getRouteOriginStopCode(rc)) }
            }
            var out: [String: String] = [:]
            for await (rc, o) in group { if let o { out[rc] = o } }
            return out
        }
        let now = Date()
        var entries: [ArrivalDetail] = []
        var seenLines = Set<String>()
        for row in candidates where seenLines.insert(row.lineCode).inserted {
            guard let tt = tts[row.lineCode], let next = nextOriginScheduleStart(tt, now: now) else { continue }
            let mins = minutesUntilClock(now: now, minuteOfDay: next.minuteOfDay, nextDay: next.nextDay)
            guard mins >= 0, mins < arrivalMinutesUnknown else { continue }
            let direction = row.routeDescr.nilIfBlank ?? row.lineDescr
            let lineLabel: String
            switch (row.lineId.isEmpty, direction.isEmpty) {
            case (false, false): lineLabel = "\(row.lineId) · \(direction)"
            case (false, true): lineLabel = row.lineId
            case (true, false): lineLabel = direction
            default: lineLabel = row.lineCode
            }
            let origin = originByRoute[row.routeCode]
            let originLabel: String? = (origin != nil && origin != stopCode) ? await getStopLabel(origin!) : nil
            entries.append(ArrivalDetail(
                routeCode: row.routeCode, vehCode: "", minutes: arrivalMinutesUnknown,
                destinationLabel: row.routeDescr.nilIfBlank ?? row.routeCode, lineLabel: lineLabel,
                originDepartureMinutes: mins, originStopDescription: originLabel,
                originScheduleClock: formatMinuteOfDay(next.minuteOfDay), isScheduleOnly: true))
        }
        return arrivals + entries
    }

    // MARK: Routes & lines

    /// Stops for a route code; when OASA has none, treats the input as a line number.
    func getRouteStops(_ routeCodeOrLine: String) async throws -> RouteStopsFetch {
        let input = routeCodeOrLine.trimmed
        guard !input.isEmpty else { return RouteStopsFetch(stops: [], effectiveRouteCode: input) }
        if let e = routeStopsCache[input], e.fresh(Self.dayTTL) {
            return RouteStopsFetch(stops: e.value, effectiveRouteCode: input)
        }
        return try await coalesced("stops:\(input)") { [self] in
            do {
                var effective = input
                var stops = try await fetchStopsRetryingOnce(input)
                if stops.isEmpty, let resolved = await resolveLineToRouteCode(input), resolved != input {
                    effective = resolved
                    stops = try await fetchStopsRetryingOnce(resolved)
                }
                if !stops.isEmpty {
                    catalog.storeRouteStops(effective, stops)
                    routeStopsCache[effective] = Entry(value: stops, at: Date())
                }
                return RouteStopsFetch(stops: stops, effectiveRouteCode: effective)
            } catch {
                // Offline: fall back to the persisted catalog.
                var effective = input
                var stops = catalog.routeStops(input)
                if stops.isEmpty, let line = catalog.linesByExactCodeOrId(input).first,
                   let rc = catalog.routes(forLine: line.lineCode).first?.routeCode {
                    effective = rc
                    stops = catalog.routeStops(rc)
                }
                if stops.isEmpty { throw error }
                return RouteStopsFetch(stops: stops, effectiveRouteCode: effective)
            }
        }
    }

    /// OASA answers bursts with `null`; one paced retry tells throttling from "no such route".
    private func fetchStopsRetryingOnce(_ routeCode: String) async throws -> [RouteStop] {
        let first = mapWebStops(try await api.webGetStops(routeCode: routeCode))
        if !first.isEmpty { return first }
        return mapWebStops(try await api.webGetStops(routeCode: routeCode))
    }

    private func resolveLineToRouteCode(_ input: String) async -> String? {
        if catalog.lines.isEmpty { await catalog.warmLinesIfEmpty() }
        guard let line = catalog.linesByExactCodeOrId(input).first else { return nil }
        guard let rows = try? await api.webGetRoutes(lineCode: line.lineCode) else { return nil }
        let routes = rows.compactMap { catalogRoute($0, fallbackLine: line.lineCode) }
        catalog.upsertRoutes(routes)
        // RouteType 1 (outbound) first so the map matches the line's canonical name.
        return routes.min { ($0.type == 0 ? Int.max : $0.type, $0.routeCode) < ($1.type == 0 ? Int.max : $1.type, $1.routeCode) }?.routeCode
    }

    /// Primary route for a line (internal code or public number), from cache or webGetRoutes.
    func primaryRouteCodeForLine(_ lineCodeOrId: String) async -> String? {
        let input = lineCodeOrId.trimmed
        guard !input.isEmpty else { return nil }
        if catalog.lines.isEmpty { await catalog.warmLinesIfEmpty() }
        if let line = catalog.linesByExactCodeOrId(input).first {
            let cached = catalog.routes(forLine: line.lineCode)
            // Cached rows from webRoutesForStop lack RouteType; only trust a typed cache.
            if let rc = cached.first(where: { $0.type != 0 })?.routeCode { return rc }
        }
        return await resolveLineToRouteCode(input)
    }

    func lineCodeForRoute(_ routeCode: String) -> String? {
        catalog.route(code: routeCode)?.lineCode.nilIfBlank
    }

    /// Line metadata + every direction for the line containing `routeCode`.
    func getLineRouteInfoForRoute(_ routeCode: String, hintStopCode: String? = nil) async -> LineRouteInfo? {
        let rc = routeCode.trimmed
        guard !rc.isEmpty else { return nil }
        if let e = lineRouteInfoCache[rc], e.fresh(Self.dayTTL) { return e.value }

        var lineCode = lineCodeForRoute(rc)
        var metaFromWeb: RouteAtStop?
        if lineCode == nil, let hint = hintStopCode?.nilIfBlank {
            metaFromWeb = (try? await getRoutesForStop(hint))?.first { $0.routeCode == rc }
            lineCode = metaFromWeb?.lineCode.nilIfBlank
        }
        guard let lc = lineCode else { return nil }

        var directions = catalog.routes(forLine: lc).map { RouteDirection(routeCode: $0.routeCode, descr: $0.descr) }
        if directions.count < 2, let rows = try? await api.webGetRoutes(lineCode: lc) {
            let routes = rows.compactMap { catalogRoute($0, fallbackLine: lc) }
            if !routes.isEmpty {
                catalog.upsertRoutes(routes)
                directions = catalog.routes(forLine: lc).map { RouteDirection(routeCode: $0.routeCode, descr: $0.descr) }
            }
        }
        if directions.isEmpty { directions = [RouteDirection(routeCode: rc, descr: "")] }
        if catalog.lines.isEmpty { await catalog.warmLinesIfEmpty() }
        let line = catalog.line(code: lc)
        let info = LineRouteInfo(
            lineCode: lc,
            lineId: line?.lineId.nilIfBlank ?? metaFromWeb?.lineId ?? "",
            lineDescr: line?.lineDescr.nilIfBlank ?? metaFromWeb?.lineDescr ?? "",
            directions: directions)
        lineRouteInfoCache[rc] = Entry(value: info, at: Date())
        return info
    }

    func getBusesOnRoute(_ routeCode: String, forceRefresh: Bool = false) async -> [BusOnRoute] {
        let rc = routeCode.trimmed
        guard !rc.isEmpty else { return [] }
        if !forceRefresh, let e = busCache[rc], e.fresh(Self.busTTL) { return e.value }
        let buses = (try? await coalesced("bus:\(rc)") { [self] in
            try await api.getBusLocation(routeCode: rc).compactMap { j -> BusOnRoute? in
                let no = j.s("VEH_NO")
                guard !no.isEmpty, let lat = j.double("CS_LAT"), let lng = j.double("CS_LNG") else { return nil }
                return BusOnRoute(vehicleNo: no, lat: lat, lng: lng)
            }
        }) ?? []
        busCache[rc] = Entry(value: buses, at: Date())
        return buses
    }

    func getRouteDailyTimetable(_ lineCode: String) async -> RouteDailyTimetable {
        let lc = lineCode.trimmed
        guard !lc.isEmpty else { return .empty }
        if let e = timetableCache[lc], e.fresh(Self.timetableTTL) { return e.value }
        let tt = (try? await coalesced("tt:\(lc)") { [self] in
            let raw = try await api.getDailySchedule(lineCode: lc)
            return RouteDailyTimetable(
                originDepartures: mapDailyScheduleSlots(raw.come),
                terminusDepartures: mapDailyScheduleSlots(raw.go))
        }) ?? .empty
        if !tt.isEmpty { timetableCache[lc] = Entry(value: tt, at: Date()) }
        return tt
    }

    func getClosestStops(lat: Double, lng: Double) async throws -> [NearbyStop] {
        let key = String(format: "%.4f,%.4f", lat, lng)
        if let e = closestCache[key], e.fresh(Self.closestTTL) { return e.value }
        return try await coalesced("near:\(key)") { [self] in
            let stops = try await api.getClosestStops(lat: lat, lng: lng).compactMap { j -> NearbyStop? in
                let code = j.s("StopCode")
                guard !code.isEmpty, let la = j.double("StopLat"), let lo = j.double("StopLng") else { return nil }
                return NearbyStop(stopCode: code, description: j.s("StopDescr"), lat: la, lng: lo,
                                  distanceKm: j.double("distance"))
            }.sorted { ($0.distanceKm ?? .greatestFiniteMagnitude) < ($1.distanceKm ?? .greatestFiniteMagnitude) }
            for s in stops { catalog.rememberStop(code: s.stopCode, descr: s.description, lat: s.lat, lng: s.lng) }
            closestCache[key] = Entry(value: stops, at: Date())
            return stops
        }
    }
}
