# Dashboard bundle budget

The WebView dashboard ships its JS/CSS straight from app assets. To stop it
bloating, `verifyDashboardBundleSize` (in `build.gradle`, wired into `check` and
`verifyActiveApp`) enforces a **split** byte budget.

## Why split

Not all dashboard bytes are equal:

- **Startup bundle** — `js/app.js` and the render-blocking CSS linked from
  `index.template.html` (`base`, `components`, `screens`, `status-tools`). This
  is loaded up front and is what affects startup time and responsiveness.
- **Lazy support JS** — first-party feature chunks such as Map, Troubleshooter,
  demo data/streaming, and secondary action groups. These are loaded on demand
  and stay bounded separately from startup.
- **Lazy CSS** — `css/screens-map.css` and `css/troubleshooter.css`, injected by
  their lazy chunks (`map.ts#ensureMapStyles`, `troubleshooter.ts`) only when the
  Map tab or the recovery modal is actually used, so they don't touch startup.
- **Lazy panel JS** — deferred panel-sized UI chunks such as Insights. This
  bucket keeps a user-facing panel from inflating startup without hiding its
  own growth inside the support bucket.
- **Lazy expert JS** — expert-surface chunks (`js/signals-panel.js`,
  `js/scrubber.js`). Destination-specific controls stay in this bounded bucket
  instead of charging every on-demand interaction to the generic support
  allowance.
- **Lazy detail JS** — the tab-detail chunks split OUT of the eager `app.js`
  in the G2 startup-headroom pass (2026-07-11): the Charge tab's history/cost
  renders + the shared monthly trend chart (`js/charge-history.js`), the
  Insights maintenance log/form (`js/maintenance-panel.js`), and the DTC
  detail sheet + scan narration (`js/dtc-detail.js`). None paints on the
  Drive-first startup path; they load via `core.ts`
  `ensureChargeHistoryModule`/`ensureMaintenancePanelModule`/
  `ensureDtcDetailModule` on tab entry or first interaction.
- **DTC reference data** — `js/dtc-lookup.js` and `js/dtc-causes.js`, ~337 KB of
  pure lookup tables. These are **lazy-loaded** via `loadDashboardScript` only when
  a user opens a specific diagnostic code, so they don't touch startup.

A single combined budget let the lazy DTC tables (≈60% of the bytes) dominate the
number, so startup code could creep up unnoticed while the total still looked
fine — and conversely a one-line copy edit to startup code could fail CI because
the total sat 566 bytes under the cap. Splitting fixes both: startup gets a tight
guard, lazy support and DTC data get their own ceilings, and no bucket can grow
unchecked.

## Current budgets

| Bucket | Files | Budget | Roughly today |
|--------|-------|--------|---------------|
| Startup | `js/app.js` + render-blocking `css/**/*.css` (excludes the lazy CSS below) | **412,000 B** | ~385 KB |
| Lazy support JS | first-party lazy JS chunks except panel, expert, detail, and DTC data | **90,000 B** | ~89 KB |
| Lazy panel JS | `js/insights-panel.js`, `js/connection-tools.js` | **45,000 B** | ~43 KB |
| Lazy expert JS | `js/signals-panel.js`, `js/scrubber.js` | **40,000 B** | ~20 KB |
| Lazy detail JS | `js/charge-history.js`, `js/maintenance-panel.js`, `js/dtc-detail.js` | **25,000 B** | ~21 KB |
| Lazy CSS | `css/screens-map.css`, `css/troubleshooter.css` | **23,500 B** | ~15 KB |
| DTC data | `js/dtc-lookup.js`, `js/dtc-causes.js` | **380,000 B** | ~267 KB |

The startup budget was last raised to 412,000 B for the v2 design work (swipe
tab navigation, DTC bottom sheet, card-stack rework, cell-balance heatmap, and
the rest of the tab-by-tab design match) — see the history comments above
`dashboardStartupBudgetBytes` in `build.gradle`. The G2 startup-headroom pass
(2026-07-11) then moved the charge-history, maintenance, and DTC-detail
renders into the lazy detail bucket, dropping the startup set from ~402 KB to
~385 KB (~27 KB headroom) without touching the budget — reclaimed bytes stay
reclaimed.

`lib/**` (vendored Leaflet) is excluded from both — it's third-party code we don't
own and don't edit. Leaflet JavaScript is also off the startup script path; it is
loaded by `ensureMapModule()` only when the Map tab needs it. `fonts/**` (the
Space Grotesk display face, ~22 KB woff2) is likewise outside the buckets: it is
`font-display: swap`, so it never blocks first paint — text renders in the system
stack and upgrades when the face is parsed.

Use `./gradlew dashboardAssetReport` to print eager JS, lazy JS, CSS, Leaflet
assets, generated HTML, and the current budget headroom.

## What loads over `file://`

The dashboard is served from `file:///android_asset/`, where `fetch`/XHR and ES
modules are blocked by the WebView's CORS rules. That's why the DTC data ships as
classic `<script>` files (injected on demand) rather than JSON fetched at runtime —
and why these reference tables count toward a JS budget at all.

## DTC data growth guard

`dtc-data.test.js` keeps the lazy DTC source tables under 8,000 source lines and
checks the runtime `dtcLookupFamilyCounts` prefix index. If the lookup/cause data
outgrows that budget, move the tables to a generated/chunked representation by
DTC family (`P04`, `U00`, etc.) before raising the byte budget.

## Bumping a budget

Raise the relevant constant in `build.gradle` (`dashboardStartupBudgetBytes`,
`dashboardLazySupportBudgetBytes`, `dashboardLazyPanelBudgetBytes`,
`dashboardLazyExpertBudgetBytes`, `dashboardLazyDetailBudgetBytes`,
`dashboardLazyCssBudgetBytes`, or `dashboardDtcDataBudgetBytes`) and say why
in the commit. Treat the startup
budget as a ratchet you justify, not a number you quietly grow.

Before raising the startup budget, verify that trip and insight rollups still
load only on Map/Insights demand. They are intentionally outside app launch:
the first dashboard render should read the storage overview only, then hydrate
heavier SQLite-backed rollups after the user opens the relevant surface.
