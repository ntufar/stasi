import SwiftUI
import MapKit

// MARK: - Route map (port of MapScreen / MapViewModel; MapKit replaces MapLibre,
// no API key either way). Numbered stops with distinct origin/terminus, route
// polyline, live buses with heading every 15s, timetable tab, and nearby stop
// pins on the manual map before a line is chosen.

enum MapPreset: Hashable {
    /// OASA route code (from Arrivals rows, recents).
    case route(String)
    /// Internal line code (from Search); resolved to its primary direction.
    case line(String)
}

@MainActor
final class RouteMapModel: ObservableObject {
    let manualMode: Bool
    private let preset: MapPreset?

    @Published var input = ""
    @Published private(set) var appliedRouteCode = ""
    @Published private(set) var stops: [RouteStop] = []
    @Published private(set) var buses: [BusOnRoute] = []
    @Published private(set) var isLoading = false
    @Published private(set) var error: String?
    @Published var selectedVehicle: String?
    /// Public line number for the title ("Line 140").
    @Published private(set) var lineLabel: String?
    @Published private(set) var lineDescr: String?
    @Published private(set) var directions: [RouteDirection] = []
    /// Internal OASA line code for getDailySchedule (not the public number).
    @Published private(set) var internalLineCode: String?
    @Published var tab = 0 {
        didSet { if tab == 1 { fetchTimetableIfNeeded() } }
    }
    @Published private(set) var timetable: RouteDailyTimetable?
    @Published private(set) var timetableLoading = false
    @Published private(set) var timetableError: String?
    @Published private(set) var lastBusWarning = false
    /// Closest stops shown as pins on the manual map while no line is loaded.
    @Published private(set) var nearbyStops: [RouteStop] = []

    private var started = false
    private var routeTask: Task<Void, Never>?
    private var pollTask: Task<Void, Never>?
    private var timetableTask: Task<Void, Never>?
    private var timetableLineCode: String?
    private var repo: OasaRepository { OasaRepository.shared }

    init(preset: MapPreset?) {
        self.preset = preset
        manualMode = preset == nil
    }

    func start() {
        guard !started else { return }
        started = true
        switch preset {
        case let .route(rc):
            input = rc
            load { rc }
        case let .line(lc):
            input = lc
            load { [repo] in await repo.primaryRouteCodeForLine(lc) }
        case nil:
            break
        }
    }

    /// Manual entry: public line number first (users type "140"), else a route code.
    func applyInput() {
        let q = input.trimmed
        guard !q.isEmpty else { return }
        load { [repo] in await repo.primaryRouteCodeForLine(q) ?? q }
    }

    private func load(_ resolve: @escaping () async -> String?) {
        lineLabel = nil
        lineDescr = nil
        directions = []
        internalLineCode = nil
        nearbyStops = []
        routeTask?.cancel()
        routeTask = Task {
            isLoading = true
            error = nil
            guard let code = await resolve() else {
                isLoading = false
                error = L("toast_route_load_failed")
                return
            }
            await runRoute(code, refreshLineInfo: true)
        }
    }

    func selectDirection(_ routeCode: String) {
        guard !routeCode.isEmpty, routeCode != appliedRouteCode else { return }
        appliedRouteCode = routeCode
        selectedVehicle = nil
        routeTask?.cancel()
        routeTask = Task { await runRoute(routeCode, refreshLineInfo: false) }
    }

    func toggleDirection() {
        guard directions.count >= 2 else { return }
        let i = directions.firstIndex { $0.routeCode == appliedRouteCode } ?? 0
        selectDirection(directions[(i + 1) % directions.count].routeCode)
    }

    private func runRoute(_ code: String, refreshLineInfo: Bool) async {
        stopPolling()
        isLoading = true
        error = nil
        buses = []
        tab = 0
        timetable = nil
        timetableError = nil
        timetableLoading = false
        timetableLineCode = nil
        nearbyStops = []
        let fetch: RouteStopsFetch
        do {
            fetch = try await repo.getRouteStops(code)
        } catch {
            guard !Task.isCancelled else { return }
            isLoading = false
            stops = []
            self.error = L("map_error_route_load")
            return
        }
        guard !Task.isCancelled else { return }
        guard !fetch.stops.isEmpty else {
            isLoading = false
            stops = []
            error = L("map_error_no_stops", code)
            return
        }
        let route = fetch.effectiveRouteCode
        AppState.shared.recordRouteVisit(route)
        stops = fetch.stops
        appliedRouteCode = route
        isLoading = false
        startPolling()
        if refreshLineInfo || directions.isEmpty {
            let info = await repo.getLineRouteInfoForRoute(route, hintStopCode: fetch.stops.first?.stopCode)
            guard !Task.isCancelled else { return }
            if let info {
                lineLabel = info.lineId.nilIfBlank ?? info.lineCode
                lineDescr = info.lineDescr.nilIfBlank
                directions = info.directions
            }
            internalLineCode = info?.lineCode.nilIfBlank ?? repo.lineCodeForRoute(route)
        }
    }

    /// Live positions every 15s while the map is visible. The first fetch on
    /// (re)appearing bypasses the 15s bus cache (SPEC §5 RESUMED refresh).
    func startPolling() {
        guard !appliedRouteCode.isEmpty, !stops.isEmpty else { return }
        let rc = appliedRouteCode
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            var force = true
            while !Task.isCancelled {
                let fresh = await OasaRepository.shared.getBusesOnRoute(rc, forceRefresh: force)
                guard !Task.isCancelled, let self, self.appliedRouteCode == rc else { return }
                self.buses = fresh
                force = false
                try? await Task.sleep(nanoseconds: 15_000_000_000)
            }
        }
    }

    func stopPolling() {
        pollTask?.cancel()
        pollTask = nil
    }

    func refreshNearby(lat: Double, lng: Double) {
        guard manualMode, stops.isEmpty, !isLoading else { return }
        Task {
            let nearby = (try? await repo.getClosestStops(lat: lat, lng: lng)) ?? []
            guard manualMode, stops.isEmpty, !isLoading else { return }
            nearbyStops = nearby.prefix(20).enumerated().map { i, n in
                RouteStop(stopCode: n.stopCode, description: n.description, lat: n.lat, lng: n.lng, order: i + 1)
            }
        }
    }

    private func fetchTimetableIfNeeded() {
        guard let line = internalLineCode?.nilIfBlank else {
            timetable = nil
            timetableError = L("map_error_line_code_missing")
            return
        }
        if line == timetableLineCode, timetable != nil { return }
        timetableTask?.cancel()
        timetableTask = Task {
            timetableLoading = true
            timetableError = nil
            let tt = await repo.getRouteDailyTimetable(line)
            guard !Task.isCancelled else { return }
            timetableLineCode = line
            timetable = tt
            timetableLoading = false
            lastBusWarning = !tt.isEmpty && isLastBusApproaching(tt)
            timetableError = tt.isEmpty ? L("map_error_timetable_empty") : nil
        }
    }
}

// MARK: - View

private let athensRegion = MKCoordinateRegion(
    center: CLLocationCoordinate2D(latitude: 37.98, longitude: 23.73),
    span: MKCoordinateSpan(latitudeDelta: 0.15, longitudeDelta: 0.15))

/// Middle route stops show names below this latitude span (~MapLibre zoom 14).
private let midStopNameMaxSpan = 0.03

struct RouteMapView: View {
    @StateObject private var model: RouteMapModel
    @StateObject private var locator = LocationManager()
    @EnvironmentObject var appState: AppState
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.pushRoute) private var pushRoute
    @State private var camera: MapCameraPosition = .region(athensRegion)
    @State private var framedFingerprint: String?
    @State private var centeredOnUser = false
    @State private var latSpan: Double = athensRegion.span.latitudeDelta
    @State private var heading: Double = 0
    @State private var nearbyKey: String?

    init(preset: MapPreset?) {
        _model = StateObject(wrappedValue: RouteMapModel(preset: preset))
    }

    private var nearbyOnly: Bool { model.stops.isEmpty && !model.nearbyStops.isEmpty }
    private var mapStops: [RouteStop] { model.stops.isEmpty ? model.nearbyStops : model.stops }
    private var showTabs: Bool { !model.stops.isEmpty && model.error == nil && !model.isLoading }
    private var drawOrder: [(offset: Int, element: RouteStop)] {
        let all = Array(mapStops.enumerated())
        guard !nearbyOnly, all.count > 2 else { return all }
        return Array(all[1..<(all.count - 1)]) + [all[0], all[all.count - 1]]
    }
    private var fingerprint: String {
        mapStops.map { "\($0.stopCode):\($0.order)" }.joined(separator: "|") + "|n=\(nearbyOnly)"
    }

    var body: some View {
        VStack(spacing: 0) {
            if model.manualMode { inputRow }
            if showTabs {
                Picker("", selection: $model.tab) {
                    Text(L("map_tab_map")).tag(0)
                    Text(L("map_tab_timetable")).tag(1)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 12).padding(.vertical, 6)
            }
            if !showTabs || model.tab == 0 {
                ZStack {
                    map
                    if model.isLoading { ProgressView().controlSize(.large) }
                    VStack {
                        if let err = model.error {
                            Text(err)
                                .font(.callout)
                                .padding(.horizontal, 16).padding(.vertical, 8)
                                .background(Color(uiColor: .systemRed).opacity(0.9), in: RoundedRectangle(cornerRadius: 8))
                                .foregroundStyle(.white)
                                .padding(16)
                        }
                        Spacer()
                        HStack {
                            Spacer()
                            myLocationButton.padding(16)
                        }
                    }
                }
            } else {
                TimetablePanel(model: model)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) { titleView }
            if model.directions.count >= 2 {
                ToolbarItem(placement: .primaryAction) {
                    Button { model.toggleDirection() } label: {
                        Image(systemName: "arrow.left.arrow.right")
                    }
                    .accessibilityLabel(L("cd_swap_direction"))
                }
            }
        }
        .alert(L("map_vehicle_title"), isPresented: Binding(
            get: { model.selectedVehicle != nil }, set: { if !$0 { model.selectedVehicle = nil } })
        ) {
            Button(L("ok")) { model.selectedVehicle = nil }
        } message: {
            Text(L("map_vehicle_body", model.selectedVehicle ?? ""))
        }
        .task {
            model.start()
            locator.start()
        }
        .onAppear { model.startPolling() }
        .onDisappear {
            locator.stop()
            model.stopPolling()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.startPolling() }
        }
        .onChange(of: fingerprint) { _, _ in frameForStops() }
        .onChange(of: locator.location) { _, loc in
            guard let loc else { return }
            let c = loc.coordinate
            // Re-query nearby pins when the user moves ~250 m (Android lat*400 key).
            let key = "\(Int(c.latitude * 400))_\(Int(c.longitude * 400))"
            if model.manualMode, key != nearbyKey {
                nearbyKey = key
                model.refreshNearby(lat: c.latitude, lng: c.longitude)
            }
            // Manual map with nothing loaded yet: center on the user once.
            if mapStops.isEmpty, !centeredOnUser {
                centeredOnUser = true
                withAnimation { camera = .region(MKCoordinateRegion(center: c, latitudinalMeters: 1500, longitudinalMeters: 1500)) }
            }
        }
    }

    private var titleView: some View {
        let direction = model.directions.first { $0.routeCode == model.appliedRouteCode }?.descr.nilIfBlank
        return VStack(spacing: 0) {
            Text(model.lineLabel.map { L("map_title_line", $0) } ?? L("map_title_fallback"))
                .font(.headline).lineLimit(1)
            if let sub = direction ?? model.lineDescr {
                Text(sub).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
        }
    }

    private var inputRow: some View {
        HStack(spacing: 8) {
            TextField(L("map_label_route_or_code"), text: $model.input)
                .textFieldStyle(.roundedBorder)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .onSubmit { model.applyInput() }
            Button(L("map_show")) { model.applyInput() }
                .buttonStyle(.borderedProminent)
                .disabled(model.input.trimmed.isEmpty)
        }
        .padding(.horizontal, 12).padding(.vertical, 6)
    }

    private var map: some View {
        Map(position: $camera) {
            if !nearbyOnly && mapStops.count >= 2 {
                MapPolyline(coordinates: mapStops.map(\.coordinate))
                    .stroke(Color.routeLine, style: StrokeStyle(lineWidth: 5, lineCap: .round, lineJoin: .round))
            }
            // Middle stops first so origin/terminus (and their names) draw on top.
            ForEach(drawOrder, id: \.element.id) { index, stop in
                let kind = stopKind(index)
                Annotation(stop.description, coordinate: stop.coordinate, anchor: .center) {
                    Button {
                        pushRoute(.arrivals(stopCode: stop.stopCode,
                                            routeHint: nearbyOnly ? nil : model.appliedRouteCode.nilIfBlank))
                    } label: {
                        StopMarker(kind: kind, sequence: nearbyOnly ? nil : index + 1,
                                   name: showName(kind) ? truncateStopMapLabel(stop.description.nilIfBlank ?? stop.stopCode) : nil)
                    }
                    .buttonStyle(.plain)
                }
                .annotationTitles(.hidden)
            }
            ForEach(model.buses) { bus in
                Annotation(bus.vehicleNo, coordinate: bus.coordinate, anchor: .center) {
                    Button { model.selectedVehicle = bus.vehicleNo } label: {
                        BusArrow().rotationEffect(.degrees(busHeading(bus, stops: model.stops) - heading))
                    }
                    .buttonStyle(.plain)
                }
                .annotationTitles(.hidden)
            }
            UserAnnotation()
        }
        .mapStyle(.standard(pointsOfInterest: .excludingAll))
        .mapControls {
            MapCompass()
            MapScaleView()
        }
        .onMapCameraChange(frequency: .onEnd) { ctx in
            latSpan = ctx.region.span.latitudeDelta
            heading = ctx.camera.heading
        }
    }

    private var myLocationButton: some View {
        Button {
            guard locator.isAuthorized, let loc = locator.location else {
                locator.start()
                return
            }
            withAnimation {
                if mapStops.isEmpty {
                    camera = .region(MKCoordinateRegion(center: loc.coordinate, latitudinalMeters: 1500, longitudinalMeters: 1500))
                } else {
                    camera = fitting(mapStops.map(\.coordinate) + [loc.coordinate])
                }
            }
        } label: {
            Image(systemName: "location.fill")
                .font(.title3)
                .frame(width: 52, height: 52)
                .background(.regularMaterial, in: Circle())
                .shadow(radius: 3)
        }
        .accessibilityLabel(L("cd_my_location"))
    }

    private func stopKind(_ index: Int) -> StopMarker.Kind {
        if nearbyOnly { return .nearby }
        if index == 0 { return .start }
        if index == mapStops.count - 1 { return .end }
        return .mid
    }

    /// Origin/terminus/nearby names always; middle stops once zoomed in.
    private func showName(_ kind: StopMarker.Kind) -> Bool {
        guard appState.showMapStopNames else { return false }
        return kind != .mid || latSpan < midStopNameMaxSpan
    }

    /// Fit a newly shown route; nearby pins fit together with the user.
    /// Periodic refreshes keep the same fingerprint, so the camera is not reset.
    private func frameForStops() {
        guard fingerprint != framedFingerprint, !mapStops.isEmpty else { return }
        framedFingerprint = fingerprint
        var coords = mapStops.map(\.coordinate)
        if nearbyOnly, let user = locator.location?.coordinate { coords.append(user) }
        withAnimation { camera = fitting(coords) }
    }
}

private func fitting(_ coords: [CLLocationCoordinate2D]) -> MapCameraPosition {
    var rect = MKMapRect.null
    for c in coords {
        let p = MKMapPoint(c)
        rect = rect.union(MKMapRect(x: p.x, y: p.y, width: 0, height: 0))
    }
    guard !rect.isNull else { return .region(athensRegion) }
    let minPad = MKMapPointsPerMeterAtLatitude(coords.first?.latitude ?? 38) * 300
    return .rect(rect.insetBy(dx: -max(rect.width * 0.12, minPad), dy: -max(rect.height * 0.12, minPad)))
}

private extension RouteStop {
    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: lat, longitude: lng) }
}

private extension BusOnRoute {
    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: lat, longitude: lng) }
}

// MARK: - Markers

struct StopMarker: View {
    enum Kind { case start, mid, end, nearby }
    let kind: Kind
    let sequence: Int?
    let name: String?

    private var color: Color {
        switch kind {
        case .start: return .stopStart
        case .end: return .stopEnd
        case .mid, .nearby: return .stopMid
        }
    }

    private var size: CGFloat { kind == .start || kind == .end ? 24 : 19 }

    var body: some View {
        Circle()
            .fill(color)
            .overlay(Circle().stroke(.white, lineWidth: 2))
            .overlay {
                if let sequence {
                    Text("\(sequence)")
                        .font(.system(size: sequence > 99 ? 8 : 10, weight: .bold))
                        .foregroundStyle(.white)
                        .shadow(color: .black, radius: 1)
                }
            }
            .frame(width: size, height: size)
            .overlay(alignment: .top) {
                if let name {
                    // Below the pin without shifting the pin's anchor.
                    Text(name)
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(Color(rgb: 0x212121))
                        .padding(.horizontal, 3)
                        .background(.white.opacity(0.85), in: RoundedRectangle(cornerRadius: 3))
                        .fixedSize()
                        .offset(y: size + 2)
                }
            }
            .contentShape(Rectangle())
    }
}

/// Slim kite glyph pointing north; rotated to the bus heading (Android createBusArrowBitmap).
struct BusArrow: View {
    var body: some View {
        KiteShape()
            .fill(Color.busMarker)
            .overlay(KiteShape().stroke(Color.busStroke, style: StrokeStyle(lineWidth: 2, lineJoin: .round)))
            .frame(width: 28, height: 28)
            .contentShape(Rectangle())
    }
}

private struct KiteShape: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: r.midX, y: r.minY + r.height * 0.08))
        p.addLine(to: CGPoint(x: r.minX + r.width * 0.78, y: r.minY + r.height * 0.92))
        p.addLine(to: CGPoint(x: r.midX, y: r.minY + r.height * 0.72))
        p.addLine(to: CGPoint(x: r.minX + r.width * 0.22, y: r.minY + r.height * 0.92))
        p.closeSubpath()
        return p
    }
}

/// Heading toward the next stop after the closest one (Android computeBusHeading).
func busHeading(_ bus: BusOnRoute, stops: [RouteStop]) -> Double {
    let sorted = stops.sorted { $0.order < $1.order }
    guard sorted.count >= 2 else { return 0 }
    let here = CLLocation(latitude: bus.lat, longitude: bus.lng)
    var closest = 0
    var best = Double.greatestFiniteMagnitude
    for (i, s) in sorted.enumerated() {
        let d = here.distance(from: CLLocation(latitude: s.lat, longitude: s.lng))
        if d < best { best = d; closest = i }
    }
    if closest < sorted.count - 1 {
        let next = sorted[closest + 1]
        return bearingDegrees(bus.lat, bus.lng, next.lat, next.lng)
    }
    let prev = sorted[closest - 1], last = sorted[closest]
    return bearingDegrees(prev.lat, prev.lng, last.lat, last.lng)
}

func bearingDegrees(_ lat1: Double, _ lng1: Double, _ lat2: Double, _ lng2: Double) -> Double {
    let φ1 = lat1 * .pi / 180, φ2 = lat2 * .pi / 180
    let Δλ = (lng2 - lng1) * .pi / 180
    let y = sin(Δλ) * cos(φ2)
    let x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
    return (atan2(y, x) * 180 / .pi + 360).truncatingRemainder(dividingBy: 360)
}

// MARK: - Timetable tab (origin | terminus on the same row)

private struct TimetablePanel: View {
    @ObservedObject var model: RouteMapModel

    var body: some View {
        Group {
            if model.timetableLoading || (model.timetable == nil && model.timetableError == nil) {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let err = model.timetableError {
                Text(err).foregroundStyle(.secondary).padding(24)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let tt = model.timetable {
                table(tt)
            }
        }
    }

    private func table(_ tt: RouteDailyTimetable) -> some View {
        let origins = tt.originDepartures
        let termini = tt.terminusDepartures
        let rows = max(origins.count, termini.count)
        return List {
            Text(L("timetable_daily_title")).font(.headline).listRowSeparator(.hidden)
            if model.lastBusWarning {
                Text(L("timetable_last_service_warning"))
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(Color.lastServiceAmber)
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.lastServiceAmber.opacity(0.15), in: RoundedRectangle(cornerRadius: 8))
                    .listRowSeparator(.hidden)
            }
            HStack(alignment: .top) {
                Text(L("timetable_origin")).frame(maxWidth: .infinity, alignment: .leading)
                Divider()
                Text(L("timetable_terminus")).frame(maxWidth: .infinity, alignment: .leading)
            }
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Color.stasiAccent)
            if rows == 0 {
                Text(L("timetable_no_data")).foregroundStyle(.secondary)
            }
            ForEach(0..<rows, id: \.self) { i in
                HStack(alignment: .top) {
                    cell(origins[safe: i], highlighted: model.lastBusWarning && i == origins.count - 1)
                    Divider()
                    cell(termini[safe: i], highlighted: false)
                }
            }
            Text(L("timetable_footer_note"))
                .font(.caption).foregroundStyle(.secondary)
                .padding(.top, 16)
                .listRowSeparator(.hidden)
        }
        .listStyle(.plain)
    }

    private func cell(_ row: RouteDailyTimetableRow?, highlighted: Bool) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            if let row {
                Text(row.primaryRange).fontWeight(highlighted ? .semibold : .regular)
                if let sec = row.secondaryRange {
                    Text(sec).font(.subheadline).foregroundStyle(.secondary)
                }
            } else {
                Text("—").foregroundStyle(.tertiary)
            }
        }
        .monospacedDigit()
        .padding(.vertical, 4).padding(.horizontal, highlighted ? 6 : 0)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(highlighted ? Color.lastServiceAmber.opacity(0.15) : .clear, in: RoundedRectangle(cornerRadius: 4))
    }
}

private extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}
