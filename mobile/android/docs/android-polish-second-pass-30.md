# Android App Polish / Debug Tracker - Second Pass

Date: 2026-05-20

Scope: Android standalone app only. This pass re-checked the first fixes and focused on factual issues still present in the phone app code, map screen, diagnostics review, and SQLite summary layer.

## Validated And Addressed In This Pass

1. `MAP2-01` Map had no visible scale cue, so a square around-the-block route and a 20-mile drive could feel visually identical. Fixed with a route distance scale pill.
2. `MAP2-02` Map had no compass cue, which made fullscreen feel like a decorative panel instead of a map. Fixed with a compact north marker.
3. `MAP2-03` Map HUD styles existed after the first pass but the HUD markup was missing. Fixed by adding the HUD inside `mapFrame`.
4. `MAP2-04` Route projection treated longitude degrees the same at every latitude, which can distort Bay Area/Tahoe routes. Fixed by scaling longitude by latitude cosine.
5. `MAP2-05` Recent route exports were capped too low for review. Fixed by increasing `recentRoutes` point payloads from 240 to 500.
6. `MAP2-06` Map layer buttons had a tablist role but no selected state. Fixed with initial and live `aria-selected`.
7. `MAP2-07` Map fullscreen still needed a stronger mobile surface. Verified and kept edge-to-edge fullscreen styling with app chrome hidden.
8. `MAP2-08` Fullscreen map controls were visually present but needed clearer state copy. Verified `Full map` / `Exit map` state remains active.
9. `MAP2-09` Map session rows could look clickable without enough current-route context. Verified active row highlighting and route distance/point counts.
10. `MAP2-10` Empty map copy needed to point at GPS permission and SQLite route samples. Verified the real empty state now says exactly that.
11. `NAV2-01` Bottom nav icons were visual only; buttons lacked explicit labels. Fixed with `aria-label` for Drive, Trips, Map, Charge, Insights, and Diagnostics.
12. `NAV2-02` Bottom nav active item lacked `aria-current`. Fixed initial state and live updates in `setView()`.
13. `NAV2-03` Bottom nav active state could become visual-only after view changes. Fixed by updating current-page semantics whenever the view changes.
14. `NAV2-04` SVG nav icons needed to stay hidden from screen readers because the button labels carry the names. Verified all icon SVGs remain `aria-hidden`.
15. `REVIEW2-01` Last-session review did not expose useful telemetry rows. Fixed with `reviewUsefulSamples`.
16. `REVIEW2-02` Last-session review did not expose empty/broken-pipe row counts. Fixed with `reviewEmptySamples`.
17. `REVIEW2-03` Backend session review did not include useful telemetry counts. Fixed with `usefulTelemetryCount`.
18. `REVIEW2-04` Backend session review did not include empty telemetry counts. Fixed with `emptyTelemetryCount`.
19. `WARN2-01` Older broken-pipe sessions were easy to misread as real data. Fixed with an `empty-telemetry` warning.
20. `WARN2-02` The engine-ran-but-RPM-stayed-zero question needed a persistent diagnostic flag. Fixed with `rpm-zero-moving`.
21. `WARN2-03` kW/power was blank but not explicitly diagnosed after a session. Fixed with `power-pid-missing`.
22. `DATA2-01` Storage summary counted raw telemetry and useful telemetry differently but session review did not. Fixed by aligning the review payload with storage summary concepts.
23. `DATA2-02` Empty telemetry could distort per-session troubleshooting. Fixed by showing useful/empty counts side-by-side.
24. `DATA2-03` Route review could silently drop useful route detail on longer sessions. Fixed by increasing route point availability for recent routes.
25. `UX2-01` Load/GPS layout from the first pass needed verification against the current DOM. Verified a two-column signal grid with a one-column mobile fallback.
26. `UX2-02` Large cell swatches from the first pass needed verification. Verified reduced height/radius/opacity stays in the v2 visual language.
27. `UX2-03` Power/kW needed honest real-mode copy instead of demo-like confidence. Verified `Volt PID needed` until validated pack/charger PIDs exist.
28. `ACCESS2-01` Map layer controls were keyboard/screen-reader ambiguous. Fixed selected state updates.
29. `ACCESS2-02` Current bottom navigation destination was keyboard/screen-reader ambiguous. Fixed current-page state updates.
30. `DOC2-01` The second pass itself was not tracked separately. Fixed with this document so remaining validation items are explicit.

## Still Waiting On Field Validation

- `FIELD-POWER-KW`: No real `power_kw`, pack current, pack voltage, charger power, or kWh rows are available yet. UI must keep saying a Volt PID is needed.
- `FIELD-ENGINE-RPM`: If the engine truly ran during the second 20-mile drive, standard `010C` did not prove it in the captured rows. The next scan should target Volt-specific engine/generator signals and confirm standard RPM responses while the engine is audibly running.
- `FIELD-BATTERY-PIDS`: Battery/cell/charge-state UI should stay non-demo until `ATSH7E4` / `ATSH7E7` style Volt PIDs are validated.
- `FIELD-MAP-GPS`: The map can now render route-bearing sessions, but route quality still depends on phone location permission, background collection, and actual GPS sample density.

## Verification

- Dashboard JavaScript syntax check passed by extracting the `<script>` block and running `node --check`.
- `git diff --check` passed for the changed dashboard, database store, and tracker files.
- Android debug build passed with `.\gradlew.bat :app:assembleDebug`.
- Install is pending: `adb devices` returned no connected devices after an ADB server restart.
