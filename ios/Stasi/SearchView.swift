import SwiftUI

// MARK: - Search (port of SearchScreen / SearchViewModel): lines + stops from
// the persisted catalog with Greek accent-insensitive + Greeklish matching.
// Opening Search warms the lines catalog and starts the 24h incremental sync
// that fills the stop index.

struct SearchView: View {
    @EnvironmentObject var catalog: CatalogStore
    @State private var query = ""
    @State private var lineHits: [BusLine] = []
    @State private var stopHits: [(code: String, name: String)] = []

    var body: some View {
        List {
            switch catalog.linesState {
            case .loading:
                HStack(spacing: 8) {
                    ProgressView()
                    Text(L("search_loading_catalog")).font(.caption).foregroundStyle(.secondary)
                }
            case .unavailable:
                VStack(alignment: .leading, spacing: 6) {
                    Text(L("search_catalog_unavailable")).font(.caption).foregroundStyle(.red)
                    Button(L("retry")) { Task { await startCatalog() } }
                }
            case .ready:
                EmptyView()
            }
            if !lineHits.isEmpty {
                Section(L("lines_heading")) {
                    ForEach(lineHits) { line in
                        NavigationLink(value: AppRoute.lineMap(lineCode: line.lineCode)) {
                            Text("\(line.displayId) · \(line.lineDescr)").lineLimit(2)
                        }
                    }
                }
            }
            if query.trimmed.count >= 2 {
                Section(L("stops_heading")) {
                    if stopHits.isEmpty && catalog.isSyncing {
                        HStack(spacing: 8) {
                            ProgressView()
                            Text(L("search_indexing_stops")).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    ForEach(stopHits, id: \.code) { s in
                        NavigationLink(value: AppRoute.arrivals(stopCode: s.code, routeHint: nil)) {
                            Text("\(s.name) (\(s.code))")
                        }
                    }
                }
            }
        }
        .navigationTitle(L("search_title"))
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always),
                    prompt: L("search_label_stop_or_line"))
        .autocorrectionDisabled()
        .textInputAutocapitalization(.never)
        .task(id: query) {
            // 250 ms debounce (Android onQueryChange).
            try? await Task.sleep(nanoseconds: 250_000_000)
            guard !Task.isCancelled else { return }
            runSearch()
        }
        .onChange(of: catalog.stopsRevision) { _, _ in runSearch() }
        .onChange(of: catalog.lines.count) { _, _ in runSearch() }
        .task { await startCatalog() }
    }

    private func startCatalog() async {
        guard await catalog.warmLinesIfEmpty() else { return }
        // Unstructured so leaving the tab does not cancel a multi-minute sync.
        Task { await catalog.syncIncremental() }
    }

    private func runSearch() {
        lineHits = catalog.searchLines(query)
        stopHits = catalog.searchStops(query)
    }
}
