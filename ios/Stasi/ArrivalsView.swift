import SwiftUI
import UIKit

// MARK: - Arrivals (port of ArrivalsScreen / ArrivalsViewModel): big countdown
// minutes, line · direction, origin-departure hints, last-service chip,
// schedule-only rows, alerts, 30s forced poll + 15s wall-clock tick.

enum ArrivalListRow: Identifiable {
    case live(ArrivalDetail)
    case scheduled(routeCode: String, lineLabel: String, originStop: String?, clock: String, minutesUntil: Int)

    var id: String {
        switch self {
        case let .live(a): return "live-\(a.routeCode)-\(a.vehCode)"
        case let .scheduled(rc, _, _, clock, _): return "sched-\(rc)-\(clock)"
        }
    }
}

/// Schedule-based origin hints become their own rows so they are not confused
/// with the live bus counted down above them (SPEC §7).
func buildArrivalListRows(_ arrivals: [ArrivalDetail]) -> [ArrivalListRow] {
    var emitted = Set<String>()
    var out: [ArrivalListRow] = []
    for a in arrivals {
        let clock = a.originScheduleClock?.nilIfBlank
        func scheduledRow() -> ArrivalListRow? {
            guard let clock, !a.routeCode.isEmpty, emitted.insert(a.routeCode).inserted else { return nil }
            return .scheduled(routeCode: a.routeCode, lineLabel: a.lineLabel, originStop: a.originStopDescription,
                              clock: clock.trimmed, minutesUntil: a.originDepartureMinutes ?? arrivalMinutesUnknown)
        }
        if a.isScheduleOnly {
            if let r = scheduledRow() { out.append(r) }
            continue
        }
        var live = a
        if clock != nil {
            live.originScheduleClock = nil
            live.originDepartureMinutes = nil
            live.originStopDescription = nil
        }
        out.append(.live(live))
        if let r = scheduledRow() { out.append(r) }
    }
    return out
}

struct ArrivalsView: View {
    let stopCode: String
    let routeHint: String?

    @EnvironmentObject var appState: AppState
    @ObservedObject private var alerts = ArrivalAlertCenter.shared
    @Environment(\.scenePhase) private var scenePhase
    @State private var title: String?
    @State private var arrivals: [ArrivalDetail] = []
    @State private var fetchedAt: Date?
    @State private var loading = true
    @State private var failed = false
    @State private var siblingRoutes: Set<String> = []
    @State private var now = Date()
    @State private var toast: String?

    private var stopTitle: String { title ?? stopCode }

    var body: some View {
        List {
            if loading && arrivals.isEmpty {
                HStack { Spacer(); ProgressView(); Spacer() }.listRowSeparator(.hidden)
            } else {
                if failed {
                    Text(L("arrivals_load_failed")).font(.callout).foregroundStyle(.red)
                }
                if let label = freshnessLabel(fetchedAt, now: now) {
                    Text(label).font(.caption).foregroundStyle(.secondary)
                }
                if arrivals.isEmpty && !failed {
                    Text(L("arrivals_share_no_arrivals")).foregroundStyle(.secondary)
                }
                ForEach(buildArrivalListRows(arrivals)) { row in
                    switch row {
                    case let .live(a): liveRow(a)
                    case let .scheduled(rc, lineLabel, originStop, clock, mins):
                        scheduledRow(routeCode: rc, lineLabel: lineLabel, originStop: originStop, clock: clock, minutes: mins)
                    }
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle(stopTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                Button { appState.toggleFavorite(stopCode) } label: {
                    Image(systemName: appState.isFavorite(stopCode) ? "star.fill" : "star")
                }
                .accessibilityLabel(L("cd_favorite"))
                actionsMenu
            }
        }
        .overlay(alignment: .bottom) {
            if let toast {
                Text(toast)
                    .font(.callout)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(.thinMaterial, in: Capsule())
                    .padding(.bottom, 24)
                    .transition(.opacity)
            }
        }
        .refreshable { await refresh() }
        .task {
            appState.recordStopVisit(stopCode)
            if let cached = OasaRepository.shared.cachedArrivals(stopCode), !cached.arrivals.isEmpty {
                publish(cached.arrivals, at: cached.fetchedAt)
                loading = false
            }
            title = await OasaRepository.shared.getStopLabel(stopCode)
        }
        .task {
            // 30s forced poll while visible (each tick bypasses the short cache).
            while !Task.isCancelled {
                await refresh()
                try? await Task.sleep(nanoseconds: 30_000_000_000)
            }
        }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                now = Date()
            }
        }
        .task(id: routeHint) {
            guard let hint = routeHint else { return }
            let info = await OasaRepository.shared.getLineRouteInfoForRoute(hint)
            siblingRoutes = Set(info?.directions.map(\.routeCode) ?? [])
            publish(arrivals, at: fetchedAt)
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await refresh() } }
        }
    }

    // MARK: Rows

    private func liveRow(_ a: ArrivalDetail) -> some View {
        let display = effectiveMinutes(a.minutes, since: fetchedAt, now: now)
        return HStack(alignment: .top, spacing: 8) {
            NavigationLink(value: AppRoute.routeMap(routeCode: a.routeCode)) {
                VStack(alignment: .leading, spacing: 0) {
                    Text(minutesText(display))
                        .font(.system(size: 48, weight: .bold))
                        .foregroundStyle(display >= arrivalMinutesUnknown ? Color.secondary : Color.stasiAccent)
                        .minimumScaleFactor(0.6)
                    HStack(spacing: 8) {
                        Text(a.lineLabel).font(.headline)
                        if a.isLastBusWarning { lastServiceChip }
                    }
                    .padding(.top, 10)
                    Text(a.destinationLabel)
                        .font(.subheadline).foregroundStyle(.secondary)
                        .padding(.top, 6)
                    if let origin = originText(a) {
                        Text(origin).font(.caption).foregroundStyle(.secondary).padding(.top, 8)
                    }
                }
            }
            .disabled(a.routeCode.isEmpty)
            if !a.routeCode.isEmpty {
                let active = alerts.isActive(stopCode: stopCode, routeCode: a.routeCode, vehCode: a.vehCode)
                Button {
                    alerts.toggle(stopCode: stopCode, stopTitle: stopTitle, routeCode: a.routeCode,
                                  vehCode: a.vehCode, lineLabel: a.lineLabel)
                } label: {
                    Image(systemName: active ? "bell.fill" : "bell")
                        .font(.title3)
                        .foregroundStyle(active ? Color.stasiAccent : Color.secondary)
                        .frame(width: 44, height: 44)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(L("cd_alert"))
                .padding(.top, 6)
            }
        }
        .padding(.vertical, 8)
    }

    private func scheduledRow(routeCode: String, lineLabel: String, originStop: String?, clock: String, minutes: Int) -> some View {
        let fromPart = originStop?.nilIfBlank.map { " (\($0))" } ?? ""
        let display = effectiveMinutes(minutes, since: fetchedAt, now: now)
        let approx: String? = display >= arrivalMinutesUnknown ? nil
            : display <= 0 ? L("arrivals_traffic_approx_less_than") : L("arrivals_traffic_approx_minutes", display)
        return NavigationLink(value: AppRoute.routeMap(routeCode: routeCode)) {
            VStack(alignment: .leading, spacing: 0) {
                Text(clock).font(.system(size: 40, weight: .bold)).foregroundStyle(.teal)
                Text(lineLabel).font(.headline).padding(.top, 8)
                Text(L("arrivals_schedule_from_origin", fromPart))
                    .font(.subheadline).foregroundStyle(.secondary).padding(.top, 6)
                if let approx {
                    Text(L("arrivals_traffic_start_approx", approx))
                        .font(.caption).foregroundStyle(.secondary).padding(.top, 6)
                }
            }
        }
        .disabled(routeCode.isEmpty)
        .padding(.vertical, 8)
    }

    private var lastServiceChip: some View {
        Text(L("arrivals_last_service_warning"))
            .font(.caption2.bold())
            .foregroundStyle(.black)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(Color.lastServiceAmber, in: RoundedRectangle(cornerRadius: 4))
    }

    /// Fallback live origin hint ("From origin (X): 12 min") when there is no schedule row.
    private func originText(_ a: ArrivalDetail) -> String? {
        guard let om = a.originDepartureMinutes, om < arrivalMinutesUnknown else { return nil }
        let display = effectiveMinutes(om, since: fetchedAt, now: now)
        let fromPart = a.originStopDescription?.nilIfBlank.map { " (\($0))" } ?? ""
        return L("arrivals_from_origin_minutes", fromPart, minutesShort(display))
    }

    // MARK: Actions menu (refresh, map, share, copy summary, copy link)

    private var actionsMenu: some View {
        Menu {
            Button(L("cd_refresh"), systemImage: "arrow.clockwise") { Task { await refresh() } }
            if let rc = arrivals.first(where: { !$0.routeCode.isEmpty })?.routeCode {
                NavigationLink(value: AppRoute.routeMap(routeCode: rc)) {
                    Label(L("cd_map"), systemImage: "map")
                }
            }
            ShareLink(item: shareText(), subject: Text(L("arrivals_share_subject", stopTitle))) {
                Label(L("arrivals_action_share"), systemImage: "square.and.arrow.up")
            }
            Button(L("arrivals_action_copy_summary"), systemImage: "doc.on.doc") {
                UIPasteboard.general.string = summaryText()
                showToast(L("arrivals_copied_summary"))
            }
            Button(L("arrivals_action_copy_link"), systemImage: "link") {
                UIPasteboard.general.string = stopDeepLink(stopCode)
                showToast(L("arrivals_copied_link"))
            }
        } label: {
            Image(systemName: "ellipsis.circle")
        }
        .accessibilityLabel(L("cd_more_actions"))
    }

    private func shareMinutes(_ m: Int) -> String {
        m >= arrivalMinutesUnknown ? L("arrivals_share_minutes_unknown") : minutesShort(m)
    }

    private func shareText() -> String {
        var lines = [L("arrivals_share_heading", stopTitle, stopCode)]
        if let f = freshnessLabel(fetchedAt, now: now) { lines.append(f) }
        let live = arrivals.filter { !$0.isScheduleOnly }
        if live.isEmpty {
            lines.append(L("arrivals_share_no_arrivals"))
        } else {
            lines.append(L("arrivals_share_next_arrivals"))
            for (i, a) in live.prefix(3).enumerated() {
                var line = "\(i + 1). \(shareMinutes(a.minutes)) · \(a.lineLabel) → \(a.destinationLabel)"
                if let om = a.originDepartureMinutes, om < arrivalMinutesUnknown {
                    line += " | " + L("arrivals_share_from_origin", shareMinutes(om))
                }
                lines.append(line)
            }
        }
        lines.append(L("arrivals_share_deep_link", stopDeepLink(stopCode)))
        return lines.joined(separator: "\n")
    }

    private func summaryText() -> String {
        var lines = [L("arrivals_share_heading", stopTitle, stopCode)]
        let live = arrivals.filter { !$0.isScheduleOnly }
        if live.isEmpty {
            lines.append(L("arrivals_share_no_arrivals"))
        } else {
            for a in live.prefix(2) {
                lines.append("\(shareMinutes(a.minutes)) · \(a.lineLabel) → \(a.destinationLabel)")
            }
        }
        return lines.joined(separator: "\n")
    }

    private func showToast(_ text: String) {
        withAnimation { toast = text }
        Task {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            withAnimation { toast = nil }
        }
    }

    // MARK: Loading

    /// Live minutes publish first; origin / last-bus / schedule-only rows fill in on a second pass.
    private func refresh() async {
        let repo = OasaRepository.shared
        let snap = await repo.getStopArrivalsSnapshot(stopCode, forceRefresh: true)
        loading = false
        failed = snap.isStale
        if snap.isStale && snap.fetchedAt == nil && !arrivals.isEmpty { return }
        publish(snap.arrivals, at: snap.fetchedAt)
        now = Date()
        let enriched = await repo.enrichStopArrivals(stopCode, snap.arrivals, routeHint: routeHint)
        // Ignore if a newer fetch landed meanwhile.
        guard fetchedAt == snap.fetchedAt else { return }
        publish(enriched, at: snap.fetchedAt)
    }

    private func publish(_ list: [ArrivalDetail], at date: Date?) {
        fetchedAt = date
        guard let hint = routeHint else {
            arrivals = list.sorted { $0.minutes < $1.minutes }
            return
        }
        func rank(_ a: ArrivalDetail) -> Int {
            a.routeCode == hint ? 0 : siblingRoutes.contains(a.routeCode) ? 1 : 2
        }
        arrivals = list.sorted { (rank($0), $0.minutes) < (rank($1), $1.minutes) }
    }
}
