# Dashboard Script Contract

The production dashboard ships its JavaScript as **one built bundle**, compiled from the
editable source in `app/src/main/dashboard-src/js/` by `dashboard-tests/build.mjs` (esbuild).

`index.html` loads a single classic script:

1. `js/app.js`

`app.js` is a classic IIFE — **never** an ES module. The WebView serves the dashboard from
`file:///android_asset/`, where `<script type="module">` is fetched with CORS semantics that
`file://` cannot satisfy, so a module bootstrap silently never runs on-device. esbuild bundles
the eager source files (via side-effect imports, in dependency order) into `app.js`, so each
file's IIFE runs in order and shares state through `window.VoltDashboard` exactly as before.
The bundle target is `chrome66` so Android 9-era WebViews do not receive syntax they cannot
parse, such as optional chaining or nullish coalescing.

Eager order (the `EAGER` array in `build.mjs`):

1. `core` (seeds `window.VoltDashboard`)
2. `panels`
3. `map`
4. `scrubber`
5. `drive`
6. `telemetry`
7. `actions` (its bootstrap calls into map/drive/telemetry, so it comes after them)
8. `troubleshooter`
9. `connection-status`
10. `connection-tools`

The large DTC dictionaries (`dtc-causes`, `dtc-lookup`) and the demo fixture (`demo-data`)
are **not** startup scripts. They build into their own `js/<name>.js` chunks, and `core.js`
loads them on demand (`VD.ensureDtcData()` / the demo gate) by injecting a classic `<script>`
— so the lazy-load paths are unchanged.

`dashboard-tests/script-order.test.js` asserts three things: (1) `index.template.html` and the
generated `index.html` load `js/app.js` as a single classic (non-module) script, (2)
`build.mjs`'s `EAGER` array matches the dependency order above, and (3) the built JS does not
ship syntax known to break the Android 9 WebView parser. When adding a new eager script, add it
to `dashboard-src/js/`, insert it into `EAGER` in `build.mjs`, and update the test's
`EXPECTED_EAGER_ORDER` in the same change.

The output dir `app/src/main/assets/dashboard/js/` is **gitignored** — it's a build artifact.
Gradle's `buildDashboardJs` (wired into `preBuild`) rebuilds it before packaging; CI's
dashboard and APK-building jobs run the build too. The jsdom loader in
`dashboard-tests/setup/load-dashboard.js` loads the **source** files from `dashboard-src/js/`
(not the bundle), so unit tests don't need a build.
