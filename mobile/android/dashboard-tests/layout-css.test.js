import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const HERE = dirname(fileURLToPath(import.meta.url));
const DASHBOARD_ASSETS = resolve(HERE, '../app/src/main/assets/dashboard');

describe('dashboard layout css', () => {
  it('does not clip vertical touch scrolling at the app shell', () => {
    const baseCss = readFileSync(resolve(DASHBOARD_ASSETS, 'css/base.css'), 'utf8');
    const bodyRule = baseCss.match(/(?:^|\n)\s*body\s*\{[^}]+\}/)?.[0] || '';
    const appRule = baseCss.match(/\.app\s*\{[^}]+\}/)?.[0] || '';

    // Whitespace-tolerant so `overflow:clip` (no space) can't slip past this guard.
    expect(appRule).not.toMatch(/overflow\s*:\s*clip/);
    expect(bodyRule).toMatch(/display\s*:\s*flex/);
    expect(bodyRule).not.toMatch(/overflow\s*:\s*hidden/);
    expect(bodyRule).toMatch(/overflow-y\s*:\s*auto/);
    expect(appRule).toMatch(/overflow-y\s*:\s*visible/);
    expect(appRule).toMatch(/min-height\s*:\s*auto/);
  });

  it('keeps the empty map message below the overlay controls', () => {
    // .map-empty was split into the lazy screens-map.css (G3) — Map-tab-exclusive.
    const mapCss = readFileSync(resolve(DASHBOARD_ASSETS, 'css/screens-map.css'), 'utf8');
    const mapEmptyRule = mapCss.match(/\.map-empty\s*\{[^}]+\}/)?.[0] || '';

    expect(mapEmptyRule).toMatch(/padding\s*:\s*120px\s+20px\s+56px/);
  });

  it('keeps all six bottom nav items inside narrow Android WebView widths', () => {
    const screensCss = readFileSync(resolve(DASHBOARD_ASSETS, 'css/screens.css'), 'utf8');
    const baseCss = readFileSync(resolve(DASHBOARD_ASSETS, 'css/base.css'), 'utf8');
    const navRule = screensCss.match(/\.bottom-nav\s*\{[^}]+\}/)?.[0] || '';
    const navButtonRule = screensCss.match(/\.bottom-nav button\s*\{[^}]+\}/)?.[0] || '';
    const appRule = baseCss.match(/\.app\s*\{[^}]+\}/)?.[0] || '';

    expect(navRule).toMatch(/position\s*:\s*fixed/);
    expect(navRule).toMatch(/left\s*:\s*50%/);
    expect(navRule).toMatch(/width\s*:\s*min\(760px,\s*calc\(100vw - 24px\)\)/);
    expect(navRule).toMatch(/transform\s*:\s*translateX\(-50%\)/);
    expect(navRule).not.toMatch(/width\s*:\s*100%/);
    expect(navRule).not.toMatch(/padding-bottom\s*:\s*calc\(54px \+ env\(safe-area-inset-bottom\)\)/);
    expect(navRule).toMatch(/grid-template-columns\s*:\s*repeat\(6,\s*minmax\(0,\s*1fr\)\)/);
    expect(navRule).toMatch(/gap\s*:\s*4px/);
    expect(navButtonRule).toMatch(/min-width\s*:\s*0/);
    // 28px of page-end breathing (round-3 rhythm pass) on top of the nav-safe
    // clearance — the guard is that --nav-safe is reserved at all.
    expect(appRule).toMatch(/calc\(var\(--nav-safe\) \+ 28px\)/);
  });

  it('defines touch + nav geometry tokens', () => {
    const baseCss = readFileSync(resolve(DASHBOARD_ASSETS, 'css/base.css'), 'utf8');
    const buttonRule = baseCss.match(/(?:^|\n)\s*button\s*\{[^}]+\}/)?.[0] || '';

    expect(baseCss).toMatch(/--touch-min:\s*44px/);
    expect(baseCss).toMatch(/--touch-min-dense:\s*40px/);
    expect(baseCss).toMatch(/--nav-h:\s*68px/);
    expect(baseCss).toMatch(/--nav-safe:\s*calc\(/);
    expect(buttonRule).toMatch(/min-height\s*:\s*var\(--touch-min\)/);
  });

  it('keeps tab content from overflowing the viewport horizontally', () => {
    const baseCss = readFileSync(resolve(DASHBOARD_ASSETS, 'css/base.css'), 'utf8');
    const componentsCss = readFileSync(resolve(DASHBOARD_ASSETS, 'css/components.css'), 'utf8');
    const viewRule = baseCss.match(/(?:^|\n)\s*\.view\s*\{[^}]+\}/)?.[0] || '';
    const heroRule = componentsCss.match(/\.hero\s*\{[^}]+\}/)?.[0] || '';
    const statusMetaRule = componentsCss.match(/\.status-meta\s*\{[^}]+\}/)?.[0] || '';

    // The .view and .hero stacks are single-column grids. Without an explicit
    // minmax(0, 1fr) column, the implicit `auto` track sizes to the widest
    // card's min-content, so one card with long nowrap text (the OBD session
    // adapter/PIDs values) stretched every Drive card past the screen edge.
    expect(viewRule).toMatch(/grid-template-columns\s*:\s*minmax\(0,\s*1fr\)/);
    expect(heroRule).toMatch(/grid-template-columns\s*:\s*minmax\(0,\s*1fr\)/);
    // Bare 1fr columns also kept the nowrap+ellipsis <strong> values from ever
    // truncating; minmax(0, 1fr) restores the intended ellipsis behavior.
    expect(statusMetaRule).toMatch(/grid-template-columns\s*:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)/);
  });

  it('keeps the floating nav readable over scrollable page content', () => {
    const screensCss = readFileSync(resolve(DASHBOARD_ASSETS, 'css/screens.css'), 'utf8');
    const baseCss = readFileSync(resolve(DASHBOARD_ASSETS, 'css/base.css'), 'utf8');
    const navRule = screensCss.match(/\.bottom-nav\s*\{[^}]+\}/)?.[0] || '';
    const navButtonRule = screensCss.match(/\.bottom-nav button\s*\{[^}]+\}/)?.[0] || '';

    expect(navRule).toMatch(/isolation\s*:\s*isolate/);
    // The nav surface is opaque-enough glass via the theme token (dark
    // rgba(21,23,32,0.92) / light counterpart in the light block) — a solid
    // background-color fallback must stay so content can't bleed through.
    expect(navRule).toMatch(/background-color\s*:\s*var\(--surface-nav\)/);
    expect(baseCss).toMatch(/--surface-nav\s*:\s*rgba\(21,23,32,0\.92\)/);
    expect(navRule).toMatch(/border\s*:\s*1px\s+solid\s+var\(--line-strong\)/);
    expect(navRule).toMatch(/border-radius\s*:\s*24px/);
    expect(navRule).toMatch(/z-index\s*:\s*40/);
    expect(navButtonRule).toMatch(/z-index\s*:\s*1/);
    expect(screensCss).not.toMatch(/body::after/);
    expect(screensCss).not.toMatch(/\.bottom-nav::before/);
  });

  it('keeps the active bottom-nav item free of a separate top rail', () => {
    const screensCss = readFileSync(resolve(DASHBOARD_ASSETS, 'css/screens.css'), 'utf8');

    expect(screensCss).not.toMatch(/\.bottom-nav\s+button::before/);
    expect(screensCss).not.toMatch(/\.bottom-nav\s+button\.is-active::before/);
    expect(screensCss).not.toMatch(/\.bottom-nav\s+button\.is-active\s*\{[^}]*inset\s+0\s+1px\s+0/);
  });

  it('keeps Settings command buttons readable and grouped by intent', () => {
    const screensCss = readFileSync(resolve(DASHBOARD_ASSETS, 'css/screens.css'), 'utf8');
    const dashboardHtml = readFileSync(resolve(DASHBOARD_ASSETS, 'index.html'), 'utf8');
    const commandRule = screensCss.match(/\.settings-command\s*\{[^}]+\}/)?.[0] || '';
    const commandCopyRule = screensCss.match(/\.settings-command span,\s*\.settings-command small\s*\{[^}]+\}/)?.[0] || '';
    const connectionActionsRule = screensCss.match(/\.connection-actions\s*\{[^}]+\}/)?.[0] || '';

    expect(commandRule).toMatch(/display\s*:\s*grid/);
    expect(commandRule).toMatch(/min-height\s*:\s*56px/);
    expect(commandRule).toMatch(/text-align\s*:\s*left/);
    expect(commandCopyRule).toMatch(/overflow-wrap\s*:\s*anywhere/);
    expect(connectionActionsRule).toMatch(/repeat\(2,\s*minmax\(0,\s*1fr\)\)/);
    expect(screensCss).toMatch(/@media\s*\(min-width:\s*640px\)\s*\{[\s\S]*\.connection-actions,\s*\.db-action-grid/);
    expect(dashboardHtml).toContain('class="settings-command"');
    expect(dashboardHtml).toContain('Reconnect last');
    expect(dashboardHtml).toContain('Backup &amp; restore');
    expect(dashboardHtml).toContain('data-action="restore"');
    expect(dashboardHtml).not.toContain('id="disconnectBtn"');
  });

  it('keeps status-tone alpha tints on the --*-rgb tokens, not raw literals', () => {
    // The rgba(255,122,69,…) volt tints were tokenized to rgba(var(--volt-rgb), …)
    // (2026-06-12); the remaining tone triplets (ok/warn/bad/ev/mixed + the
    // map accent) followed in the UI polish pass. The single allowed literal
    // per tone is the token definition itself in base.css; any new raw
    // literal is drift back to a hardcoded color.
    const cssFiles = ['base.css', 'components.css', 'screens.css', 'screens-map.css', 'status-tools.css', 'troubleshooter.css'];
    const tones = [
      { token: '--volt-rgb', triplet: [255, 122, 69], hexToken: '--volt', hex: '#ff7a45' },
      { token: '--ev-rgb', triplet: [184, 230, 59], hexToken: '--ev', hex: '#b8e63b' },
      { token: '--ok-rgb', triplet: [53, 230, 160], hexToken: '--ok', hex: '#35e6a0' },
      { token: '--warn-rgb', triplet: [255, 184, 74], hexToken: '--warn', hex: '#ffb84a' },
      { token: '--bad-rgb', triplet: [255, 107, 95], hexToken: '--bad', hex: '#ff6b5f' },
      { token: '--mixed-rgb', triplet: [164, 140, 255], hexToken: '--mixed', hex: '#a48cff' },
      { token: '--map-accent-rgb', triplet: [76, 196, 255], hexToken: '--map-accent', hex: '#4cc4ff' },
    ];

    const baseCss = readFileSync(resolve(DASHBOARD_ASSETS, 'css/base.css'), 'utf8');
    for (const { token, triplet, hexToken, hex } of tones) {
      const [r, g, b] = triplet;
      const literal = new RegExp(`rgba\\(\\s*${r}\\s*,\\s*${g}\\s*,\\s*${b}\\s*,`);
      const defPattern = new RegExp(`${token}\\s*:\\s*${r},\\s*${g},\\s*${b}\\s*;`);

      for (const name of cssFiles) {
        const css = readFileSync(resolve(DASHBOARD_ASSETS, `css/${name}`), 'utf8');
        // Token definitions and rgba(var(--x-rgb, r, g, b), …) fallbacks are the
        // allowed spellings of the triplet; anything else is a raw tint literal.
        const withoutAllowed = css
          .replace(new RegExp(`${token}\\s*:\\s*${r},\\s*${g},\\s*${b}\\s*;`, 'g'), '')
          .replace(new RegExp(`var\\(${token},\\s*${r},\\s*${g},\\s*${b}\\)`, 'g'), '');
        expect(withoutAllowed, `${name} reintroduced a raw ${token} rgba literal`).not.toMatch(literal);
      }

      // The default-scheme triplet must exist and stay in sync with its hex tone.
      expect(baseCss, `base.css must define ${token}`).toMatch(defPattern);
      expect(baseCss, `${hexToken} default hex changed without updating ${token}`)
        .toMatch(new RegExp(`${hexToken}\\s*:\\s*${hex}\\s*;`));
    }
  });
});
