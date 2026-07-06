// Charge + Insights tabs — empty states and populated KPIs.
const { test, expect } = require('@playwright/test');
const { openDashboard, setView } = require('./harness');

test('charge tab shows the empty state with no charge data', async ({ page }) => {
  await openDashboard(page);
  await page.evaluate(() => window.VoltDashboard.setStorage({}));
  await setView(page, 'charge');
  await expect(page.locator('#chargeEmptyState')).toBeVisible();
});

test('charge tab renders KPIs from charge summary', async ({ page }) => {
  await openDashboard(page);
  await page.evaluate(() =>
    window.VoltDashboard.setStorage({
      sessionCount: 5,
      chargeSummary: { chargeSessionCount: 3, chargingHintCount: 2, maxPowerKw: 48.2 },
    }),
  );
  await setView(page, 'charge');

  await expect(page.locator('#chargeEmptyState')).toBeHidden();
  await expect(page.locator('#realChargeSessions')).toHaveText('3');
  await expect(page.locator('#realChargeHints')).toHaveText('2');
  await expect(page.locator('#realChargePower')).toHaveText('48.2 kW');
  await expect(page.locator('#realChargeStatus')).toHaveText('recorded');
});

test('insights tab shows "--" placeholders with no insight data', async ({ page }) => {
  await openDashboard(page);
  await page.evaluate(() => {
    window.VoltTrackerAndroid.getInsights = () => '{}';
  });
  await setView(page, 'insights');
  await expect(page.locator('#insightTripCount')).toHaveText('--');
});

test('insights tab renders aggregate stats', async ({ page }) => {
  await openDashboard(page);
  await page.evaluate(() => {
    window.VoltTrackerAndroid.getInsights = () =>
      JSON.stringify({
        tripCount: 5,
        totalDistanceMeters: 50000,
        maxSpeedKph: 130,
        gpsTripCount: 4,
      });
  });
  await setView(page, 'insights');

  await expect(page.locator('#insightTripCount')).toHaveText('5 drives');
  await expect(page.locator('#insightTopSpeed')).toContainText('mph');
  await expect(page.locator('#insightGpsTrips')).toHaveText('4/5');
});
