import Foundation

// MARK: - OASA Telematics API client (parity with Android OasaApi.kt)
// Base URL uses HTTPS (iOS ATS forbids cleartext; telematics.oasa.gr serves TLS).
// All calls are POST with query params (?act=...&p1=...). Responses are read as
// loose JSON rows because OASA mixes strings and numbers for the same field.

enum OasaError: Error {
    case badResponse
    case decoding
    /// The API returned no rows where rows are required (throttled or unknown id).
    case empty
}

/// Client-side politeness (SPEC §6): at most one request per 1.2s per gate,
/// where gates are per endpoint, or per line/route for catalog-style calls so
/// background ingest does not block the user's route fetch (Android
/// `EndpointRateLimiter`). A small gap across all gates avoids the bursty
/// chains OASA answers with `null`. Slots are reserved before sleeping so
/// concurrent callers queue instead of firing together.
actor EndpointRateLimiter {
    static let perGateInterval: TimeInterval = 1.2
    static let globalGap: TimeInterval = 0.3

    private var nextSlot: [String: Date] = [:]
    private var nextAny = Date.distantPast

    func wait(gate: String) async {
        let now = Date()
        let slot = max(now, nextSlot[gate] ?? .distantPast, nextAny)
        nextSlot[gate] = slot.addingTimeInterval(Self.perGateInterval)
        nextAny = slot.addingTimeInterval(Self.globalGap)
        let delay = slot.timeIntervalSince(now)
        if delay > 0 {
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
        }
    }
}

final class OasaAPI {
    static let shared = OasaAPI()
    private let limiter = EndpointRateLimiter()
    private let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 30
        c.timeoutIntervalForResource = 45
        return URLSession(configuration: c)
    }()

    private func post(act: String, gate: String? = nil, params: [String: String]) async throws -> Any? {
        await limiter.wait(gate: gate ?? act)
        var comps = URLComponents(string: "https://telematics.oasa.gr/api/")!
        comps.queryItems = [URLQueryItem(name: "act", value: act)]
            + params.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        var req = URLRequest(url: comps.url!)
        req.httpMethod = "POST"
        req.setValue("Stasi/1.0 (+https://github.com/ntufar/stasi)", forHTTPHeaderField: "User-Agent")
        let (data, resp) = try await session.data(for: req)
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else { throw OasaError.badResponse }
        let text = String(data: data, encoding: .utf8)?.trimmed ?? ""
        if text.isEmpty || text == "null" || text == "\"\"" { return nil }
        do {
            return try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        } catch {
            throw OasaError.decoding
        }
    }

    /// List endpoints: OASA returns `null` both for "no data" (quiet stop) and
    /// when throttling. Both map to `[]`; callers decide whether empty is fine.
    private func rows(act: String, gate: String? = nil, params: [String: String] = [:]) async throws -> [JSONRow] {
        let json = try await post(act: act, gate: gate, params: params)
        return (json as? [Any])?.compactMap { $0 as? JSONRow } ?? []
    }

    func webGetLines() async throws -> [JSONRow] {
        try await rows(act: "webGetLines")
    }

    func webGetRoutes(lineCode: String) async throws -> [JSONRow] {
        try await rows(act: "webGetRoutes", gate: "webGetRoutes::\(lineCode)", params: ["p1": lineCode])
    }

    func webGetStops(routeCode: String) async throws -> [JSONRow] {
        try await rows(act: "webGetStops", gate: "webGetStops::\(routeCode)", params: ["p1": routeCode])
    }

    func getStopNameAndXY(stopCode: String) async throws -> [JSONRow] {
        try await rows(act: "getStopNameAndXY", params: ["p1": stopCode])
    }

    func getStopArrivals(stopCode: String) async throws -> [JSONRow] {
        try await rows(act: "getStopArrivals", params: ["p1": stopCode])
    }

    func webRoutesForStop(stopCode: String) async throws -> [JSONRow] {
        try await rows(act: "webRoutesForStop", params: ["p1": stopCode])
    }

    func getBusLocation(routeCode: String) async throws -> [JSONRow] {
        try await rows(act: "getBusLocation", params: ["p1": routeCode])
    }

    func getClosestStops(lat: Double, lng: Double) async throws -> [JSONRow] {
        try await rows(act: "getClosestStops", params: ["p1": "\(lat)", "p2": "\(lng)"])
    }

    /// `{ come: [...], go: [...] }` keyed by the internal line code (not the public number).
    func getDailySchedule(lineCode: String) async throws -> (come: [JSONRow], go: [JSONRow]) {
        let json = try await post(
            act: "getDailySchedule", gate: "getDailySchedule::\(lineCode)", params: ["line_code": lineCode])
        let obj = json as? JSONRow ?? [:]
        return ((obj["come"] as? [JSONRow]) ?? [], (obj["go"] as? [JSONRow]) ?? [])
    }
}
