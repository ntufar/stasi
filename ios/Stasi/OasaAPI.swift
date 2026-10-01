import Foundation

// MARK: - OASA Telematics API client (parity with Android OasaApi.kt)
// Base URL uses HTTPS (iOS ATS forbids cleartext; telematics.oasa.gr serves TLS).
// All calls are POST with query params (?act=...&p1=...).

enum OasaError: Error {
    case badResponse
    case decoding
    /// The API returned no rows where rows are required (throttled or unknown id).
    case empty
}

private struct OasaLineJson: Decodable {
    let LineCode: String?
    let LineID: String?
    let LineDescr: String?
}

private struct OasaRouteJson: Decodable {
    let RouteCode: String?
    let LineCode: String?
    let RouteDescr: String?
    let RouteType: Int?

    enum CodingKeys: String, CodingKey {
        case RouteCode, LineCode, RouteDescr, RouteType
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        RouteCode = try c.decodeIfPresent(String.self, forKey: .RouteCode)
        LineCode = try c.decodeIfPresent(String.self, forKey: .LineCode)
        RouteDescr = try c.decodeIfPresent(String.self, forKey: .RouteDescr)
        // OASA sends RouteType as either a number or a numeric string.
        if let n = try? c.decodeIfPresent(Int.self, forKey: .RouteType) {
            RouteType = n
        } else {
            let rawString = ((try? c.decodeIfPresent(String.self, forKey: .RouteType)) ?? nil)?.trimmingCharacters(in: .whitespaces)
            RouteType = rawString.flatMap { Int($0) }
        }
    }
}

private struct OasaWebStopJson: Decodable {
    let StopCode: String?
    let StopDescr: String?
    let StopLat: String?
    let StopLng: String?
    let RouteStopOrder: String?
}

private struct OasaArrivalJson: Decodable {
    let route_code: String?
    let veh_code: String?
    let btime2: String?
    let line_code: String?
    let route_descr: String?
}

private struct OasaWebRouteForStopJson: Decodable {
    let RouteCode: String?
    let LineCode: String?
    let RouteDescr: String?
    let LineID: String?
    let LineDescr: String?
}

private struct OasaBusJson: Decodable {
    let VEH_NO: String?
    let CS_LAT: String?
    let CS_LNG: String?
    let ROUTE_CODE: String?
}

private struct OasaClosestStopJson: Decodable {
    let StopCode: String?
    let StopDescr: String?
    let StopLat: String?
    let StopLng: String?
    let distance: String?
}

private struct OasaScheduleSlotJson: Decodable {
    let sde_start1: String?
    let sde_end1: String?
    let sde_start2: String?
    let sde_end2: String?
}

private struct OasaScheduleJson: Decodable {
    let come: [OasaScheduleSlotJson]?
    let go: [OasaScheduleSlotJson]?
}

/// 1 request / 1.2s per endpoint (SPEC §6), plus a small gap across
/// endpoints: OASA throttles bursty chains (lines→routes→stops) with `null`.
/// Actor keeps it race-free.
actor EndpointRateLimiter {
    private var lastFire: [String: Date] = [:]
    private var lastAnyFire: Date?
    func wait(endpoint: String) async {
        var delay = 0.0
        if let last = lastFire[endpoint] {
            delay = max(delay, 1.2 - Date().timeIntervalSince(last))
        }
        if let lastAny = lastAnyFire {
            delay = max(delay, 0.4 - Date().timeIntervalSince(lastAny))
        }
        if delay > 0 {
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
        }
        lastFire[endpoint] = Date()
        lastAnyFire = Date()
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

    /// List endpoints: OASA returns `null` for "no data" (quiet stop) and
    /// also when throttling a bursty client. Both decode as empty; callers
    /// decide whether empty is fine (arrivals) or a retryable failure (routes).
    private func postList<T: Decodable>(act: String, params: [String: String]) async throws -> [T] {
        await limiter.wait(endpoint: act)
        var comps = URLComponents(string: "https://telematics.oasa.gr/api/")!
        comps.queryItems = [URLQueryItem(name: "act", value: act)]
            + params.map { URLQueryItem(name: $0.key, value: $0.value) }
        var req = URLRequest(url: comps.url!)
        req.httpMethod = "POST"
        req.setValue("Stasi/1.0 (+https://github.com/ntufar/stasi)", forHTTPHeaderField: "User-Agent")
        let (data, resp) = try await session.data(for: req)
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else { throw OasaError.badResponse }
        if data.count <= 4 { return [] }
        do {
            // `null` decodes as nil for optional arrays.
            return try JSONDecoder().decode([T]?.self, from: data) ?? []
        } catch {
            if let s = String(data: data, encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines),
               s.isEmpty || s == "null" || s == "\"\"" {
                return []
            }
            throw error
        }
    }

    private func post<T: Decodable>(act: String, params: [String: String]) async throws -> T {
        await limiter.wait(endpoint: act)
        var comps = URLComponents(string: "https://telematics.oasa.gr/api/")!
        comps.queryItems = [URLQueryItem(name: "act", value: act)]
            + params.map { URLQueryItem(name: $0.key, value: $0.value) }
        var req = URLRequest(url: comps.url!)
        req.httpMethod = "POST"
        req.setValue("Stasi/1.0 (+https://github.com/ntufar/stasi)", forHTTPHeaderField: "User-Agent")
        let (data, resp) = try await session.data(for: req)
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else { throw OasaError.badResponse }
        return try JSONDecoder().decode(T.self, from: data)
    }

    func webGetLines() async throws -> [BusLine] {
        let json: [OasaLineJson] = try await postList(act: "webGetLines", params: [:])
        return json.compactMap { j in
            guard let code = j.LineCode?.trimmingCharacters(in: .whitespaces), !code.isEmpty else { return nil }
            return BusLine(lineCode: code, lineId: j.LineID ?? code, lineDescr: j.LineDescr ?? "")
        }
    }

    func webGetRoutes(lineCode: String) async throws -> [(code: String, descr: String, type: Int, lineCode: String)] {
        let json: [OasaRouteJson] = try await postList(act: "webGetRoutes", params: ["p1": lineCode])
        return json.compactMap { j in
            guard let rc = j.RouteCode?.trimmingCharacters(in: .whitespaces), !rc.isEmpty else { return nil }
            return (rc, j.RouteDescr ?? "", j.RouteType ?? 0, j.LineCode ?? lineCode)
        }
    }

    func webGetStops(routeCode: String) async throws -> [RouteStop] {
        let json: [OasaWebStopJson] = try await postList(act: "webGetStops", params: ["p1": routeCode])
        return json.compactMap { j in
            guard let sc = j.StopCode?.trimmingCharacters(in: .whitespaces), !sc.isEmpty else { return nil }
            return RouteStop(
                stopCode: sc,
                description: j.StopDescr ?? sc,
                lat: Double(j.StopLat ?? "") ?? 0,
                lng: Double(j.StopLng ?? "") ?? 0,
                order: Int(j.RouteStopOrder ?? "") ?? 0
            )
        }.sorted { $0.order < $1.order }
    }

    func getStopArrivals(stopCode: String) async throws -> [ArrivalDetail] {
        let json: [OasaArrivalJson] = try await postList(act: "getStopArrivals", params: ["p1": stopCode])
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return json.compactMap { j in
            let rc = (j.route_code ?? "").trimmingCharacters(in: .whitespaces)
            let vc = (j.veh_code ?? "").trimmingCharacters(in: .whitespaces)
            guard !rc.isEmpty, !vc.isEmpty else { return nil }
            let mins = parseArrivalMinutes(j.btime2)
            let lineCode = (j.line_code ?? "").trimmingCharacters(in: .whitespaces)
            return ArrivalDetail(
                routeCode: rc, vehCode: vc, minutes: mins,
                destinationLabel: j.route_descr ?? "", lineLabel: lineCode.isEmpty ? rc : lineCode,
                originDepartureMinutes: nil, originStopDescription: nil, originScheduleClock: nil,
                isLastBusWarning: false, isScheduleOnly: false, fetchedAtMillis: now
            )
        }.sorted { $0.minutes < $1.minutes }
    }

    func webRoutesForStop(stopCode: String) async throws -> [LineRouteInfo] {
        let json: [OasaWebRouteForStopJson] = try await postList(act: "webRoutesForStop", params: ["p1": stopCode])
        var byLine: [String: LineRouteInfo] = [:]
        for j in json {
            let lc = (j.LineCode ?? "").trimmingCharacters(in: .whitespaces)
            let rc = (j.RouteCode ?? "").trimmingCharacters(in: .whitespaces)
            guard !lc.isEmpty, !rc.isEmpty else { continue }
            var info = byLine[lc] ?? LineRouteInfo(
                lineCode: lc, lineId: j.LineID ?? lc, lineDescr: j.LineDescr ?? "",
                directions: [])
            if !info.directions.contains(where: { $0.routeCode == rc }) {
                info = LineRouteInfo(
                    lineCode: info.lineCode, lineId: info.lineId, lineDescr: info.lineDescr,
                    directions: info.directions + [RouteDirection(routeCode: rc, descr: j.RouteDescr ?? rc)])
            }
            byLine[lc] = info
        }
        return Array(byLine.values)
    }

    func getBusLocation(routeCode: String) async throws -> [BusOnRoute] {
        let json: [OasaBusJson] = try await postList(act: "getBusLocation", params: ["p1": routeCode])
        return json.compactMap { j in
            guard let veh = j.VEH_NO?.trimmingCharacters(in: .whitespaces), !veh.isEmpty,
                  let lat = Double(j.CS_LAT ?? ""), let lng = Double(j.CS_LNG ?? "") else { return nil }
            return BusOnRoute(vehicleNo: veh, lat: lat, lng: lng)
        }
    }

    func getClosestStops(lat: Double, lng: Double) async throws -> [NearbyStop] {
        let json: [OasaClosestStopJson] = try await postList(
            act: "getClosestStops", params: ["p1": "\(lat)", "p2": "\(lng)"])
        return json.compactMap { j in
            guard let sc = j.StopCode?.trimmingCharacters(in: .whitespaces), !sc.isEmpty else { return nil }
            let dKm = Double(j.distance ?? "")
            return NearbyStop(
                stopCode: sc, description: j.StopDescr ?? sc,
                lat: Double(j.StopLat ?? "") ?? 0, lng: Double(j.StopLng ?? "") ?? 0,
                distanceKm: dKm)
        }
    }

    func getDailySchedule(lineCode: String) async throws -> RouteDailyTimetable {
        let json: OasaScheduleJson = try await post(act: "getDailySchedule", params: ["line_code": lineCode])
        func rows(_ slots: [OasaScheduleSlotJson]?) -> [TimetableRow] {
            (slots ?? []).compactMap { s in
                guard let a = s.sde_start1, !a.isEmpty else { return nil }
                let primary = s.sde_end1.map { "\(a) – \($0)" } ?? a
                let secondary: String? = {
                    guard let b = s.sde_start2, !b.isEmpty else { return nil }
                    return s.sde_end2.map { "\(b) – \($0)" } ?? b
                }()
                return TimetableRow(primaryRange: primary, secondaryRange: secondary)
            }
        }
        return RouteDailyTimetable(originDepartures: rows(json.come), terminusDepartures: rows(json.go))
    }

    /// Resolve a public line number / internal code to route codes (prefers RouteType 1 outbound).
    func resolveLineToRoutes(lineQuery: String) async throws -> (info: LineRouteInfo, routes: [(code: String, descr: String, type: Int, lineCode: String)]) {
        let lines = try await webGetLines()
        let q = lineQuery.trimmingCharacters(in: .whitespaces)
        guard let match = lines.first(where: {
            $0.lineId.caseInsensitiveCompare(q) == .orderedSame
                || $0.lineCode.caseInsensitiveCompare(q) == .orderedSame
        }) ?? lines.first(where: { $0.lineId == q || $0.lineCode == q }) else {
            throw OasaError.badResponse
        }
        var routes = try await webGetRoutes(lineCode: match.lineCode)
        guard !routes.isEmpty else { throw OasaError.empty }
        routes.sort { ($0.type == 1 ? 0 : 1, $0.code) < ($1.type == 1 ? 0 : 1, $1.code) }
        let info = LineRouteInfo(
            lineCode: match.lineCode, lineId: match.lineId, lineDescr: match.lineDescr,
            directions: routes.map { RouteDirection(routeCode: $0.code, descr: $0.descr) })
        return (info, routes)
    }
}
