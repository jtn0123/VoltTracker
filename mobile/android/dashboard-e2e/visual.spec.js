// Visual-regression baselines for the reworked dashboard surfaces.
//
// Component-level screenshots (not full pages) of deterministic, CSS/SVG/text surfaces — no canvas
// traces, no Leaflet tiles (those are font/network/timing dependent). The clock is frozen so
// relative timestamps ("2 days ago") don't drift the baseline.
//
// Baselines are platform-suffixed by Playwright and are generated/committed for LINUX (CI). On a
// non-Linux dev box run `npm run test:visual:update` to preview locally; those *-darwin.png files
// are gitignored. CI compares against the committed *-linux.png baselines.
const { test, expect } = require('@playwright/test');
const { openDashboard, setView } = require('./harness');

const FIXED = '2026-06-15T12:00:00.000Z';
const FIXED_MS = new Date(FIXED).getTime();
const DAY = 86_400_000;

test.beforeEach(async ({ page }) => {
  await openDashboard(page, { fixedTime: FIXED });
});

test('header — title, status pill, slim last-connected line', async ({ page }) => {
  await page.evaluate((endMs) => {
    window.VoltTrackerAndroid.getRecentSessions = () =>
      JSON.stringify([{ adapter: 'OBDLink MX+ 54242', endMs, outcome: 'success' }]);
    window.VoltDashboard.setStatus({ state: 'ready', detail: 'ready' });
  }, FIXED_MS - 2 * DAY);

  // Semantic guard: a screenshot alone passes even if the baseline captured a broken state, so
  // assert the intended DOM is actually present before pinning pixels.
  await expect(page.locator('.topbar')).toBeVisible();
  await expect(page.locator('#lastConnectedLabel')).toHaveText('OBDLink MX+ 54242');
  await expect(page.locator('.topbar')).toHaveScreenshot('header.png');
});

test('charge — KPI grid', async ({ page }) => {
  await page.evaluate(() =>
    window.VoltDashboard.setStorage({
      sessionCount: 5,
      chargeSummary: { chargeSessionCount: 12, chargingHintCount: 3, maxPowerKw: 48.2 },
    }),
  );
  await setView(page, 'charge');

  // Semantic guard: the grid must be visible and actually populated with KPI cells.
  const chargeGrid = page.locator('.charge-grid').first();
  await expect(chargeGrid).toBeVisible();
  expect(await chargeGrid.locator('> *').count()).toBeGreaterThan(0);
  await expect(chargeGrid).toHaveScreenshot('charge-grid.png');
});

test('charge — live-charge ETA card is wired and renders while charging (D6)', async ({ page }) => {
  // Drive an active charge (plausible charger power + a known SOC below target) so the new M8
  // live-charge card renders, then assert its DOM is actually present and populated. A partials/
  // template wiring regression (card in source, missing from the generated index.html) would make
  // these fail loudly — the jsdom unit spec proves the function, not the assembled page. No
  // screenshot is added here, so no *-linux.png baseline is created or altered.
  await page.evaluate(() => {
    window.VoltDashboard.updateTelemetry({
      source: 'obd',
      connected: true,
      sampleCount: 1,
      updatedAt: Date.now(),
      chargerPowerKw: 3.5,
      soc: 50,
    });
    window.VoltDashboard.updateLiveUi();
  });
  await setView(page, 'charge');

  const liveCard = page.locator('#liveChargeCard');
  await expect(liveCard).toBeVisible();
  // 14 kWh usable * (100-50)/100 = 7.0 kWh remaining; 7.0 / 3.5 kW = 2h → the
  // v2 headline is "50% — full around <wall clock 2h from now>".
  await expect(page.locator('#liveChargeSoc')).toHaveText('50%');
  await expect(page.locator('#liveChargeRemaining')).toHaveText('7.0 kWh');
  await expect(page.locator('#liveChargeEta')).toHaveText(/^50% — full around \d{1,2}:\d{2}\s?(AM|PM)?$/);
});

test('insights — aggregate stats', async ({ page }) => {
  await page.evaluate(() => {
    window.VoltTrackerAndroid.getInsights = () =>
      JSON.stringify({
        tripCount: 18,
        totalDistanceMeters: 482_000,
        totalDriveMs: 36_000_000,
        maxSpeedKph: 140,
        longestTripMeters: 54_000,
        gpsTripCount: 15,
      });
  });
  await setView(page, 'insights');

  // Semantic guard (v2): the extra lifetime stats fold into the lifetime
  // sheet's second row — visible, populated, and screenshot-pinned.
  const statsRow = page.locator('.insights-sheet .insights-sheet-more');
  await expect(statsRow).toBeVisible();
  await expect(page.locator('#insightLongest')).toHaveText('34 mi');
  await expect(statsRow).toHaveScreenshot('insights-stats.png');
});

test('insights — savings row is wired and computes once prefs are set (D6)', async ({ page }) => {
  // The M6 savings row stays hidden until logged distance + all three comparison prefs exist; seed
  // both, then assert the row is visible with an honest computed figure. This catches a partials/
  // template wiring regression on the new Insights surface (row in source, not in generated
  // index.html) that every jsdom unit test would still pass. No screenshot, so no new baseline.
  await page.evaluate(() => {
    window.VoltTrackerAndroid.getInsights = () =>
      JSON.stringify({ tripCount: 12, totalDistanceMeters: 5000 * 1609.344 });
    window.VoltDashboard.prefs.set('mpg', 30);
    window.VoltDashboard.prefs.set('gasPricePerGal', 4);
    window.VoltDashboard.prefs.set('pricePerKwh', 0.14);
  });
  await setView(page, 'insights');
  await page.evaluate(() => window.VoltDashboard.renderInsightStats());

  const savingsRow = page.locator('#insightSavingsRow');
  await expect(savingsRow).toBeVisible();
  // gas (5000/30*4) - EV (5000/3.5*0.14) ≈ $466.67.
  await expect(page.locator('#insightSavings')).toHaveText('$466.67');
  await expect(page.locator('#insightSavingsNote')).toContainText('Estimated');
});

test('insights — maintenance log is wired and lists logged entries (D6)', async ({ page }) => {
  // The M1/M5 maintenance log renders real entries newest-first into #maintenanceList.
  // renderMaintenanceList reads state.maintenanceLog (populated from the bridge on load), so seed
  // state directly, render, and assert real rows appear — guarding the assembled Insights surface
  // against a wiring regression the jsdom unit spec can't see.
  await setView(page, 'insights');
  await page.evaluate(() => {
    window.VoltDashboard.state.maintenanceLog = [
      { id: 2, createdAtMs: 1_700_000_500_000, odometerKm: 1609.344, type: 'Oil change', note: 'Synthetic' },
      { id: 1, createdAtMs: 1_700_000_000_000, odometerKm: null, type: 'Tire rotation', note: '' },
    ];
    window.VoltDashboard.renderMaintenanceList();
  });

  const list = page.locator('#maintenanceList');
  await expect(list).toBeVisible();
  // Entry rows (not the empty-state article, which also carries .real-insight-item).
  const rows = list.locator('.real-insight-item:not(.maint-empty)');
  await expect(rows).toHaveCount(2);
  await expect(rows.first().locator('strong')).toHaveText('Oil change');
});
