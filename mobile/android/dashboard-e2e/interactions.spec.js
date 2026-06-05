// Real click interactions: bottom-nav, demo start/stop, backup/restore -> bridge wiring.
//
// NOTE: the restore Replace/Merge dialog is a NATIVE Android AlertDialog (BackupController.kt),
// not web — Playwright can only verify that the web layer calls the bridge; the dialog itself is
// covered by the JVM/instrumented side.
const { test, expect } = require('@playwright/test');
const { openDashboard, setView } = require('./harness');

test('bottom-nav switches the active view', async ({ page }) => {
  await openDashboard(page);
  await page.locator('nav.bottom-nav [data-nav="trips"]').click();
  await expect(page.locator('body')).toHaveAttribute('data-active-view', 'trips');
  await expect(page.locator('#view-trips')).toHaveClass(/is-active/);

  await page.locator('nav.bottom-nav [data-nav="map"]').click();
  await expect(page.locator('body')).toHaveAttribute('data-active-view', 'map');
});

test('Start/Stop demo toggles demo state and calls the bridge', async ({ page }) => {
  await openDashboard(page);
  // The demo controls live in the Diag (settings) view — make it active so they're clickable.
  await setView(page, 'settings');
  await page.evaluate(() => {
    window.__demoCalls = 0;
    window.VoltTrackerAndroid.demo = () => {
      window.__demoCalls += 1;
    };
    // Open the disclosure groups so their buttons are clickable.
    document.querySelectorAll('details').forEach((d) => {
      d.open = true;
    });
  });

  await page.locator('[data-action="demo"]').first().click();
  await expect.poll(() => page.evaluate(() => window.VoltDashboard.state.demoActive)).toBe(true);
  expect(await page.evaluate(() => window.__demoCalls)).toBeGreaterThanOrEqual(1);

  await page.locator('[data-action="stopDemo"]').first().click();
  await expect.poll(() => page.evaluate(() => window.VoltDashboard.state.demoActive)).toBe(false);
});

test('"Restore file" calls the native restore bridge', async ({ page }) => {
  await openDashboard(page);
  await setView(page, 'settings');
  await page.evaluate(() => {
    window.__restoreCalls = 0;
    window.VoltTrackerAndroid.restoreBackup = () => {
      window.__restoreCalls += 1;
    };
    document.querySelectorAll('details').forEach((d) => {
      d.open = true;
    });
  });

  await page.locator('[data-action="restore"]').first().click();
  expect(await page.evaluate(() => window.__restoreCalls)).toBe(1);
});
