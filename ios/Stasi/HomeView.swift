import SwiftUI

// MARK: - Home: favorite stops with live next arrivals (2 per stop) + recents.

struct HomeView: View {
    @EnvironmentObject var appState: AppState
    @EnvironmentObject var catalog: CatalogCache
    @State private var arrivalsByStop: [String: [ArrivalDetail]] = [:]
    @State private var error: String?
    @State private var showAddSheet = false

    var body: some View {
        NavigationStack {
            List {
                if !appState.recentStopCodes.isEmpty {
                    Section(String(localized: "home_recent")) {
                        ForEach(appState.recentStopCodes, id: \.self) { code in
                            NavigationLink(code) {
                                ArrivalsView(stopCode: code, stopName: code, routeHint: nil)
                            }
                        }
                    }
                }
                Section(String(localized: "home_favorites")) {
                    if appState.favorites.isEmpty {
                        Text(String(localized: "home_empty"))
                            .foregroundStyle(.secondary)
                    }
                    ForEach(appState.favorites) { fav in
                        NavigationLink {
                            ArrivalsView(stopCode: fav.stopCode, stopName: fav.displayName, routeHint: nil)
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(fav.displayName).font(.headline)
                                let rows = (arrivalsByStop[fav.stopCode] ?? []).prefix(2)
                                if rows.isEmpty {
                                    Text("…").foregroundStyle(.secondary)
                                }
                                ForEach(Array(rows)) { a in
                                    HStack {
                                        Text(a.lineLabel).bold()
                                        Text(a.destinationLabel).lineLimit(1)
                                            .foregroundStyle(.secondary)
                                        Spacer()
                                        Text(minutesText(a.effectiveMinutes))
                                            .font(.title3.bold())
                                            .foregroundStyle(.green)
                                    }
                                }
                            }
                        }
                        .swipeActions {
                            Button(String(localized: "action_remove"), role: .destructive) {
                                appState.toggleFavorite(stopCode: fav.stopCode)
                            }
                        }
                    }
                    .onMove { appState.moveFavorite(from: $0, to: $1) }
                }
            }
            .navigationTitle("Stasi")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { showAddSheet = true } label: {
                        Image(systemName: "plus")
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { Task { await refreshAll() } } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                }
            }
            .refreshable { await refreshAll() }
            .sheet(isPresented: $showAddSheet) {
                AddFavoriteSheet()
                    .environmentObject(appState)
            }
            .task {
                await catalog.ensureLoaded()
                await refreshAll()
            }
        }
    }

    private func refreshAll() async {
        await withTaskGroup(of: (String, [ArrivalDetail]).self) { group in
            for fav in appState.favorites {
                group.addTask {
                    let list = (try? await OasaAPI.shared.getStopArrivals(stopCode: fav.stopCode)) ?? []
                    return (fav.stopCode, list)
                }
            }
            for await (code, list) in group {
                arrivalsByStop[code] = list
            }
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
                TextField(String(localized: "stop_code_hint"), text: $code)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }
            .navigationTitle(String(localized: "action_add_favorite"))
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "action_save")) {
                        let c = code.trimmingCharacters(in: .whitespaces)
                        guard !c.isEmpty else { return }
                        if !appState.isFavorite(c) {
                            appState.favorites.append(FavoriteStop(stopCode: c))
                        }
                        dismiss()
                    }
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "action_cancel")) { dismiss() }
                }
            }
        }
    }
}

func minutesText(_ m: Int) -> String {
    if m >= 900 { return "–" }
    if m <= 0 { return String(localized: "now") }
    return "\(m)′"
}
