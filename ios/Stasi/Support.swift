import SwiftUI
import UIKit

// MARK: - In-app language (port of Android AppLocale + per-app locale)
// `String(localized:)` follows the system language, not the in-app choice, so
// every user-visible string goes through `L(...)`, which reads the bundle of
// the persisted `ui_locale` tag. Background work (alerts) uses the same path.

enum L10n {
    static let defaultsKey = "ui_locale"

    static var tag: String {
        UserDefaults.standard.string(forKey: defaultsKey) ?? "el"
    }

    static var locale: Locale { Locale(identifier: tag) }

    private static var cache: [String: Bundle] = [:]
    private static let lock = NSLock()

    static var bundle: Bundle {
        let t = tag
        lock.lock()
        defer { lock.unlock() }
        if let b = cache[t] { return b }
        let b = Bundle.main.path(forResource: t, ofType: "lproj").flatMap(Bundle.init(path:)) ?? .main
        cache[t] = b
        return b
    }
}

/// Localized string for the in-app language; printf-style args (`%ld`, `%@`).
func L(_ key: String, _ args: CVarArg...) -> String {
    let format = L10n.bundle.localizedString(forKey: key, value: nil, table: nil)
    if args.isEmpty { return format }
    return String(format: format, locale: L10n.locale, arguments: args)
}

/// "5 min" / "5΄" (Android `minutes_short`).
func minutesShort(_ m: Int) -> String { L("minutes_short", m) }

/// Big-number text for an arrival row: "—" when unknown.
func minutesText(_ m: Int) -> String {
    m >= arrivalMinutesUnknown ? "—" : minutesShort(m)
}

/// "Updated 2 minutes ago" in the app language (Android `freshnessUpdatedLabel`).
func freshnessLabel(_ date: Date?, now: Date = Date(), key: String = "arrivals_updated_at") -> String? {
    guard let date else { return nil }
    let f = RelativeDateTimeFormatter()
    f.locale = L10n.locale
    f.unitsStyle = .full
    f.dateTimeStyle = .named
    // Under 5s (or clock skew) reads "now" rather than "in 0 seconds".
    let relative = now.timeIntervalSince(date) < 5
        ? f.localizedString(for: now, relativeTo: now)
        : f.localizedString(for: date, relativeTo: now)
    return L(key, relative)
}

// MARK: - Theme (port of ui/theme)

extension UIColor {
    convenience init(rgb: UInt32, alpha: CGFloat = 1) {
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: alpha)
    }
}

extension Color {
    init(rgb: UInt32) { self.init(uiColor: UIColor(rgb: rgb)) }

    /// Primary accent: light green on AMOLED dark, deep green on light.
    static let stasiAccent = Color(uiColor: UIColor { trait in
        trait.userInterfaceStyle == .dark ? UIColor(rgb: 0x81C784) : UIColor(rgb: 0x2E7D32)
    })
    static let lastServiceAmber = Color(rgb: 0xFFA726)
    static let routeLine = Color(rgb: 0x4CAF50)
    static let stopStart = Color(rgb: 0x4CAF50)
    static let stopMid = Color(rgb: 0x00ACC1)
    static let stopEnd = Color(rgb: 0xF44336)
    static let busMarker = Color(rgb: 0xFFC107)
    static let busStroke = Color(rgb: 0x1B1B1B)
}

// MARK: - Loose JSON rows (OASA mixes strings and numbers for the same field)

typealias JSONRow = [String: Any]

extension Dictionary where Key == String, Value == Any {
    /// Trimmed string value for `key`, accepting numbers; "" when absent/null.
    func s(_ key: String) -> String {
        if let v = self[key] as? String { return v.trimmingCharacters(in: .whitespacesAndNewlines) }
        if let v = self[key] as? NSNumber { return v.stringValue }
        return ""
    }

    func double(_ key: String) -> Double? { Double(s(key)) }
    func int(_ key: String) -> Int? { Int(s(key)) ?? double(key).map { Int($0) } }
}

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
    var nilIfBlank: String? { trimmed.isEmpty ? nil : self }
}

/// Truncate OASA stop descriptions for map name labels (Android `truncateStopMapLabel`).
func truncateStopMapLabel(_ raw: String, maxChars: Int = 22) -> String {
    let t = raw.trimmed
    if t.count <= maxChars { return t }
    return String(t.prefix(maxChars - 1)).trimmed + "…"
}

/// `stasi://stop/<code>` deep link (same scheme as Android).
func stopDeepLink(_ stopCode: String) -> String { "stasi://stop/\(stopCode.trimmed)" }
