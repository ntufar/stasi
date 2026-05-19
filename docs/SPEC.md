# Stasi – Athens Bus App Specification
Version: 0.45 | Date: 2026-05-20 | Author: Nicolai Tufar

## 1. Purpose
Stasi is a fast, private Android app for Athens public transport. It replaces the official OASA Telematics app by showing real-time arrivals, nearby stops, and route maps without ads, accounts, or clutter.

Goal: open app → see your bus in under 1 second.

## 2. Target Users
- Daily commuters in Athens
- Tourists with limited data
- Users frustrated by slow official app

Primary language: Greek UI by default; user may switch **English** or **Greek** in the app menu (persisted). **GitHub Pages site** (`docs/index.html`): full **English** and **Greek** copy with an **EN / ΕΛ** toggle; choice is stored in `localStorage` (`stasi-site-lang`), defaulting to Greek when the browser locale is Greek, otherwise English. Changing language **updates all visible UI immediately** (including the current screen), without requiring navigation away or process restart. **`StasiApplication.onCreate`** and **`MainActivity.onCreate`** both call **`AppLocale.apply`** from DataStore so background work (e.g. arrival-alert notifications) sees the same locale as Compose. Compose uses a `ContextWrapper` around the activity that overrides `getResources()` with `withAppLocaleTag` so `stringResource` tracks the chosen locale without replacing `LocalContext` with a non-activity context; `MainActivity` also supplies `LocalActivityResultRegistryOwner` for activity-result APIs (e.g. permission launcher on Home). Default resource fallback language for missing keys is English (`values/`). **Launcher / app label:** English `Stasi` (`values/`); Greek **Στάση** (`values-el/`).

## 3. Core Features (MVP)
1. Home screen with favorite stops, showing next 2 arrivals per stop live
   - favorites support local aliases and manual ordering
   - recently viewed stop/route shortcuts appear at the top of Home
2. Search stops and lines by name, Greek fuzzy match (ignores accents). **Latin-only** queries (two or more letters, no digits) are mapped letter-by-letter to a rough Greek form so Greeklish like `syntagma` or `nosok` can match OASA Greek names. **Stops** are served from the local Room cache populated by an incremental catalog sync (`webGetLines` → routes → `webGetStops` per route, throttled to at most about once per 24h); opening **Search** schedules that sync when the lines catalog is available so stop search is not limited to routes the user has already opened on the map.
3. Arrivals screen: big minutes, line ID, destination; optional **origin departure** line when the stop is not that route’s first stop (see item 7)
   - **Minutes countdown:** between API updates, the main minutes value (and matching origin / schedule-approx lines) **count down by wall clock** from the last snapshot so the board does not look stuck when the operator repeats the same ETA or a poll is slightly late; ticks about every **15 s** while the screen is **RESUMED**.
   - shows a freshness stamp for the last arrivals fetch (single relative phrase: no duplicate “ago”; wording follows the **app** locale via `DateUtils` + `strings.xml`)
   - **top app bar:** center-aligned title (one line, ellipsized); **back** remains visible; **refresh, drawer, favorite, map, share, and copy actions** live under the **overflow (⋮)** menu so the bar stays uncluttered on long Greek stop names
   - **Pull-to-refresh:** dragging down on the arrivals list moves the **whole list down** with the gesture (same offset curve as the Material threshold); releasing past the threshold runs the same forced arrivals fetch as overflow **Refresh**; the pull indicator stays until the request finishes (success or failure).
4. Nearby stops using GPS, sorted by distance
5. Route map: draw **all route stops** on the map (not only the polyline), with **clear direction of travel**:
   - **Tabs** when a route has loaded: **Map** / **Timetable** (EN) or **Χάρτης** / **Δρομολόγια** (EL) — daily timetable from OASA `getDailySchedule` using the line’s internal `line_code`; origin (**Αφετηρία** / `come`) and terminus (**Τέρμα** / `go`) appear as **two columns on the same row** so outbound and return time bands are visible together; shorter lists pad with “—” on the missing side;
   - stops ordered along the route with **sequence numbers** (1 … N);
   - **stop names** from OASA (`StopDescr`, truncated when very long) when **Show stop names on map** is enabled in Settings (default **on**): on a **loaded route**, **first and last** stops are always labeled; **middle** stops gain labels when the user **zooms in** (about zoom level 14+), with MapLibre **collision avoidance** so crowded areas stay readable; on the **manual nearby** map, each pin may show a name below the marker (same collision rules). When off, no name labels are drawn;
   - **first stop** (departure) and **last stop** (terminus) visually distinct from middle stops (e.g. color/size);
   - **live buses** shown with **heading** (arrow or rotated icon) approximating direction toward the next segment of the route; when the map screen **RESUMED**, live positions are **re-fetched immediately** (not skipped when the 15s in-memory bus cache is still fresh).
   - **Manual map without a route:** when location permission is available, show **nearby stops** from `getClosestStops` (same source as Home nearby list) as pins on the map—**uniform stop styling** (not origin/terminus colors); **no route polyline** is drawn between those pins. When **Show stop names on map** is on, each pin shows the **stop name** below the marker; when off, pins have no name labels. Pins disappear once a line is loaded or while a route fetch is in progress. **Initial camera** for that mode **fits the pins and the user** when both exist; if there is still no fix, fit the pins only. **Line / route code entry** is a **single compact row**: outlined field uses a **placeholder** hint (no floating label) for a shorter control, with **Show** beside it on the same baseline row; the field’s **search icon** (tap) and the keyboard **search** action apply the same code as **Show**.
   - **Initial map camera (route loaded):** when opening the route map **before a line is loaded** (manual entry screen), the map **centers and zooms on the user’s location** if a GPS fix is available (after a short wait), matching the default zoom used by **My Location** when there is no route and **before** nearby pins arrive; otherwise the default world view stays until nearby data, search, or the FAB. When a route is first shown for a given stop sequence, the map **fits the whole route** (stops and polyline) in view and does **not** auto-center on the user’s location for that initial framing. Periodic live refresh must **not** reset the camera. Changing route/direction (different stop sequence) runs this logic again. The **My Location** FAB still fits **route + user** in one view when pressed (with location permission).
6. **Map → arrivals:** tapping a **stop marker** on the route map opens the **Arrivals** screen for that stop code (same as Search/Home), showing upcoming buses and times. When arriving from a route map, the **current route code** is forwarded so arrivals for the viewed route sort first, preventing confusion at stops shared between directions. **Direction preference:** schedule-only departures (`addScheduleOnlyDepartures`) deduplicate by line—when a route-code hint is present the hinted direction wins over whichever direction the API happens to return first. Live arrivals from the same line (any direction) also sort above unrelated lines when a hint is provided. **Direction label:** the `lineLabel` shown to users uses the route-specific direction (`RouteDescr`, e.g. "ΚΗΦΙΣΙΑ - Π. ΦΑΛΗΡΟ") rather than the line's canonical name (`LineDescr`, which may describe the opposite direction); this applies to both live and schedule-only rows. **Initial route direction:** when resolving a line number to a route, `RouteType 1` (outbound/primary) is preferred over `RouteType 2` (return), so the map initially shows the direction matching the line's canonical name.
7. **Arrivals at a stop (not the route origin):** when the viewed stop is **not** the **first stop** of that route (in OASA route order), the app shows **when the next service from the route’s origin** is planned. **Schedule-based** hints (`getDailySchedule`, `come` / αφετηρία, Europe/Athens, next window start) appear as their **own list row** (clock + line + “Δρομολόγιο από αφετηρία”), **not** nested under a live vehicle row, so users do not confuse them with the bus counted down in minutes above. **Fallback** when schedule data is missing: a single secondary line on the live row from live `getStopArrivals` at the origin stop. For routes **without** any live bus, a standalone **schedule-only** row with the next origin departure clock is appended so the user always sees when the next service starts. Origin schedule rows are omitted when the user is already at the origin stop **and** there is a live bus for that route.
8. Offline cache: lines and stops cached 24h. **Arrivals** use a short Room freshness window (~20s) for deduplication when the same stop is read repeatedly (e.g. origin enrichment, background workers). The **Arrivals screen** always **forces a network refresh** when first shown, on each **RESUMED**, on overflow **Refresh**, on **pull-to-release** on the list, and on **every 30s poll tick** while the ViewModel runs (polls bypass the short cache so each tick hits `getStopArrivals`). **Progressive load:** if Room still has arrivals for the stop, the list shows that snapshot **immediately** while the forced fetch runs; after the live board returns, **minutes and line labels** appear at once and **origin / last-bus / schedule-only rows** fill in on a second pass (enrichment runs in parallel with route-hint sorting where possible). **`getDailySchedule`** is paced **per line code** (not one global queue) so many lines at a busy stop can load timetables concurrently within the API politeness limit. **`getClosestStops`** is held in memory for **about 5 minutes** per rounded lat/lng key so Home / manual-map nearby can update without treating the same spot as fixed for 24h. **In-memory caches** in `OasaRepository` with longer TTLs: timetable (`getDailySchedule`) about **6 hours**; routes-for-stop, route stops, and line-route info up to **24 hours**—these mainly avoid redundant calls during catalog resolution and map enrichment within a session.
9. **Navigation drawer** (menu icon on main screens): jump to **Home**, **Search**, or **Route map** (manual entry); **Settings** (arrival-alert lead time, **show stop names on map** on/off, quiet hours, see item 11); language **English / Greek**. **Edge-swipe to open the drawer is disabled on Route map** (manual or preset line) so horizontal map pans are not mistaken for opening the drawer; use the **menu** icon there. Other screens keep edge-swipe where the platform drawer allows it.
10. **Last Bus Warning:** when the last scheduled service window for a line (from `getDailySchedule` origin departures) is ending within **30 minutes**, the app warns the user:
    - **Arrivals screen:** an amber chip ("Last service" / "Τελευταίο") appears next to the line label for affected routes.
    - **Timetable tab (MapScreen):** an amber banner appears below the title, and the last origin departure row is highlighted with an amber background.
    - The warning activates when `now` is within 30 min of the last time-window end in origin departures, or up to 2 hours past it (service ended). Computed per line using `Europe/Athens` timezone.
11. **Arrival alerts (local notifications):** one-shot reminders tied to a **live arrival row** (stop + route + vehicle id from OASA):
    - **Alerts icon** on each live row when `routeCode` is known: **filled** `Notifications` when an alert is active for that row, **outlined** `NotificationsNone` otherwise (`cd_alert`). Tapping toggles the alert on or off.
    - **Enabling:** schedules a **unique** chained `OneTimeWorkRequest` (`ArrivalAlertWorker`) via **WorkManager** (`work-runtime-ktx`). Each run performs **one** forced **`getStopArrivalsSnapshot(..., forceRefresh = true)`**, updates the notification if needed, then **reschedules itself** after **30 s** (no long in-worker `delay` loop, which the OS often stops in the background). Polling continues while the alert remains in DataStore and for at most **30 minutes** from the first run (`started_at_millis` in work input).
    - **Fire condition:** the worker polls for the live row whose **`routeCode`** and **`vehCode`** match the alert. When **effective minutes** (see `effectiveMinutesSinceSnapshot` — same as the Arrivals list) **≤** the user’s **arrival alert threshold** (see Settings), it starts a **single notification** (same id) that **updates on each reschedule** (about every **30 s**): countdown copy uses effective minutes so the shade counts down between OASA polls even when **`btime2`** repeats the same integer, then **“The bus has arrived at …”** while the vehicle still appears with **≤ 0** effective minutes, then **“The bus has left …”** when that vehicle **no longer appears** after a notification was already shown. **Sound/vibration** applies to the **first** post only (`setOnlyAlertOnce`). After **departed**, the alert is removed from DataStore and chaining stops. The threshold is read from **Settings** on each run so changes apply to in-flight workers.
    - **Expiry:** if threshold is never reached, the worker removes the alert after **30 minutes** of polling and exits successfully.
    - **Android 13+:** **`POST_NOTIFICATIONS`** is declared in the manifest; on first tap to **enable** an alert, if permission is missing the system permission sheet runs, and the alert is only scheduled after grant (pending args held in composable state).
    - **Channel:** `arrival_alerts` is created at **`Application.onCreate`** after **`AppLocale.apply`** from persisted settings (`NotificationHelper.createChannel` on a locale-wrapped context). Worker notifications also resolve strings through **`withAppLocaleTag`** on each poll so copy matches the in-app language even when **`MainActivity` has not run** in the process. Strings use `notification_channel_*` and `notification_arrival_*` / title variants for arrived vs departed (EN + EL). **Countdown** notifications use `Spannable` so the **public route number** (e.g. before ` · ` in the line label) and the **minutes segment** (e.g. `14 min` / Greek `΄` suffix) are **bold and slightly larger**; **BigTextStyle** repeats the styled body when the notification is expanded.
    - **Content intent:** `MainActivity` with `navigate_to_stop` extra = stop code (for opening the right stop; **navigation must consume this extra** in the Compose layer to land on Arrivals—intent is prepared for deep link).
    - **Persistence:** active alert keys live in a separate **Preferences DataStore** (`arrival_alerts`, string set); `ArrivalsViewModel` collects `activeAlerts` filtered by current `stopCode` to drive icon state. Cancelling work clears via `cancelUniqueWork` + repository remove.
    - **Foreground list:** while Arrivals is visible, the screen **refreshes arrivals every 30 seconds** (forced network each tick) and **on each `RESUMED`** lifecycle (`refreshNow`, forced network), independent of the worker; the list and **arrival-alert notifications** both use **`effectiveMinutesSinceSnapshot`** so minutes count down between polls (see item 3).
    - **Quiet hours:** when enabled in Settings, alert notifications are suppressed during the configured local time window while the alert remains active until the bus leaves or the worker times out.
    - Privacy: **no push server**, no accounts — local notifications + on-device polling + DataStore only.

## 4. Out of Scope for MVP
- Ticket purchase
- Trip planning across multiple lines

## 5. Tech Stack
- Language: Kotlin 1.9
- UI: Jetpack Compose + Material 3
- Architecture: MVVM, Repository pattern
- Networking: Retrofit2 + Gson, Coroutines
- Storage: Room for cache; DataStore for **favorites + UI language + arrival alert threshold minutes + show map stop names + quiet hours** (`SettingsRepository` / `settings` preferences), **recent activity** (`RecentActivityRepository`, `recent_activity` preferences), and **active arrival alerts** (`AlertsRepository`, `arrival_alerts` preferences file)
- Background: **WorkManager** for arrival alert polling (`ArrivalAlertWorker`)
- Per-app locale: AndroidX AppCompat `AppCompatDelegate.setApplicationLocales` (English `en`, Greek `el`); **`ProvideAppLocaleCompositionLocals`** in `MainActivity` supplies `LocalContext` as a `ContextWrapper` (activity base, localized `Resources`) plus matching `LocalConfiguration`; **`LocalActivityResultRegistryOwner`** is provided from `MainActivity` so `rememberLauncherForActivityResult` keeps working.
- Maps: MapLibre SDK (open source, no API key)
- Min SDK 26, Target SDK 35

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

Rate limit: max 1 request per 1.2s per endpoint per user. In-memory caches in `OasaRepository` (TTLs vary by endpoint—see item 8) reduce duplicate calls within a session; Room persists lines/stops/arrivals across sessions.

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
  - repository/OasaRepository.kt, FavoritesRepository.kt, SettingsRepository.kt, **AlertsRepository.kt**
- workers/
  - **ArrivalAlertWorker.kt**
- util/
  - **NotificationHelper.kt**
- ui/
  - home/HomeScreen.kt
  - arrivals/ArrivalsScreen.kt
  - search/SearchScreen.kt
  - map/MapScreen.kt
  - theme/
- di/AppContainer.kt (wires repositories including `alertsRepository`)
- MainActivity.kt
- StasiApplication.kt (`AppContainer`, MapLibre init, notification channel)
- StasiApp.kt (NavHost + drawer)

## 9. UI Flows
Any main screen → **menu icon** → drawer → Home / Search / Route map, **Settings** (dropdown: minutes within which the first arrival notification is shown; default **5**, allowed **1–30** from presets; **show stop names on map** on/off, default **on**; **quiet hours** for arrival alerts), or **Language** (English / Greek). On **Route map**, open the drawer via **menu** only (no edge-swipe), so map gestures stay uninterrupted.
Home → tap favorite → Arrivals
Home → search icon → Search → select stop → Arrivals
Home → recent stop/route cards jump back to the last viewed stop or route
Arrivals → **alerts icon** on a live row → optional **POST_NOTIFICATIONS** grant (Android 13+) → **WorkManager** poll until the bus is gone from live data or 30 min timeout → **local notification** that updates (countdown → arrived → left); tap again (or cancel path) to clear alert and worker
Arrivals/Home → refresh action immediately reloads arrivals and updates the freshness stamp; on **Arrivals**, **pull down and release** on the list does the same (with a visible pull indicator until the fetch completes)
Home → favorite card overflow menu supports rename / move up / move down / remove
Arrivals → map icon → MapScreen with route polyline
MapScreen → **tabs** — **Χάρτης** (map) / **Δρομολόγια** (timetable from `getDailySchedule` when line code is known); tabs appear once route stops have loaded successfully
MapScreen (manual, no line yet) → map shows **nearby stop pins** from `getClosestStops` when location is available; tap pin → Arrivals
MapScreen → tap bus → show vehicle number
MapScreen → tap stop marker → Arrivals for that stop (including origin-departure hint when applicable; see Core Features item 7)

Design rules:
- Dark theme by default, AMOLED black
- Arrivals list: **minutes** in 48sp bold using **primary (accent) color**; **line / route title** in semibold `onSurface`; **direction and origin-departure hints** in smaller type with muted **`onSurfaceVariant`**; clear vertical spacing between those tiers
- One primary action per screen
- No splash screen
- All user-visible chrome (labels, errors, tabs where applicable) localized via `strings.xml` (`values` English, `values-el` Greek); API-supplied stop/line names stay as returned by OASA. Network and fetch failures use app strings (not raw exception messages). The arrivals screen shows a localized error line when a refresh fails.

## 10. Key Behaviors for Copilot
- When generating API calls, always use suspend functions with Retrofit, wrap in try/catch, fallback to Room cache
- For Greek search, normalize strings: Normalizer.normalize(input, NFD).replace("\p{M}".toRegex(), "").lowercase(); Latin-only letter queries are expanded with a simple Greeklish→Greek letter map before normalization.
- All network calls must go through OasaRepository, never directly from ViewModel; background workers that need OASA data should also use `OasaRepository` (same as `ArrivalAlertWorker`)
- Use StateFlow in ViewModel, collectAsState in Compose
- MapLibre: add source once, update data, do not recreate style on recomposition
- Respect cleartext: manifest already has usesCleartextTraffic=true
- **Specification:** after any user-facing feature or behavior change, update **`docs/SPEC.md`** (version/date and the sections that describe the product). See project rule *spec-documentation*.

## 11. Performance Targets
- Cold start < 800ms on Pixel 6
- Arrivals list shows live minutes within about **1s on 4G** (enrichment may trail by a few seconds on busy stops)
- Cache hit for lines/stops 100% offline

## 12. Privacy
- No analytics, no crashlytics in MVP
- Location used only for nearby, not stored
- Arrival alerts: no third-party push; only user-initiated local notifications and on-device stored alert keys
- User-Agent: "Stasi/1.0 (+https://github.com/you/stasi)"
- **Public privacy policy:** `docs/privacy.html` on GitHub Pages at `https://ntufar.github.io/stasi/privacy.html` (EN/EL, same language toggle as the landing page). Linked from `docs/index.html` footer and Privacy section.

## 13. Acceptance Criteria
- User opens app, sees favorite stop with correct minutes within 1s
- Search "syntagma" finds "ΣΥΝΤΑΓΜΑ"
- Map shows line 140 **polyline and stop markers** (numbered, direction clear), updates bus positions periodically
- On MapScreen with a loaded route, **Δρομολόγια** tab shows timetable sections (Αφετηρία / Τέρμα) when the API returns data
- On first load of a route on the map, the camera **prioritizes the user’s location** (zoom ~15) when GPS is available; otherwise the route fits in view
- Tapping a stop on the map opens arrivals for that stop; arrivals for the map's current route sort first
- At a non-origin stop, arrivals list shows **origin departure** information for each route where data is available (schedule-based when `getDailySchedule` returns windows, else live origin arrivals)
- Routes with **no running bus** still show a **schedule-only** row with the next origin departure clock
- App works airplane mode after first load for cached stops
- Drawer opens from menu icon; choosing a top-level destination preserves reasonable back stack behavior (`popUpTo` start destination with state save/restore)
- Switching language updates UI immediately (activity recreate); choice survives app restart
- Home shows recent stop/route shortcuts and favorite cards can be renamed or reordered without leaving the screen
- Arrival cards and Home favorites show a last-updated freshness label after each successful fetch
- On **Arrivals**, **pull down and release** on the list forces a refresh (same as overflow **Refresh**) and shows a pull indicator until the fetch completes
- On Arrivals, user can enable an alert on a live row (with `routeCode` + `vehCode`); within **30 minutes** either the worker shows an updating notification from **≤ threshold minutes** (Settings, default 5) through **arrived** until **that vehicle drops off** the stop’s live list (then “bus has left” copy), or the alert clears without notification if the threshold is never reached
- Quiet hours suppress alert notifications while keeping the alert active until the configured window ends or the bus departs
- Android 13+: denying notification permission leaves the alert off after the permission flow; granting it schedules the worker as above
- Active alert rows show the **filled** notifications icon; tapping again cancels WorkManager unique work and removes the key from DataStore

## 14. Next Steps for Development
1. Add automated tests for favorites ordering/alias storage and quiet-hours alert suppression
2. Consider per-favorite alert presets if future UX work needs stop-specific defaults
3. Revisit route/stop deep-link handling for any additional notification entry points
