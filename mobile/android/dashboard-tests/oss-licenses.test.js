import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { JSDOM } from 'jsdom';
import { describe, expect, it } from 'vitest';

// C3 — the Settings "Open-source licenses" ledger. Everything third-party that
// ships inside the app must be credited there, and the licenses that require a
// copyright notice to travel with the distribution (Leaflet's BSD-2-Clause,
// Space Grotesk's SIL OFL 1.1) must carry that notice text. The in-map ODbL
// credit is separate and load-bearing: OpenStreetMap's license requires visible
// attribution wherever the map data is shown, which is Leaflet's attribution
// control on the Map tab.

const HERE = dirname(fileURLToPath(import.meta.url));
const INDEX_HTML = resolve(HERE, '../app/src/main/assets/dashboard/index.html');
const MAP_TS = resolve(HERE, '../app/src/main/dashboard-src/js/map.ts');

function licensesSection() {
  const dom = new JSDOM(readFileSync(INDEX_HTML, 'utf8'));
  return dom.window.document.getElementById('ossLicenses');
}

describe('Settings open-source licenses (C3)', () => {
  it('renders a collapsible licenses block inside the Settings view', () => {
    const section = licensesSection();
    expect(section).not.toBeNull();
    expect(section.tagName).toBe('DETAILS');
    expect(section.closest('[data-view="settings"]')).not.toBeNull();
    // Collapsed by default — a quiet footer entry, not a wall of text.
    expect(section.hasAttribute('open')).toBe(false);
    expect(section.querySelector('summary').textContent).toMatch(
      /open-source licenses/i,
    );
  });

  it('credits every third-party component shipped in the app', () => {
    const text = licensesSection().textContent;
    for (const name of [
      'Leaflet',
      'OpenStreetMap',
      'CARTO',
      'Space Grotesk',
    ]) {
      expect(text, `${name} must be credited`).toContain(name);
    }
  });

  it('carries the copyright notices BSD-2-Clause and OFL require', () => {
    const text = licensesSection().textContent.replace(/\s+/g, ' ');
    // Leaflet — BSD-2-Clause requires the copyright notice with the distribution.
    expect(text).toContain('Vladimir Agafonkin');
    expect(text).toContain('CloudMade');
    expect(text).toMatch(/BSD 2-Clause/i);
    // Space Grotesk — OFL 1.1 requires the copyright notice; the full license
    // text ships as fonts/OFL.txt and the entry must point at it.
    expect(text).toContain('The Space Grotesk Project Authors');
    expect(text).toContain('fonts/OFL.txt');
    // OpenStreetMap data — ODbL attribution line.
    expect(text).toMatch(/OpenStreetMap contributors/);
    expect(text).toMatch(/Open Database License/);
  });

  it('keeps the ODbL-critical in-map attribution on the tile layers', () => {
    const map = readFileSync(MAP_TS, 'utf8');
    // Primary (CARTO) layer and the OSM fallback layer both credit OSM.
    const attributions = [...map.matchAll(/attribution:\s*"([^"]+)"/g)].map(
      (m) => m[1],
    );
    expect(attributions.length).toBeGreaterThanOrEqual(2);
    for (const attribution of attributions) {
      expect(attribution).toMatch(/OpenStreetMap/);
    }
    // The attribution control must stay enabled on the Leaflet map.
    expect(map).toMatch(/attributionControl:\s*true/);
  });
});
