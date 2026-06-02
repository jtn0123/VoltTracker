// Trips tab — real layout + interaction in headless Chromium.
//
// These assert things jsdom physically cannot: actual rendered box sizes (a chip that is too tall
// = the empty-map-box regression we fixed), real computed bar widths, and that header actions
// aren't clipped. They render the real index.html with real CSS.
const { test, expect } = require('@playwright/test');
const { openDashboard, setView } = require('./harness');

const ROUTE_POINTS = [
  { lat: 32.70, lng: -117.16 },
  { lat: 32.74, lng: -117.12 },
  { lat: 32.78, lng: -117.08 },
];

async function seedTrips(page, trips, recentRoutes = []) {
  await page.evaluate(
    ({ trips, recentRoutes }) => {
      const VD = window.VoltDashboard;
      VD.state.storage = { recentRoutes };
      VD.state.trips = trips;
      VD.renderRealTrips();
    },
    { trips, recentRoutes },
  );
}

test.beforeEach(async ({ page }) => {
  await openDashboard(page);
  await setView(page, 'trips');
});

test('logged-drive chips are compact, not tall empty boxes', async ({ page }) => {
  await seedTrips(
    page,
    [
      { id: 1, startedAtMs: Date.now(), durationMs: 600_000, distanceMeters: 8000, maxSpeedKph: 90, sampleCount: 500, pointCount: 3, hasRoute: true },
      { id: 2, startedAtMs: Date.now() - 1, durationMs: 0, distanceMeters: 0, sampleCount: 660, hasRoute: false },
    ],
    [{ session: { id: 1 }, points: ROUTE_POINTS }],
  );

  const chips = page.locator('#realTripsList .real-trip-chip');
  await expect(chips).toHaveCount(2);

  // The empty-in-chip-map regression made these ~188px tall. A compact text chip is far shorter;
  // assert real rendered height, which only a layout engine can give us.
  for (const chip of await chips.all()) {
    const box = await chip.boundingBox();
    expect(box.height, 'chip should be compact (no reserved map box)').toBeLessThan(96);
  }

  // Clear, labelled badges (not the old cryptic "Nx").
  await expect(chips.nth(0).locator('.trip-route-state')).toHaveText('3 pts');
  await expect(chips.nth(1).locator('.trip-route-state')).toHaveText('660 samples');
});

test('drive-summary bars are proportional and empty for a "--" metric', async ({ page }) => {
  await seedTrips(page, [
    { id: 1, startedAtMs: Date.now(), durationMs: 600_000, distanceMeters: 8000, sampleCount: 500, hasRoute: false },
    { id: 2, startedAtMs: Date.now() - 1, durationMs: 1_200_000, distanceMeters: 0, sampleCount: 100, hasRoute: false },
  ]);
  await page.evaluate(() => window.VoltDashboard.selectRealTrip(2));

  // Distance is "--" for trip 2 → the bar must be empty (0 width), not a misleading full bar.
  const distanceRow = page.locator('#realTripEnergyRows > div').first();
  await expect(distanceRow.locator('b')).toHaveText('--');
  const barWidth = await distanceRow.locator('i').evaluate((el) => el.getBoundingClientRect().width);
  expect(barWidth, 'a "--" metric must render no bar').toBeLessThanOrEqual(1);

  // Switch to the drive with real distance → its bar must actually have width.
  await page.evaluate(() => window.VoltDashboard.selectRealTrip(1));
  const filledWidth = await page
    .locator('#realTripEnergyRows > div')
    .first()
    .locator('i')
    .evaluate((el) => el.getBoundingClientRect().width);
  expect(filledWidth, 'a real distance must render a visible bar').toBeGreaterThan(10);
});

test('the "map" header action is fully visible, not clipped', async ({ page }) => {
  await seedTrips(page, [
    { id: 1, startedAtMs: Date.now(), durationMs: 600_000, distanceMeters: 8000, sampleCount: 500, hasRoute: false },
  ]);

  const mapLink = page.locator('#realTripsCard [data-nav-jump="map"]');
  await expect(mapLink).toBeVisible();
  // Not clipped: its content fits its box, and its box sits within the viewport width.
  const metrics = await mapLink.evaluate((el) => ({
    scrollWidth: el.scrollWidth,
    clientWidth: el.clientWidth,
    right: el.getBoundingClientRect().right,
  }));
  expect(metrics.scrollWidth, 'label must not overflow its box').toBeLessThanOrEqual(metrics.clientWidth + 1);
  expect(metrics.right, 'action must sit inside the viewport').toBeLessThanOrEqual(412);
});
