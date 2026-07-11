# Technical Specification: Migrating the Map Provider from MapLibre to Google Maps

- **Status:** Draft / Decision pending
- **Author:** Generated for evaluation, 2026-07-11
- **Affects:** `app/src/main/java/io/github/ntufar/stasi/ui/map/MapScreen.kt`, `app/src/main/java/io/github/ntufar/stasi/util/OfflineMapManager.kt`, `app/build.gradle.kts`, `StasiApplication.kt`, CI, Play listing (privacy section)

---

## 1. Summary

Stasi currently renders its route map with **MapLibre GL Android 11.6.1** using the free CARTO Voyager basemap style (`https://basemaps.cartocdn.com/gl/voyager-gl-style/style.json`), with no API key and no account. This document specifies what switching to the **Google Maps SDK for Android** would involve, what it would cost, what would be gained and lost, and answers the monetization question that motivated the investigation.

### 1.1 Answer to the monetization question

**No — Google does not pay app developers for promoted locations shown on an embedded map.**

- "Promoted pins" / Local Search Ads appear only inside **Google's own Maps app and google.com/maps**. They are a Google Ads product; advertisers pay Google, and there is **no revenue-share program** for third-party apps that embed a map via the Maps SDK.
- The old "AdSense for Maps" / Maps ad units program was **discontinued in 2016** and has no successor.
- The relationship is the reverse of the premise: the developer is Google's **customer**. You need a Google Cloud project with a billing account attached, and usage beyond the free allowance is billed to you.
- Additionally, the Google Maps Platform Terms of Service **restrict displaying your own third-party ads** on or over the map content, so the map cannot be independently monetized with ads either.

Switching to Google Maps therefore creates **zero revenue** and adds a billing dependency. Any decision to migrate must be justified on product grounds (basemap quality, familiarity, POI density), not monetization.

### 1.2 Recommendation

**Do not migrate.** Keep MapLibre. The migration is technically feasible (Section 4) but it:

1. produces no revenue (Section 1.1),
2. **loses offline maps entirely** — the Google Maps SDK has no offline-region API for third-party apps, so `OfflineMapManager` and the "download Athens for offline use" feature would be deleted with no replacement (Section 5.1),
3. contradicts the app's published positioning ("privacy-minded", "no map API key" — stated in `README.md` and the Play listing), since the Google Maps SDK transmits device identifiers and usage data to Google (Section 5.2),
4. adds an API key, a Google Cloud project, billing setup, and key-restriction maintenance (Section 4.3).

The remainder of the document is the full specification in case the decision is revisited.

---

## 2. Current State (MapLibre)

### 2.1 Dependency and initialization

- `org.maplibre.gl:android-sdk:11.6.1` in `app/build.gradle.kts`.
- `MapLibre.getInstance()` in `StasiApplication.kt`.
- No API key; basemap is CARTO's free vector style (fair-use terms for light traffic, attribution required and present).

### 2.2 Map features in `MapScreen.kt` (~1,180 lines)

The map is a MapLibre `MapView` hosted in Compose via `AndroidView`, with all dynamic content rendered through **GeoJSON sources + style layers** (data-driven styling):

| Feature | Implementation |
|---|---|
| Route polyline | `GeoJsonSource` + `LineLayer` (green, 5 px, round caps/joins) |
| Stops | One `GeoJsonSource`, multiple `CircleLayer`s filtered by a `kind` property (`start` / `mid` / `end` / `nearby`), white stroke |
| Stop sequence numbers | `SymbolLayer` with `textField = get(seq)`, halo, overlap allowed |
| Stop names | `SymbolLayer` with zoom-dependent filter (`mid` stops only ≥ `ROUTE_MID_STOP_NAME_MIN_ZOOM`), collision-aware placement (`textOptional`, `textAllowOverlap=false`) |
| Live vehicles | `SymbolLayer` with a custom bus bitmap, `iconRotate` bound to a per-feature `bearing` property, map-aligned rotation |
| User location | `CircleLayer` blue dot fed by FusedLocationProvider (Play Services location is already a dependency) |
| Camera | `CameraUpdateFactory` fit-to-bounds over route |

### 2.3 Offline maps (`OfflineMapManager.kt`)

MapLibre `OfflineManager` downloads an **offline tile pyramid** for Athens (bounds 37.85–38.10 N, 23.55–23.85 E, zoom 10–15) with progress reporting, delete, and status refresh. This is a shipped user-facing feature.

---

## 3. Target State (Google Maps SDK for Android)

### 3.1 Dependencies

```kotlin
// app/build.gradle.kts
implementation("com.google.maps.android:maps-compose:6.x")          // Compose wrapper (GoogleMap composable)
implementation("com.google.android.gms:play-services-maps:19.x")
// remove: org.maplibre.gl:android-sdk
```

`maps-compose` replaces the `AndroidView`/`MapView` lifecycle plumbing (the manual `LifecycleEventObserver` in `MapScreen.kt` becomes unnecessary — a real simplification, and the one genuine technical win of this migration).

### 3.2 Feature mapping

| Current (MapLibre style layers) | Google Maps equivalent | Parity |
|---|---|---|
| `LineLayer` route polyline | `Polyline` composable (`points`, `color`, `width`, round caps) | ✅ Full |
| `CircleLayer` stops by kind | `Circle` composables **or** `Marker` with pre-rendered bitmap per kind | ⚠️ `Circle` is geographic (meters, resizes with zoom) not screen-px; markers with generated bitmaps needed for constant-size dots |
| `SymbolLayer` sequence-number text | No text layer exists. Bake the number into the marker bitmap (`Canvas` → `BitmapDescriptor`), regenerated per stop | ⚠️ Workaround |
| `SymbolLayer` stop names with zoom filter + collision avoidance | No collision-aware labeling for app content. Must implement manually: listen to camera idle, show/hide `Marker` title bitmaps by zoom level; no automatic overlap resolution | ❌ Degraded |
| `SymbolLayer` rotating bus icons | `Marker(rotation = bearing, flat = true, icon = busBitmap)` | ✅ Full |
| User-location blue dot | `isMyLocationEnabled = true` (built-in) or a `Marker`; built-in layer is simpler than current custom code | ✅ Full |
| `CameraUpdateFactory` bounds fit | `CameraUpdateFactory.newLatLngBounds` (near-identical API) | ✅ Full |
| Dark/AMOLED map style | Cloud-based map styling (Map ID) or local JSON style | ✅ Full (Map ID adds console config) |
| **Offline region download** | **None. Not available in the SDK.** | ❌ **Feature removed** |
| Basemap data quality (Athens) | Google basemap — better POI/business coverage, more familiar rendering | ✅ Improvement |

### 3.3 Data-flow change

Today, live updates are pushed by mutating GeoJSON sources (`setGeoJson`) — the style layers re-render without recreating objects. With `maps-compose`, vehicles/stops become composable `Marker`s driven by state; recomposition handles updates. For ~5–50 vehicles and ~10–80 stops per route this is well within budget, but each marker is an object on the map rather than a feature in a batched layer; very dense views (all-Athens nearby mode) should cap rendered markers (~200) to avoid jank.

---

## 4. Migration Plan

### Phase 0 — Account setup (one-time, outside the repo)

1. Create/choose a Google Cloud project; **attach a billing account** (mandatory even for free-tier usage).
2. Enable **Maps SDK for Android**.
3. Create an API key restricted to:
   - Application restriction: Android apps, package `io.github.ntufar.stasi` + release and debug SHA-1 fingerprints (the CI keystore's SHA-1 must be included).
   - API restriction: Maps SDK for Android only.

### Phase 1 — Key plumbing

1. Add `MAPS_API_KEY` via the **Secrets Gradle Plugin** (`com.google.android.libraries.mapsplatform.secrets-gradle-plugin`), reading from `local.properties` locally and a GitHub Actions secret in CI (mirrors the existing `keystore.properties` pattern).
2. Manifest entry:
   ```xml
   <meta-data android:name="com.google.android.geo.API_KEY"
              android:value="${MAPS_API_KEY}" />
   ```
3. Note: an Android API key ships inside the APK and is extractable; the package+SHA-1 restriction is the only real protection. This is normal but is a new attack/abuse surface to monitor in the Cloud console.

### Phase 2 — Rewrite `MapScreen.kt`

1. Replace `AndroidView(MapView)` + lifecycle observer with `GoogleMap` composable (`cameraPositionState`, `properties`, `uiSettings`).
2. Port features per the table in 3.2. New helper: `stopMarkerBitmap(kind, seq, name?)` rendering the circle + number (+ optionally name) into a `BitmapDescriptor`, cached by `(kind, seq, textHash)`.
3. Implement zoom-threshold label logic (camera-idle listener replicating `stopNameLayerFilter()` behavior).
4. Keep FusedLocationProvider flow; feed either the built-in my-location layer or a marker.

### Phase 3 — Remove offline maps

1. Delete `OfflineMapManager.kt` and its settings UI entry points.
2. Update `README.md`, `docs/SPEC.md`, CHANGELOG, and Play listing: remove offline-maps and "no map API key" claims.
3. Update the privacy policy (`docs/privacy.html`): disclose Google Maps SDK data collection, and update the **Play Console Data Safety form** (the SDK collects device identifiers and diagnostics — this changes the app's declared data collection from "none" to "shared with Google").

### Phase 4 — Verification & release

1. Manual matrix: route map, nearby mode, live vehicles rotating, location dot, dark theme, airplane mode (map now blank — confirm acceptable UX with a "map unavailable offline" notice), fresh install without Play Services (map fails — MapLibre had no such dependency; affects de-Googled devices, which overlap with this app's privacy-minded audience).
2. Confirm CI builds with the secret key; confirm release-signed build renders (SHA-1 restriction is the classic failure mode).
3. Version bump + CHANGELOG entry describing removed offline feature.

**Estimated effort:** 3–5 working days (Phase 2 dominates; label rendering and bitmap marker generation are the fiddly parts), plus console/account setup.

---

## 5. Costs, Risks, and Losses

### 5.1 Offline maps — hard loss

The Google Maps SDK for Android **does not offer offline map downloads to third-party apps** (offline areas exist only in Google's own Maps app). There is no workaround within ToS (caching tiles is prohibited). The shipped Athens offline-region feature is removed outright. For a transit app used underground and by visitors on limited roaming data, this is a material regression.

### 5.2 Privacy positioning

README and the landing page advertise "privacy-minded… no map API key". The Google Maps SDK communicates with Google services and collects device/usage data under Google's privacy policy. Consequences: privacy-policy update, Data Safety form update, and a weakened differentiator. MapLibre + CARTO involves only anonymous tile fetches.

### 5.3 Pricing

Under Google Maps Platform pricing (post-March 2025 tiers), **mobile dynamic map loads (Maps SDK for Android) are in the no-charge tier**, so expected ongoing cost for this app is **$0** at any realistic scale. However: a billing account must still be attached, pricing terms can change (they have, twice, since 2018), and a leaked/abused key could generate charges on *other* enabled SKUs — keep the key API-restricted and set a billing alert at $1.

### 5.4 Play-services coupling

MapLibre runs on any Android device; Google Maps requires Google Play Services. Devices without it (Huawei, GrapheneOS/microG users) lose the map tab.

### 5.5 CARTO dependency (for balance)

The status quo has its own risk: the free CARTO style is fair-use, unauthenticated, and could be rate-limited or discontinued. The mitigation if that happens is another MapLibre-compatible style host (OpenFreeMap, Stadia free tier, self-hosted PMTiles) — a one-line `STYLE_URI` change, not a provider migration.

---

## 6. Alternatives Considered

| Option | Monetization | Offline | API key | Effort | Verdict |
|---|---|---|---|---|---|
| **Keep MapLibre + CARTO** (status quo) | none | ✅ | none | 0 | **Recommended** |
| MapLibre + self-hosted PMTiles of Attica | none | ✅ (fully bundled possible) | none | ~2 days | Good hardening follow-up; removes CARTO dependency |
| Google Maps SDK | none (developer pays; no ad revenue share) | ❌ | required + billing | 3–5 days | Not recommended |
| Mapbox SDK | none | ✅ | required + billing (paid past free tier) | 3–4 days | No benefit over MapLibre (same API lineage, adds cost) |

---

## 7. Decision

Pending. Default per Section 1.2 is **no migration**. If basemap quality/POI familiarity ever becomes a user-reported problem, revisit this spec; Phases 0–4 are written to be executable as-is.
