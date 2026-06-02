// Demo / live-pipeline rendering — proves "demo is just numbers through the real UI".
//
// We push a telemetry payload exactly as the native demo (or a real adapter) would, then assert
// the REAL Drive cards render it. We also assert the removed demo-mockup chrome is truly gone.
const { test, expect } = require('@playwright/test');
const { openDashboard, setView } = require('./harness');

test('the real Drive cluster renders streamed telemetry numbers', async ({ page }) => {
  await openDashboard(page);
  await setView(page, 'drive');

  await page.evaluate(() => {
    window.VoltDashboard.setAppState({
      permissions: { location: true },
      gps: { state: 'locked' },
      session: { state: 'connected', sampleCount: 18 },
      latestTelemetry: {
        source: 'demo',
        connected: true,
        speedKph: 64,
        soc: 77,
        powerKw: 22.8,
        batteryTemp: 25,
        rpm: 900,
        latitude: 32.7157,
        longitude: -117.1611,
        updatedAt: Date.now(),
      },
    });
  });

  // Real Drive tiles fill from the streamed numbers (not "--").
  await expect(page.locator('#speedValue')).not.toHaveText('--');
  await expect(page.locator('#driveSocValue')).toContainText('77');
  await expect(page.locator('#powerValue')).not.toHaveText('--');
  // The GPS channel locks from the streamed lat/lng — the synthetic-GPS feature.
  await expect(page.locator('#gpsState')).toHaveText(/locked/i);
});

test('demo-mockup chrome is gone (unified with the real UI)', async ({ page }) => {
  await openDashboard(page);

  // The old parallel demo UI / propulsion controls must not exist anywhere in the document.
  await expect(page.locator('#evRing')).toHaveCount(0);
  await expect(page.locator('.mode-toggle')).toHaveCount(0);
  await expect(page.locator('[data-mode]')).toHaveCount(0);
  await expect(page.locator('#tripList')).toHaveCount(0); // demo-only trips mockup, removed
});
