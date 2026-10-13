import SwiftUI

// MARK: - Demo mode
// The OASA Telematics API answers only on some networks (for example, it can be
// unreachable outside Greece). When a request fails, the app switches to a small
// bundled sample (two real lines) and shows a banner. The user can retry live data
// at any time. Demo mode is also a visible switch in Settings.

@MainActor
final class DemoMode: ObservableObject {
    static let shared = DemoMode()
    @Published private(set) var isActive = false

    func activate() {
        guard !isActive else { return }
        isActive = true
        CatalogStore.shared.enterDemo()
    }

    func useLiveData() {
        guard isActive else { return }
        isActive = false
        OasaRepository.shared.resetCaches()
        CatalogStore.shared.liveDataRestored()
    }
}

struct DemoBanner: View {
    @EnvironmentObject var appState: AppState
    @ObservedObject private var demo = DemoMode.shared

    var body: some View {
        if demo.isActive {
            HStack(spacing: 10) {
                Image(systemName: "info.circle.fill")
                Text(L("demo_banner")).font(.footnote)
                Spacer(minLength: 4)
                Button(L("demo_retry")) { demo.useLiveData() }
                    .font(.footnote.bold())
            }
            .padding(.horizontal, 14).padding(.vertical, 8)
            .background(Color.orange.opacity(0.9))
            .foregroundStyle(.black)
        }
    }
}

// MARK: - Bundled sample data, answering like the OASA API does

enum DemoData {
    private static let fixture: JSONRow = {
        let data = Data(demoFixtureJSON.utf8)
        return (try? JSONSerialization.jsonObject(with: data)) as? JSONRow ?? [:]
    }()

    /// Center of Athens (Syntagma). Distances in demo mode are measured from here.
    private static let anchor = (lat: 37.9755, lng: 23.7348)

    private static func rows(_ key: String) -> [JSONRow] { fixture[key] as? [JSONRow] ?? [] }
    private static func dict(_ key: String) -> JSONRow { fixture[key] as? JSONRow ?? [:] }

    private static func norm(_ code: String) -> String {
        let t = code.trimmingCharacters(in: .whitespaces)
        let s = t.drop { $0 == "0" }
        return s.isEmpty ? t : String(s)
    }

    private static var allRouteCodes: [String] { (dict("stops").keys).sorted() }

    /// Route rows (with line info) that serve the stop.
    private static func routesAt(_ stopCode: String) -> [JSONRow] {
        let n = norm(stopCode)
        var out: [JSONRow] = []
        for (lineCode, routes) in dict("routes") {
            for r in routes as? [JSONRow] ?? [] {
                let rc = r.s("RouteCode")
                let stops = (dict("stops")[rc] as? [JSONRow]) ?? []
                if stops.contains(where: { norm($0.s("StopCode")) == n || norm($0.s("StopID")) == n }) {
                    var row = r
                    if let line = rows("lines").first(where: { $0.s("LineCode") == lineCode }) {
                        row["LineID"] = line["LineID"]
                        row["LineDescr"] = line["LineDescr"]
                    }
                    out.append(row)
                }
            }
        }
        return out.sorted { $0.s("RouteCode") < $1.s("RouteCode") }
    }

    private static func stopRow(_ stopCode: String) -> JSONRow? {
        let n = norm(stopCode)
        for rc in allRouteCodes {
            if let s = (dict("stops")[rc] as? [JSONRow])?.first(where: {
                norm($0.s("StopCode")) == n || norm($0.s("StopID")) == n }) { return s }
        }
        return nil
    }

    private static func km(_ a: (Double, Double), _ b: (Double, Double)) -> Double {
        let dLat = (b.0 - a.0) * 111.2
        let dLng = (b.1 - a.1) * 111.2 * cos(a.0 * .pi / 180)
        return (dLat * dLat + dLng * dLng).squareRoot()
    }

    /// Same shape as the live API: `[JSONRow]` for list endpoints, `JSONRow` for the schedule.
    static func response(act: String, params: [String: String]) -> Any? {
        let p1 = params["p1"] ?? ""
        switch act {
        case "webGetLines":
            return rows("lines")
        case "webGetRoutes":
            return (dict("routes")[p1] as? [JSONRow]) ?? []
        case "webGetStops":
            return (dict("stops")[p1] as? [JSONRow]) ?? []
        case "getBusLocation":
            return ((dict("buses")[p1] as? [JSONRow]) ?? []).map { var r = $0; r["ROUTE_CODE"] = p1; return r }
        case "getDailySchedule":
            return dict("schedule")[params["line_code"] ?? ""]
        case "webRoutesForStop":
            return routesAt(p1)
        case "getStopNameAndXY":
            guard let s = stopRow(p1) else { return [] }
            return [["stop_descr": s["StopDescr"] ?? "", "stop_lat": s["StopLat"] ?? "",
                     "stop_lng": s["StopLng"] ?? "", "stop_id": p1] as JSONRow]
        case "getStopArrivals":
            let seed = Int(norm(p1)) ?? 0
            var out: [JSONRow] = []
            for (i, r) in routesAt(p1).enumerated() {
                let rc = r.s("RouteCode")
                let buses = (dict("buses")[rc] as? [JSONRow]) ?? []
                let first = 2 + (seed + i) % 5
                for (k, minutes) in [first, first + 7 + seed % 4].enumerated() where !buses.isEmpty {
                    out.append(["route_code": rc, "veh_code": buses[k % buses.count].s("VEH_NO"),
                                "btime2": String(minutes)])
                }
            }
            return out
        case "getClosestStops":
            var seen = Set<String>()
            var stops: [JSONRow] = []
            for rc in allRouteCodes {
                for var s in (dict("stops")[rc] as? [JSONRow]) ?? [] where seen.insert(s.s("StopCode")).inserted {
                    let d = km(anchor, (s.double("StopLat") ?? 0, s.double("StopLng") ?? 0))
                    s["distance"] = String(d)
                    stops.append(s)
                }
            }
            return Array(stops.sorted { ($0.double("distance") ?? 0) < ($1.double("distance") ?? 0) }.prefix(20))
        default:
            return nil
        }
    }
}
