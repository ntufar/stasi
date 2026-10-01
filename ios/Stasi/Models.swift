import Foundation

// MARK: - Domain models (parity with Android OasaRepository data classes)

struct BusLine: Codable, Hashable, Identifiable {
    var id: String { lineCode }
    let lineCode: String
    let lineId: String
    let lineDescr: String
}

struct RouteDirection: Codable, Hashable {
    let routeCode: String
    let descr: String
}

struct LineRouteInfo: Codable, Hashable {
    let lineCode: String
    let lineId: String
    let lineDescr: String
    let directions: [RouteDirection]
}

struct RouteStop: Codable, Hashable, Identifiable {
    var id: String { stopCode }
    let stopCode: String
    let description: String
    let lat: Double
    let lng: Double
    let order: Int
}

struct BusOnRoute: Codable, Hashable, Identifiable {
    var id: String { vehicleNo }
    let vehicleNo: String
    let lat: Double
    let lng: Double
}

struct TimetableRow: Codable, Hashable {
    let primaryRange: String
    let secondaryRange: String?
}

struct RouteDailyTimetable: Codable, Hashable {
    let originDepartures: [TimetableRow]
    let terminusDepartures: [TimetableRow]
}

struct ArrivalDetail: Codable, Hashable, Identifiable {
    var id: String { "\(routeCode)#\(vehCode)" }
    let routeCode: String
    let vehCode: String
    let minutes: Int
    let destinationLabel: String
    let lineLabel: String
    var originDepartureMinutes: Int?
    var originStopDescription: String?
    var originScheduleClock: String?
    var isLastBusWarning: Bool
    var isScheduleOnly: Bool
    var fetchedAtMillis: Int64

    /// Wall-clock countdown between polls (SPEC §3.2): minutes tick down from snapshot.
    var effectiveMinutes: Int {
        guard minutes < 900 else { return minutes }
        let elapsedMin = Int((Int64(Date().timeIntervalSince1970 * 1000) - fetchedAtMillis) / 60_000)
        return minutes - max(0, elapsedMin)
    }
}

struct NearbyStop: Codable, Hashable, Identifiable {
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
    var displayName: String { (alias?.isEmpty == false) ? alias! : stopCode }

    init(stopCode: String, alias: String? = nil) {
        self.stopCode = stopCode
        self.alias = alias
    }
}
