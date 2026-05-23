# Dashboard JS smoke tests

Vitest + jsdom smoke suite for the production dashboard JS bundle that ships
inside the Android WebView. The tests load the real files from
`../app/src/main/assets/dashboard/js/` (no copy, no transpile) into a jsdom
window and then poke at the surface that the Android side touches:

- `window.VoltTrackerNative.*` — the WebView callback ABI the JVM side invokes
  via `evaluateJavascript`. If a method name drifts here, the Java side
  silently no-ops.
- `window.VoltDashboard.state` — the shared dashboard state shape. The tests
  fail loudly when seeded keys change so we catch accidental renames during
  partial refactors.
- The C6 stale-tile indicator — verifies the `.stale` class lands on the
  live telemetry tiles after the documented threshold.

## Run

```bash
npm install
npm test
```

Requires Node 20+. `npm install` needs network access. CI runs the same
two commands in `.github/workflows/android.yml`.

## Layout

- `setup/voltbridge.fixture.js` — fake `VoltTrackerAndroid` bridge that
  mirrors every `@JavascriptInterface` method on `VoltBridge.java` with
  sensible defaults. Update this when the bridge gains or renames a method.
- `setup/load-dashboard.js` — installs the bridge, builds the minimal DOM
  the bootstrap path touches, then evals the 5 JS files in the same order
  as `index.template.html` (`core`, `panels`, `map`, `telemetry`,
  `actions`).
- `*.test.js` — one file per smoke check.

The suite is intentionally narrow: it is not a behavioral test for every
panel, it is a tripwire that fires when the JS ABI or core state shape
drifts.
