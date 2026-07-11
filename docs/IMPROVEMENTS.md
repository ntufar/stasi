# Stasi — Improvement Backlog

A deep review of the project as of 2026-07-11 (v0.2.2, versionCode 7), covering architecture,
testing, performance, reliability, features, accessibility, privacy, build/CI, distribution, and
documentation. Companion documents:

- [VISUAL_IMPROVEMENTS.md](VISUAL_IMPROVEMENTS.md) — visual design proposals (color scheme, line
  badges, arrivals hierarchy). Not repeated here.
- [GOOGLE_MAPS_MIGRATION.md](GOOGLE_MAPS_MIGRATION.md) — map provider evaluation (verdict: keep
  MapLibre). Not repeated here.

Each item is tagged **[P1]** (do soon — pays for itself immediately), **[P2]** (high value, more
effort), or **[P3]** (nice to have / long-term).

---

## 1. Architecture & code health

### 1.1 [P1] Split `OasaRepository` (1,308 lines)

`data/repository/OasaRepository.kt` is a god class: Retrofit calls, rate limiting, five in-memory
caches with different TTLs, Room persistence, catalog sync, arrivals enrichment (origin
departures, schedule-only rows, last-bus detection), and Greeklish search all live in one file.
Every feature change touches it, and it is effectively untestable as a unit.

Suggested decomposition, keeping the existing manual-DI style (`di/AppContainer.kt`):

| New class | Responsibility pulled out |
| --- | --- |
| `CatalogRepository` | lines/routes/stops catalog + 24h incremental sync |
| `ArrivalsRepository` | live arrivals, short Room freshness window, 30s polling support |
| `ScheduleRepository` | `getDailySchedule` fetch/cache, per-line-code pacing, last-bus windows |
| `NearbyRepository` | `getClosestStops` + 5-minute memory cache |
| `ArrivalsEnricher` (pure) | origin-departure rows, schedule-only rows, route-hint sorting — pure functions over data, unit-testable without mocks |

The enricher is the highest-value extraction: it encodes the app's most subtle product logic
(SPEC items 6–7, 10) and currently has no direct tests.

### 1.2 [P1] Decompose `MapScreen.kt` (1,181 lines) and `StasiApp.kt` (593 lines)

- `MapScreen.kt` mixes MapLibre style/layer plumbing, camera policy, tabs, the timetable UI, and
  the manual line-entry form. Extract: `MapLibreStyleController` (sources/layers/images, plain
  class holding the map reference), `RouteCameraPolicy` (pure — the initial-camera rules in SPEC
  item 5 are intricate and worth unit tests), `TimetableTab` composable, `LineEntryRow`
  composable.
- `StasiApp.kt` holds the NavHost, drawer, settings dialogs, and language switching. Extract the
  drawer content and each settings dialog into their own files; define routes in a sealed
  `Screen` type instead of raw strings.

### 1.3 [P2] Introduce a thin domain layer

DTO shapes (`OasaDto.kt`) currently flow up into ViewModels/UI. Introduce small domain models
(`Arrival`, `Stop`, `RouteDetails`) mapped at the repository boundary so an OASA field rename
touches one mapper, not every screen. This also makes the enricher (1.1) cleanly typed.

### 1.4 [P2] Typed error handling

Repository calls today surface generic failures; the UI shows one localized error line. Model a
sealed hierarchy (`Offline`, `OasaDown` (5xx), `RateLimited`, `EmptyData`, `Parse`) so screens
can differentiate "you're offline — showing cached data from 12:41" from "OASA is not
responding". The distinction matters for a transit app used on the street with flaky data.

### 1.5 [P1] Kotlin 2.x + Compose compiler plugin upgrade

Kotlin is pinned at 1.9.24 with `composeOptions.kotlinCompilerExtensionVersion = "1.5.14"` and
Compose BOM 2024.10.01 — all more than a year behind. Upgrading to Kotlin 2.x brings the K2
compiler, the Compose compiler Gradle plugin (drops the manual extension-version pin), and
**strong skipping mode** (free recomposition-skipping wins for the arrivals list that re-ticks
every 15s). Bump KSP and Room (2.7+ for KMP-ready APIs and bug fixes) at the same time. This is
the enabler for several performance items below.

### 1.6 [P1] Gradle version catalog + dependency updates automation

All dependency coordinates are hardcoded strings in `app/build.gradle.kts`. Move to
`gradle/libs.versions.toml` and add **Renovate or Dependabot** (`.github/dependabot.yml` with
`package-ecosystem: gradle` + `github-actions`). Today nothing tells you that OkHttp, Navigation,
WorkManager, MapLibre, etc. have updates — for a solo-maintained app, automation is the only
realistic way these stay current.

### 1.7 [P2] Static analysis: detekt + ktlint (or ktfmt)

Only Android Lint runs in CI. Add detekt (with the compose ruleset —
`io.nlopez.compose.rules:detekt`) and a formatter check. The Compose rules catch real bugs:
unstable collections in parameters, `remember` misuse, modifier-ordering issues — relevant given
the hand-rolled 15s ticker and map recomposition constraints in SPEC item 10.

---

## 2. Testing

Current state: good parsing/util unit tests (`OasaArrivalJsonParsingTest`, `GreekTextTest`,
`QuietHoursTest`, …), one instrumented smoke test. Nothing covers ViewModels, repositories,
navigation, or the DAO. SPEC §14 itself lists favorites-ordering and quiet-hours-suppression
tests as next steps.

### 2.1 [P1] ViewModel tests

Add `kotlinx-coroutines-test` + Turbine and cover:

- `ArrivalsViewModel` — progressive load (cached snapshot → live board → enrichment second
  pass), 30s poll tick forcing network, route-hint sorting, error line on failed refresh.
- `HomeViewModel` — favorites ordering, aliases, recent shortcuts, freshness stamps.
- `MapViewModel` — direction preference (`RouteType 1` first), live-bus refresh on RESUMED.

These encode the SPEC's most detailed acceptance criteria and currently regress silently.

### 2.2 [P1] Repository tests with MockWebServer

OkHttp is already a direct dependency; add `mockwebserver` and test `OasaRepository` (or its
post-split parts): cache TTL behavior, forced-refresh bypass, rate-limiter integration, fallback
to Room on network error, and the 24h catalog-sync throttle. The OASA API returns quirky JSON
(the parsing tests exist because of this) — repository-level tests catch the integration seams
the parsing tests miss.

### 2.3 [P2] Room DAO tests via Robolectric

`StasiDao` queries (freshness-window arrivals dedup, catalog upserts) can run on Robolectric with
an in-memory database — no emulator needed, so they fit the fast CI job.

### 2.4 [P2] `ArrivalAlertWorker` tests

`workmanager-testing` + a fake repository: threshold crossing → notification, vehicle
disappearing → "bus has left", 30-minute expiry, quiet-hours suppression. This is the app's only
background feature and its failure mode (silent non-notification) is invisible to the user.

### 2.5 [P2] Screenshot tests (Roborazzi)

Arrivals row variants (live / schedule-only / last-service chip / origin hint), Home favorite
card, Search result — in both locales and font scales. Runs on JVM, cheap in CI, and guards the
dense visual rules in SPEC §9 while the VISUAL_IMPROVEMENTS work lands.

### 2.6 [P3] More instrumented coverage + coverage reporting

Expand beyond the smoke test: drawer navigation, language switch (immediate UI update is an
acceptance criterion), deep-link/`navigate_to_stop` handling. Add Kover for a coverage signal in
CI (as information, not a gate).

---

## 3. Performance

### 3.1 [P1] Baseline Profiles + Macrobenchmark

SPEC §11 targets cold start < 800ms, but nothing measures or optimizes it. Add a
`:macrobenchmark` module: a startup benchmark makes the target verifiable, and a generated
**Baseline Profile** typically cuts cold start 15–30% on real devices — the single biggest
lever for the "open app → see your bus in under 1 second" goal. R8 is already configured, so
this slots right in.

### 3.2 [P1] Defer MapLibre initialization

`StasiApplication.onCreate` calls `MapLibre.getInstance()` (native lib load + JNI setup) on every
cold start, but most launches go Home → Arrivals and never open the map. Initialize lazily on
first map use (guarded `synchronized` helper). Directly serves the cold-start target.

### 3.3 [P2] Compose recomposition audit

The arrivals board ticks every 15s and the map refreshes every 15–30s. After the Kotlin 2.x
upgrade (1.5): verify strong skipping is active, ensure `LazyColumn` items have stable `key`s
(stop+route+veh), and use the compiler metrics reports
(`-Pandroidx.enableComposeCompilerMetrics`) once to find unstable parameters. Cheap to do, and
this list is the app's hottest UI path.

### 3.4 [P2] Startup tracing in debug builds

Add `androidx.tracing` sections around app-container construction, DataStore first reads, and
first arrivals fetch so Perfetto traces show where the <1s budget actually goes.

### 3.5 [P3] Migrate Gson → kotlinx.serialization (or Moshi)

Gson is reflection-based: slower parsing, and it silently bypasses Kotlin null-safety (a missing
field becomes `null` in a non-null `val` — a real crash class under R8 renaming, currently held
back by proguard keeps). kotlinx.serialization is compile-time generated, faster, and removes
the keep rules. Medium effort because of OASA's quirky payloads — do it after 2.2 exists so
MockWebServer fixtures verify equivalence.

---

## 4. Networking & reliability

### 4.1 [P1] Replace global cleartext with a Network Security Config

`AndroidManifest.xml` sets `android:usesCleartextTraffic="true"` app-wide because the OASA base
URL is `http://telematics.oasa.gr`. Two steps:

1. **Test whether `https://telematics.oasa.gr/api/` works** and switch if it does (even with a
   marginal cert, HTTPS beats cleartext for a location-adjacent app).
2. If HTTP must stay, scope it: `res/xml/network_security_config.xml` permitting cleartext for
   `telematics.oasa.gr` only, with `cleartextTrafficPermitted="false"` as the base policy. Same
   functionality, dramatically smaller exposure — and "privacy-minded" marketing should not ship
   with a global cleartext waiver.

### 4.2 [P2] OkHttp hardening

- Explicit `connectTimeout`/`readTimeout` tuned for mobile (OASA can hang; the default 10s read
  can stall the arrivals board past its 1s budget with no user feedback).
- Retry-with-jitter for idempotent GET-like calls (all OASA calls are effectively reads).
- Make sure the **logging interceptor is debug-only** (bind it behind `BuildConfig.DEBUG`) — it
  is currently a plain `implementation` dependency; logging response bodies in release wastes
  memory and can leak coordinates into logcat.

### 4.3 [P2] Offline-first nearby stops

`getClosestStops` requires network, but the full stop catalog with coordinates is already in
Room after catalog sync. Add a haversine-sorted local query as fallback (or primary) so Nearby
and manual-map pins work in airplane mode — matching the existing "works offline after first
load" acceptance criterion, which today only holds for search/favorites.

### 4.4 [P3] Stale-data affordance

When showing cached arrivals during an outage, surface age prominently ("as of 12:41 — OASA not
responding") rather than only the small freshness stamp. Pairs with typed errors (1.4).

---

## 5. Product features

### 5.1 [P1] Home-screen widget (Glance)

The killer feature for "see your bus in under a second": a Jetpack Glance widget showing the next
arrivals for 1–2 favorite stops, refreshed by WorkManager on a 15-minute baseline plus manual
tap-to-refresh. All the plumbing exists (repositories, WorkManager, DataStore favorites). This is
the most-requested feature class for transit apps and removes even the app-launch step.

### 5.2 [P2] App shortcuts + finish deep links

- **Static/dynamic shortcuts** (long-press launcher icon) to the top 3 favorite stops.
- The manifest already declares `stasi://stop` and notifications carry `navigate_to_stop`; SPEC
  notes the Compose layer must consume it. Verify/complete end-to-end handling, then add **HTTPS
  App Links** from the GitHub Pages site (`https://ntufar.github.io/stasi/stop/<code>` +
  `assetlinks.json`) so shared stop links open the app.

### 5.3 [P2] GTFS static feed integration

OASA publishes a GTFS static feed. Importing it (even build-time into a prepackaged Room DB)
gives: complete offline timetables (no more per-line `getDailySchedule` pacing for the timetable
tab), stop/route data without the slow incremental `webGetLines`→routes→stops crawl, and the
foundation for a future journey planner. Big lever, moderate effort; the daily-schedule API
remains for live-day accuracy.

### 5.4 [P2] Favorites/settings export–import

Favorites, aliases, ordering, and settings live only in DataStore. A JSON export/import (SAF file
picker) protects users across device moves — important because `allowBackup` behavior is
inconsistent across OEMs, and the app deliberately has no accounts.

### 5.5 [P3] Line favorites

Favorites are stops-only. Commuters also think in lines ("where is the 140 right now?") — a
favorited line opening directly into the live route map is a small addition on existing nav.

### 5.6 [P3] Per-favorite alert presets

Already SPEC §14.2: stop-specific default alert lead times (e.g. 10 min for the far stop, 3 for
the near one).

### 5.7 [P3] Android 16 Live Updates for arrival alerts

The arrival-alert notification (countdown → arrived → left) is exactly the "Live Updates" /
`ProgressStyle` use case on Android 16+: promoted ongoing notification with a progress bar to
arrival. Degrade gracefully to the current notification below API 36.

### 5.8 [P3] Wear OS tile / complication

"Next bus at my stop" on the wrist. Substantial new module — only worth it after the widget
(5.1) proves the surface.

### 5.9 [P3] Journey planner

Out of MVP scope per SPEC §4, but GTFS (5.3) plus a simple RAPTOR/CSA implementation over the
static feed would make Stasi a full alternative to the official app. Keep on the horizon.

---

## 6. UX & accessibility

(Visual design specifics — palette, badges, hierarchy — are in
[VISUAL_IMPROVEMENTS.md](VISUAL_IMPROVEMENTS.md).)

### 6.1 [P1] Accessibility audit

Nothing in the codebase suggests a TalkBack pass has happened. Minimum bar:

- `contentDescription` audit on all icon buttons (map FABs, overflow actions, alert bell) and
  meaningful semantics on arrival rows ("Line 3 to Nea Filadelfeia, 4 minutes" as one node, not
  four fragments).
- The 15s countdown must **not** be a live region (TalkBack would announce every tick); the
  refresh action's result should be politely announced once.
- Touch targets ≥ 48dp (the per-row alert bell and favorite-card overflow are candidates to
  check).
- Font-scale 2.0 test on Arrivals — 48sp minutes plus large scale risks clipping.
- Run the Accessibility Scanner + enable lint's accessibility checks in CI.

### 6.2 [P2] Per-app language visible to the system

The app switches locale via `AppCompatDelegate.setApplicationLocales`, but the manifest has no
`android:localeConfig` and there is no `res/xml/locales_config.xml`. Adding it (en, el) makes
Stasi appear in **Settings → System → App languages** on Android 13+, and lets
`autoStoreLocales` persist the choice for free.

### 6.3 [P2] Light theme + dynamic color + themed icon

The app is dark-only by design default, but a proper light scheme (already sketched in
VISUAL_IMPROVEMENTS.md), an optional Material You dynamic-color toggle, and a **monochrome
launcher icon layer** (Android 13 themed icons — currently missing from
`mipmap-anydpi-v26/ic_launcher.xml`) modernize the surface cheaply.

### 6.4 [P3] Adaptive layouts

Single-pane phone layout everywhere. With `material3-adaptive` + `WindowSizeClass`: two-pane
Home (favorites list | arrivals detail) on tablets/foldables/landscape, and a side-by-side
map+timetable on expanded width. Low urgency for a commuter phone app, but the Compose migration
cost is low if done screen-by-screen.

---

## 7. Privacy & security

### 7.1 [P1] Backup rules

`android:allowBackup="true"` with no `fullBackupContent`/`dataExtractionRules`. Cloud backup
currently includes the Room cache (harmless), DataStore favorites (desirable), and active
arrival-alert keys (stale after restore — the WorkManager jobs won't exist, leaving phantom
"active" bells). Add `res/xml/backup_rules.xml` + `dataExtractionRules` including `settings` and
favorites but excluding `arrival_alerts` and the arrivals cache.

### 7.2 [P2] Opt-in, privacy-preserving crash reporting

"No crashlytics" is the right default, but today a field crash is invisible — users just churn.
**ACRA** with a mailto sender (crash report drafted as an email the user chooses to send) keeps
the no-server, no-tracking promise while giving a feedback channel. Document it in the privacy
policy either way.

### 7.3 [P2] Gradle dependency verification / lockfiles

Supply-chain baseline for a signed, published app: enable Gradle dependency verification
(`gradle/verification-metadata.xml` with SHA-256) or at least dependency locking, so CI fails on
a silently-swapped artifact. Pair with the wrapper validation already in CI.

### 7.4 [P3] Reproducible/verifiable releases

Publish APK checksums with GitHub releases, and consider making the build reproducible (fixed
timestamps, stable R8) so users can verify Play/GitHub artifacts match the source. Strong fit
for the app's privacy positioning, and a prerequisite for F-Droid (8.4).

---

## 8. Build, CI & distribution

### 8.1 [P1] Modernize CI Gradle setup

- Replace `setup-java`'s coarse `cache: gradle` with **`gradle/actions/setup-gradle`** — proper
  read-only cache on PRs, configuration-cache support, and a dependency-graph submission option
  (enables GitHub's Dependabot alerts for Gradle deps without Renovate).
- Enable **Gradle configuration cache** and build cache in `gradle.properties`
  (`org.gradle.configuration-cache=true`, `org.gradle.caching=true`) — AGP 8.13 supports it; the
  `clean` step in the lint job defeats caching and should be dropped.
- Add detekt/ktlint (1.7) and Roborazzi verify (2.5) steps once they exist.

### 8.2 [P2] Release process automation

- Generate GitHub release notes from `CHANGELOG.md` (keep-a-changelog format + a small action)
  so tags, Play "what's new" (`distribution/whatsnew/` is already gitignored — wire it), and the
  changelog can't drift apart.
- A `versionCode`/`versionName` bump check on release PRs.

### 8.3 [P2] Fastlane metadata directory

Store Play listing text, screenshots, and changelogs as `fastlane/metadata/android/{en-US,el-GR}`
in-repo. Makes listing changes reviewable, enables supply-based upload from the existing
`release-play-store.yml`, and is the exact format F-Droid consumes.

### 8.4 [P3] F-Droid distribution

The natural audience for a "privacy-minded, no ads, no accounts" transit app. One blocker:
`play-services-location`. Add a product flavor (`foss`) using `android.location.LocationManager`
(fused accuracy is overkill for "stops within 500m"), or adopt a drop-in like the microG unified
API. Everything else (MapLibre, no proprietary services) is already F-Droid-clean.

### 8.5 [P3] In-app update nudge (Play flavor only)

Play's in-app updates API (flexible mode) for the Play build, so arrival-logic fixes actually
reach users. Keep it out of the `foss` flavor.

---

## 9. Data quality & search

### 9.1 [P2] Catalog sync as a scheduled WorkManager job

The lines/stops catalog sync currently piggybacks on opening Search, throttled to ~24h. Move it
to a periodic WorkManager request with `NetworkType.UNMETERED` preference + charging constraint,
keeping the on-open trigger as fallback. Result: search is complete on day one even for users who
never idle in the Search screen, without burning their mobile data.

### 9.2 [P2] Room FTS for stop search

Stop search normalizes and scans in memory/SQL LIKE. With thousands of catalog stops, a Room
`@Fts4` table over normalized stop names (plus the Greeklish-expanded form) gives prefix search
that stays instant and enables multi-word queries ("ag dimitriou"). The existing
`GreekText` normalizer becomes the FTS tokenizer input.

### 9.3 [P3] Smarter Greeklish

The letter-by-letter Latin→Greek map misses digraphs (th→θ, ps→ψ, ks/x→ξ, ou→ου, ai→αι/e→αι).
A small digraph-first mapping table plus generating *both* candidate expansions would noticeably
improve tourist-typed queries like "thiseio" or "psychiko". Pure function, easy to test — extend
`GreekTextTest` with real stop-name fixtures.

### 9.4 [P3] Stop metadata enrichment

OASA stop names are shouty and truncated. Post-GTFS (5.3), title-case display names, dedupe
same-name stops by direction ("ΣΥΝΤΑΓΜΑ" ×4), and show served lines as chips in search results
so users pick the right platform before opening arrivals.

---

## 10. Documentation & project hygiene

### 10.1 [P1] Split SPEC.md into spec + architecture notes

SPEC.md v0.45 has grown into a hybrid of product spec and implementation log (single paragraphs
describing `ContextWrapper` details, worker rescheduling, cache TTL tables). Keep SPEC.md as the
*product* contract (behaviors, acceptance criteria) and move implementation mechanics to
`docs/ARCHITECTURE.md` with a module/data-flow diagram. Both stay maintained; each becomes
readable. (Also note: `docs/` doubles as the GitHub Pages root — consider moving internal docs to
a non-published directory or accepting they're public.)

### 10.2 [P2] CONTRIBUTING.md + issue templates

The README already documents JDK-17 pinning and build steps; extract contributor workflow
(branching, spec-update rule from SPEC §10, test expectations) into CONTRIBUTING.md, and add
GitHub issue templates (bug / stop-data problem / feature) — transit apps get a distinctive
"this stop shows wrong data" report class that benefits from a structured template asking for
stop code + time + screenshot.

### 10.3 [P3] KDoc on public repository/util APIs

The tricky utilities (`ArrivalDisplayMinutes`, `DailyScheduleWallClock`, `EndpointRateLimiter`)
have tests but little prose on *why* (e.g. why effective minutes count down between polls).
One-paragraph KDoc headers preserve the reasoning that currently lives only in SPEC.md.

---

## Suggested sequencing

A realistic order that front-loads compounding wins:

1. **Foundations (1–2 weeks):** version catalog + Renovate (1.6) → Kotlin 2.x/Compose upgrade
   (1.5) → CI modernization (8.1) → network security config (4.1) → backup rules (7.1).
2. **Safety net:** ViewModel tests (2.1) → MockWebServer repository tests (2.2) → then the
   `OasaRepository` split (1.1) *with tests already watching*.
3. **Speed:** Macrobenchmark + Baseline Profile (3.1), lazy MapLibre init (3.2).
4. **Visible wins:** Glance widget (5.1), accessibility pass (6.1), `localeConfig` (6.2),
   offline nearby (4.3).
5. **Long game:** GTFS import (5.3) → search/data quality (9.x) → F-Droid flavor (8.4) →
   journey planner (5.9).
