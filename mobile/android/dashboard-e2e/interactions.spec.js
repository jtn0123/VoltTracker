// Real click interactions: bottom-nav, demo start/stop, backup/restore -> bridge wiring.
//
// NOTE: the restore Replace/Merge dialog is a NATIVE Android AlertDialog (BackupController.kt),
// not web — Playwright can only verify that the web layer calls the bridge; the dialog itself is
// covered by the JVM/instrumented side.
const { test, expect } = require('@playwright/test');
const { openDashboard, setView } = require('./harness');

test('bottom-nav switches the active view', async ({ page }) => {
  await openDashboard(page);
  await page.locator('nav.bottom-nav [data-nav="diagnostics"]').click();
  await expect(page.locator('body')).toHaveAttribute('data-active-view', 'diagnostics');
  await expect(page.locator('#view-diagnostics')).toHaveClass(/is-active/);

  await page.locator('nav.bottom-nav [data-nav="map"]').click();
  await expect(page.locator('body')).toHaveAttribute('data-active-view', 'map');
});

test('bottom-nav floats above content instead of becoming a full footer band', async ({ page }) => {
  await openDashboard(page);

  const navMetrics = await page.locator('nav.bottom-nav').evaluate((nav) => {
    const rect = nav.getBoundingClientRect();
    const style = getComputedStyle(nav);
    return {
      position: style.position,
      height: rect.height,
      width: rect.width,
      viewportWidth: window.innerWidth,
      viewportHeight: window.innerHeight,
      bottomGap: window.innerHeight - rect.bottom,
      radius: Number.parseFloat(style.borderRadius),
    };
  });

  expect(navMetrics.position).toBe('fixed');
  expect(navMetrics.height).toBeLessThan(90);
  expect(navMetrics.width).toBeLessThanOrEqual(navMetrics.viewportWidth - 20);
  expect(navMetrics.bottomGap).toBeGreaterThanOrEqual(8);
  expect(navMetrics.radius).toBeGreaterThanOrEqual(20);
});

test('settings exposes connection actions without expanding a disclosure', async ({ page }) => {
  await openDashboard(page);
  await setView(page, 'settings');

  await expect(page.locator('#connectBtn')).toBeVisible();
  await expect(page.locator('#connectBtn')).toHaveText(/connect/i);
  await expect(page.locator('#permissionBtn')).toBeVisible();
  await expect(page.locator('#scanBtn')).toBeVisible();
  await expect(page.locator('#lastBtn')).toBeVisible();
  await expect(page.locator('#view-preferences [data-action="restore"]')).toBeVisible();
  await expect(page.locator('#view-preferences [data-action="restoreEncrypted"]')).toBeVisible();
  await expect(page.locator('#disconnectBtn')).toHaveCount(0);
});

test('Drive Focus and Settings section shortcuts remain independently clickable', async ({ page }) => {
  await openDashboard(page);
  await setView(page, 'settings');

  await page.locator('button[data-drive-preset="focus"]').click();
  await expect(page.locator('html')).toHaveAttribute('data-drive-preset', 'focus');
  await page.locator('button[data-drive-preset="detailed"]').click();
  await expect(page.locator('html')).toHaveAttribute('data-drive-preset', 'detailed');

  await page.locator('button[data-settings-target="settingsData"]').click();
  await expect(page.locator('#settingsData')).toBeFocused();
});

test('Start/Stop demo toggles demo state and calls the bridge', async ({ page }) => {
  await openDashboard(page);
  // The demo sandbox lives in Diagnostics — make it active so the controls are clickable.
  await setView(page, 'diagnostics');
  await page.locator('[data-diagnostics-mode="advanced"]').click();
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

  // Target the sandbox's morphing toggle specifically — other tabs now carry
  // their own Demo / Testing entry points, so a bare [data-action="demo"]
  // first() can resolve to a button on a hidden view.
  await page.locator('[data-demo-toggle]').click();
  await expect.poll(() => page.evaluate(() => window.VoltDashboard.state.demoActive)).toBe(true);
  expect(await page.evaluate(() => window.__demoCalls)).toBeGreaterThanOrEqual(1);

  await page.locator('[data-demo-toggle]').click();
  await expect.poll(() => page.evaluate(() => window.VoltDashboard.state.demoActive)).toBe(false);
});

test('browser preview Start/Stop demo owns the sample data boundary', async ({ page }) => {
  await openDashboard(page, { withBridge: false });
  await setView(page, 'settings');

  await expect(page.locator('#connectBtn')).toHaveText(/Start demo/i);
  await page.locator('#connectBtn').click();

  await expect.poll(() => page.evaluate(() => window.VoltDashboard.state.demoActive)).toBe(true);
  await expect(page.locator('#demoBanner')).toBeVisible();
  await expect(page.locator('#rawFrames')).toContainText('>010D');
  await expect(page.locator('#rawFrames')).toContainText('41 0D');
  expect(await page.evaluate(() => window.VoltDashboard.state.storage.recentRoutes.length)).toBeGreaterThan(0);
  expect(await page.evaluate(() => window.VoltDashboard.state.storage.latestDiagnosticCodes.length)).toBeGreaterThan(0);

  await page.locator('#connectBtn').click();

  await expect.poll(() => page.evaluate(() => window.VoltDashboard.state.demoActive)).toBe(false);
  await expect(page.locator('#demoBanner')).toBeHidden();
  expect(await page.evaluate(() => window.VoltDashboard.state.storage.recentRoutes.length)).toBe(0);
  expect(await page.evaluate(() => window.VoltDashboard.state.storage.latestDiagnosticCodes.length)).toBe(0);
});

test('"Restore file" calls the native restore bridge', async ({ page }) => {
  await openDashboard(page);
  await setView(page, 'settings');
  await page.evaluate(() => {
    window.__restoreCalls = 0;
    window.VoltTrackerAndroid.restoreBackup = () => {
      window.__restoreCalls += 1;
    };
  });

  await page.locator('#view-preferences [data-action="restore"]').click();
  await expect.poll(() => page.evaluate(() => window.__restoreCalls)).toBe(1);
});
