# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

<!-- Add changes here; copy into a new `## [x.y.z] - YYYY-MM-DD` section when tagging a release. -->

## [0.0.3] - 2026-05-19

### Changed

- Target and compile SDK raised to **35** (Google Play requirement for new releases).

## [0.0.2] - 2026-05-17

### Added

- **Arrival alerts** — Optional local notification when a chosen live arrival is within a few minutes (WorkManager; user-triggered, no remote push server).
- **Alert settings** — Configurable lead time and optional quiet hours (no notifications during set hours).
- **Deep links** — Open a stop’s arrivals screen from `stasi://stop/{code}` (and matching HTTPS intent).
- **Pull-to-refresh** on the arrivals screen; countdown text refreshes every 15 seconds while the screen is visible.
- **Navigation drawer** on Home, Search, and Route map.
- **Greek / English** in-app language with immediate UI updates (Settings + locale applied at app start).
- **Route map** — Nearby stop pins when no route is loaded; daily timetable panel with route tabs.
- **Scheduled origin departures** shown on arrivals for non-origin stops (from daily schedule data).
- **Last-bus warning** on arrivals and map when the current run is the last scheduled service of the day.
- **Share / copy** arrivals summary from the arrivals overflow menu.
- **Greeklish search** — Latin queries can match Greek stop and line names.

### Changed

- Station screen loads faster with fewer redundant OASA API calls (timetable/route caches, parallel fetches, per-key request deduplication).
- Arrivals and map caching tuned (shorter arrival TTL, force refresh on pull/poll, bus positions re-fetched when returning to the map).
- Map camera fits the full route when a line is loaded; closest-stops cache TTL 5 minutes.
- Arrival notifications use emphasized route number and countdown styling with a shown-at timestamp.
- Android 13+ predictive back callback enabled.
- CI runs instrumented UI tests on an API 31 emulator; release workflow runs unit tests before building.

## [0.0.1] - 2026-05-09

### Added

- Initial GitHub release with signed APK artifacts.
- Athens bus arrivals, Greek-aware stop search, favorites, nearby stops, and route map with live vehicles (`io.github.ntufar.stasi`).
