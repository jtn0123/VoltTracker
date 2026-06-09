// Visual-regression baselines for the reworked dashboard surfaces.
//
// Component-level screenshots (not full pages) of deterministic, CSS/SVG/text surfaces — no canvas
// traces, no Leaflet tiles (those are font/network/timing dependent). The clock is frozen so
// relative timestamps ("2 days ago") don't drift the baseline.
//
// Baselines are platform-suffixed by Playwright and are generated/committed for LINUX (CI). On a
// non-Linux dev box run `npm run test:visual:update` to preview locally; those *-darwin.png files
// are gitignored. CI compares against the committed *-linux.png baselines.
const { test, expect } = require('@playwright/test');
const { openDashboard, setView } = require('./harness');

const FIXED = '2026-06-15T12:00:00.000Z';
const FIXED_MS = new Date(FIXED).getTime();
const DAY = 86_400_000;

test.beforeEach(async ({ page }) => {
  await openDashboard(page, { fixedTime: FIXED });
});

test('header — title, status pill, slim last-connected line', async ({ page }) => {
  await page.evaluate((endMs) => {
    window.VoltTrackerAndroid.getRecentSessions = () =>
      JSON.stringify([{ adapter: 'OBDLink MX+ 54242', endMs, outcome: 'success' }]);
    window.VoltDashboard.setStatus({ state: 'ready', detail: 'ready' });
  }, FIXED_MS - 2 * DAY);

  await expect(page.locator('.topbar')).toHaveScreenshot('header.png');
});

test('charge — KPI grid', async ({ page }) => {
  await page.evaluate(() =>
    window.VoltDashboard.setStorage({
      sessionCount: 5,
      chargeSummary: { chargeSessionCount: 12, chargingHintCount: 3, maxPowerKw: 48.2 },
    }),
  );
  await setView(page, 'charge');

  await expect(page.locator('.charge-grid').first()).toHaveScreenshot('charge-grid.png');
});

test('insights — aggregate stats', async ({ page }) => {
  await page.evaluate(() => {
    window.VoltTrackerAndroid.getInsights = () =>
      JSON.stringify({
        tripCount: 18,
        totalDistanceMeters: 482_000,
        totalDriveMs: 36_000_000,
        maxSpeedKph: 140,
        longestTripMeters: 54_000,
        gpsTripCount: 15,
      });
  });
  await setView(page, 'insights');

  await expect(page.locator('#insightStatsCard')).toHaveScreenshot('insights-stats.png');
});
