import SwiftUI
import UserNotifications

// MARK: - Arrival alerts (port of Android ArrivalAlertWorker + NotificationHelper)
// While the app runs, each active alert polls its stop every 30s: once the
// watched vehicle's effective minutes reach the threshold, one notification
// (same id) updates countdown → arrived → left; sound only on the first post.
// iOS suspends timers in the background (no WorkManager equivalent), so each
// poll also queues a time-triggered notification at the predicted threshold
// crossing; polling resumes and corrects it when the app is foregrounded.
// Alerts persist across launches and expire 30 minutes after they start.

@MainActor
final class ArrivalAlertCenter: ObservableObject {
    static let shared = ArrivalAlertCenter()

    private static let pollInterval: TimeInterval = 30
    private static let maxRuntime: TimeInterval = 30 * 60

    private var timers: [String: Timer] = [:]
    private var state: AppState { AppState.shared }
    private let center = UNUserNotificationCenter.current()

    func isActive(stopCode: String, routeCode: String, vehCode: String) -> Bool {
        state.activeAlerts[alertKey(stopCode: stopCode, routeCode: routeCode, vehCode: vehCode)] != nil
    }

    func toggle(stopCode: String, stopTitle: String, routeCode: String, vehCode: String, lineLabel: String) {
        let key = alertKey(stopCode: stopCode, routeCode: routeCode, vehCode: vehCode)
        if state.activeAlerts[key] != nil {
            cancel(key: key)
            return
        }
        Task {
            // Alert is only enabled after the permission is granted (SPEC §11).
            let granted = (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
            guard granted else { return }
            let alert = ActiveAlert(stopCode: stopCode, stopTitle: stopTitle, routeCode: routeCode,
                                    vehCode: vehCode, lineLabel: lineLabel, startedAt: Date())
            state.activeAlerts[key] = alert
            startTimer(key)
            await poll(key)
        }
    }

    /// Re-arm persisted alerts on launch / foreground; drop expired ones.
    func resumeAll() {
        for (key, alert) in state.activeAlerts {
            if Date().timeIntervalSince(alert.startedAt) >= Self.maxRuntime {
                cancel(key: key)
            } else {
                startTimer(key)
                Task { await poll(key) }
            }
        }
    }

    func cancel(key: String) {
        timers[key]?.invalidate()
        timers.removeValue(forKey: key)
        state.activeAlerts.removeValue(forKey: key)
        center.removePendingNotificationRequests(withIdentifiers: [predictedId(key)])
    }

    private func startTimer(_ key: String) {
        timers[key]?.invalidate()
        timers[key] = Timer.scheduledTimer(withTimeInterval: Self.pollInterval, repeats: true) { _ in
            Task { @MainActor in await ArrivalAlertCenter.shared.poll(key) }
        }
    }

    private func predictedId(_ key: String) -> String { "\(key)#predicted" }

    private func poll(_ key: String) async {
        guard var alert = state.activeAlerts[key] else {
            timers[key]?.invalidate()
            timers.removeValue(forKey: key)
            return
        }
        let now = Date()
        if now.timeIntervalSince(alert.startedAt) >= Self.maxRuntime {
            cancel(key: key)
            return
        }
        let snapshot = await OasaRepository.shared.getStopArrivalsSnapshot(alert.stopCode, forceRefresh: true)
        guard !snapshot.isStale else { return } // network failure — keep polling
        guard state.activeAlerts[key] != nil else { return } // cancelled meanwhile
        let quiet = state.isQuietNow
        let threshold = state.alertThresholdMinutes
        let hit = snapshot.arrivals.first { $0.routeCode == alert.routeCode && $0.vehCode == alert.vehCode }

        guard let hit else {
            if alert.notified {
                if !quiet { post(alert, phase: .departed, minutes: 0, withSound: false) }
                cancel(key: key)
            }
            // Vehicle not on the board yet or a transient gap — keep polling.
            return
        }
        let minutes = effectiveMinutes(hit.minutes, since: snapshot.fetchedAt, now: now)
        if alert.notified || minutes <= threshold {
            center.removePendingNotificationRequests(withIdentifiers: [predictedId(key)])
            if !quiet {
                post(alert, phase: minutes <= 0 ? .arrived : .countdown, minutes: minutes, withSound: !alert.notified)
            }
            alert.notified = true
            state.activeAlerts[key] = alert
        } else if minutes < arrivalMinutesUnknown {
            schedulePredicted(alert, fireIn: TimeInterval(minutes - threshold) * 60, threshold: threshold)
        }
    }

    enum Phase { case countdown, arrived, departed }

    private func content(_ alert: ActiveAlert, phase: Phase, minutes: Int) -> UNMutableNotificationContent {
        let c = UNMutableNotificationContent()
        switch phase {
        case .countdown:
            c.title = L("notification_arrival_title", alert.lineLabel)
            c.body = minutes <= 0
                ? L("notification_arrival_arrived", alert.stopTitle)
                : L("notification_arrival_text", minutes, alert.stopTitle)
        case .arrived:
            c.title = L("notification_arrival_title_arrived", alert.lineLabel)
            c.body = L("notification_arrival_arrived", alert.stopTitle)
        case .departed:
            c.title = L("notification_arrival_title_departed", alert.lineLabel)
            c.body = L("notification_arrival_left", alert.stopTitle)
        }
        c.userInfo = ["stopCode": alert.stopCode]
        c.threadIdentifier = alert.key
        return c
    }

    private func post(_ alert: ActiveAlert, phase: Phase, minutes: Int, withSound: Bool) {
        let c = content(alert, phase: phase, minutes: minutes)
        c.sound = withSound ? .default : nil
        center.add(UNNotificationRequest(identifier: alert.key, content: c, trigger: nil))
    }

    private func schedulePredicted(_ alert: ActiveAlert, fireIn: TimeInterval, threshold: Int) {
        let id = predictedId(alert.key)
        let deadline = alert.startedAt.addingTimeInterval(Self.maxRuntime)
        let fireAt = Date().addingTimeInterval(max(1, fireIn))
        guard fireAt < deadline,
              !Stasi.isQuietNow(enabled: state.quietHoursEnabled, startMinutes: state.quietStartMinutes,
                                endMinutes: state.quietEndMinutes, now: fireAt) else {
            center.removePendingNotificationRequests(withIdentifiers: [id])
            return
        }
        let c = content(alert, phase: .countdown, minutes: threshold)
        c.sound = .default
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, fireIn), repeats: false)
        // Same identifier replaces the previous prediction.
        center.add(UNNotificationRequest(identifier: id, content: c, trigger: trigger))
    }
}

// MARK: - Notification presentation + tap handling

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    /// Show alerts as banners while the app is open too.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
    }

    /// Tapping an alert opens Arrivals for its stop.
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        if let stop = response.notification.request.content.userInfo["stopCode"] as? String {
            Task { @MainActor in Router.shared.openStop(stop) }
        }
        completionHandler()
    }
}
