import SwiftUI
import UserNotifications

// MARK: - Arrival alert polling (port of Android ArrivalAlertWorker)
// Foreground + background polling: every 30s refetch the stop; fire local
// notification when effective minutes <= threshold; "arrived"/"left" follow-ups.

@MainActor
final class ArrivalAlertCenter: ObservableObject {
    static let shared = ArrivalAlertCenter()
    private var timers: [String: Timer] = [:]
    private var startedAt: [String: Date] = [:]
    private var didNotify: Set<String> = []

    func isActive(stopCode: String, routeCode: String, vehCode: String) -> Bool {
        AppState.shared.activeAlertKeys.contains(alertKey(stopCode: stopCode, routeCode: routeCode, vehCode: vehCode))
    }

    func toggle(stopCode: String, stopName: String, routeCode: String, vehCode: String, lineLabel: String) {
        let key = alertKey(stopCode: stopCode, routeCode: routeCode, vehCode: vehCode)
        if AppState.shared.activeAlertKeys.contains(key) {
            cancel(key: key)
        } else {
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
                guard granted else { return }
                Task { @MainActor in
                    self.start(
                        key: key, stopCode: stopCode, stopName: stopName,
                        routeCode: routeCode, vehCode: vehCode, lineLabel: lineLabel)
                }
            }
        }
    }

    func start(key: String, stopCode: String, stopName: String, routeCode: String, vehCode: String, lineLabel: String) {
        AppState.shared.activeAlertKeys.insert(key)
        startedAt[key] = Date()
        timers[key]?.invalidate()
        let timer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { _ in
            Task { @MainActor in
                await self.poll(
                    key: key, stopCode: stopCode, stopName: stopName,
                    routeCode: routeCode, vehCode: vehCode, lineLabel: lineLabel)
            }
        }
        timers[key] = timer
        Task { @MainActor in
            await self.poll(
                key: key, stopCode: stopCode, stopName: stopName,
                routeCode: routeCode, vehCode: vehCode, lineLabel: lineLabel)
        }
    }

    func cancel(key: String) {
        timers[key]?.invalidate()
        timers.removeValue(forKey: key)
        startedAt.removeValue(forKey: key)
        didNotify.remove(key)
        AppState.shared.activeAlertKeys.remove(key)
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [key])
    }

    private func poll(key: String, stopCode: String, stopName: String, routeCode: String, vehCode: String, lineLabel: String) async {
        let state = AppState.shared
        // 30-minute expiry.
        if let start = startedAt[key], Date().timeIntervalSince(start) > 30 * 60 {
            cancel(key: key)
            return
        }
        guard let arrivals = try? await OasaAPI.shared.getStopArrivals(stopCode: stopCode) else { return }
        let match = arrivals.first { $0.routeCode == routeCode && $0.vehCode == vehCode }
        let quiet = isQuietNow(
            enabled: state.quietHoursEnabled, startMinutes: state.quietStartMinutes,
            endMinutes: state.quietEndMinutes)
        if let m = match {
            let eff = m.effectiveMinutes
            if eff <= state.alertThresholdMinutes, !quiet {
                if eff <= 0 {
                    notify(id: key, title: String(localized: "alert_arrived_title"),
                           body: String(localized: "alert_arrived_body \(lineLabel) \(stopName)"),
                           onlyOnce: false)
                } else {
                    notify(id: key, title: lineLabel,
                           body: String(localized: "alert_countdown_body \(eff) \(stopName)"),
                           onlyOnce: !didNotify.contains(key))
                    didNotify.insert(key)
                }
            }
        } else if didNotify.contains(key) {
            // Vehicle dropped off the board after notification → departed.
            if !quiet {
                notify(id: key, title: String(localized: "alert_left_title"),
                       body: String(localized: "alert_left_body \(lineLabel) \(stopName)"),
                       onlyOnce: false)
            }
            cancel(key: key)
        }
    }

    private func notify(id: String, title: String, body: String, onlyOnce: Bool) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = onlyOnce ? nil : .default
        let req = UNNotificationRequest(identifier: id, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(req)
    }
}
