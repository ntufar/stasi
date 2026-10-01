import Foundation
import Combine

// MARK: - Persistent app state (port of Android DataStore repositories)
// Favorites + ordering + aliases, settings, recent activity, active alerts.

/// One live-row arrival alert (Android `AlertKey` + `ArrivalAlertWorker` input data).
struct ActiveAlert: Codable, Hashable {
    let stopCode: String
    let stopTitle: String
    let routeCode: String
    let vehCode: String
    let lineLabel: String
    let startedAt: Date
    /// True once the first notification was shown (then countdown → arrived → left).
    var notified: Bool = false

    var key: String { alertKey(stopCode: stopCode, routeCode: routeCode, vehCode: vehCode) }
}

func alertKey(stopCode: String, routeCode: String, vehCode: String) -> String {
    "\(stopCode):\(routeCode):\(vehCode)"
}

struct RecentVisit: Codable, Hashable {
    let code: String
    let at: Date
}

@MainActor
final class AppState: ObservableObject {
    static let shared = AppState()

    static let alertThresholdChoices = [1, 2, 3, 5, 7, 10, 12, 15, 20, 25, 30]
    private static let maxRecentStops = 3

    @Published var favorites: [FavoriteStop] = [] {
        didSet { saveJSON(favorites, key: "favorites_v1") }
    }
    @Published private(set) var recentStops: [RecentVisit] = [] {
        didSet { saveJSON(recentStops, key: "recent_stops_v2") }
    }
    @Published private(set) var recentRoute: RecentVisit? {
        didSet { saveJSON(recentRoute, key: "recent_route_v1") }
    }
    @Published var activeAlerts: [String: ActiveAlert] = [:] {
        didSet { saveJSON(activeAlerts, key: "arrival_alerts_v2") }
    }

    // Settings (SPEC defaults: threshold 5, show names on, quiet hours off 23:00–07:00, dark on, Greek)
    @Published var localeTag: String = L10n.tag {
        didSet { UserDefaults.standard.set(localeTag, forKey: L10n.defaultsKey) }
    }
    @Published var alertThresholdMinutes: Int = UserDefaults.standard.object(forKey: "alert_threshold") as? Int ?? 5 {
        didSet { UserDefaults.standard.set(alertThresholdMinutes, forKey: "alert_threshold") }
    }
    @Published var showMapStopNames: Bool = UserDefaults.standard.object(forKey: "show_map_names") as? Bool ?? true {
        didSet { UserDefaults.standard.set(showMapStopNames, forKey: "show_map_names") }
    }
    @Published var darkMode: Bool = UserDefaults.standard.object(forKey: "dark_mode") as? Bool ?? true {
        didSet { UserDefaults.standard.set(darkMode, forKey: "dark_mode") }
    }
    @Published var quietHoursEnabled: Bool = UserDefaults.standard.bool(forKey: "quiet_enabled") {
        didSet { UserDefaults.standard.set(quietHoursEnabled, forKey: "quiet_enabled") }
    }
    @Published var quietStartMinutes: Int = UserDefaults.standard.object(forKey: "quiet_start") as? Int ?? (23 * 60) {
        didSet { UserDefaults.standard.set(quietStartMinutes, forKey: "quiet_start") }
    }
    @Published var quietEndMinutes: Int = UserDefaults.standard.object(forKey: "quiet_end") as? Int ?? (7 * 60) {
        didSet { UserDefaults.standard.set(quietEndMinutes, forKey: "quiet_end") }
    }

    private init() {
        favorites = loadJSON(key: "favorites_v1") ?? []
        if let v2: [RecentVisit] = loadJSON(key: "recent_stops_v2") {
            recentStops = v2
        } else if let v1: [String] = loadJSON(key: "recent_stops_v1") {
            recentStops = v1.prefix(Self.maxRecentStops).map { RecentVisit(code: $0, at: Date()) }
        }
        recentRoute = loadJSON(key: "recent_route_v1")
        activeAlerts = loadJSON(key: "arrival_alerts_v2") ?? [:]
        UserDefaults.standard.removeObject(forKey: "arrival_alerts_v1")
    }

    var isQuietNow: Bool {
        Stasi.isQuietNow(enabled: quietHoursEnabled, startMinutes: quietStartMinutes, endMinutes: quietEndMinutes)
    }

    // MARK: Favorites

    func isFavorite(_ stopCode: String) -> Bool {
        favorites.contains { $0.stopCode == stopCode }
    }

    func addFavorite(_ stopCode: String) {
        let c = stopCode.trimmed
        guard !c.isEmpty, !isFavorite(c) else { return }
        favorites.append(FavoriteStop(stopCode: c))
    }

    func toggleFavorite(_ stopCode: String) {
        if let i = favorites.firstIndex(where: { $0.stopCode == stopCode }) {
            favorites.remove(at: i)
        } else {
            addFavorite(stopCode)
        }
    }

    func removeFavorite(_ stopCode: String) {
        favorites.removeAll { $0.stopCode == stopCode }
    }

    func moveFavorite(from: IndexSet, to: Int) {
        var arr = favorites
        arr.move(fromOffsets: from, toOffset: to)
        favorites = arr
    }

    /// Move by `delta` positions (menu "Move up" / "Move down").
    func moveFavorite(_ stopCode: String, delta: Int) {
        guard let i = favorites.firstIndex(where: { $0.stopCode == stopCode }) else { return }
        let target = min(max(i + delta, 0), favorites.count - 1)
        guard target != i else { return }
        var arr = favorites
        let entry = arr.remove(at: i)
        arr.insert(entry, at: target)
        favorites = arr
    }

    func renameFavorite(_ stopCode: String, alias: String) {
        guard let i = favorites.firstIndex(where: { $0.stopCode == stopCode }) else { return }
        favorites[i].alias = alias.trimmed.nilIfBlank
    }

    // MARK: Recent activity

    func recordStopVisit(_ code: String) {
        let c = code.trimmed
        guard !c.isEmpty else { return }
        recentStops = Array(([RecentVisit(code: c, at: Date())] + recentStops.filter { $0.code != c })
            .prefix(Self.maxRecentStops))
    }

    func recordRouteVisit(_ routeCode: String) {
        let c = routeCode.trimmed
        guard !c.isEmpty else { return }
        recentRoute = RecentVisit(code: c, at: Date())
    }
}

// MARK: - Navigation (tabs + per-tab stacks; deep links push onto Home)

enum AppRoute: Hashable {
    case arrivals(stopCode: String, routeHint: String?)
    case routeMap(routeCode: String)
    case lineMap(lineCode: String)
}

@MainActor
final class Router: ObservableObject {
    static let shared = Router()

    enum Tab: Hashable { case home, search, nearby, map, settings }

    @Published var tab: Tab = .home
    @Published var homePath: [AppRoute] = []
    @Published var searchPath: [AppRoute] = []
    @Published var nearbyPath: [AppRoute] = []
    @Published var mapPath: [AppRoute] = []

    /// `stasi://stop/<code>` and notification taps land on Arrivals (SPEC §11 content intent).
    func openStop(_ stopCode: String) {
        let c = stopCode.trimmed
        guard !c.isEmpty else { return }
        tab = .home
        if case .arrivals(c, _)? = homePath.last { return }
        homePath.append(.arrivals(stopCode: c, routeHint: nil))
    }

    func handle(url: URL) {
        guard url.scheme == "stasi", let code = url.pathComponents.dropFirst().first else { return }
        switch url.host {
        case "stop":
            openStop(code)
        #if DEBUG
        // Debug-only entry points for simulator checks (`xcrun simctl openurl`).
        case "route":
            tab = .home
            homePath.append(.routeMap(routeCode: code))
        case "line":
            tab = .home
            homePath.append(.lineMap(lineCode: code))
        #endif
        default:
            break
        }
    }
}

// MARK: - UserDefaults JSON helpers

private func saveJSON<T: Encodable>(_ value: T, key: String) {
    if let data = try? JSONEncoder().encode(value) {
        UserDefaults.standard.set(data, forKey: key)
    }
}

private func loadJSON<T: Decodable>(key: String) -> T? {
    guard let data = UserDefaults.standard.data(forKey: key) else { return nil }
    return try? JSONDecoder().decode(T.self, from: data)
}
