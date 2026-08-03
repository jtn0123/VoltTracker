// Opt-in capture tool (NOT a test): full-page screenshots of every dashboard view with
// the built-in demo mode running, for the Compose-rewrite before/after review loop.
// Run with SHOT_DIR=/path/to/output npx playwright test before-screens.spec.js
// (optionally PW_EXECUTABLE_PATH=/path/to/chromium to reuse a preinstalled browser).
const { test } = require('@playwright/test');
const { openDashboard, loadDemoScenario, setView } = require('./harness');

if (process.env.PW_EXECUTABLE_PATH) {
  test.use({ launchOptions: { executablePath: process.env.PW_EXECUTABLE_PATH } });
}
test.skip(!process.env.SHOT_DIR, 'screenshot capture is opt-in — set SHOT_DIR');

const OUT = process.env.SHOT_DIR;
const VIEWS = ['map', 'charge', 'insights', 'diagnostics', 'settings'];

test('capture before screenshots of all views', async ({ page }) => {
  test.setTimeout(240_000);
  await openDashboard(page);
  await loadDemoScenario(page, 'typical');
  await page.waitForTimeout(1500);

  // Start the REAL browser demo stream: remove the mock bridge's native demo()
  // so startDemo() falls back to runBrowserDemoStream (1 Hz synthetic drive).
  await page.evaluate(() => {
    delete window.VoltTrackerAndroid.demo;
    window.VoltDashboard.startDemo();
  });
  await setView(page, 'drive');
  await page.waitForTimeout(40_000);
  await page.screenshot({ path: `${OUT}/before-drive.png`, fullPage: true });

  for (const view of VIEWS) {
    await setView(page, view);
    await page.waitForTimeout(1200);
    await page.screenshot({ path: `${OUT}/before-${view}.png`, fullPage: true });
  }
});
