// Playwright config for the dashboard end-to-end / visual suite.
//
// Unlike dashboard-tests/ (jsdom — fast, no rendering), this serves the REAL generated
// index.html + css + js over HTTP and drives it in headless Chromium, so it exercises actual CSS
// layout, the real Leaflet/WebView-equivalent rendering, and click interactions. We serve over
// HTTP (not file://) because the dashboard ships a `script-src 'self'` CSP that desktop Chromium
// blocks for file:// origins but honours for http://localhost — the same effective origin model
// the Android WebView gives file:///android_asset.
const { defineConfig, devices } = require('@playwright/test');
const path = require('node:path');

const DASHBOARD_DIR = path.resolve(__dirname, '../app/src/main/assets/dashboard');
const PORT = Number(process.env.DASHBOARD_E2E_PORT || 8099);
const BASE_URL = `http://127.0.0.1:${PORT}`;

module.exports = defineConfig({
  testDir: __dirname,
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: process.env.CI ? [['github'], ['list']] : 'list',
  use: {
    baseURL: BASE_URL,
    // A phone-ish portrait viewport at scale 1 so layout assertions and screenshots are stable.
    viewport: { width: 412, height: 915 },
    deviceScaleFactor: 1,
    colorScheme: 'dark',
    trace: 'retain-on-failure',
  },
  projects: [
    {
      // Functional / layout assertions — deterministic everywhere, run on every PR.
      name: 'chromium',
      testIgnore: /visual\.spec\.js/,
      use: { ...devices['Desktop Chrome'], viewport: { width: 412, height: 915 } },
    },
    {
      // Pixel screenshots — baselines are Linux/CI-only (font-sensitive). Run via the dedicated
      // dashboard-visual CI job; locally use `npm run test:visual:update` to preview.
      name: 'visual',
      testMatch: /visual\.spec\.js/,
      use: { ...devices['Desktop Chrome'], viewport: { width: 412, height: 915 } },
    },
  ],
  // Static-serve the dashboard assets. python3 is present on macOS and the CI ubuntu image.
  webServer: {
    command: `python3 -m http.server ${PORT} --bind 127.0.0.1 --directory "${DASHBOARD_DIR}"`,
    url: `${BASE_URL}/index.html`,
    reuseExistingServer: !process.env.CI,
    timeout: 30_000,
  },
  // Screenshots are font-sensitive across OSes; Playwright already suffixes baselines per platform.
  // A small tolerance absorbs sub-pixel AA differences without hiding real layout regressions.
  expect: {
    toHaveScreenshot: { maxDiffPixelRatio: 0.02, animations: 'disabled' },
  },
});
