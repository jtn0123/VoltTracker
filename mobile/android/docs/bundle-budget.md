# Dashboard bundle budget

The WebView dashboard ships its JS/CSS straight from app assets. To stop it
bloating, `verifyDashboardBundleSize` (in `build.gradle`, wired into `check` and
`verifyActiveApp`) enforces a **split** byte budget.

## Why split

Not all dashboard bytes are equal:

- **Core bundle** — the boot + interactive JS and all CSS. This is loaded up front
  and is what affects startup time and responsiveness.
- **DTC reference data** — `js/dtc-lookup.js` and `js/dtc-causes.js`, ~337 KB of
  pure lookup tables. These are **lazy-loaded** via `loadDashboardScript` only when
  a user opens a specific diagnostic code, so they don't touch startup.

A single combined budget let the lazy DTC tables (≈60% of the bytes) dominate the
number, so the core bundle could creep up unnoticed while the total still looked
fine — and conversely a one-line copy edit to core could fail CI because the total
sat 566 bytes under the cap. Splitting fixes both: core gets a tight guard, the DTC
data gets its own (larger) ceiling, and neither can grow unchecked.

## Current budgets

| Bucket | Files | Budget | Roughly today |
|--------|-------|--------|---------------|
| Core | `js/**/*.js` + `css/**/*.css`, excl. `lib/**` and the DTC data files | **400,000 B** | ~377 KB |
| DTC data | `js/dtc-lookup.js`, `js/dtc-causes.js` | **380,000 B** | ~337 KB |

`lib/**` (vendored Leaflet) is excluded from both — it's third-party code we don't
own and don't edit.

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

Raise the relevant constant in `build.gradle` (`dashboardCoreBudgetBytes` /
`dashboardDtcDataBudgetBytes`) and say why in the commit. Treat the core budget as
a ratchet you justify, not a number you quietly grow.
