import { readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

const DASHBOARD_HTML = resolve('../app/src/main/assets/dashboard/index.html');
const DASHBOARD_JS = resolve('../app/src/main/assets/dashboard/js');
const TEMPLATE_HTML = resolve('../app/src/main/dashboard-src/index.template.html');
const BUILD_MJS = resolve('build.mjs');

// The dashboard ships as ONE classic-IIFE bundle (js/app.js), built by build.mjs
// from the eager source files concatenated in dependency order. It MUST stay a
// classic script: the dashboard is served from file:///android_asset/ in the
// WebView, where `<script type="module">` is fetched with CORS semantics file://
// cannot satisfy — so a module bootstrap silently never runs on-device.
//
// Dependency order: core first (it seeds window.VoltDashboard); actions' bootstrap
// calls into map/drive/telemetry/etc., so it comes after them.
const EXPECTED_EAGER_ORDER = [
  'core',
  'storage-status',
  'signals-panel',
  'insights-panel',
  'map',
  'scrubber',
  'drive',
  'telemetry',
  'actions',
  'troubleshooter',
  'connection-status',
  'connection-tools',
];

function firstPartyScripts(html) {
  const parsed = new DOMParser().parseFromString(html, 'text/html');
  return [...parsed.querySelectorAll('script[src]')]
    .map((script) => ({
      src: script.getAttribute('src') ?? '',
      type: script.getAttribute('type') ?? '',
    }))
    .filter((script) => script.src.startsWith('js/'));
}

describe('dashboard production script bundle', () => {
  it('ships js/app.js as a single classic (non-module) script', () => {
    for (const html of [
      readFileSync(DASHBOARD_HTML, 'utf8'),
      readFileSync(TEMPLATE_HTML, 'utf8'),
    ]) {
      const scripts = firstPartyScripts(html);
      expect(scripts.map((s) => s.src)).toEqual(['js/app.js']);
      // A module would silently never load over file:// in the WebView.
      expect(scripts.every((s) => s.type !== 'module')).toBe(true);
    }
  });

  it('build.mjs bundles the eager source files in dependency order', () => {
    const build = readFileSync(BUILD_MJS, 'utf8');
    const match = build.match(/const EAGER = \[([\s\S]*?)\];/);
    expect(match).not.toBeNull();
    const order = [...match[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]);
    expect(order).toEqual(EXPECTED_EAGER_ORDER);
  });

  it('ships syntax that Android 9 WebView can parse', () => {
    const build = readFileSync(BUILD_MJS, 'utf8');
    expect(build).toContain('target: "chrome66"');

    for (const file of readdirSync(DASHBOARD_JS).filter((name) => name.endsWith('.js'))) {
      const js = readFileSync(resolve(DASHBOARD_JS, file), 'utf8');
      expect(js, `${file} must not ship optional chaining`).not.toMatch(/\?\./);
      expect(js, `${file} must not ship nullish coalescing`).not.toMatch(/\?\?/);
    }
  });
});
