// Error paths — the dashboard must degrade loudly-but-gracefully, never blank-screen.
//
// (a) Map tile failures: map.ts createRemoteTileLayer counts tile errors on the primary CARTO
//     basemap — at MAP_TILE_WARNING_THRESHOLD (3) it shows the #mapTileError banner, and at
//     MAP_TILE_FALLBACK_THRESHOLD (6) it swaps the layer to the plain OSM basemap. We force both
//     by aborting the tile CDN requests with route interception; the route polyline itself is
//     drawn from local data and must keep rendering.
// (b) Garbage bridge payloads: window.VoltTrackerNative.* is the WebView ABI — Android pushes
//     JSON strings into it. Malformed JSON must fall back cleanly (core.ts parsePayload returns
//     the fallback value), and a native error envelope ({ok:false, error, message}) must surface
//     as a "blocked" status (storage-status.ts reportNativeReadError) — either way the dashboard
//     keeps rendering and navigation keeps working.
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

test('tile failures raise the banner, fall back to OSM, and keep the route rendered', async ({ page }) => {
  let cartoAborts = 0;
  let osmRequests = 0;
  // Abort every basemap tile: the primary CARTO layer fails (banner at 3 errors, OSM fallback at
  // 6), and the fallback OSM layer fails too (its own "backup tiles" copy at 3 errors).
  await page.route('https://*.basemaps.cartocdn.com/**', (route) => {
    cartoAborts += 1;
    return route.abort();
  });
  await page.route('https://*.tile.openstreetmap.org/**', (route) => {
    osmRequests += 1;
    return route.abort();
  });

  await openDashboard(page);
  await page.evaluate((r) => window.VoltDashboard.setStorage({ recentRoutes: [r] }), ROUTE);
  await setView(page, 'map');

  // >= 3 primary tile errors: the polite-status banner appears with its retry affordance.
  await expect(page.locator('#mapTileError')).toBeVisible({ timeout: 20_000 });
  await expect(page.locator('#mapTileErrorCopy')).toContainText(/tiles/i);
  await expect(page.locator('#mapTileRetryBtn')).toBeVisible();

  // >= 6 primary tile errors: the layer is swapped to the OSM basemap — proven by real requests
  // hitting the OSM CDN (the swap is invisible in the DOM tree otherwise).
  await expect.poll(() => osmRequests, { timeout: 20_000 }).toBeGreaterThan(0);
  expect(cartoAborts).toBeGreaterThanOrEqual(6);

  // The blocked fallback then surfaces its own copy — the banner never silently clears.
  await expect(page.locator('#mapTileErrorCopy')).toContainText(/backup map tiles/i, {
    timeout: 20_000,
  });
  await expect(page.locator('#mapTileError')).toBeVisible();

  // Tiles are cosmetic: the route summary + polyline still render from local data.
  await expect(page.locator('#mapEmpty')).toBeHidden();
  await expect(page.locator('#mapPointBadge')).toContainText('pts');
  await expect(page.locator('#mapDistance')).not.toHaveText('--');
  await expect(page.locator('#mapLeaflet .leaflet-overlay-pane path').first()).toBeAttached();
});

test('malformed bridge JSON falls back cleanly and the dashboard stays functional', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err && err.message ? err.message : String(err)));
  await openDashboard(page);
  await setView(page, 'diagnostics');

  // Push garbage through every native setter exactly as a buggy Android side would.
  await page.evaluate(() => {
    const native = window.VoltTrackerNative;
    native.setStorage('{"recentRoutes": [{{ definitely not json');
    native.setAppState('%%%');
    native.setStatus('}{');
    native.updateTelemetry('<garbage payload>');
    native.setDevices('{"not": "an array"');
    native.setHistory('null]');
  });

  // parsePayload swallowed the garbage into fallbacks: status is the idle fallback (not blank,
  // not crashed) and the screen chrome is still painted.
  await expect(page.locator('#stateText')).toHaveText('idle');
  await expect(page.locator('#statusCopy')).toHaveText('Ready.');
  await expect(page.locator('#screenTitle')).not.toBeEmpty();

  // Navigation still works after the garbage.
  await page.locator('nav.bottom-nav [data-nav="drive"]').click();
  await expect(page.locator('body')).toHaveAttribute('data-active-view', 'drive');
  await page.locator('nav.bottom-nav [data-nav="diagnostics"]').click();
  await expect(page.locator('body')).toHaveAttribute('data-active-view', 'diagnostics');

  // The pipeline is still alive: a subsequent well-formed payload renders normally.
  await page.evaluate(() => {
    window.VoltTrackerNative.setStorage(
      JSON.stringify({
        sessionCount: 2,
        sampleCount: 240,
        recentSessions: [
          { id: 1, mode: 'drive', adapterName: 'OBDLink MX+', startedAtMs: Date.now() - 3600_000, status: 'complete', sampleCount: 120, usefulSampleCount: 120, emptySampleCount: 0 },
          { id: 2, mode: 'drive', adapterName: 'OBDLink MX+', startedAtMs: Date.now() - 7200_000, status: 'complete', sampleCount: 120, usefulSampleCount: 120, emptySampleCount: 0 },
        ],
      }),
    );
  });
  await expect(page.locator('#dbSessionCount')).toHaveText('2');
  await expect(page.locator('#dbSessionList .history-row')).toHaveCount(2);

  expect(pageErrors, 'garbage payloads must not throw uncaught errors').toEqual([]);
});

test('a native error envelope surfaces as a blocked status instead of silence', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err && err.message ? err.message : String(err)));
  await openDashboard(page);
  await setView(page, 'diagnostics');

  // The Android side reports read failures as {ok:false, error, message} through the same setter.
  await page.evaluate(() => {
    window.VoltTrackerNative.setStorage(
      JSON.stringify({ ok: false, error: 'db_read_failed', message: 'Could not read local storage summary.' }),
    );
  });

  // The failure is surfaced, not swallowed: blocked state + the native message in the status copy.
  await expect(page.locator('#stateText')).toHaveText('blocked');
  await expect(page.locator('#statusCopy')).toContainText(/could not read local storage/i);

  // And the dashboard is still navigable — an error status must not wedge the UI.
  await page.locator('nav.bottom-nav [data-nav="drive"]').click();
  await expect(page.locator('body')).toHaveAttribute('data-active-view', 'drive');
  expect(pageErrors).toEqual([]);
});
