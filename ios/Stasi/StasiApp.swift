import SwiftUI

@main
struct StasiApp: App {
    @StateObject private var appState = AppState.shared
    @StateObject private var catalog = CatalogCache.shared

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(appState)
                .environmentObject(catalog)
                .preferredColorScheme(.dark)
                .environment(\.locale, Locale(identifier: appState.localeTag))
        }
    }
}

struct ContentView: View {
    @EnvironmentObject var appState: AppState
    @State private var selection = 0

    var body: some View {
        TabView(selection: $selection) {
            HomeView()
                .tabItem { Label(String(localized: "tab_home"), systemImage: "house.fill") }
                .tag(0)
            SearchView()
                .tabItem { Label(String(localized: "tab_search"), systemImage: "magnifyingglass") }
                .tag(1)
            NearbyView()
                .tabItem { Label(String(localized: "tab_nearby"), systemImage: "location.fill") }
                .tag(2)
            RouteMapView()
                .tabItem { Label(String(localized: "tab_map"), systemImage: "map.fill") }
                .tag(3)
            SettingsView()
                .tabItem { Label(String(localized: "tab_settings"), systemImage: "gearshape.fill") }
                .tag(4)
        }
    }
}
