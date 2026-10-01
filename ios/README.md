# Stasi for iPhone (iOS)

Native SwiftUI port of the Stasi Android app — live Athens bus arrivals
from the OASA Telematics API. Lives in `ios/`, same repo as Android.

Bundle id: `io.github.ntufar.stasi` · iOS 17+ · EN/EL localized.

## Features (parity with Android)

- **Home** — recent stops + recent route ("Continue"), favorite stops with the
  next 2 arrivals each, freshness label, 30s auto-refresh; rename / move up /
  move down / remove (long-press or swipe), drag to reorder, + to add by code
- **Search** — lines + stops from a persisted catalog (24h incremental sync,
  works offline), Greek accent-insensitive + Greeklish (`syntagma`); line → map
- **Arrivals** — big minutes (wall-clock countdown), `line · direction`,
  origin-departure hints, last-service chip, schedule-only rows, route-hint
  sorting from the map, 30s forced poll, pull-to-refresh, favorite, share
  snapshot, copy summary / deep link, row → route map
- **Nearby** — GPS stops sorted by distance
- **Route map** — MapKit (no API key): numbered stops (green origin, red
  terminus), names on origin/terminus and on middle stops when zoomed in,
  polyline, live buses with heading arrows (tap → vehicle number), swap
  direction, my-location fit, nearby pins on the manual map, timetable tab
  (origin | terminus columns, last-service banner)
- **Arrival alerts** — local notification that updates countdown → arrived →
  left, threshold 1–30 min (default 5), quiet hours, 30-min expiry, persisted
  across launches; tap opens the stop
- **Deep links** — `stasi://stop/<code>` (same as Android)
- **Settings** — threshold, map names, quiet hours, theme (dark/light),
  language (Ελληνικά/English, applied immediately)

## Build

Requires Xcode 16+ on macOS.

```bash
xcodebuild -project ios/Stasi.xcodeproj -scheme Stasi \
  -destination 'platform=iOS Simulator,name=iPhone 17' build
```

Or open `ios/Stasi.xcodeproj` in Xcode and Run on a simulator/device.

## Layout

```
ios/Stasi/
  StasiApp.swift        # App entry, tabs, deep links, shared navigation routes
  Support.swift         # In-app localization L(...), theme colors, helpers
  Models.swift          # Domain models (parity with OasaRepository data classes)
  OasaAPI.swift         # Telematics client (POST, paced per endpoint/line/route)
  Schedule.swift        # getDailySchedule wall-clock + quiet-hours helpers
  CatalogStore.swift    # Persistent lines/routes/stops catalog + search (Room equivalent)
  OasaRepository.swift  # Caches, arrivals mapping + enrichment (port of OasaRepository.kt)
  AppState.swift        # Favorites, settings, recents, active alerts, Router
  ArrivalAlerts.swift   # Alert polling + notifications, AppDelegate
  GreekText.swift       # Accent-insensitive + Greeklish search
  HomeView SearchView ArrivalsView NearbyView RouteMapView SettingsView
  Info.plist  en.lproj/  el.lproj/  Assets.xcassets/
ios/Stasi.xcodeproj/    # hand-maintained minimal project (SDKROOT=iphoneos)
```

Debug builds also accept `stasi://route/<routeCode>` and `stasi://line/<lineCode>`
for simulator checks (`xcrun simctl openurl booted stasi://route/5045`).

## Differences from Android

- MapKit replaces MapLibre; there is no offline map tile download on iOS.
- The language choice is in-app: strings resolve through `L(...)` against the
  chosen `.lproj`, not the system language.
- iOS suspends timers in the background (no WorkManager), so an alert also
  schedules a time-triggered notification at the predicted threshold crossing;
  polling resumes and corrects it when the app returns to the foreground. The
  "arrived" / "left" updates need the app to have run recently.
- Tab bar instead of a navigation drawer; Nearby is its own tab.
