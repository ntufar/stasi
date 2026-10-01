import SwiftUI

// MARK: - Arrivals: big countdown minutes, line/destination, alerts, 30s poll.

struct ArrivalsView: View {
    let stopCode: String
    let stopName: String
    let routeHint: String?
    @EnvironmentObject var appState: AppState
    @State private var arrivals: [ArrivalDetail] = []
    @State private var loading = false
    @State private var error: String?
    @State private var fetchedAt: Date?
    @State private var tick = Date()
    @State private var pollTask: Task<Void, Never>?

    var body: some View {
        List {
            if loading && arrivals.isEmpty {
                ProgressView().frame(maxWidth: .infinity)
            }
            if let error {
                Text(error).foregroundStyle(.red).font(.caption)
            }
            ForEach(sortedArrivals) { a in
                HStack(alignment: .top, spacing: 12) {
                    Text(minutesText(a.effectiveMinutes))
                        .font(.system(size: 44, weight: .bold))
                        .foregroundStyle(.green)
                        .frame(minWidth: 90, alignment: .trailing)
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Text(a.lineLabel).bold()
                            if a.isLastBusWarning {
                                Text(String(localized: "last_service"))
                                    .font(.caption2.bold())
                                    .padding(.horizontal, 6).padding(.vertical, 2)
                                    .background(.orange.opacity(0.3))
                                    .clipShape(Capsule())
                            }
                        }
                        Text(a.destinationLabel)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                        if let clock = a.originScheduleClock {
                            Text(String(localized: "origin_departure \(clock)"))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        if a.isScheduleOnly {
                            Text(String(localized: "schedule_only"))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Spacer()
                    Button {
                        ArrivalAlertCenter.shared.toggle(
                            stopCode: stopCode, stopName: stopName,
                            routeCode: a.routeCode, vehCode: a.vehCode, lineLabel: a.lineLabel)
                    } label: {
                        Image(systemName: ArrivalAlertCenter.shared.isActive(
                            stopCode: stopCode, routeCode: a.routeCode, vehCode: a.vehCode)
                            ? "bell.fill" : "bell")
                    }
                    .buttonStyle(.borderless)
                    .disabled(a.isScheduleOnly)
                }
                .padding(.vertical, 4)
            }
            if let fetchedAt {
                Text(freshnessText(fetchedAt))
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .navigationTitle(stopName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                HStack {
                    Button {
                        appState.toggleFavorite(stopCode: stopCode)
                    } label: {
                        Image(systemName: appState.isFavorite(stopCode)
                              ? "star.fill" : "star")
                    }
                    Button {
                        Task { await refresh(force: true) }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    ShareLink(item: "Stasi stop \(stopCode) – \(stopName)") {
                        Image(systemName: "square.and.arrow.up")
                    }
                }
            }
        }
        .refreshable { await refresh(force: true) }
        .task {
            appState.pushRecentStop(stopCode)
            await refresh(force: true)
            // 30s poll + 15s wall-clock countdown tick.
            pollTask?.cancel()
            pollTask = Task {
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 15_000_000_000)
                    tick = Date()
                    let secs = Int(Date().timeIntervalSince(fetchedAt ?? Date()))
                    if secs >= 30 {
                        await refresh(force: true)
                    }
                }
            }
        }
        .onDisappear { pollTask?.cancel() }
    }

    private var sortedArrivals: [ArrivalDetail] {
        guard let hint = routeHint, !hint.isEmpty else {
            return arrivals.sorted { $0.effectiveMinutes < $1.effectiveMinutes }
        }
        return arrivals.sorted {
            let a0 = $0.routeCode == hint ? 0 : 1
            let a1 = $1.routeCode == hint ? 0 : 1
            if a0 != a1 { return a0 < a1 }
            return $0.effectiveMinutes < $1.effectiveMinutes
        }
    }

    private func refresh(force: Bool) async {
        loading = true
        error = nil
        do {
            arrivals = try await OasaAPI.shared.getStopArrivals(stopCode: stopCode)
            fetchedAt = Date()
        } catch {
            self.error = String(localized: "arrivals_error")
        }
        loading = false
    }

    private func freshnessText(_ date: Date) -> String {
        let secs = Int(Date().timeIntervalSince(date))
        if secs < 15 { return String(localized: "updated_just_now") }
        if secs < 60 { return String(localized: "updated_seconds \(secs)") }
        return String(localized: "updated_minutes \(secs / 60)")
    }
}
