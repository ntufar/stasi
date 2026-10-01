import SwiftUI
import CoreLocation

// MARK: - Location (Android fused location equivalent). Never stored.

@MainActor
final class LocationManager: NSObject, ObservableObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    @Published var location: CLLocation?
    @Published var authorization: CLAuthorizationStatus

    override init() {
        authorization = manager.authorizationStatus
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        manager.distanceFilter = 50
        location = manager.location
    }

    var isAuthorized: Bool {
        authorization == .authorizedWhenInUse || authorization == .authorizedAlways
    }

    var isDenied: Bool { authorization == .denied || authorization == .restricted }

    /// One-shot fix (asks for permission first if needed).
    func request() {
        if authorization == .notDetermined { manager.requestWhenInUseAuthorization() }
        manager.requestLocation()
    }

    /// Continuous updates while a map is visible.
    func start() {
        if authorization == .notDetermined { manager.requestWhenInUseAuthorization() }
        manager.startUpdatingLocation()
    }

    func stop() { manager.stopUpdatingLocation() }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let last = locations.last
        Task { @MainActor in self.location = last }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            self.authorization = status
            if self.isAuthorized { manager.requestLocation() }
        }
    }
}

// MARK: - Nearby stops via GPS, sorted by distance (Android Home "Nearby stops").

struct NearbyView: View {
    @StateObject private var locator = LocationManager()
    @State private var stops: [NearbyStop] = []
    @State private var loading = false
    @State private var failed = false

    var body: some View {
        List {
            if locator.isDenied {
                Text(L("nearby_permission_denied")).font(.callout).foregroundStyle(.secondary)
            }
            if failed {
                Text(L("home_nearby_load_failed")).font(.callout).foregroundStyle(.red)
            }
            if loading && stops.isEmpty {
                HStack { Spacer(); ProgressView(); Spacer() }
            }
            ForEach(stops) { s in
                NavigationLink(value: AppRoute.arrivals(stopCode: s.stopCode, routeHint: nil)) {
                    HStack {
                        VStack(alignment: .leading) {
                            Text(s.description).font(.headline)
                            Text(s.stopCode).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        if let d = s.distanceKm {
                            Text(d < 1 ? "\(Int(d * 1000)) m" : String(format: "%.1f km", d))
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .navigationTitle(L("home_nearby_stops"))
        .toolbar {
            Button { locator.request() } label: { Image(systemName: "location.fill") }
                .accessibilityLabel(L("home_location_button"))
        }
        .refreshable {
            locator.request()
            await refresh()
        }
        .onReceive(locator.$location.compactMap { $0 }) { _ in
            Task { await refresh() }
        }
        .task { locator.request() }
    }

    private func refresh() async {
        guard let loc = locator.location else { return }
        loading = true
        defer { loading = false }
        do {
            stops = Array(try await OasaRepository.shared.getClosestStops(
                lat: loc.coordinate.latitude, lng: loc.coordinate.longitude).prefix(20))
            failed = false
        } catch {
            failed = true
        }
    }
}
