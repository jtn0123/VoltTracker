// Regression: body.map-scrubber-active must NOT stick on non-map views. The bug
// (caught in review) was renderScrubber() adding the flag unconditionally while
// being reached off-map via renderMapIfLoaded() on every telemetry tick, hiding
// the global nav labels on Drive/Insights/etc. with no scrubber visible.
const { test, expect } = require('@playwright/test');
const { openDashboard, loadDemoScenario, setView } = require('./harness');

const FIXED = '2026-06-15T12:00:00.000Z';

test('map-scrubber-active never sticks on non-map views after an off-map render tick', async ({
  page,
}) => {
  await openDashboard(page, { fixedTime: FIXED });
  await loadDemoScenario(page, 'power-user'); // has a GPS route -> scrubber renders on map
  await setView(page, 'map');
  // Let the lazy map module load + the scrubber flag turn on (best-effort sanity).
  await page
    .waitForFunction(() => document.body.classList.contains('map-scrubber-active'), null, {
      timeout: 3000,
    })
    .catch(() => {});

  await setView(page, 'drive');
  // Reproduce the exact off-map path a telemetry tick takes.
  await page.evaluate(() => {
    const VD = window.VoltDashboard;
    if (typeof VD.renderMapIfLoaded === 'function') VD.renderMapIfLoaded();
  });

  const stuck = await page.evaluate(() =>
    document.body.classList.contains('map-scrubber-active'),
  );
  expect(stuck).toBe(false);

  // And the nav labels stay visible on Drive (the user-visible symptom).
  const labelHidden = await page.evaluate(() => {
    const span = document.querySelector('.bottom-nav button span');
    return span ? getComputedStyle(span).display === 'none' : null;
  });
  expect(labelHidden).toBe(false);
});
