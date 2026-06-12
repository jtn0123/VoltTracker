// Functional (non-pixel) coverage for the light theme.
//
// The dark scheme is pinned by the visual baselines; light mode is asserted
// FUNCTIONALLY here instead: the page chrome, a drive-tab card and the bottom
// nav must resolve to light computed backgrounds with dark text, and a few
// key text/background pairs must hold WCAG AA contrast (>= 4.5:1). No
// screenshots — copy/layout changes shouldn't break this spec, only a theme
// regression should (e.g. a component falling back to a hardcoded dark color).
const { test, expect } = require('@playwright/test');
const { loadDemoScenario, openDashboard, setView } = require('./harness');

test.use({ colorScheme: 'light' });

/** Parses 'rgb(r, g, b)' / 'rgba(r, g, b, a)' into channel values. */
function parseRgb(text) {
  const m = /rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([\d.]+))?\s*\)/.exec(text || '');
  if (!m) return null;
  return { r: Number(m[1]), g: Number(m[2]), b: Number(m[3]), a: m[4] === undefined ? 1 : Number(m[4]) };
}

/** WCAG relative luminance (0 = black, 1 = white). */
function relativeLuminance({ r, g, b }) {
  const chan = (v) => {
    const s = v / 255;
    return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * chan(r) + 0.7152 * chan(g) + 0.0722 * chan(b);
}

/** WCAG contrast ratio between two rgb colors. */
function contrastRatio(fg, bg) {
  const l1 = relativeLuminance(fg);
  const l2 = relativeLuminance(bg);
  const [hi, lo] = l1 >= l2 ? [l1, l2] : [l2, l1];
  return (hi + 0.05) / (lo + 0.05);
}

/** Composites a (possibly alpha) color over an opaque backdrop. */
function compositeOver(color, backdrop) {
  const a = color.a;
  return {
    r: Math.round(color.r * a + backdrop.r * (1 - a)),
    g: Math.round(color.g * a + backdrop.g * (1 - a)),
    b: Math.round(color.b * a + backdrop.b * (1 - a)),
    a: 1,
  };
}

/** Effective opaque background of an element: walks up until alpha accumulates to opaque-ish. */
async function effectiveBackground(page, selector) {
  const colors = await page.$eval(selector, (start) => {
    const out = [];
    for (let el = start; el; el = el.parentElement) {
      out.push(getComputedStyle(el).backgroundColor);
    }
    out.push(getComputedStyle(document.documentElement).backgroundColor);
    return out;
  });
  // Composite from the deepest non-transparent layers; fall back to the
  // resolved --bg token for the page surface behind gradient images.
  const bgToken = await page.evaluate(() =>
    getComputedStyle(document.documentElement).getPropertyValue('--bg').trim(),
  );
  let backdrop = hexToRgb(bgToken) || { r: 255, g: 255, b: 255, a: 1 };
  for (let i = colors.length - 1; i >= 0; i -= 1) {
    const c = parseRgb(colors[i]);
    if (!c || c.a === 0) continue;
    backdrop = compositeOver(c, backdrop);
  }
  return backdrop;
}

function hexToRgb(hex) {
  const m = /^#([0-9a-f]{6})$/i.exec((hex || '').trim());
  if (!m) return null;
  const n = parseInt(m[1], 16);
  return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255, a: 1 };
}

// Old dark-theme literals that must NOT survive in light computed styles.
const OLD_DARK_NAV_TOP = 'rgba(43, 45, 56';
const OLD_DARK_PANEL = 'rgba(20, 20, 27';

test.describe('light mode — functional theme coverage', () => {
  test.beforeEach(async ({ page }) => {
    await openDashboard(page);
    await loadDemoScenario(page, 'typical');
    await setView(page, 'drive');
  });

  test('page chrome is light and body text is dark', async ({ page }) => {
    // The light token palette must be active (not the dark default).
    const bgToken = await page.evaluate(() =>
      getComputedStyle(document.documentElement).getPropertyValue('--bg').trim(),
    );
    const bg = hexToRgb(bgToken);
    expect(bg, `--bg should resolve to a hex color, got "${bgToken}"`).toBeTruthy();
    expect(relativeLuminance(bg)).toBeGreaterThan(0.7); // light page surface

    const bodyColor = parseRgb(
      await page.evaluate(() => getComputedStyle(document.body).color),
    );
    expect(relativeLuminance(bodyColor)).toBeLessThan(0.2); // dark text

    // Form controls follow the scheme.
    const scheme = await page.evaluate(() => getComputedStyle(document.body).colorScheme);
    expect(scheme).toContain('light');
  });

  test('drive-tab card renders a light surface, not the old dark panel', async ({ page }) => {
    const card = page.locator('#view-drive .live-card');
    await expect(card).toBeVisible();

    const cardBg = await card.evaluate((el) => getComputedStyle(el).backgroundColor);
    expect(cardBg).not.toContain(OLD_DARK_PANEL);
    const parsed = parseRgb(cardBg);
    expect(parsed.a).toBeGreaterThan(0.5); // still an opaque-ish card surface
    expect(relativeLuminance(parsed)).toBeGreaterThan(0.8); // white-ish card
  });

  test('bottom nav uses the light glass chrome', async ({ page }) => {
    const nav = page.locator('.bottom-nav');
    await expect(nav).toBeVisible();

    const { backgroundImage, backgroundColor } = await nav.evaluate((el) => {
      const cs = getComputedStyle(el);
      return { backgroundImage: cs.backgroundImage, backgroundColor: cs.backgroundColor };
    });
    // The gradient must resolve to light stops — not the old dark glass.
    expect(backgroundImage).not.toContain(OLD_DARK_NAV_TOP);
    const firstStop = parseRgb(backgroundImage);
    expect(firstStop, `nav gradient should have rgb stops: ${backgroundImage}`).toBeTruthy();
    expect(relativeLuminance(firstStop)).toBeGreaterThan(0.7);
    // background-color is reset by the shorthand; tolerate either transparent
    // or a light solid, but never a dark solid.
    const solid = parseRgb(backgroundColor);
    if (solid && solid.a > 0.1) {
      expect(relativeLuminance(solid)).toBeGreaterThan(0.7);
    }
  });

  test('contrast spot-checks hold WCAG AA (>= 4.5:1)', async ({ page }) => {
    const checks = [
      // Primary value on the drive live card (var(--text) on the card surface).
      { label: 'live-card speed value', text: '#view-drive .live-card .speed-value', surface: '#view-drive .live-card' },
      // Soft caption kicker on the live card (weakest text tone on a card).
      { label: 'live-card kicker', text: '#view-drive .live-card .kicker', surface: '#view-drive .live-card' },
      // Active bottom-nav label on the nav surface.
      { label: 'active nav label', text: '.bottom-nav button.is-active span', surface: '.bottom-nav' },
    ];

    for (const { label, text, surface } of checks) {
      const el = page.locator(text).first();
      await expect(el, label).toBeVisible();
      const fg = parseRgb(await el.evaluate((node) => getComputedStyle(node).color));
      const bg = await effectiveBackground(page, `${surface}`);
      const ratio = contrastRatio(fg, bg);
      expect(
        ratio,
        `${label}: ${JSON.stringify(fg)} on ${JSON.stringify(bg)} = ${ratio.toFixed(2)}:1`,
      ).toBeGreaterThanOrEqual(4.5);
    }
  });
});
