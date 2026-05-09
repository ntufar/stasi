# Stasi – Athens Bus App Specification
Version: 0.1 | Date: 2026-05-09 | Author: You

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
3. Arrivals screen: big minutes, line ID, destination
4. Nearby stops using GPS, sorted by distance
5. Route map: draw stops and connect with polyline, show live bus positions
6. Offline cache: lines and stops cached 24h, arrivals cached 30s

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

Rate limit: max 1 request per 5s per endpoint per user. Cache aggressively.

## 7. Data Models
```kotlin
data class Line(val LineCode: String, val LineID: String, val LineDescr: String)
data class Stop(val StopCode: String, val StopDescr: String, val lat: Double, val lng: Double, val order: Int)
data class Arrival(val lineCode: String, val destination: String, val minutes: Int)
```

## 8. App Structure
com.example.stasi/
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
MapScreen → tap bus → show vehicle number

Design rules:
- Dark theme by default, AMOLED black
- Minutes in 48sp bold, Greek font
- One primary action per screen
- No splash screen

## 10. Key Behaviors for Copilot
- When generating API calls, always use suspend functions with Retrofit, wrap in try/catch, fallback to Room cache
- For Greek search, normalize strings: Normalizer.normalize(input, NFD).replace("\p{M}".toRegex(), "").lowercase()
- All network calls must go through OasaRepository, never directly from ViewModel
- Use StateFlow in ViewModel, collectAsState in Compose
- MapLibre: add source once, update data, do not recreate style on recomposition
- Respect cleartext: manifest already has usesCleartextTraffic=true

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
- Map shows line 140 stops connected, updates bus every 15s
- App works airplane mode after first load for cached stops

## 14. Next Steps for Development
1. Implement OasaRepository with caching
2. Build HomeScreen with DataStore favorites
3. Add SearchScreen with fuzzy Greek
4. Integrate MapLibre and draw route
