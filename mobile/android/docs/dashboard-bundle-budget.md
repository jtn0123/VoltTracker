# Dashboard Bundle Budget

Date: 2026-05-27

> **Note (2026-06-17):** the single combined budget below is a historical
> snapshot. The budget has since been **split** into per-bucket byte budgets —
> see [`bundle-budget.md`](bundle-budget.md) and the dashboard budget constants
> in `mobile/android/build.gradle` for the current numbers (this note quotes
> none on purpose, so it can't drift). Use `./gradlew dashboardAssetReport`
> for current local asset sizes.

Scope: first-party dashboard JavaScript and CSS under
`mobile/android/app/src/main/assets/dashboard`, excluding vendored
`lib/leaflet`.

## Current Budget

| Metric | Bytes |
|---|---:|
| Current first-party JS/CSS | 614,604 |
| Enforced budget | 650,000 |
| Remaining headroom | 35,396 |

`verifyActiveApp` runs `verifyDashboardBundleSize`, which fails when the
first-party dashboard bundle exceeds the budget.

## CI Headroom Report

The `dashboard-tests` job in `.github/workflows/android.yml` appends a bundle
report to the workflow run summary after building the bundle: per-file sizes,
plus a "Budget headroom" table showing actual bytes vs the core and DTC-data
budgets. When either bundle is within **15 KB** of its budget the step emits a
workflow warning annotation (it does not fail the job — the hard gate stays
`verifyDashboardBundleSize` in the `unit-tests` job). Use the report to spot a
shrinking margin before a PR trips the hard budget.

## Largest Assets

| Asset | Bytes | Note |
|---|---:|---|
| `js/dtc-lookup.js` | 219,599 | Large DTC description dictionary; best candidate for lazy loading. |
| `js/dtc-causes.js` | 117,610 | Large causes/severity dictionary; should move with the lookup data. |
| `js/panels.js` | 40,490 | Main dashboard panel render logic. |
| `css/screens.css` | 27,995 | Screen-specific dashboard styling. |
| `js/map.js` | 26,178 | Map rendering and tile control logic. |

The current budget is passing. The DTC dictionaries account for more than half
of the first-party bytes, but they are lazy-loaded through `VD.ensureDtcData()`
instead of parsed during dashboard startup. Keep tracking this file after major
dashboard or dictionary changes.
