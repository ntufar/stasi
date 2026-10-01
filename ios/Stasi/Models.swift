import Foundation

// MARK: - Domain models (parity with Android OasaRepository data classes)

/// Sentinel for unknown ETA so rows sort last (Android `ARRIVAL_MINUTES_UNKNOWN`).
let arrivalMinutesUnknown = 999

struct BusLine: Codable, Hashable, Identifiable {
    var id: String { lineCode }
    let lineCode: String
    /// Public-facing number ("140"); may be empty in OASA data.
    let lineId: String
    let lineDescr: String

    var displayId: String { lineId.isEmpty ? lineCode : lineId }
}

/// One direction of a line, as cached from webGetRoutes / webRoutesForStop.
struct CatalogRoute: Codable, Hashable {
    let routeCode: String
    let lineCode: String
    let descr: String
    /// OASA RouteType: 1 = outbound/primary, 2 = return; 0 when unknown.
    let type: Int
}

struct RouteDirection: Codable, Hashable {
    let routeCode: String
    let descr: String
}

struct LineRouteInfo: Codable, Hashable {
    let lineCode: String
    /// Public-facing line number, e.g. "750"; may be empty.
    let lineId: String
    let lineDescr: String
    let directions: [RouteDirection]
}

struct RouteStop: Codable, Hashable, Identifiable {
    var id: String { "\(stopCode)#\(order)" }
    let stopCode: String
    let description: String
    let lat: Double
    let lng: Double
    let order: Int
}

struct RouteStopsFetch {
    let stops: [RouteStop]
    /// Route code actually used; may differ from what the user typed (line number → route).
    let effectiveRouteCode: String
}

struct BusOnRoute: Hashable, Identifiable {
    var id: String { vehicleNo }
    let vehicleNo: String
    let lat: Double
    let lng: Double
}

/// One or two time bands from getDailySchedule for one direction bucket.
struct RouteDailyTimetableRow: Hashable {
    let primaryRange: String
    let secondaryRange: String?
}

/// `come` = origin (αφετηρία), `go` = terminus (τέρμα).
struct RouteDailyTimetable: Hashable {
    let originDepartures: [RouteDailyTimetableRow]
    let terminusDepartures: [RouteDailyTimetableRow]

    static let empty = RouteDailyTimetable(originDepartures: [], terminusDepartures: [])
    var isEmpty: Bool { originDepartures.isEmpty && terminusDepartures.isEmpty }
}

struct ArrivalDetail: Hashable, Identifiable {
    var id: String { "\(routeCode)#\(vehCode)#\(isScheduleOnly)" }
    let routeCode: String
    let vehCode: String
    let minutes: Int
    let destinationLabel: String
    /// "140 · ΠΟΛΥΓΩΝΟ - ΓΛΥΦΑΔΑ" (public number · route direction).
    let lineLabel: String
    /// Minutes until the next departure from the route's origin when this stop is not the origin.
    var originDepartureMinutes: Int? = nil
    var originStopDescription: String? = nil
    /// Next scheduled origin departure (Europe/Athens wall clock "HH:mm").
    var originScheduleClock: String? = nil
    var isLastBusWarning: Bool = false
    /// No live bus; row only surfaces the next scheduled origin departure.
    var isScheduleOnly: Bool = false
}

struct ArrivalSnapshot {
    let arrivals: [ArrivalDetail]
    let fetchedAt: Date?
    /// True when the network fetch failed and these rows are the last known data.
    var isStale: Bool = false
}

/// Minutes shown between polls: tick down by wall clock from the snapshot so the
/// board does not look frozen when OASA repeats the same ETA (SPEC §3).
func effectiveMinutes(_ minutes: Int, since snapshot: Date?, now: Date = Date()) -> Int {
    if minutes >= arrivalMinutesUnknown { return minutes }
    guard let snapshot else { return minutes }
    let elapsed = max(0, Int(now.timeIntervalSince(snapshot) / 60))
    return max(0, minutes - elapsed)
}

struct NearbyStop: Hashable, Identifiable {
    var id: String { stopCode }
    let stopCode: String
    let description: String
    let lat: Double
    let lng: Double
    let distanceKm: Double?
}

struct FavoriteStop: Codable, Hashable, Identifiable {
    var id: String { stopCode }
    var stopCode: String
    var alias: String?

    init(stopCode: String, alias: String? = nil) {
        self.stopCode = stopCode
        self.alias = alias
    }
}
