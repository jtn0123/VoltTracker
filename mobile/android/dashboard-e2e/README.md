# dashboard-e2e — Playwright end-to-end / layout tests

Renders the **real** generated dashboard (`app/src/main/assets/dashboard/index.html` + css + js) in
headless Chromium and asserts actual rendered layout and interaction.

## Why this exists (vs `dashboard-tests/`)

`dashboard-tests/` runs the dashboard modules in **jsdom** — fast, but jsdom has no layout engine, so it
**cannot see** sizing, clipping, overlap, or real computed styles. Most of the UI bugs we hit in the
field (a chip rendered as a tall empty box, a clipped header action, a "full" bar next to a `--`
value) are invisible to jsdom. This suite catches that whole class by using a real browser.

| | `dashboard-tests/` (jsdom) | `dashboard-e2e/` (Playwright) |
|---|---|---|
| DOM | fake, no layout | real Chromium |
| Speed | ms | ~1–2s/test |
| Catches | logic, wiring, structure | **layout, clipping, sizes, real CSS, clicks** |

## Run

```bash
cd mobile/android/dashboard-e2e
npm ci
npx playwright install chromium      # one-time browser download
npm test                             # run all specs
npm run test:headed                  # watch it in a browser
npm run report                       # open the HTML report after a run
```

The dashboard is static-served over HTTP by `python3 -m http.server` (configured in
`playwright.config.js`) — not `file://` — because the dashboard ships a `script-src 'self'` CSP that
desktop Chromium blocks for `file://` origins but honours for `http://localhost`, matching the
same-origin model the Android WebView gives `file:///android_asset`.

## How a test works

`harness.openDashboard(page)` injects a stub `window.VoltTrackerAndroid` bridge (as the WebView
would), loads `index.html`, and waits for `window.VoltDashboard` to wire up. Tests then seed
`VoltDashboard.state` and call the real render entry points (`renderRealTrips`, `setAppState`,
`setView`, …) to drive a screen, then assert with real layout (`getBoundingClientRect`, computed
styles) and text/interaction.

## Notes

- Tests here are **functional/layout** assertions, which are deterministic across OSes. Pixel
  screenshots (`toHaveScreenshot`) are supported by the config but intentionally not used yet —
  they're font-sensitive and need per-platform baselines generated in CI. Add them once we want
  pixel-level regression coverage.
- CI: the `dashboard-e2e` job in `.github/workflows/android.yml` runs this and is part of the
  required `ci-success` gate.
