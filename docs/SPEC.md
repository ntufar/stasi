# Stasi – Athens Bus App Specification
Version: 0.5 | Date: 2026-05-10 | Author: Nicolai Tufar

## 1. Purpose
Stasi is a fast, private Android app for Athens public transport. It replaces the official OASA Telematics app by showing real-time arrivals, nearby stops, and route maps without ads, accounts, or clutter.

Goal: open app → see your bus in under 1 second.

## 2. Target Users
- Daily commuters in Athens
- Tourists with limited data
- Users frustrated by slow official app

Primary language: Greek UI, with English fallback.

## 3. Core Features (MVP)
1. Home screen with favorite stops, showing next 2 arrivals per stop live
2. Search stops and lines by name, Greek fuzzy match (ignores accents)
3. Arrivals screen: big minutes, line ID, destination; optional **origin departure** line when the stop is not that route’s first stop (see item 7)
4. Nearby stops using GPS, sorted by distance
5. Route map: draw **all route stops** on the map (not only the polyline), with **clear direction of travel**:
   - **Tabs** when a route has loaded: **Χάρτης** (live map) and **Δρομολόγια** (daily timetable from OASA `getDailySchedule` using the line’s internal `line_code`; **Αφετηρία** / **Τέρμα** sections map to API `come` / `go` time windows);
   - stops ordered along the route with **sequence numbers** (1 … N);
   - **first stop** (departure) and **last stop** (terminus) visually distinct from middle stops (e.g. color/size);
   - **live buses** shown with **heading** (arrow or rotated icon) approximating direction toward the next segment of the route.
   - **Initial map camera:** when a route is first shown for a given stop sequence, the map **centers and zooms on the user’s location** if a GPS fix is available (after a short wait for a fix); otherwise it **fits the whole route** in view. Periodic live refresh must **not** reset the camera. Changing route/direction (different stop sequence) runs this logic again. The **My Location** FAB still fits **route + user** in one view when pressed (with location permission).
6. **Map → arrivals:** tapping a **stop marker** on the route map opens the **Arrivals** screen for that stop code (same as Search/Home), showing upcoming buses and times.
7. **Arrivals at a stop (not the route origin):** for each upcoming service, when the viewed stop is **not** the **first stop** of that route (in OASA route order), the UI also shows **when the next bus on the same route is expected to depart from the route’s origin** (first stop), as a secondary line (Greek copy, e.g. departure-from-terminus wording). If the user is already at the origin stop, this line is omitted. Implementation uses cached or fetched route stop order plus live arrivals at the origin stop code.
8. Offline cache: lines and stops cached 24h, arrivals cached 30s

## 4. Out of Scope for MVP
- Ticket purchase
- Trip planning across multiple lines
- Notifications

## 5. Tech Stack
- Language: Kotlin 1.9
- UI: Jetpack Compose + Material 3
- Architecture: MVVM, Repository pattern
- Networking: Retrofit2 + Gson, Coroutines
- Storage: Room for cache, DataStore for preferences
- Maps: MapLibre SDK (open source, no API key)
- Min SDK 26, Target SDK 34

## 6. Data Sources
Base URL: http://telematics.oasa.gr/api/
All calls are POST with query params.

Endpoints:
- webGetLines → List<Line> {LineCode, LineID, LineDescr}
- webGetRoutes?p1=LINE_CODE → List<Route> {RouteCode, RouteDescr}
- webGetStops?p1=ROUTE_CODE → List<Stop> {StopCode, StopDescr}
- getStopNameAndXY?p1=STOP_CODE → {StopLat, StopLng}
- getStopArrivals?p1=STOP_CODE → List<Arrival> {line_code, route_descr, btime2}
- getBusLocation?p1=ROUTE_CODE → List<Bus> {CS_LAT, CS_LNG, VEH_NO}
- getClosestStops?p1=LAT&p2=LNG → List<Stop>
- getDailySchedule?line_code=LINE_CODE → JSON object `{ come: [...], go: [...] }` (daily time bands; internal line code, not public line number)

Rate limit: max 1 request per 5s per endpoint per user. Cache aggressively.

## 7. Data Models
```kotlin
data class Line(val LineCode: String, val LineID: String, val LineDescr: String)
data class Stop(val StopCode: String, val StopDescr: String, val lat: Double, val lng: Double, val order: Int)
data class Arrival(val lineCode: String, val destination: String, val minutes: Int)
```

## 8. App Structure
io.github.ntufar.stasi/
- data/
  - api/OasaApi.kt
  - local/AppDatabase.kt, StopDao.kt
  - repository/OasaRepository.kt
- ui/
  - home/HomeScreen.kt
  - arrivals/ArrivalsScreen.kt
  - search/SearchScreen.kt
  - map/MapScreen.kt
  - theme/
- MainActivity.kt
- StasiApp.kt

## 9. UI Flows
Home → tap favorite → Arrivals
Home → search icon → Search → select stop → Arrivals
Arrivals → map icon → MapScreen with route polyline
MapScreen → **tabs** — **Χάρτης** (map) / **Δρομολόγια** (timetable from `getDailySchedule` when line code is known); tabs appear once route stops have loaded successfully
MapScreen → tap bus → show vehicle number
MapScreen → tap stop marker → Arrivals for that stop (including origin-departure hint when applicable; see Core Features item 7)

Design rules:
- Dark theme by default, AMOLED black
- Arrivals list: **minutes** in 48sp bold using **primary (accent) color**; **line / route title** in semibold `onSurface`; **direction and origin-departure hints** in smaller type with muted **`onSurfaceVariant`**; clear vertical spacing between those tiers
- One primary action per screen
- No splash screen

## 10. Key Behaviors for Copilot
- When generating API calls, always use suspend functions with Retrofit, wrap in try/catch, fallback to Room cache
- For Greek search, normalize strings: Normalizer.normalize(input, NFD).replace("\p{M}".toRegex(), "").lowercase()
- All network calls must go through OasaRepository, never directly from ViewModel
- Use StateFlow in ViewModel, collectAsState in Compose
- MapLibre: add source once, update data, do not recreate style on recomposition
- Respect cleartext: manifest already has usesCleartextTraffic=true
- **Specification:** after any user-facing feature or behavior change, update **`docs/SPEC.md`** (version/date and the sections that describe the product). See project rule *spec-documentation*.

## 11. Performance Targets
- Cold start < 800ms on Pixel 6
- Arrivals refresh < 1s on 4G
- Cache hit for lines/stops 100% offline

## 12. Privacy
- No analytics, no crashlytics in MVP
- Location used only for nearby, not stored
- User-Agent: "Stasi/1.0 (+https://github.com/you/stasi)"

## 13. Acceptance Criteria
- User opens app, sees favorite stop with correct minutes within 1s
- Search "syntagma" finds "ΣΥΝΤΑΓΜΑ"
- Map shows line 140 **polyline and stop markers** (numbered, direction clear), updates bus positions periodically
- On MapScreen with a loaded route, **Δρομολόγια** tab shows timetable sections (Αφετηρία / Τέρμα) when the API returns data
- On first load of a route on the map, the camera **prioritizes the user’s location** (zoom ~15) when GPS is available; otherwise the route fits in view
- Tapping a stop on the map opens arrivals for that stop
- At a non-origin stop, arrivals list shows **origin departure** information for each route where data is available
- App works airplane mode after first load for cached stops

## 14. Next Steps for Development
1. Implement OasaRepository with caching
2. Build HomeScreen with DataStore favorites
3. Add SearchScreen with fuzzy Greek
4. Integrate MapLibre and draw route
