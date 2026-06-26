// E1: wide-viewport (>=900px) "rail mode". The rest of the e2e suite runs at
// 412x915, so this file is the only functional coverage for the left side-rail
// nav, the widened .app gutter, and the two-column comparison tabs.
const { test, expect } = require('@playwright/test');
const { openDashboard, loadDemoScenario, setView } = require('./harness');

const FIXED = '2026-06-15T12:00:00.000Z';

test.describe('wide layout (>=900px rail mode)', () => {
  test.use({ viewport: { width: 1024, height: 860 } });

  test('bottom nav becomes a left rail and content clears it without h-overflow', async ({
    page,
  }) => {
    await openDashboard(page, { fixedTime: FIXED });
    await loadDemoScenario(page, 'power-user');
    await setView(page, 'insights');

    const box = await page.locator('.bottom-nav').boundingBox();
    expect(box).not.toBeNull();
    expect(box.x).toBeLessThan(40);
    expect(box.width).toBeLessThan(120);
    expect(box.height).toBeGreaterThan(box.width);

    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflow).toBeLessThanOrEqual(1);

    // Content must clear the rail (regression guard: a 640px padding-inline rule
    // was overriding the rail-mode left gutter at >=900px).
    const viewLeft = await page.evaluate(
      () => document.getElementById('view-insights').getBoundingClientRect().left,
    );
    expect(viewLeft).toBeGreaterThanOrEqual(box.x + box.width);
  });

  test('insights renders two columns with the visible hero spanning full width', async ({
    page,
  }) => {
    await openDashboard(page, { fixedTime: FIXED });
    await loadDemoScenario(page, 'power-user');
    await setView(page, 'insights');

    const cols = await page.evaluate(
      () => getComputedStyle(document.getElementById('view-insights')).gridTemplateColumns,
    );
    expect(cols.trim().split(/\s+/).length).toBe(2);

    const heroSpansFull = await page.evaluate(() => {
      const hero = document.querySelector('#view-insights > .insights-hero');
      const view = document.getElementById('view-insights');
      if (!hero || !view) return false;
      return Math.abs(hero.getBoundingClientRect().width - view.getBoundingClientRect().width) < 8;
    });
    expect(heroSpansFull).toBe(true);
  });
});
