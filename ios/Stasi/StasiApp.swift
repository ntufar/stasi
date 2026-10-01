import SwiftUI

@main
struct StasiApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var appState = AppState.shared
    @StateObject private var catalog = CatalogStore.shared
    @StateObject private var router = Router.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                // Rebuild the tree when the language changes so every `L(...)` re-renders
                // immediately (Android recreates the activity).
                .id(appState.localeTag)
                .environmentObject(appState)
                .environmentObject(catalog)
                .environmentObject(router)
                .environment(\.locale, Locale(identifier: appState.localeTag))
                .preferredColorScheme(appState.darkMode ? .dark : .light)
                .tint(.stasiAccent)
                .onOpenURL { router.handle(url: $0) }
                .task { await catalog.warmLinesIfEmpty() }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { ArrivalAlertCenter.shared.resumeAll() }
        }
    }
}

struct ContentView: View {
    @EnvironmentObject var router: Router

    var body: some View {
        TabView(selection: $router.tab) {
            NavigationStack(path: $router.homePath) {
                HomeView().withAppRoutes { router.homePath.append($0) }
            }
            .tabItem { Label(L("nav_home"), systemImage: "house.fill") }
            .tag(Router.Tab.home)

            NavigationStack(path: $router.searchPath) {
                SearchView().withAppRoutes { router.searchPath.append($0) }
            }
            .tabItem { Label(L("nav_search"), systemImage: "magnifyingglass") }
            .tag(Router.Tab.search)

            NavigationStack(path: $router.nearbyPath) {
                NearbyView().withAppRoutes { router.nearbyPath.append($0) }
            }
            .tabItem { Label(L("tab_nearby"), systemImage: "location.fill") }
            .tag(Router.Tab.nearby)

            NavigationStack(path: $router.mapPath) {
                RouteMapView(preset: nil).withAppRoutes { router.mapPath.append($0) }
            }
            .tabItem { Label(L("tab_map"), systemImage: "map.fill") }
            .tag(Router.Tab.map)

            NavigationStack {
                SettingsView()
            }
            .tabItem { Label(L("settings_heading"), systemImage: "gearshape.fill") }
            .tag(Router.Tab.settings)
        }
    }
}

extension View {
    /// Shared destinations so any stack can push Arrivals or a route map;
    /// `push` appends to this tab's path (used where NavigationLink misbehaves, e.g. map annotations).
    func withAppRoutes(push: @escaping (AppRoute) -> Void) -> some View {
        let pushRoute = PushRoute(action: push)
        return environment(\.pushRoute, pushRoute)
            .navigationDestination(for: AppRoute.self) { route in
                Group {
                    switch route {
                    case let .arrivals(stopCode, routeHint):
                        ArrivalsView(stopCode: stopCode, routeHint: routeHint)
                    case let .routeMap(routeCode):
                        RouteMapView(preset: .route(routeCode))
                    case let .lineMap(lineCode):
                        RouteMapView(preset: .line(lineCode))
                    }
                }
                .environment(\.pushRoute, pushRoute)
            }
    }
}

struct PushRoute {
    var action: (AppRoute) -> Void = { _ in }
    func callAsFunction(_ route: AppRoute) { action(route) }
}

private struct PushRouteKey: EnvironmentKey {
    static let defaultValue = PushRoute()
}

extension EnvironmentValues {
    var pushRoute: PushRoute {
        get { self[PushRouteKey.self] }
        set { self[PushRouteKey.self] = newValue }
    }
}
