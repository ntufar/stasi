import SwiftUI

// MARK: - Settings (port of the Android drawer settings): alert lead time,
// stop names on map, quiet hours, theme, language.

struct SettingsView: View {
    @EnvironmentObject var appState: AppState

    private var version: String {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
        let b = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? ""
        return "\(v) (\(b))"
    }

    var body: some View {
        Form {
            Section(L("settings_alerts")) {
                Picker(L("settings_arrival_alert_threshold_label"), selection: $appState.alertThresholdMinutes) {
                    ForEach(AppState.alertThresholdChoices, id: \.self) { m in
                        Text(minutesShort(m)).tag(m)
                    }
                }
                Toggle(L("settings_quiet_hours_heading"), isOn: $appState.quietHoursEnabled)
                if appState.quietHoursEnabled {
                    hourPicker(L("settings_quiet_hours_start"), minutes: $appState.quietStartMinutes)
                    hourPicker(L("settings_quiet_hours_end"), minutes: $appState.quietEndMinutes)
                }
            }
            Section(L("settings_map")) {
                Toggle(L("settings_map_stop_names_label"), isOn: $appState.showMapStopNames)
            }
            Section(L("settings_theme_heading")) {
                Picker(L("settings_theme_heading"), selection: $appState.darkMode) {
                    Text(L("settings_theme_dark")).tag(true)
                    Text(L("settings_theme_light")).tag(false)
                }
                .pickerStyle(.segmented)
            }
            Section(L("language_heading")) {
                Picker(L("language_heading"), selection: $appState.localeTag) {
                    Text("Ελληνικά").tag("el")
                    Text("English").tag("en")
                }
                .pickerStyle(.segmented)
            }
            Section(L("settings_about")) {
                LabeledContent(L("app_name"), value: version)
                Text(L("settings_privacy_note"))
                    .font(.caption).foregroundStyle(.secondary)
                Link(L("settings_privacy_policy"), destination: URL(string: "https://ntufar.github.io/stasi/privacy.html")!)
            }
        }
        .navigationTitle(L("settings_heading"))
    }

    private func hourPicker(_ title: String, minutes: Binding<Int>) -> some View {
        Picker(title, selection: Binding(
            get: { minutes.wrappedValue / 60 },
            set: { minutes.wrappedValue = $0 * 60 })
        ) {
            ForEach(0..<24, id: \.self) { h in
                Text(String(format: "%02d:00", h)).tag(h)
            }
        }
    }
}
