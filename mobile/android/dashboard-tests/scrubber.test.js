// scrubber.js — Map-tab route scrubber. Tests the public surface
// (renderScrubber / hideScrubber / scrubAtLatLng / scrubberAttachMap) and the
// invariants around empty / single-point routes. We don't exercise the
// Leaflet marker path because it requires a real map; the early-return paths
// for missing scrubMap are what cover the no-map test environment.
import { describe, it, expect, beforeEach } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

// Minimal markup: the scrubber container plus the chart hosts the renderer
// touches. The scrubber.js bootstrap binds to #scrubToggle and #scrubPlay if
// present — include them so the bindings exercise (they're no-ops without
// data).
const SCRUBBER_EXTRA_DOM = `
  <div id="scrubber" hidden>
    <button id="scrubToggle" type="button" aria-expanded="false">Details</button>
    <button id="scrubPlay" type="button">Play</button>
    <div id="scrubChart"></div>
    <div id="scrubStack" hidden></div>
    <div id="scrubReadout"></div>
  </div>
`;

function withTwoPointRoute() {
  return {
    points: [
      { lat: 32.700, lng: -117.100, atMs: 1_700_000_000_000, speedMps: 0 },
      { lat: 32.702, lng: -117.100, atMs: 1_700_000_030_000, speedMps: 10 },
    ],
  };
}

function withSinglePointRoute() {
  return {
    points: [
      { lat: 32.700, lng: -117.100, atMs: 1_700_000_000_000, speedMps: 0 },
    ],
  };
}

describe('scrubber.js', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard({ extraDom: SCRUBBER_EXTRA_DOM, extras: ['scrubber.js'] });
  });

  it('exposes the documented public surface on window.VoltDashboard', () => {
    const VD = window.VoltDashboard;
    expect(typeof VD.renderScrubber).toBe('function');
    expect(typeof VD.hideScrubber).toBe('function');
    expect(typeof VD.scrubAtLatLng).toBe('function');
    expect(typeof VD.scrubberAttachMap).toBe('function');
  });

  it('scrubAtLatLng is a no-op when no route is loaded', () => {
    const VD = window.VoltDashboard;
    // No throws, no side effects on the DOM. Just call it.
    expect(() => VD.scrubAtLatLng(32.7, -117.1)).not.toThrow();
    expect(document.getElementById('scrubber').hidden).toBe(true);
  });

  it('renderScrubber hides the panel when the route has fewer than 2 points', () => {
    const VD = window.VoltDashboard;
    const scrubber = document.getElementById('scrubber');
    scrubber.hidden = false; // pretend a previous route had revealed it
    VD.renderScrubber(withSinglePointRoute());
    expect(scrubber.hidden).toBe(true);
  });

  it('renderScrubber reveals the panel for a 2-point route', () => {
    const VD = window.VoltDashboard;
    const scrubber = document.getElementById('scrubber');
    expect(scrubber.hidden).toBe(true);
    VD.renderScrubber(withTwoPointRoute());
    expect(scrubber.hidden).toBe(false);
  });

  it('hideScrubber re-hides the panel and clears internal route state', () => {
    const VD = window.VoltDashboard;
    VD.renderScrubber(withTwoPointRoute());
    expect(document.getElementById('scrubber').hidden).toBe(false);
    VD.hideScrubber();
    expect(document.getElementById('scrubber').hidden).toBe(true);
    // After hide, scrubAtLatLng should be a no-op again — proves scrubData
    // was reset (not just the panel re-hidden).
    expect(() => VD.scrubAtLatLng(32.7, -117.1)).not.toThrow();
  });
});
