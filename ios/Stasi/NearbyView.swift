import SwiftUI
import CoreLocation

// MARK: - Nearby stops via GPS, sorted by distance.

final class LocationManager: NSObject, ObservableObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    @Published var location: CLLocation?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    func request() {
        manager.requestWhenInUseAuthorization()
        manager.requestLocation()
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        location = locations.last
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}
}

struct NearbyView: View {
    @StateObject private var locator = LocationManager()
    @State private var stops: [NearbyStop] = []
    @State private var loading = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            List {
                if loading { ProgressView().frame(maxWidth: .infinity) }
                if let error {
                    Text(error).foregroundStyle(.red).font(.caption)
                }
                ForEach(stops) { s in
                    NavigationLink {
                        ArrivalsView(stopCode: s.stopCode, stopName: s.description, routeHint: nil)
                    } label: {
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
            .navigationTitle(String(localized: "tab_nearby"))
            .toolbar {
                Button {
                    locator.request()
                } label: { Image(systemName: "location.fill") }
            }
            .refreshable { await refresh() }
            .onReceive(locator.$location.compactMap { $0 }) { _ in
                Task { await refresh() }
            }
            .task {
                locator.request()
                // Refresh once a fix likely arrived.
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                await refresh()
            }
        }
    }

    private func refresh() async {
        guard let loc = locator.location else { return }
        loading = true
        error = nil
        do {
            stops = try await OasaAPI.shared.getClosestStops(
                lat: loc.coordinate.latitude, lng: loc.coordinate.longitude)
        } catch {
            self.error = String(localized: "nearby_error")
        }
        loading = false
    }
}
