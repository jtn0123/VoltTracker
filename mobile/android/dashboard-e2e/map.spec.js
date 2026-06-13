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

test('remote map tiles are always on with no opt-out control', async ({ page }) => {
  await page.addInitScript(() => {
    window.localStorage.setItem('volttracker.map.remoteTiles', '0');
  });
  await openDashboard(page);
  await page.evaluate((r) => window.VoltDashboard.setStorage({ recentRoutes: [r] }), ROUTE);
  await setView(page, 'map');

  await expect(page.locator('#mapTilesBtn')).toHaveCount(0);
  // Top-right now holds the full-screen toggle plus the live-only Follow button
  // (hidden on this historical route); the point is there is no map-tiles opt-out.
  await expect(page.locator('.map-top-right button')).toHaveCount(2);
  await expect(page.locator('#mapLeaflet .leaflet-tile-pane')).toBeAttached();
  expect(await page.evaluate(() => window.VoltDashboard.state.mapRemoteTilesEnabled)).toBe(true);
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

test('live GPS route appears as current and can switch back from history', async ({ page }) => {
  await openDashboard(page, { fixedTime: '2026-06-06T19:52:00Z' });
  await page.evaluate((r) => {
    const VD = window.VoltDashboard;
    VD.setStorage({ recentRoutes: [r] });
    VD.updateTelemetry({
      source: 'demo',
      latitude: 32.7001,
      longitude: -117.1001,
      speedKph: 24,
      powerKw: 4,
      soc: 78,
      sampleCount: 1,
      updatedAt: Date.now(),
    });
    VD.updateTelemetry({
      source: 'demo',
      latitude: 32.7012,
      longitude: -117.1014,
      speedKph: 31,
      powerKw: 5,
      soc: 77.9,
      sampleCount: 2,
      updatedAt: Date.now() + 1000,
    });
  }, ROUTE);
  await setView(page, 'map');

  const currentChip = page.locator('#mapDriveChips [data-map-session="__live_current__"]');
  const historyChip = page.locator('#mapDriveChips [data-map-session="1"]');
  await expect(currentChip).toBeVisible();
  await expect(currentChip).toContainText(/Today \d/);
  await expect(currentChip).toContainText('live');
  await expect(currentChip).toHaveClass(/is-active/);
  await expect(page.locator('#mapTitle')).toContainText('Current demo');

  await historyChip.click();
  await expect(historyChip).toHaveClass(/is-active/);
  await expect(currentChip).not.toHaveClass(/is-active/);

  await currentChip.click();
  await expect(currentChip).toHaveClass(/is-active/);
  await expect(page.locator('#mapTitle')).toContainText('Current demo');
});
