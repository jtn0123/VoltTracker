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

test('complete demo and sparse live telemetry keep the same Drive geometry', async ({ page }) => {
  await openDashboard(page);
  await setView(page, 'drive');

  const layoutSignature = () => page.evaluate(() => {
    const rect = (node, origin = { left: 0, top: 0 }) => {
      const box = node.getBoundingClientRect();
      return {
        left: Math.round(box.left - origin.left),
        top: Math.round(box.top - origin.top),
        width: Math.round(box.width),
        height: Math.round(box.height),
      };
    };
    const group = document.getElementById('liveReadout');
    const gridBox = group.getBoundingClientRect();
    return {
      contract: group.dataset.layoutContract,
      grid: { width: Math.round(gridBox.width), height: Math.round(gridBox.height) },
      graphs: rect(document.querySelector('.live-microcharts'), gridBox),
      slots: Array.from(group.querySelectorAll('[data-live-cell]')).map((cell) => ({
        key: cell.dataset.tileKey,
        display: getComputedStyle(cell).display,
        rect: rect(cell, gridBox),
      })),
    };
  });

  await page.evaluate(() => {
    window.VoltDashboard.setStatus({
      state: 'connected',
      detail: 'Live OBD data received',
      adapter: 'OBDLink MX+',
    });
    window.VoltDashboard.setAppState({
      adapter: { connected: true, name: 'OBDLink MX+' },
      session: { state: 'connected', sampleCount: 18, runtimeMs: 90_000 },
      gps: { state: 'locked', ageMs: 0 },
      vehicle: { state: 'driving' },
    });
    // Keep product availability identical too: this regression is about the
    // active Drive layout, not the intentional first-run guidance card.
    window.VoltDashboard.setStorage({ sampleCount: 100, sessionCount: 2 });
    window.VoltDashboard.updateTelemetry({
      source: 'demo',
      sampleCount: 18,
      speedKph: 64,
      rpm: 0,
      voltage: 13.8,
      coolantC: 81,
      throttlePct: 18,
      loadPct: 24,
      soc: 77,
      powerKw: 12.4,
      batteryTemp: 25,
      latitude: 42.33,
      longitude: -83.05,
      accuracyM: 4,
      updatedAt: Date.now(),
    });
  });
  await expect(page.locator('#coolantValue')).not.toHaveText('--');
  const demoLayout = await layoutSignature();

  await page.evaluate(() => {
    window.VoltDashboard.updateTelemetry({
      source: 'obd',
      sampleCount: 19,
      speedKph: 64,
      rpm: 0,
      voltage: 13.8,
      soc: 77,
      powerKw: 12.4,
      batteryTemp: 25,
      latitude: 42.33,
      longitude: -83.05,
      accuracyM: 4,
      updatedAt: Date.now(),
    });
  });
  await expect(page.locator('#coolantValue')).toHaveText('--');

  expect(await layoutSignature()).toEqual(demoLayout);
});
