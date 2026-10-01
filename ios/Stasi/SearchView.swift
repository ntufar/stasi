import SwiftUI

// MARK: - Search: stops + lines with Greek fuzzy match (accents ignored, Greeklish).

struct SearchView: View {
    @EnvironmentObject var catalog: CatalogCache
    @EnvironmentObject var appState: AppState
    @State private var query = ""
    @State private var stopResults: [(code: String, name: String)] = []
    @State private var searching = false
    @State private var stopCache: [(code: String, name: String, norm: String)] = []

    var body: some View {
        NavigationStack {
            List {
                let lineHits = catalog.lines.filter {
                    matchesGreekQuery(
                        haystackNorm: lineSearchNorm(
                            lineId: $0.lineId, lineCode: $0.lineCode, descr: $0.lineDescr),
                        query: query)
                }.prefix(20)
                if !lineHits.isEmpty {
                    Section(String(localized: "search_lines")) {
                        ForEach(Array(lineHits), id: \.lineCode) { line in
                            NavigationLink {
                                RouteMapView(preloadedLineQuery: line.lineId)
                            } label: {
                                VStack(alignment: .leading) {
                                    Text(line.lineId).bold()
                                    Text(line.lineDescr).foregroundStyle(.secondary).lineLimit(1)
                                }
                            }
                        }
                    }
                }
                Section(String(localized: "search_stops")) {
                    if searching { ProgressView() }
                    ForEach(stopResults.prefix(30), id: \.code) { s in
                        NavigationLink(s.name) {
                            ArrivalsView(stopCode: s.code, stopName: s.name, routeHint: nil)
                        }
                    }
                }
            }
            .navigationTitle(String(localized: "tab_search"))
            .searchable(text: $query, prompt: String(localized: "search_prompt"))
            .onChange(of: query) { _, q in
                Task { await runSearch(query: q) }
            }
            .task {
                await catalog.ensureLoaded()
                await catalog.sync()
                if stopCache.isEmpty { await buildStopCache() }
            }
        }
    }

    private func runSearch(query q: String) async {
        let t = q.trimmingCharacters(in: .whitespaces)
        guard t.count >= 2 else {
            stopResults = []
            return
        }
        if stopCache.isEmpty { await buildStopCache() }
        let hits = stopCache.filter { matchesGreekQuery(haystackNorm: $0.norm, query: t) }
        stopResults = hits.prefix(30).map { ($0.code, $0.name) }
    }

    /// Incremental catalog sync: lines → routes → stops (throttled 24h via CatalogCache).
    private func buildStopCache() async {
        searching = true
        defer { searching = false }
        var seen = Set<String>()
        var out: [(code: String, name: String, norm: String)] = []
        let lines = Array(catalog.lines.prefix(80))
        await withTaskGroup(of: [(String, String)].self) { group in
            for line in lines {
                group.addTask {
                    guard let routes = try? await OasaAPI.shared.webGetRoutes(lineCode: line.lineCode) else { return [] }
                    var pairs: [(String, String)] = []
                    for r in routes.prefix(4) {
                        let stops = (try? await OasaAPI.shared.webGetStops(routeCode: r.code)) ?? []
                        pairs += stops.map { ($0.stopCode, $0.description) }
                    }
                    return pairs
                }
            }
            for await pairs in group {
                for (code, name) in pairs where seen.insert(code).inserted {
                    out.append((code, name, stopSearchNorm(stopCode: code, descr: name)))
                }
            }
        }
        stopCache = out
        let q = query.trimmingCharacters(in: .whitespaces)
        if q.count >= 2 {
            stopResults = stopCache.filter { matchesGreekQuery(haystackNorm: $0.norm, query: q) }
                .prefix(30).map { ($0.code, $0.name) }
        }
    }
}
