import SwiftUI

// MARK: - Settings: alert threshold, map names, quiet hours, language.

struct SettingsView: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        NavigationStack {
            Form {
                Section(String(localized: "settings_alerts")) {
                    Picker(String(localized: "settings_threshold"),
                           selection: $appState.alertThresholdMinutes) {
                        ForEach(AppState.alertThresholdChoices, id: \.self) { m in
                            Text("\(m)").tag(m)
                        }
                    }
                    Toggle(String(localized: "settings_quiet_enabled"),
                           isOn: $appState.quietHoursEnabled)
                    if appState.quietHoursEnabled {
                        Stepper(String(localized: "settings_quiet_start \(appState.quietStartMinutes / 60):\(String(format: "%02d", appState.quietStartMinutes % 60))"),
                                value: $appState.quietStartMinutes, in: 0...(24 * 60 - 1), step: 15)
                        Stepper(String(localized: "settings_quiet_end \(appState.quietEndMinutes / 60):\(String(format: "%02d", appState.quietEndMinutes % 60))"),
                                value: $appState.quietEndMinutes, in: 0...(24 * 60 - 1), step: 15)
                    }
                }
                Section(String(localized: "settings_map")) {
                    Toggle(String(localized: "settings_show_names"),
                           isOn: $appState.showMapStopNames)
                }
                Section(String(localized: "settings_language")) {
                    Picker(String(localized: "settings_language"),
                           selection: $appState.localeTag) {
                        Text("Ελληνικά").tag("el")
                        Text("English").tag("en")
                    }
                    .pickerStyle(.segmented)
                }
                Section(String(localized: "settings_about")) {
                    Text("Stasi · Athens buses · OASA Telematics")
                        .foregroundStyle(.secondary)
                    Text(String(localized: "settings_privacy_note"))
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .navigationTitle(String(localized: "tab_settings"))
        }
    }
}
