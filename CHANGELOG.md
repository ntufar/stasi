# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

<!-- Add changes here; copy into a new `## [x.y.z] - YYYY-MM-DD` section when tagging a release. -->

## [0.0.2] - 2026-05-16

### Changed

- Station screen loads faster with fewer redundant OASA API calls: in-memory caching for timetables and routes-for-stop, merged origin enrichment, parallel timetable fetches, and per-key mutex deduplication for concurrent requests.
- Empty `getStopArrivals` responses are cached for 30s so the app does not refetch immediately.

## [0.0.1] - 2026-05-09

### Added

- Initial GitHub release with signed APK artifacts.
- Athens bus arrivals, Greek-aware stop search, favorites, nearby stops, and route map with live vehicles (`io.github.ntufar.stasi`).
