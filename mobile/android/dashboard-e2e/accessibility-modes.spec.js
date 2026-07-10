const { test, expect } = require('@playwright/test');
const { loadDemoScenario, openDashboard, setView } = require('./harness');

for (const viewport of [
  { width: 360, height: 640 },
  { width: 412, height: 915 },
]) {
  test(`Largest text plus high contrast fits core phone layouts at ${viewport.width}x${viewport.height}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.addInitScript(() => {
      localStorage.setItem('vt.pref.fontScale', '1.5');
      localStorage.setItem('vt.pref.highContrast', 'true');
    });
    await openDashboard(page);
    await loadDemoScenario(page, 'typical');

    for (const tab of ['drive', 'charge', 'diagnostics', 'settings']) {
      await setView(page, tab);
      const layout = await page.evaluate(() => ({
        overflow: document.documentElement.scrollWidth - window.innerWidth,
        nav: [...document.querySelectorAll('nav.bottom-nav button')].map((button) => {
          const r = button.getBoundingClientRect();
          return { width: r.width, height: r.height, right: r.right, left: r.left };
        }),
      }));
      expect(layout.overflow, `${tab} must not scroll sideways`).toBeLessThanOrEqual(1);
      expect(layout.nav.every((r) => r.width >= 44 && r.height >= 44 && r.left >= 0 && r.right <= viewport.width + 1)).toBe(true);
    }

    await expect(page.locator('html')).toHaveAttribute('data-contrast', 'high');
    expect(await page.locator('html').evaluate((node) => node.style.getPropertyValue('--font-scale'))).toBe('1.5');
  });
}

test('product-level direct controls keep a 44px minimum target', async ({ page }) => {
  await openDashboard(page);
  await loadDemoScenario(page, 'power-user');
  await setView(page, 'map');
  await page.evaluate(async () => window.VoltDashboard.ensureTroubleshooterModule());

  const tooSmall = await page.evaluate(() => {
    const touchMin = Number.parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--touch-min')) || 44;
    const resolved = (value) => {
      const parsed = Number.parseFloat(value || '');
      if (Number.isFinite(parsed)) return parsed;
      return String(value || '').includes('--touch-min') ? touchMin : 0;
    };
    const selectors = [
      '.map-layer-tabs button',
      '.map-list-fav-toggle',
      '.map-seg-pop button',
      '.troubleshooter-primary',
      '.troubleshooter-secondary',
    ];
    return selectors.flatMap((selector) =>
      [...document.querySelectorAll(selector)].flatMap((node) => {
        const style = getComputedStyle(node);
        const minW = resolved(style.minWidth);
        const minH = resolved(style.minHeight);
        const width = resolved(style.width);
        const height = resolved(style.height);
        return Math.max(minW, width) >= 44 && Math.max(minH, height) >= 44
          ? []
          : [`${selector}: ${Math.max(minW, width)}x${Math.max(minH, height)}`];
      }),
    );
  });
  expect(tooSmall).toEqual([]);
});
