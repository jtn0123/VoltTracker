# Dashboard Script Contract

The production dashboard ships its JavaScript as **one built bundle**, compiled from the
editable source in `app/src/main/dashboard-src/js/` by `dashboard-tests/build.mjs` (esbuild).

`index.html` loads a single classic script:

1. `js/app.js`

`app.js` is a classic IIFE — **never** an ES module. The WebView serves the dashboard from
`file:///android_asset/`, where `<script type="module">` is fetched with CORS semantics that
`file://` cannot satisfy, so a module bootstrap silently never runs on-device. esbuild bundles
the eager source files (via side-effect imports, in dependency order) into `app.js`. Inside
that bundle, modules call each other through typed ESM imports (C7); `window.VoltDashboard`
remains as the external ABI + cross-chunk registry, owned by `vd-registry.ts` and assembled
by `core.ts` — see the policy comment in `dashboard-src/js/vd-registry.ts`.
The bundle target is `chrome66` so Android 9-era WebViews do not receive syntax they cannot
parse, such as optional chaining or nullish coalescing.

Eager order (the `EAGER` array in `build.mjs`):

1. `prefs` (seeds the preference store; first module to evaluate)
2. `core` (assembles `window.VoltDashboard`; `vd-registry.ts` creates it)
3. `payload-validators`
4. `storage-status`
5. `drive`
6. `telemetry`
7. `actions` (its bootstrap calls into drive/telemetry, so it comes after them)
8. `connection-status`

Note esbuild resolves imports first, so a module's imports may evaluate ahead of its slot
(e.g. `drive` imports `telemetry`); new imports between eager modules must only point at
modules that already evaluate earlier, or the side-effect order changes.

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
