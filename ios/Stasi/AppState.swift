import Foundation
import Combine

// MARK: - Persistent app state (port of Android DataStore repositories)
// Favorites + ordering + aliases, settings, recents, active alerts.

@MainActor
final class AppState: ObservableObject {
    static let shared = AppState()

    @Published var favorites: [FavoriteStop] = [] {
        didSet { saveJSON(favorites, key: "favorites_v1") }
    }
    @Published var recentStopCodes: [String] = [] {
        didSet { saveJSON(recentStopCodes, key: "recent_stops_v1") }
    }
    @Published var recentLineQueries: [String] = [] {
        didSet { saveJSON(recentLineQueries, key: "recent_lines_v1") }
    }
    @Published var activeAlertKeys: Set<String> = [] {
        didSet { saveJSON(Array(activeAlertKeys), key: "arrival_alerts_v1") }
    }

    // Settings (SPEC defaults: threshold 5, show names on, quiet hours off 23:00–07:00, dark on, Greek)
    @Published var localeTag: String = UserDefaults.standard.string(forKey: "ui_locale") ?? "el" {
        didSet { UserDefaults.standard.set(localeTag, forKey: "ui_locale") }
    }
    @Published var alertThresholdMinutes: Int = UserDefaults.standard.object(forKey: "alert_threshold") as? Int ?? 5 {
        didSet { UserDefaults.standard.set(alertThresholdMinutes, forKey: "alert_threshold") }
    }
    @Published var showMapStopNames: Bool = UserDefaults.standard.object(forKey: "show_map_names") as? Bool ?? true {
        didSet { UserDefaults.standard.set(showMapStopNames, forKey: "show_map_names") }
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

    static let alertThresholdChoices = [1, 2, 3, 5, 7, 10, 12, 15, 20, 25, 30]

    private init() {
        favorites = loadJSON(key: "favorites_v1") ?? []
        recentStopCodes = loadJSON(key: "recent_stops_v1") ?? []
        recentLineQueries = loadJSON(key: "recent_lines_v1") ?? []
        activeAlertKeys = Set(loadJSON(key: "arrival_alerts_v1") ?? [String]())
    }

    func isFavorite(_ stopCode: String) -> Bool {
        favorites.contains { $0.stopCode == stopCode }
    }

    func toggleFavorite(stopCode: String, name: String? = nil) {
        if let i = favorites.firstIndex(where: { $0.stopCode == stopCode }) {
            favorites.remove(at: i)
        } else {
            favorites.append(FavoriteStop(stopCode: stopCode))
        }
    }

    func moveFavorite(from: IndexSet, to: Int) {
        var arr = favorites
        arr.move(fromOffsets: from, toOffset: to)
        favorites = arr
    }

    func renameFavorite(stopCode: String, alias: String) {
        guard let i = favorites.firstIndex(where: { $0.stopCode == stopCode }) else { return }
        favorites[i].alias = alias.isEmpty ? nil : alias
    }

    func pushRecentStop(_ code: String) {
        var r = recentStopCodes.filter { $0 != code }
        r.insert(code, at: 0)
        recentStopCodes = Array(r.prefix(5))
    }

    func pushRecentLine(_ q: String) {
        var r = recentLineQueries.filter { $0 != q }
        r.insert(q, at: 0)
        recentLineQueries = Array(r.prefix(5))
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

// MARK: - Quiet hours

func isQuietNow(enabled: Bool, startMinutes: Int, endMinutes: Int, now: Date = Date()) -> Bool {
    guard enabled else { return false }
    let cal = Calendar.current
    let mins = cal.component(.hour, from: now) * 60 + cal.component(.minute, from: now)
    if startMinutes <= endMinutes {
        return mins >= startMinutes && mins < endMinutes
    } else {
        return mins >= startMinutes || mins < endMinutes
    }
}

func alertKey(stopCode: String, routeCode: String, vehCode: String) -> String {
    "\(stopCode)|\(routeCode)|\(vehCode)"
}

// MARK: - Cached lines/stops catalog (24h policy, UserDefaults-backed)

@MainActor
final class CatalogCache: ObservableObject {
    static let shared = CatalogCache()
    @Published var lines: [BusLine] = []
    @Published var lastSyncMillis: Int64 = UserDefaults.standard.object(forKey: "catalog_sync") as? Int64 ?? 0

    var needsSync: Bool {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return lines.isEmpty || (now - lastSyncMillis) > 24 * 60 * 60 * 1000
    }

    func ensureLoaded() async {
        if !lines.isEmpty { return }
        if let data = UserDefaults.standard.data(forKey: "catalog_lines"),
           let decoded = try? JSONDecoder().decode([BusLine].self, from: data), !decoded.isEmpty {
            lines = decoded
        }
        if needsSync { await sync() }
    }

    func sync() async {
        do {
            let fresh = try await OasaAPI.shared.webGetLines()
            guard !fresh.isEmpty else { return }
            lines = fresh
            lastSyncMillis = Int64(Date().timeIntervalSince1970 * 1000)
            UserDefaults.standard.set(lastSyncMillis, forKey: "catalog_sync")
            if let data = try? JSONEncoder().encode(fresh) {
                UserDefaults.standard.set(data, forKey: "catalog_lines")
            }
        } catch { /* keep stale cache offline */ }
    }
}
