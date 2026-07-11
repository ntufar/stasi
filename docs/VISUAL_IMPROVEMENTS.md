# Visual Improvement Proposals

Proposals for improving the visual design of the Stasi app, based on a review of the current
screens (Home, Search, Arrivals, Map), the theme code (`app/src/main/java/io/github/ntufar/stasi/ui/theme/`),
and the screenshots in `docs/screenshots/`. Ordered roughly by impact-per-effort.

---

## 1. Complete the color scheme (high impact, low effort)

`Theme.kt` only defines 7 slots of the dark `ColorScheme` (primary, background, surface,
on-colors). Everything else — `secondary`, `tertiary`, all the `*Container` roles, `outline`,
`error` — falls back to the Material 3 baseline **purple** palette. This is why:

- The scheduled departure time ("15:50" on the Arrivals screen) renders **lavender**
  (`scheme.secondary`) next to the green live countdowns — it reads as a different app.
- The locate FAB on the Map screen is **purple**, clashing with the green/black identity.

**Proposal:** define a full green-anchored dark scheme (and matching light scheme). Concretely:

| Role | Suggestion |
| --- | --- |
| `secondary` | A desaturated green or warm neutral (e.g. `0xFFA5D6A7` family) so scheduled times feel related to live ones |
| `tertiary` | The amber already used ad-hoc (`0xFFFFA726`) — makes "last service" / traffic-warning styling theme-driven |
| `surfaceContainer*` | Very dark greys (`0xFF0D0F0D` … `0xFF1A1D1A`) so cards separate from the AMOLED background |
| `outlineVariant` | `0xFF2A2E2A` for hairline dividers |
| `error` / `errorContainer` | Proper dark-theme error pair instead of baseline |

Also move the hardcoded `Color(0xFFFFA726)` in `ArrivalsScreen.kt` and `MapScreen.kt` into the
theme (`tertiary` or a semantic `WarningAmber` in `Color.kt`) so it can adapt to light theme.

---

## 2. Line number badges / pills (high impact, medium effort)

Transit apps are recognizable by their line pills. Today the line is a plain text string
("3 · ΝΕΟ ΨΥΧΙΚΟ - ΑΝΩ ΠΑΤΗΣΙΑ - Ν. ΦΙΛΑΔΕΛΦΕΙΑ") on Arrivals, Home favorites, and Search.

**Proposal:** extract the line ID into a rounded rectangle badge with strong contrast:

```
┌─────┐
│  3  │  ΝΕΟ ΨΥΧΙΚΟ → Ν. ΦΙΛΑΔΕΛΦΕΙΑ
└─────┘
```

- Color the badge by transport type, following OASA conventions: blue for buses, yellow/amber
  for trolleys, green for express, dark blue for night lines. (Line type is derivable from the
  line code / catalog data.)
- Reuse the same badge composable everywhere: Arrivals rows, Home favorite cards, Search
  results, and even the Map top bar ("Γραμμή 3" → badge + destination).
- This alone makes every screen scannable: eyes find the line number first, then the time.

---

## 3. Arrivals screen: hierarchy and de-duplication (high impact, medium effort)

The Arrivals screen (see `docs/screenshots/arrivals.png`) is a wall of left-aligned text on
black. Two specific issues:

1. **Duplicated text** — the line label ("3 · ΝΕΟ ΨΥΧΙΚΟ - ΑΝΩ ΠΑΤΗΣΙΑ - Ν. ΦΙΛΑΔΕΛΦΕΙΑ") and
   the destination label below it are often the *same string* rendered twice. Show the
   destination once.
2. **No row separation** — rows blend together; the only structure is whitespace.

**Proposal:**

- Restructure each row: countdown on the left (keep it big — it's the app's identity),
  line badge + destination stacked to its right, bell aligned to the row (not floating):

  ```
  ┌────────────────────────────────────────────┐
  │  3'   [3] ΝΕΟ ΨΥΧΙΚΟ                    🔔 │
  │       → Ν. ΦΙΛΑΔΕΛΦΕΙΑ                     │
  └────────────────────────────────────────────┘
  ```

- Separate rows with `surfaceContainer` cards (subtle, AMOLED-friendly) or hairline
  `outlineVariant` dividers.
- **Urgency color-coding** for the countdown: e.g. `error`/amber when ≤ 1–2 min ("run!"),
  primary green otherwise, and a "τώρα"/"now" state instead of "0'". Optionally a gentle pulse
  animation when a bus is due.
- Scheduled (non-live) departures: prefix with a small clock icon 🕐 or a "Πρόγραμμα" chip so
  the semantic difference from live arrivals is visible, not just a different color.

---

## 4. Typography scale (medium impact, low effort)

`StasiTheme` passes no `Typography`, so everything is default Roboto with default metrics.

**Proposal:**

- Define a `Type.kt` with an explicit scale. For the big countdown numbers use
  `FontFeatureSettings "tnum"` (tabular figures) so "3'" → "13'" doesn't shift layout on tick.
- Consider a slightly condensed or rounded display face for countdowns (e.g. Roboto Condensed
  or Google Sans-like) to give the app a face of its own — one font file, used only for
  numerals.
- Greek ALL-CAPS strings from the API are visually loud. Where the string is a destination,
  render it in `bodyMedium` with reduced letter-spacing, or title-case it
  (`ΝΕΟ ΨΥΧΙΚΟ` → `Νέο Ψυχικό`) via a Greek-aware capitalizer — big readability win on long
  names.

---

## 5. Bottom navigation instead of drawer + icon row (high impact, medium effort)

Navigation is currently a modal drawer plus a crowded Home top bar (menu, refresh, search, map
icons **and** a clock). Drawers hide navigation; the four destinations here are exactly what
Material 3 `NavigationBar` is for.

**Proposal:**

- Bottom `NavigationBar`: **Αρχική / Αναζήτηση / Κοντά μου / Χάρτης** (Home / Search / Nearby /
  Map). Thumb-reachable, discoverable, standard.
- Keep the drawer only for Settings/About, or move those behind a single overflow icon.
- Drop `Refresh` from the top bar on Home — pull-to-refresh already exists on Arrivals; add it
  to Home instead.
- The persistent `ClockText` in every top bar duplicates the status-bar clock; consider
  removing it or keeping it only on Arrivals (where "updated at" freshness matters).

---

## 6. Map screen polish (medium impact, medium–high effort)

From `docs/screenshots/route-map.png`:

- **Light map inside dark chrome** — the bright street map clashes with the black top bar and
  the dark app. Use a dark MapLibre style (or a filtered/dimmed variant) when `darkTheme`,
  matching the AMOLED identity; keep the light style for light theme.
- **Stop markers** — numbered cyan circles are heavy and collide at mid-zoom. Proposal: plain
  small dots below a zoom threshold, numbered circles only when zoomed in (some logic exists in
  `MapStopLabels.kt` — tune thresholds), and use theme colors (green fill, dark stroke) instead
  of cyan.
- **Locate FAB** — inherits baseline purple; will be fixed by proposal 1, but also consider the
  standard "my location" crosshair icon in `surfaceContainerHigh` with `primary` icon tint.
- **Vehicle markers** — the amber arrow is good; add a subtle pulsing halo on live vehicles so
  "live" is felt, and a small line-number label next to the vehicle when a single route is
  displayed with multiple buses.
- **Route direction** — add chevrons along the polyline so direction of travel is visible
  without reading stop numbers.

---

## 7. Loading, empty, and error states (medium impact, low–medium effort)

Currently: centered `CircularProgressIndicator`, plain red error text, plain "no favorites"
text.

**Proposal:**

- **Skeleton loaders** on Arrivals and Home favorite cards (shimmering placeholder rows) —
  perceived speed matters for a "next bus in under a second" app.
- **Empty states with a glyph**: e.g. a bus-stop icon + "Δεν υπάρχουν αφίξεις" + a hint
  ("Τραβήξτε προς τα κάτω για ανανέωση"). One reusable `EmptyState(icon, title, hint)`
  composable.
- **Offline banner**: when showing cached data, a slim `surfaceContainerHigh` banner
  ("Εκτός σύνδεσης — δεδομένα από τις 15:46") instead of only the small freshness label.

---

## 8. Home screen structure (medium impact, medium effort)

- The "nearby stops" section leads with a bare `OutlinedButton` for location. Replace with a
  proper permission card (icon + one-line rationale + button) shown only until granted; once
  granted, auto-refresh nearby on screen entry and show distance chips ("120 m") on each stop.
- Favorite cards: the title is green `titleLarge`, but arrivals inside are small grey text —
  invert the emphasis: minutes should be the biggest element on the card (that is what the user
  opens the app for), using the same badge + countdown row as Arrivals.
- Replace the "move up / move down" dropdown items with **drag-to-reorder** handles
  (`sh` reorderable LazyColumn) — more direct and expected.
- Section headers ("Κοντινές στάσεις", "Αγαπημένα") could get leading icons (📍, ★) for faster
  scanning.

---

## 9. Motion (low impact, low effort — polish)

- `animateItem()` on LazyColumn items so refresh reorders slide instead of jump-cut.
- `AnimatedContent` on countdown text so a change from 4' → 3' does a small slide-up tick.
- Navigation transitions (fade-through between destinations) via
  `NavHost` enter/exit transitions — one-liner with Navigation Compose.

---

## 10. Material You / dynamic color option (low impact, low effort)

Offer "Δυναμικά χρώματα" in Settings on Android 12+: `dynamicDarkColorScheme(context)` /
`dynamicLightColorScheme(context)` when enabled, falling back to the brand scheme. Keep the
AMOLED black background override even in dynamic mode. Cheap to add, and users increasingly
expect it.

Also worth doing while touching the theme:

- **Themed (monochrome) app icon** for Android 13+ so the launcher icon follows Material You.
- **Edge-to-edge**: draw behind the system bars with transparent bars — with an AMOLED-black
  app this is nearly free and removes the visible status-bar seam in the screenshots.

---

## 11. Landing page (`docs/index.html`) — bonus

- Show the two screenshots inside device frames (pure CSS phone bezels) instead of bare PNGs.
- Add a dark/light scheme via `prefers-color-scheme` mirroring the app palette (green on
  near-black) so the site and app feel like one product.
- Add the app icon as a hero element and align the accent color with `primary` green
  (`#81C784` dark / `#2E7D32` light).

---

## Suggested order of execution

| Phase | Items | Rationale |
| --- | --- | --- |
| 1 | #1 color scheme, #4 typography, #10 dynamic color/edge-to-edge | Pure theme work, no layout changes, fixes the purple clashes immediately |
| 2 | #2 line badges, #3 arrivals hierarchy | The core screens; biggest visible payoff |
| 3 | #5 bottom nav, #8 home structure | Navigation/IA change, touches most screens |
| 4 | #6 map polish, #7 states, #9 motion, #11 site | Progressive polish |
