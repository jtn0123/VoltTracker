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
    expect(bodyRule).toMatch(/overflow\s*:\s*hidden/);
    expect(appRule).toMatch(/overflow-y\s*:\s*auto/);
    expect(appRule).toMatch(/min-height\s*:\s*0/);
  });

  it('keeps the empty map message below the overlay controls', () => {
    const screensCss = readFileSync(resolve(DASHBOARD_ASSETS, 'css/screens.css'), 'utf8');
    const mapEmptyRule = screensCss.match(/\.map-empty\s*\{[^}]+\}/)?.[0] || '';

    expect(mapEmptyRule).toMatch(/padding\s*:\s*120px\s+20px\s+56px/);
  });

  it('keeps all seven bottom nav items inside narrow Android WebView widths', () => {
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
    expect(navRule).toMatch(/grid-template-columns\s*:\s*repeat\(7,\s*minmax\(0,\s*1fr\)\)/);
    expect(navRule).toMatch(/gap\s*:\s*4px/);
    expect(navButtonRule).toMatch(/min-width\s*:\s*0/);
    expect(appRule).toMatch(/calc\(112px \+ env\(safe-area-inset-bottom\)\)/);
  });

  it('keeps the floating nav readable over scrollable page content', () => {
    const screensCss = readFileSync(resolve(DASHBOARD_ASSETS, 'css/screens.css'), 'utf8');
    const navRule = screensCss.match(/\.bottom-nav\s*\{[^}]+\}/)?.[0] || '';
    const navButtonRule = screensCss.match(/\.bottom-nav button\s*\{[^}]+\}/)?.[0] || '';

    expect(navRule).toMatch(/isolation\s*:\s*isolate/);
    expect(navRule).toMatch(/background-color\s*:\s*rgba\(21,23,32,0\.92\)/);
    expect(navRule).toMatch(/border\s*:\s*1px\s+solid\s+rgba\(255,255,255,0\.14\)/);
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
});
