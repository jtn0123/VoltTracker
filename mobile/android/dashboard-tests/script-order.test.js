import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

const DASHBOARD_HTML = resolve('../app/src/main/assets/dashboard/index.html');
const TEMPLATE_HTML = resolve('../app/src/main/dashboard-src/index.template.html');

// The dashboard is served from file:///android_asset/ in the Android WebView.
// `<script type="module">` is fetched with CORS semantics that file:// cannot
// satisfy, so a module bootstrap silently never runs on-device. The files are
// self-contained IIFEs sharing window.VoltDashboard, so they load as ordered
// classic scripts. This order is the dependency order (core first; actions'
// bootstrap calls into map/drive/etc.).
const EXPECTED_SCRIPTS = [
  'js/core.js',
  'js/panels.js',
  'js/map.js',
  'js/scrubber.js',
  'js/drive.js',
  'js/telemetry.js',
  'js/actions.js',
  'js/troubleshooter.js',
  'js/connection-status.js',
  'js/connection-tools.js',
];

function dashboardScripts(html) {
  const parsed = new DOMParser().parseFromString(html, 'text/html');
  return [...parsed.querySelectorAll('script[src]')]
    .map((script) => ({
      src: script.getAttribute('src') ?? '',
      type: script.getAttribute('type') ?? '',
    }))
    .filter((script) => script.src.startsWith('js/'));
}

describe('dashboard production script order', () => {
  it('loads the dashboard JS as ordered classic scripts (no type=module over file://)', () => {
    for (const html of [
      readFileSync(DASHBOARD_HTML, 'utf8'),
      readFileSync(TEMPLATE_HTML, 'utf8'),
    ]) {
      const scripts = dashboardScripts(html);
      expect(scripts.map((s) => s.src)).toEqual(EXPECTED_SCRIPTS);
      // None of the first-party scripts may be modules — modules don't load
      // over file:// in the WebView.
      expect(scripts.every((s) => s.type !== 'module')).toBe(true);
    }
  });
});
