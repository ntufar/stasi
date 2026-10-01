# Stasi for iPhone (iOS)

Native SwiftUI port of the Stasi Android app — live Athens bus arrivals
from the OASA Telematics API. Lives in `ios/`, same repo as Android.

Bundle id: `io.github.ntufar.stasi` · iOS 17+ · EN/EL localized.

## Features (parity with Android MVP)

- **Home** — favorite stops, 2 live arrivals each, recents, reorder/rename
- **Search** — stops + lines, Greek accent-insensitive + Greeklish (`syntagma`)
- **Arrivals** — big countdown minutes (wall-clock tick), 30s poll,
  pull-to-refresh, favorite/share, last-service chip
- **Nearby** — GPS stops sorted by distance
- **Route map** — MapKit (no API key): numbered stops, route polyline,
  live buses, nearby pins, origin/terminus timetable from `getDailySchedule`
- **Arrival alerts** — local notifications: countdown → arrived → left,
  threshold 1–30 min (default 5), quiet hours, 30-min expiry
- **Settings** — threshold, map names, quiet hours, language (Ελληνικά/English)

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
  StasiApp.swift      # App entry + tabs
  Models.swift        # Line/Stop/Arrival/Nearby/Favorite models
  OasaAPI.swift       # Telematics client (POST, 1.2s/endpoint pacing)
  GreekText.swift     # Accent-insensitive + Greeklish search
  AppState.swift      # Favorites/settings/recents/alerts + 24h catalog cache
  ArrivalAlerts.swift # 30s poll → local notifications (countdown/arrived/left)
  HomeView.swift SearchView.swift ArrivalsView.swift
  NearbyView.swift RouteMapView.swift SettingsView.swift
  Info.plist  en.lproj/  el.lproj/
ios/Stasi.xcodeproj/  # hand-maintained minimal project (SDKROOT=iphoneos)
```

Notes vs Android: MapKit replaces MapLibre (no API key either way);
`UserDefaults` replaces DataStore/Room (arrivals are always force-refreshed);
`UNUserNotificationCenter` + `Timer` polling replaces WorkManager chaining.
