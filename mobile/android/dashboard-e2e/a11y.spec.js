// Automated accessibility gate — runs in real Chromium so it can measure what
// the jsdom suite cannot: rendered touch-target sizes and colour contrast.
// Exercises the demo scenario matrix (VD.loadDemoScenario) across every tab so
// dense / empty / fault / extreme states are all checked, not just the happy path.
const { test, expect } = require('@playwright/test');
const path = require('node:path');
const { loadDemoScenario, openDashboard, setView } = require('./harness');

const AXE = path.join(__dirname, 'node_modules', 'axe-core', 'axe.min.js');
const TABS = ['drive', 'map', 'charge', 'insights', 'diagnostics', 'settings'];
// WCAG 2.2 "Target Size (Minimum)" 2.5.8 (AA) = 24x24 CSS px.
const MIN_TARGET = 24;

test.describe('a11y — touch targets >= 24px (WCAG 2.2 AA)', () => {
  for (const scenario of ['typical', 'empty', 'fault', 'extreme']) {
    test(`scenario: ${scenario}`, async ({ page }) => {
      await openDashboard(page);
      await loadDemoScenario(page, scenario);
      for (const tab of TABS) {
        await setView(page, tab);
        const tooSmall = await page.evaluate(
          ({ t, min }) => {
            const sec = document.querySelector(`[data-view="${t}"]`);
            if (!sec) return [];
            const bad = [];
            sec.querySelectorAll('button, a[href], [role="button"], select').forEach((el) => {
              // Skip third-party (Leaflet) controls and anything not rendered.
              if (el.closest('.leaflet-container, .leaflet-control-container')) return;
              const r = el.getBoundingClientRect();
              if (r.width === 0 || r.height === 0) return;
              if (r.width < min || r.height < min) {
                bad.push(`${(el.textContent || '').trim().slice(0, 24) || el.className} ${Math.round(r.width)}x${Math.round(r.height)}`);
              }
            });
            return bad;
          },
          { t: tab, min: MIN_TARGET },
        );
        expect(tooSmall, `${scenario} / ${tab} — controls under ${MIN_TARGET}px`).toEqual([]);
      }
    });
  }
});

test.describe('a11y — colour contrast (axe-core)', () => {
  // The app ships a strict `script-src 'self'` CSP, which blocks injecting the
  // axe runtime. Bypass it for this test context only — production CSP is untouched.
  test.use({ bypassCSP: true });
  for (const scenario of ['typical', 'fault']) {
    test(`scenario: ${scenario}`, async ({ page }) => {
      await openDashboard(page);
      await loadDemoScenario(page, scenario);
      await page.addScriptTag({ path: AXE });
      for (const tab of TABS) {
        await setView(page, tab);
        const violations = await page.evaluate(async () => {
          const view = document.querySelector('.view.is-active') || document.body;
          const res = await window.axe.run(view, {
            runOnly: { type: 'rule', values: ['color-contrast'] },
          });
          return res.violations.flatMap((v) =>
            v.nodes.map((n) => `${(n.target || []).join(' ')} :: ${(n.failureSummary || '').split('\n').pop()}`),
          );
        });
        expect(violations, `${scenario} / ${tab} — contrast failures`).toEqual([]);
      }
    });
  }
});

test.describe('a11y — destructive DTC dialog keyboard behavior', () => {
  test('clear-codes warning traps and restores focus', async ({ page }) => {
    await openDashboard(page);
    await setView(page, 'insights');

    await page.locator('#dtcClearOpenBtn').click();
    const dialog = page.locator('#dtcClearWarning');
    await expect(dialog).toBeVisible();
    await expect(dialog).toHaveAttribute('role', 'alertdialog');
    await expect(dialog).toHaveAttribute('aria-modal', 'true');
    // The shared focus-trap (createFocusTrap) marks the background non-interactive with the
    // standard `inert` attribute (+ aria-hidden), replacing the old custom data-dtc-dialog-inert
    // marker. Assert the real mechanism: background nodes become inert while the dialog is open.
    await expect(page.locator('[inert]')).not.toHaveCount(0);
    await expect(page.locator('#dtcClearWarning')).toBeFocused();

    await page.keyboard.press('Tab');
    await expect(page.locator('#dtcClearAckBox')).toBeFocused();

    await page.keyboard.press('Shift+Tab');
    await expect(page.locator('[data-action="cancelClearDtc"]')).toBeFocused();

    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
    await expect(page.locator('#dtcClearOpenBtn')).toBeFocused();
    await expect(page.locator('[inert]')).toHaveCount(0);
  });
});
