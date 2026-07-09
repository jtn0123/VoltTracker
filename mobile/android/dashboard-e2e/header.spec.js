// Shared header — spacing, single status source, no clipping. Real CSS layout.
const { test, expect } = require('@playwright/test');
const { openDashboard, setView } = require('./harness');

test('title row clears the system status bar (top inset)', async ({ page }) => {
  await openDashboard(page);
  // The app scroll container carries the top-inset floor so the title doesn't crowd the status bar.
  const padTop = await page.evaluate(
    () => parseFloat(getComputedStyle(document.querySelector('.app')).paddingTop) || 0,
  );
  expect(padTop, 'app top inset should clear the status bar').toBeGreaterThanOrEqual(28);

  // Title + state pill are present and sit at the very top of the content.
  await expect(page.locator('#screenTitle')).toBeVisible();
  await expect(page.locator('#stateBadge')).toBeVisible();
});

test('the removed adapter-health pill is gone', async ({ page }) => {
  await openDashboard(page);
  await expect(page.locator('#adapterHealthPill')).toHaveCount(0);
});

test('the last-connected line is the slim single-line form, not the boxed card', async ({ page }) => {
  await openDashboard(page);
  // Surface a real adapter session so the line renders.
  await page.evaluate(() => {
    window.VoltTrackerAndroid.getRecentSessions = () =>
      JSON.stringify([{ adapter: 'OBDLink MX+ 54242', endMs: Date.now() - 2 * 86_400_000, outcome: 'success' }]);
    window.VoltDashboard.setStatus({ state: 'ready', detail: 'ready' });
  });

  const line = page.locator('#lastConnectedBadge');
  await expect(line).toHaveClass(/connection-status__line/);
  await expect(line).toContainText('OBDLink MX+ 54242');
  // Slim: a single short row, not the old multi-line boxed badge.
  const height = await line.evaluate((el) => el.getBoundingClientRect().height);
  expect(height, 'last-connected should be one slim line').toBeLessThan(40);
});

test('header action stays unclipped on the Charge tab too', async ({ page }) => {
  await openDashboard(page);
  await setView(page, 'charge');
  // Any header link/button in a card header must fit its box (no clipped label).
  const actions = page.locator('.list-card header .link-btn, .charge-card header .link-btn');
  for (const action of await actions.all()) {
    if (!(await action.isVisible())) continue;
    const m = await action.evaluate((el) => ({ s: el.scrollWidth, c: el.clientWidth }));
    expect(m.s, 'header action label must not overflow').toBeLessThanOrEqual(m.c + 1);
  }
});

// v2 design header glyphs: every view's title icon is a stroked outline except
// the Charge bolt, which is filled — mirroring the bottom-nav icon treatment.
test('Settings header renders a stroked outline title icon', async ({ page }) => {
  await openDashboard(page);
  await setView(page, 'settings');

  await expect(page.locator('#screenTitle')).toHaveText('Settings');
  const icon = page.locator('#screenTitleIcon');
  await expect(icon).toHaveAttribute('fill', 'none');
  await expect(icon).toHaveAttribute('stroke', 'currentColor');

  const box = await icon.evaluate((el) => {
    const b = el.getBBox();
    return { width: b.width, height: b.height };
  });
  expect(box.width).toBeGreaterThan(0);
  expect(box.height).toBeGreaterThan(0);
});

test('Charge header keeps the filled bolt icon', async ({ page }) => {
  await openDashboard(page);
  await setView(page, 'charge');
  await expect(page.locator('#screenTitleIcon')).toHaveAttribute('fill', 'currentColor');
});
