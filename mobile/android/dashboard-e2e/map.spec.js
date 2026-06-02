// Map tab — real Leaflet rendering + layer switching in headless Chromium.
const { test, expect } = require('@playwright/test');
const { openDashboard, setView } = require('./harness');

const ROUTE = {
  session: { id: 1, startedAtMs: 1_700_000_000_000, endedAtMs: 1_700_000_900_000 },
  points: [
    { lat: 32.70, lng: -117.16 },
    { lat: 32.74, lng: -117.12 },
    { lat: 32.78, lng: -117.08 },
  ],
  pointCount: 3,
  distanceMeters: 7000,
};

test('shows the empty state when there is no GPS route', async ({ page }) => {
  await openDashboard(page);
  await setView(page, 'map');
  await expect(page.locator('#mapEmpty')).toBeVisible();
});

test('renders a real Leaflet route map for a logged drive', async ({ page }) => {
  await openDashboard(page);
  await page.evaluate((r) => window.VoltDashboard.setStorage({ recentRoutes: [r] }), ROUTE);
  await setView(page, 'map');

  await expect(page.locator('#mapEmpty')).toBeHidden();
  await expect(page.locator('#mapPointBadge')).toContainText('pts');
  await expect(page.locator('#mapDistance')).not.toHaveText('--');
  await expect(page.locator('#mapStopsCount')).toBeVisible();
  // The map canvas is mounted and sized (the route summary above proves the route data flowed
  // through). We don't assert on Leaflet's internal tiles — they're network/size/timing dependent
  // and not the regression we care about here.
  await expect(page.locator('#mapLeaflet')).toBeVisible();
});

test('map layer tabs switch the active layer', async ({ page }) => {
  await openDashboard(page);
  await page.evaluate((r) => window.VoltDashboard.setStorage({ recentRoutes: [r] }), ROUTE);
  await setView(page, 'map');

  const routesTab = page.locator('.map-layer-tabs [data-map-layer="routes"]');
  await routesTab.click();
  await expect(routesTab).toHaveClass(/is-active/);
  await expect(page.locator('#mapFrame')).toHaveAttribute('data-layer', 'routes');
});
