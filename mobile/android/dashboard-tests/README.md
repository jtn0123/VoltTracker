# Dashboard smoke tests

Vitest + jsdom smoke suite for the production dashboard bundle that ships
inside the Android WebView. The tests load the real files from
`../app/src/main/dashboard-src/js/` (no copy, no transpile) into a jsdom
window and then poke at the surface that the Android side touches:

- `window.VoltTrackerNative.*` — the WebView callback ABI the JVM side invokes
  via `evaluateJavascript`. If a method name drifts here, the Android side
  silently no-ops.
- `window.VoltDashboard.state` — the shared dashboard state shape. The tests
  fail loudly when seeded keys change so we catch accidental renames during
  partial refactors.
- The stale-tile indicator — verifies the `.stale` class lands on the
  live telemetry tiles after the documented threshold.

## Run

```bash
npm ci
npm test
npm run test:coverage
```

Requires Node 22.x. `npm ci` needs network access. CI runs lint and the
coverage-gated suite in `.github/workflows/android.yml`.

## Layout

- `setup/voltbridge.fixture.js` — fake `VoltTrackerAndroid` bridge that
  mirrors every `@JavascriptInterface` method on `VoltBridge.kt` with
  sensible defaults. Update this when the bridge gains or renames a method.
- `setup/load-dashboard.js` — installs the bridge, loads the generated
  dashboard body, then imports the production dashboard files in the same
  order as `index.template.html` so coverage is attributed to the real files.
- `*.test.js` — one file per smoke check.

The suite is intentionally focused: it is not a behavioral test for every
panel, but it is a tripwire for bridge ABI drift, core state-shape changes,
storage-error handling, accessibility basics, and DTC dictionary integrity.
