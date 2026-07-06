// Fully offline — the dashboard is a local-first app and must keep working when the device has
// no network at all. Everything it renders (routes, sessions, charges, insights) comes from the
// local bridge/demo data; only the map basemap tiles are remote, so going offline must degrade to
// the tile-failure banner on the map tab — never a crash or a blank screen.
//
// We simulate "no network" by aborting every request that is not the localhost static server
// (context.route), which mirrors airplane mode in the WebView: the asset origin still resolves
// (file:///android_asset there, http://127.0.0.1 here) while every external host is unreachable.
const { test, expect } = require('@playwright/test');
const { loadDemoScenario, openDashboard, setView } = require('./harness');

const LOCAL_HOSTS = new Set(['127.0.0.1', 'localhost', '::1', '[::1]']);

async function blockExternalNetwork(context) {
  await context.route('**/*', (route) => {
    let host = '';
    try {
      host = new URL(route.request().url()).hostname;
    } catch (_err) {
      host = '';
    }
    if (LOCAL_HOSTS.has(host)) return route.continue();
    return route.abort();
  });
}

test('tabs still switch and demo data still renders with no network', async ({ page, context }) => {
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err && err.message ? err.message : String(err)));
  await blockExternalNetwork(context);

  await openDashboard(page);
  await loadDemoScenario(page, 'typical');

  // Bottom-nav still drives the views — real clicks, not just state pokes.
  await page.locator('nav.bottom-nav [data-nav="charge"]').click();
  await expect(page.locator('body')).toHaveAttribute('data-active-view', 'charge');
  // Charge history is local data: the KPIs fill in instead of the empty state.
  await expect(page.locator('#chargeEmptyState')).toBeHidden();
  await expect(page.locator('#realChargeSessions')).toHaveText(/^\d+$/);

  await page.locator('nav.bottom-nav [data-nav="insights"]').click();
  await expect(page.locator('body')).toHaveAttribute('data-active-view', 'insights');
  await expect(page.locator('#insightTripCount')).not.toHaveText('--');
  await expect(page.locator('#insightTripCount')).toHaveText(/^\d+ drives?$/);

  await page.locator('nav.bottom-nav [data-nav="diagnostics"]').click();
  await expect(page.locator('body')).toHaveAttribute('data-active-view', 'diagnostics');
  // The stored-session list renders from local rows.
  expect(await page.locator('#dbSessionList .history-row').count()).toBeGreaterThan(0);

  await page.locator('nav.bottom-nav [data-nav="drive"]').click();
  await expect(page.locator('body')).toHaveAttribute('data-active-view', 'drive');

  expect(pageErrors, 'offline mode must not throw uncaught errors').toEqual([]);
});

test('map tab draws the local route and shows tile-failure messaging instead of crashing', async ({ page, context }) => {
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err && err.message ? err.message : String(err)));
  await blockExternalNetwork(context);

  await openDashboard(page);
  await loadDemoScenario(page, 'typical');
  await setView(page, 'map');

  // Routes are local data: the summary fills and the polyline is drawn even with zero network.
  await expect(page.locator('#mapEmpty')).toBeHidden();
  await expect(page.locator('#mapPointBadge')).toContainText('pts');
  await expect(page.locator('#mapDistance')).not.toHaveText('--');
  await expect(page.locator('#mapLeaflet .leaflet-overlay-pane path').first()).toBeAttached();

  // Only the basemap tiles are remote, so the dashboard surfaces the tile banner (>= 3 tile
  // errors; both the CARTO primary and the OSM fallback are unreachable offline).
  await expect(page.locator('#mapTileError')).toBeVisible({ timeout: 20_000 });
  await expect(page.locator('#mapTileErrorCopy')).toContainText(/tiles/i);
  await expect(page.locator('#mapTileRetryBtn')).toBeVisible();

  // Still alive afterwards: navigation away and back works.
  await page.locator('nav.bottom-nav [data-nav="drive"]').click();
  await expect(page.locator('body')).toHaveAttribute('data-active-view', 'drive');
  await page.locator('nav.bottom-nav [data-nav="map"]').click();
  await expect(page.locator('body')).toHaveAttribute('data-active-view', 'map');

  expect(pageErrors, 'offline map must not throw uncaught errors').toEqual([]);
});
