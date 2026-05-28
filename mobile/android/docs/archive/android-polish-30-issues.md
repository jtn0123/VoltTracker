# Android App Polish / Debug Tracker

Date: 2026-05-20

Scope: Android standalone app only. Each item below is either validated from current code/data or explicitly blocked on the next car field pass.

## Validated And Addressed In This Pass

1. `MAP-01` Latest-only route selection: the UI only exposed `latestRoute`, so earlier route-bearing sessions from today's runs could not be selected. Fixed by adding `recentRoutes` to storage summary and selectable map session rows.
2. `MAP-02` Bland map surface: the map was a plain abstract grid. Fixed with stronger pseudo-basemap roads, labels, route guides, and v2-compatible contrast.
3. `MAP-03` Fullscreen map used a card-inset treatment and left app chrome competing for space. Fixed with edge-to-edge fullscreen styling and hidden bottom nav while fullscreen.
4. `MAP-04` Fullscreen button copy was too terse. Fixed by changing state labels to `Full map` / `Exit map`.
5. `MAP-05` Map session list did not show route distance/point context. Fixed by showing route distance and point count per route-bearing session.
6. `MAP-06` Map session list had no selected state. Fixed with an active route row style tied to `selectedMapSessionId`.
7. `MAP-07` Map was vulnerable to empty/no-route sessions because the latest session could be a zero-row diagnostic artifact. Fixed by choosing reviewable routes from sessions with stored GPS points.
8. `NAV-01` Bottom nav used letters instead of actual icons. Fixed with inline SVG icons for Drive, Trips, Map, Charge, Insights, and Diagnostics.
9. `NAV-02` Bottom nav could remain visible over fullscreen map. Fixed with `body.map-full-active .bottom-nav { display: none; }`.
10. `LAYOUT-01` Body/app could still allow horizontal overflow on some WebView sizes. Fixed with `overflow-x: hidden` and clipped app container.
11. `LAYOUT-02` Load and GPS were vertically stacked even with horizontal space available. Fixed with a compact `drive-signal-grid`.
12. `LAYOUT-03` GPS lived only as a small mini tile. Fixed with a proper GPS metric card alongside Load.
13. `LAYOUT-04` Card padding was a little bulky for phone density. Fixed by tightening repeated card padding from 14px to 12px.
14. `LAYOUT-05` Cell swatches felt too heavy compared with the v2 visual language. Fixed by reducing cell height, radius, gap, and opacity.
15. `POWER-01` Real Power showed a blank value with no explanation because `power_kw` is absent in today's DB. Fixed with an explicit `Volt PID needed` state.
16. `POWER-02` The real Battery overview could imply power/SOC should be present. Existing copy now remains honest until Volt-specific PIDs are validated.
17. `DB-01` Empty broken-pipe telemetry rows could still be inserted through the DB layer. Fixed with `isUsefulTelemetry()` guard in `recordTelemetry()`.
18. `DB-02` Latest telemetry could point at an empty `{}` row. Fixed by filtering latest telemetry queries to useful rows.
19. `DB-03` Latest health could point at an empty row. Fixed by filtering latest health queries to useful rows.
20. `DB-04` State counts included empty unknown broken-pipe samples. Fixed by filtering state counts to useful rows.
21. `DB-05` Speed trace could include empty samples. Fixed by filtering speed trace to useful rows.
22. `DB-06` Average sample interval was distorted by long empty broken-pipe spans. Fixed by computing intervals from useful telemetry.
23. `DB-07` Max speed queries did not consistently apply useful-row filtering. Fixed for telemetry-backed max queries.
24. `DB-08` Latest review could choose a zero-row newest session. Fixed with `latestReviewableSession()`.
25. `DB-09` Storage summary hid the difference between raw telemetry rows and useful rows. Fixed with `rawTelemetryCount` and `emptyTelemetryCount`.
26. `DB-10` Session list hid broken-pipe pollution. Fixed by showing useful and empty sample counts.
27. `LOG-01` Repeated identical status rows could spam `status_events`. Fixed with a 5-second duplicate status throttle.
28. `LOG-02` A broken pipe kept the live loop running and persisting empty rows. Fixed by stopping polling when `readObdSample()` fails.
29. `BG-01` Samples incorrectly recorded `foregroundServiceActive: false` after session start. Fixed by starting foreground mode after clearing the previous session.
30. `UX-01` Diagnostics did not make old bad data legible. Fixed by exposing raw rows, empty rows, useful sample counts, route-bearing sessions, and PID-needed labels.

## Verified Blocked By Field Data

- `FIELD-POWER-KW`: `power_kw`, SOC, kWh, pack current, pack voltage, and charge power are all absent in today's real DB. The app must not fake these in real mode.
- `FIELD-ENGINE-RPM`: standard `010C` parsed correctly but returned `410C0000` for every useful row. Need a known engine-running scan to determine whether this is a Volt-specific PID issue or a missed logging window.
- `FIELD-BATTERY-PIDS`: scan mode needs real responses from `ATSH7E4` and `ATSH7E7` before battery/cell/charger values can be promoted into live polling.

## Verification

- Android debug build passed after the changes.
- Dashboard JavaScript syntax check passed by extracting the `<script>` block and running `node --check`.
- Patched APK installed successfully on the connected phone after the previous pass; reinstall after this pass is required for the latest map/nav changes.
