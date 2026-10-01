import SwiftUI

// MARK: - Home: recent stop/route shortcuts + favorite stops with the next two
// arrivals each, refreshed every 30s (port of HomeScreen / HomeViewModel).

struct HomeView: View {
    @EnvironmentObject var appState: AppState
    @Environment(\.scenePhase) private var scenePhase
    @State private var snapshots: [String: ArrivalSnapshot] = [:]
    @State private var titles: [String: String] = [:]
    @State private var recentRouteTitle: String?
    @State private var showAddSheet = false
    @State private var renameTarget: FavoriteStop?
    @State private var renameValue = ""
    @State private var now = Date()

    var body: some View {
        List {
            if !appState.recentStops.isEmpty || appState.recentRoute != nil {
                Section(L("home_recent")) {
                    ForEach(appState.recentStops, id: \.code) { visit in
                        NavigationLink(value: AppRoute.arrivals(stopCode: visit.code, routeHint: nil)) {
                            recentRow(caption: L("home_recent_stop"), title: titles[visit.code] ?? visit.code,
                                      subtitle: visit.code)
                        }
                    }
                    if let route = appState.recentRoute {
                        NavigationLink(value: AppRoute.routeMap(routeCode: route.code)) {
                            recentRow(caption: L("home_recent_route"), title: recentRouteTitle ?? route.code,
                                      subtitle: route.code)
                        }
                    }
                }
            }
            Section(L("home_favorites")) {
                if appState.favorites.isEmpty {
                    Text(L("home_favorites_empty")).foregroundStyle(.secondary)
                }
                ForEach(appState.favorites) { fav in
                    NavigationLink(value: AppRoute.arrivals(stopCode: fav.stopCode, routeHint: nil)) {
                        favoriteCard(fav)
                    }
                    .contextMenu { favoriteMenu(fav) }
                    .swipeActions {
                        Button(L("home_favorite_remove"), role: .destructive) {
                            appState.removeFavorite(fav.stopCode)
                        }
                        Button(L("home_favorite_rename")) { beginRename(fav) }.tint(.blue)
                    }
                }
                .onMove { appState.moveFavorite(from: $0, to: $1) }
            }
        }
        .navigationTitle(L("app_name"))
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                Button { Task { await refreshAll(force: true) } } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .accessibilityLabel(L("cd_refresh"))
                Button { showAddSheet = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel(L("action_add_favorite"))
            }
            if appState.favorites.count > 1 {
                ToolbarItem(placement: .topBarLeading) { EditButton() }
            }
        }
        .refreshable { await refreshAll(force: true) }
        .sheet(isPresented: $showAddSheet) { AddFavoriteSheet() }
        .alert(L("home_favorite_rename_title"), isPresented: Binding(
            get: { renameTarget != nil }, set: { if !$0 { renameTarget = nil } })
        ) {
            TextField(L("home_favorite_alias_label"), text: $renameValue)
            Button(L("ok")) {
                if let t = renameTarget { appState.renameFavorite(t.stopCode, alias: renameValue) }
                renameTarget = nil
            }
            Button(L("action_cancel"), role: .cancel) { renameTarget = nil }
        }
        .task(id: appState.favorites.map(\.stopCode)) {
            // Initial load + 30s ticker while Home is visible (Android tickerFlow).
            while !Task.isCancelled {
                await refreshAll(force: false)
                try? await Task.sleep(nanoseconds: 30_000_000_000)
            }
        }
        .task(id: appState.recentStops.map(\.code)) {
            for v in appState.recentStops where titles[v.code] == nil {
                titles[v.code] = await OasaRepository.shared.getStopLabel(v.code)
            }
        }
        .task(id: appState.recentRoute?.code) { await loadRecentRouteTitle() }
        .task {
            // Freshness labels tick between refreshes.
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                now = Date()
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await refreshAll(force: false) } }
        }
    }

    private func recentRow(caption: String, title: String, subtitle: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(caption).font(.caption).foregroundStyle(.secondary)
            Text(title).font(.headline)
            Text(subtitle).font(.caption).foregroundStyle(.secondary)
        }
    }

    private func favoriteCard(_ fav: FavoriteStop) -> some View {
        let name = titles[fav.stopCode] ?? fav.stopCode
        let snap = snapshots[fav.stopCode]
        return VStack(alignment: .leading, spacing: 4) {
            Text(fav.alias ?? name)
                .font(.title3.bold())
                .foregroundStyle(Color.stasiAccent)
            if fav.alias != nil {
                Text(name).font(.caption).foregroundStyle(.secondary)
            }
            if let label = freshnessLabel(snap?.fetchedAt, now: now, key: "home_updated_at") {
                Text(label).font(.caption2).foregroundStyle(.secondary)
            }
            if let snap {
                let rows = snap.arrivals.prefix(2)
                if rows.isEmpty {
                    Text(L("home_no_arrivals")).font(.caption).foregroundStyle(.secondary)
                }
                ForEach(Array(rows)) { a in
                    VStack(alignment: .leading, spacing: 1) {
                        HStack(spacing: 6) {
                            Text(minutesText(effectiveMinutes(a.minutes, since: snap.fetchedAt, now: now)))
                                .font(.subheadline.weight(.medium))
                                .foregroundStyle(.secondary)
                            Text("·").foregroundStyle(.tertiary)
                            Text(a.lineLabel).font(.subheadline.weight(.semibold)).lineLimit(1)
                        }
                        Text("→ \(a.destinationLabel)")
                            .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    .padding(.top, 4)
                }
            } else {
                ProgressView().padding(.top, 4)
            }
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private func favoriteMenu(_ fav: FavoriteStop) -> some View {
        Button(L("home_favorite_rename"), systemImage: "pencil") { beginRename(fav) }
        Button(L("home_favorite_move_up"), systemImage: "arrow.up") { appState.moveFavorite(fav.stopCode, delta: -1) }
        Button(L("home_favorite_move_down"), systemImage: "arrow.down") { appState.moveFavorite(fav.stopCode, delta: 1) }
        Button(L("home_favorite_remove"), systemImage: "trash", role: .destructive) {
            appState.removeFavorite(fav.stopCode)
        }
    }

    private func beginRename(_ fav: FavoriteStop) {
        renameValue = fav.alias ?? ""
        renameTarget = fav
    }

    private func refreshAll(force: Bool) async {
        let repo = OasaRepository.shared
        let codes = appState.favorites.map(\.stopCode)
        await withTaskGroup(of: (String, String, ArrivalSnapshot).self) { group in
            for code in codes {
                group.addTask {
                    async let title = repo.getStopLabel(code)
                    let snap = await repo.getStopArrivalsSnapshot(code, forceRefresh: force)
                    return (code, await title, snap)
                }
            }
            for await (code, title, snap) in group {
                titles[code] = title
                // Keep the previous rows if this fetch failed and nothing is cached.
                if !(snap.isStale && snap.fetchedAt == nil && snapshots[code] != nil) {
                    snapshots[code] = snap
                }
            }
        }
        now = Date()
    }

    private func loadRecentRouteTitle() async {
        guard let rc = appState.recentRoute?.code else { recentRouteTitle = nil; return }
        let info = await OasaRepository.shared.getLineRouteInfoForRoute(rc)
        switch (info?.lineId.nilIfBlank, info?.lineDescr.nilIfBlank) {
        case let (id?, d?): recentRouteTitle = "\(id) · \(d)"
        case let (id?, nil): recentRouteTitle = id
        case let (nil, d?): recentRouteTitle = d
        default: recentRouteTitle = rc
        }
    }
}

private struct AddFavoriteSheet: View {
    @EnvironmentObject var appState: AppState
    @Environment(\.dismiss) var dismiss
    @State private var code = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField(L("stop_code_hint"), text: $code)
                    .keyboardType(.numberPad)
                    .autocorrectionDisabled()
            }
            .navigationTitle(L("action_add_favorite"))
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("action_save")) {
                        appState.addFavorite(code)
                        dismiss()
                    }
                    .disabled(code.trimmed.isEmpty)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("action_cancel")) { dismiss() }
                }
            }
        }
    }
}
