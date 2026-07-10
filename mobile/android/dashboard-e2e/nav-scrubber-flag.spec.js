// Regression: body.map-scrubber-active must NOT stick on non-map views. The bug
// was renderScrubber() adding the flag unconditionally while being reached
// off-map via renderMapIfLoaded() on telemetry ticks, hiding the global nav
// labels on Drive/etc. with no scrubber visible.
const { test, expect } = require('@playwright/test');
const { openDashboard, loadDemoScenario, setView } = require('./harness');

const FIXED = '2026-06-15T12:00:00.000Z';

test('map-scrubber-active never sticks on non-map views after an off-map render tick', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 }); // narrow: exercise the compact-nav media path
  await openDashboard(page, { fixedTime: FIXED });
  await loadDemoScenario(page, 'power-user');
  await setView(page, 'map');
  await page
    .waitForFunction(() => document.body.classList.contains('map-scrubber-active'), null, {
      timeout: 3000,
    })
    .catch(() => {});

  await setView(page, 'drive');
  await page.evaluate(() => {
    const VD = window.VoltDashboard;
    if (typeof VD.renderMapIfLoaded === 'function') VD.renderMapIfLoaded();
  });

  const stuck = await page.evaluate(() =>
    document.body.classList.contains('map-scrubber-active'),
  );
  expect(stuck).toBe(false);

  const labelHidden = await page.evaluate(() => {
    const span = document.querySelector('.bottom-nav button span');
    return span ? getComputedStyle(span).display === 'none' : null;
  });
  expect(labelHidden).toBe(false);
});

for (const viewport of [
  { width: 360, height: 640 },
  { width: 412, height: 915 },
]) {
  test(`route playback yields the bottom thumb zone at ${viewport.width}x${viewport.height}`, async ({
    page,
  }) => {
    await page.setViewportSize(viewport);
    await openDashboard(page, { fixedTime: FIXED });
    await loadDemoScenario(page, 'power-user');
    await setView(page, 'map');

    const scrubber = page.locator('#scrubber');
    const nav = page.locator('.bottom-nav');
    await expect(scrubber).toBeVisible();

    const measured = await nav.evaluate((node) => ({
      actual: Math.ceil(node.getBoundingClientRect().height),
      reserved: Math.round(
        Number.parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--nav-h')),
      ),
    }));
    expect(measured.reserved).toBe(measured.actual);

    await page.locator('#scrubPlay').click();
    await expect(nav).toHaveCSS('pointer-events', 'none');
    await expect(nav).toHaveCSS('opacity', '0');

    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    await page.evaluate(() => window.scrollBy(0, -80));
    await expect(nav).toHaveCSS('pointer-events', 'auto');
    await expect(nav).toHaveCSS('opacity', '1');

    await page.locator('#scrubPlay').evaluate((button) => button.click());
    await expect(nav).toHaveCSS('pointer-events', 'auto');
    await expect(nav).toHaveCSS('opacity', '1');
  });
}
