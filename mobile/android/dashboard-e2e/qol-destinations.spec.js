const { test, expect } = require('@playwright/test');
const { loadDemoScenario, openDashboard, setView } = require('./harness');

test('All drives opens the searchable drive browser and focuses search', async ({ page }) => {
  await openDashboard(page);
  await loadDemoScenario(page, 'power-user');
  await setView(page, 'map');

  await expect(page.locator('#view-map .map-layout')).toBeHidden();
  await page.locator('#mapAllDrivesBtn').click();
  await expect(page.locator('#view-map .map-layout')).toBeVisible();
  await expect(page.locator('#mapSessionSearch')).toBeVisible();
  await expect(page.locator('#mapSessionFavoritesOnly')).toBeVisible();
  await expect(page.locator('#mapSessionSearch')).toBeFocused();

  await page.locator('#mapSessionListCloseBtn').click();
  await expect(page.locator('#view-map .map-layout')).toBeHidden();
  await expect(page.locator('#mapAllDrivesBtn')).toBeFocused();
});

test('Connect adapter performs the next safe setup step instead of stopping at the tab', async ({ page }) => {
  await openDashboard(page);
  await page.locator('#appEmptyState [data-action="smartConnect"]').click();
  await expect(page.locator('body')).toHaveAttribute('data-active-view', 'settings');
  await expect(page.locator('#deviceSelect')).toBeFocused();
  await expect(page.locator('#statusCopy')).toContainText(/paired|adapter/i);
});

test('cost prompts land on the exact electricity-rate field', async ({ page }) => {
  await openDashboard(page);
  await loadDemoScenario(page, 'typical');
  await setView(page, 'map');
  await page.locator('#mapAllDrivesBtn').click();
  await page.locator('#mapSessionList [data-trip-detail]').first().click();
  await page.locator('#tripDetailCostNote [data-nav-jump="settings"]').click();
  await expect(page.locator('body')).toHaveAttribute('data-active-view', 'settings');
  await expect(page.locator('#pricePerKwhInput')).toBeFocused();
});

test('saved-drive notification deep link opens an actionable receipt', async ({ page }) => {
  await openDashboard(page);
  await loadDemoScenario(page, 'typical');
  const routeKey = await page.evaluate(() => {
    const route = window.VoltDashboard.state.storage.recentRoutes[0];
    const key = String(route.session.id);
    window.VoltTrackerNative.openTripReceipt(key);
    return key;
  });

  await expect(page.locator('body')).toHaveAttribute('data-active-view', 'map');
  await expect(page.locator('#tripDetailSheet')).toBeVisible();
  await expect(page.locator('#tripReceipt')).toBeVisible();
  await expect(page.locator('#tripReceipt')).toContainText('Saved successfully');
  await expect(page.locator('#tripReceiptName')).toHaveAttribute('data-trip-rename', routeKey);
  await expect(page.locator('#tripReceiptFavorite')).toHaveAttribute('data-trip-favorite', routeKey);
});

test('Settings rail exposes and follows the active section', async ({ page }) => {
  await openDashboard(page);
  await setView(page, 'settings');
  await page.locator('[data-settings-target="settingsData"]').click();
  await expect(page.locator('[data-settings-target="settingsData"]')).toHaveAttribute('aria-current', 'location');
  await expect(page.locator('#settingsData')).toBeFocused();
});

test('Activity recreation callback restores the working tab while cold start stays on Drive', async ({ page }) => {
  await openDashboard(page);
  await expect(page.locator('body')).toHaveAttribute('data-active-view', 'drive');
  await page.evaluate(() => window.VoltTrackerNative.restoreView('charge'));
  await expect(page.locator('body')).toHaveAttribute('data-active-view', 'charge');
  await expect(page.locator('#view-charge')).toHaveClass(/is-active/);
});
