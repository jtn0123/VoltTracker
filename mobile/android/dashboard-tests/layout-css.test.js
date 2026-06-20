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
    const screensCss = readFileSync(resolve(DASHBOARD_ASSETS, 'css/screens.css'), 'utf8');
    const mapEmptyRule = screensCss.match(/\.map-empty\s*\{[^}]+\}/)?.[0] || '';

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
    // .app bottom padding now derives from the shared --nav-safe geometry token
    // (which already folds in env(safe-area-inset-bottom)) plus a 12px breathing
    // gap, instead of the old hardcoded 112px + env() literal.
    expect(appRule).toMatch(/calc\(var\(--nav-safe\) \+ 12px\)/);
  });

  it('defines touch + nav geometry tokens', () => {
    // Shared geometry/touch-target tokens live in base.css :root so screens.css
    // and the partials can reference one source of truth. --nav-safe already
    // folds env(safe-area-inset-bottom) in — consumers must never re-add env().
    const baseCss = readFileSync(resolve(DASHBOARD_ASSETS, 'css/base.css'), 'utf8');
    const screensCss = readFileSync(resolve(DASHBOARD_ASSETS, 'css/screens.css'), 'utf8');
    const buttonRule = baseCss.match(/(?:^|\n)\s*button\s*\{[^}]+\}/)?.[0] || '';
    const scrubBtnRule = screensCss.match(/\.scrub-btn\s*\{[^}]+\}/)?.[0] || '';

    expect(baseCss).toMatch(/--touch-min:\s*44px/);
    expect(baseCss).toMatch(/--touch-min-dense:\s*40px/);
    expect(baseCss).toMatch(/--nav-h:\s*68px/);
    expect(baseCss).toMatch(/--nav-safe:\s*calc\(/);

    // The standard control floor is wired through the token, not a raw 44px.
    expect(buttonRule).toMatch(/min-height\s*:\s*var\(--touch-min\)/);
    expect(scrubBtnRule).toMatch(/min-height\s*:\s*var\(--touch-min\)/);
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

  it('keeps volt-orange alpha tints on the --volt-rgb token, not raw literals', () => {
    // The rgba(255,122,69,…) tints were tokenized to rgba(var(--volt-rgb), …)
    // (2026-06-12). The single allowed literal is the token definition itself
    // in base.css; any new raw literal is drift back to the hardcoded color.
    const cssFiles = ['base.css', 'components.css', 'screens.css', 'status-tools.css', 'troubleshooter.css'];
    const literal = /rgba\(\s*255\s*,\s*122\s*,\s*69\s*,/;

    for (const name of cssFiles) {
      const css = readFileSync(resolve(DASHBOARD_ASSETS, `css/${name}`), 'utf8');
      const withoutTokenDef = css.replace(/--volt-rgb\s*:\s*255,\s*122,\s*69\s*;/, '');
      expect(withoutTokenDef, `${name} reintroduced a raw volt-orange rgba literal`).not.toMatch(literal);
    }

    const baseCss = readFileSync(resolve(DASHBOARD_ASSETS, 'css/base.css'), 'utf8');
    expect(baseCss).toMatch(/--volt-rgb\s*:\s*255,\s*122,\s*69\s*;/);
    // The triplet must stay in sync with the default --volt hex (#ff7a45).
    expect(baseCss).toMatch(/--volt\s*:\s*#ff7a45\s*;/);
  });
});
