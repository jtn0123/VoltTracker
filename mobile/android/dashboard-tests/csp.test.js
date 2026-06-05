import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { JSDOM } from 'jsdom';
import { describe, expect, it } from 'vitest';

// The dashboard runs from file:///android_asset/ in the Android WebView, where the
// CSP <meta> is the only thing standing between us and a remote-resource load. This
// suite pins that policy so a future partial/map edit can't silently widen it (the
// failure mode that round-5 E2 hit: an OSM tile fallback that wasn't in img-src).
//
// Scope note: CSP governs *resource loads* (scripts, images, fetch/XHR). It does NOT
// govern user-initiated navigation, so the DTC "search Google" link strings in
// core.ts / dtc-lookup.ts are intentionally out of scope here.

const HERE = dirname(fileURLToPath(import.meta.url));
const DASHBOARD = resolve(HERE, '../app/src/main/assets/dashboard');
// JS source moved to dashboard-src/js/ (assets/dashboard/js/ is the built bundle).
const DASHBOARD_SRC = resolve(HERE, '../app/src/main/dashboard-src');

function sourceFor(name) {
  const ts = resolve(DASHBOARD_SRC, `js/${name}.ts`);
  return existsSync(ts) ? ts : resolve(DASHBOARD_SRC, `js/${name}.js`);
}

// The exact resource hosts the dashboard is allowed to reach. Keep in sync with the
// CSP meta in dashboard-src/index.template.html. Adding a host here is a deliberate,
// reviewable act — which is the whole point of this test.
const ALLOWED_REMOTE_HOSTS = [
  'https://*.basemaps.cartocdn.com',
  'https://*.tile.openstreetmap.org',
];

function readDashboard(file) {
  return readFileSync(resolve(DASHBOARD, file), 'utf8');
}

function cspContent(html) {
  const dom = new JSDOM(html);
  const meta = dom.window.document.querySelector(
    'meta[http-equiv="Content-Security-Policy"]',
  );
  return meta?.getAttribute('content') ?? '';
}

/** Parse "a 'self'; b x y" into { a: ["'self'"], b: ["x","y"] }. */
function parseDirectives(csp) {
  const out = {};
  for (const part of csp.split(';')) {
    const tokens = part.trim().split(/\s+/).filter(Boolean);
    if (!tokens.length) continue;
    out[tokens[0]] = tokens.slice(1);
  }
  return out;
}

describe('dashboard content-security-policy', () => {
  const html = readDashboard('index.html');
  const directives = parseDirectives(cspContent(html));

  it('declares a CSP meta with the expected lockdown directives', () => {
    expect(Object.keys(directives)).toEqual(
      expect.arrayContaining([
        'default-src',
        'img-src',
        'script-src',
        'style-src',
        'connect-src',
        'frame-ancestors',
        'base-uri',
        'form-action',
      ]),
    );
    expect(directives['default-src']).toEqual(["'self'"]);
    // No remote scripts, ever — first-party JS only.
    expect(directives['script-src']).toEqual(["'self'"]);
    expect(directives['frame-ancestors']).toEqual(["'none'"]);
    expect(directives['base-uri']).toEqual(["'none'"]);
    expect(directives['form-action']).toEqual(["'none'"]);
  });

  it('only allows the known tile CDNs as remote img/connect sources', () => {
    for (const key of ['img-src', 'connect-src']) {
      const remotes = directives[key].filter((s) => s.startsWith('http'));
      expect(remotes.sort()).toEqual([...ALLOWED_REMOTE_HOSTS].sort());
    }
  });

  it('loads no remote scripts from the document', () => {
    const dom = new JSDOM(html);
    const remoteScripts = [...dom.window.document.querySelectorAll('script[src]')]
      .map((s) => s.getAttribute('src'))
      .filter((src) => /^https?:\/\//.test(src ?? ''));
    expect(remoteScripts).toEqual([]);
  });

  it('keeps every Leaflet tile URL within the CSP allowlist', () => {
    // map.ts builds the basemap + OSM-fallback tile URLs. Those are the actual
    // img/connect resources CSP governs; a host here that isn't in the allowlist
    // would be silently blocked on-device (blank map).
    const mapJs = readFileSync(sourceFor('map'), 'utf8');
    const tileUrls = [...mapJs.matchAll(/https:\/\/\{s\}\.[a-z0-9.]+/g)].map((m) => m[0]);
    expect(tileUrls.length).toBeGreaterThan(0);

    const allowedSuffixes = ALLOWED_REMOTE_HOSTS.map((h) => h.replace('https://*.', ''));
    for (const url of tileUrls) {
      const host = url.replace('https://{s}.', '');
      // Match on an exact host or a true domain-suffix boundary — `startsWith` would let
      // `basemaps.cartocdn.com.evil.com` slip past and weaken this security regression test.
      expect(
        allowedSuffixes.some((suffix) => host === suffix || host.endsWith('.' + suffix)),
        `${url} is not covered by the CSP allowlist`,
      ).toBe(true);
    }
  });
});
