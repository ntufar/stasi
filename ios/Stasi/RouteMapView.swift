import SwiftUI
import MapKit

// MARK: - Route map (MapKit, no API key): route polyline + numbered stops,
// live buses, nearby pins when no route, timetable tab.

struct RouteMapView: View {
    @EnvironmentObject var appState: AppState
    var preloadedLineQuery: String? = nil
    @State private var lineQuery: String = ""
    @State private var routes: [(code: String, descr: String, type: Int, lineCode: String)] = []
    @State private var selectedRoute: String?
    @State private var stops: [RouteStop] = []
    @State private var buses: [BusOnRoute] = []
    @State private var timetable: RouteDailyTimetable?
    @State private var lineInfo: LineRouteInfo?
    @State private var tab = 0
    @State private var error: String?
    @State private var loading = false
    @State private var camera: MapCameraPosition = .automatic
    @State private var pollTask: Task<Void, Never>?

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack {
                    TextField(String(localized: "line_code_hint"), text: $lineQuery)
                        .textFieldStyle(.roundedBorder)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .onSubmit { Task { await loadLine() } }
                    Button(String(localized: "action_show")) {
                        Task { await loadLine() }
                    }
                    .buttonStyle(.borderedProminent)
                }
                .padding(8)

                if !routes.isEmpty {
                    Picker(String(localized: "direction_label"), selection: Binding(
                        get: { selectedRoute ?? routes.first?.code },
                        set: { v in
                            selectedRoute = v
                            Task { await loadRoute() }
                        })) {
                        ForEach(routes, id: \.code) { r in
                            Text(r.descr.isEmpty ? r.code : r.descr).tag(Optional(r.code))
                        }
                    }
                    .pickerStyle(.menu)
                    .padding(.horizontal, 8)

                    Picker("", selection: $tab) {
                        Text(String(localized: "map_tab")).tag(0)
                        Text(String(localized: "timetable_tab")).tag(1)
                    }
                    .pickerStyle(.segmented)
                    .padding(8)
                }

                if tab == 0 {
                    Map(position: $camera) {
                        ForEach(stops) { s in
                            Annotation(s.description, coordinate: CLLocationCoordinate2D(
                                latitude: s.lat, longitude: s.lng)) {
                                NavigationLink {
                                    ArrivalsView(
                                        stopCode: s.stopCode, stopName: s.description,
                                        routeHint: selectedRoute)
                                } label: {
                                    VStack(spacing: 0) {
                                        Text("\(s.order)")
                                            .font(.caption2.bold())
                                            .foregroundStyle(.white)
                                            .frame(width: 26, height: 26)
                                            .background(
                                                s.order == 1 || s.order == stops.count
                                                ? Color.orange : Color.blue,
                                                in: Circle())
                                        if appState.showMapStopNames {
                                            Text(s.description)
                                                .font(.caption2)
                                                .padding(2)
                                                .background(.thinMaterial)
                                                .clipShape(RoundedRectangle(cornerRadius: 4))
                                        }
                                    }
                                }
                            }
                        }
                        ForEach(buses) { b in
                            Annotation("🚌 \(b.vehicleNo)", coordinate: CLLocationCoordinate2D(
                                latitude: b.lat, longitude: b.lng)) {
                                Text("🚌")
                            }
                        }
                        if stops.count >= 2 {
                            MapPolyline(coordinates: stops.map {
                                CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lng)
                            })
                            .stroke(.blue, lineWidth: 3)
                        }
                    }
                    .mapStyle(.standard)
                } else {
                    timetableView
                }

                if let error {
                    Text(error).foregroundStyle(.red).font(.caption).padding(4)
                }
                if loading { ProgressView().padding(4) }
            }
            .navigationTitle(String(localized: "tab_map"))
            .navigationBarTitleDisplayMode(.inline)
            .task {
                if let q = preloadedLineQuery, !q.isEmpty {
                    lineQuery = q
                    await loadLine()
                }
                pollTask?.cancel()
                pollTask = Task {
                    while !Task.isCancelled {
                        try? await Task.sleep(nanoseconds: 15_000_000_000)
                        if selectedRoute != nil {
                            await refreshBuses()
                        }
                    }
                }
            }
            .onDisappear { pollTask?.cancel() }
        }
    }

    private var timetableView: some View {
        List {
            if let t = timetable {
                Section(String(localized: "timetable_origin")) {
                    if t.originDepartures.isEmpty {
                        Text("—").foregroundStyle(.secondary)
                    }
                    ForEach(t.originDepartures, id: \.primaryRange) { r in
                        Text(r.secondaryRange.map { "\(r.primaryRange) · \($0)" } ?? r.primaryRange)
                    }
                }
                Section(String(localized: "timetable_terminus")) {
                    if t.terminusDepartures.isEmpty {
                        Text("—").foregroundStyle(.secondary)
                    }
                    ForEach(t.terminusDepartures, id: \.primaryRange) { r in
                        Text(r.secondaryRange.map { "\(r.primaryRange) · \($0)" } ?? r.primaryRange)
                    }
                }
            } else {
                Text(String(localized: "timetable_empty")).foregroundStyle(.secondary)
            }
        }
    }

    private func loadLine() async {
        let q = lineQuery.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return }
        loading = true
        error = nil
        do {
            let (info, found) = try await OasaAPI.shared.resolveLineToRoutes(lineQuery: q)
            lineInfo = info
            routes = found
            selectedRoute = found.first?.code
            appState.pushRecentLine(q)
            await loadRoute()
        } catch {
            self.error = String(localized: "map_load_error")
        }
        loading = false
    }

    private func loadRoute() async {
        guard let code = selectedRoute else { return }
        loading = true
        error = nil
        do {
            let fetched = try await OasaAPI.shared.webGetStops(routeCode: code)
            guard !fetched.isEmpty else { throw OasaError.empty }
            stops = fetched
            if let first = fetched.first, let last = fetched.last {
                camera = .rect(MKMapRect(
                    origin: MKMapPoint(CLLocationCoordinate2D(
                        latitude: min(first.lat, last.lat),
                        longitude: min(first.lng, last.lng))),
                    size: MKMapSize(
                        width: abs(first.lng - last.lng) * 100_000 + 50_000,
                        height: abs(first.lat - last.lat) * 100_000 + 50_000)))
            }
            await refreshBuses()
            if let lc = lineInfo?.lineCode {
                timetable = try? await OasaAPI.shared.getDailySchedule(lineCode: lc)
            }
        } catch {
            self.error = String(localized: "map_load_error")
        }
        loading = false
    }

    private func refreshBuses() async {
        guard let code = selectedRoute else { return }
        buses = (try? await OasaAPI.shared.getBusLocation(routeCode: code)) ?? []
    }
}
