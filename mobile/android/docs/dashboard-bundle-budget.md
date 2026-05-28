# Dashboard Bundle Budget

Date: 2026-05-27

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
