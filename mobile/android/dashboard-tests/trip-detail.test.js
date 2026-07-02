// M7 — per-trip detail sheet. A map-session row's "Details" button opens a
// modal sheet populated from the route projection + trip rollup: distance,
// duration, avg/max speed, efficiency, start/end, plus an efficiency-vs-speed
// scatter scoped to that one drive. Rendering stays XSS-safe (createElement /
// setSvgAttrs / textContent only).
import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

const BASE = 1_700_000_000_000;
const MPH_TO_MPS = 1 / 2.2369363;

// A stored drive with pre-enriched points (eff + speedMps + altM) so the scatter
// renders deterministically without depending on powerTrack interpolation.
function driveStorage({ id = 'd1', label = '', distanceMeters = 18000 } = {}) {
  const mphs = [22, 35, 48, 55, 62, 68, 70, 64];
  const startedAtMs = BASE;
  const endedAtMs = BASE + 8 * 30_000; // ~4 min
  const points = mphs.map((mph, i) => ({
    lat: 34.05 + i * 0.003,
    lng: -118.25 - i * 0.002,
    atMs: startedAtMs + i * 30_000,
    speedMps: mph * MPH_TO_MPS,
    altM: 100 - i * 4,
    eff: 3.0 + (i % 4) * 0.4,
  }));
  return {
    trips: [{ id, sessionId: id, startedAtMs, endedAtMs, pointCount: points.length, hasRoute: true, distanceMeters, adapterName: 'Adapter', label }],
    storage: {
      recentRoutes: [{
        _effDone: true,
        session: { id, sessionId: id, mode: 'drive', adapterName: 'Adapter', startedAtMs, endedAtMs, label },
        points,
        pointCount: points.length,
        distanceMeters,
      }],
    },
  };
}

describe('per-trip detail sheet (M7)', () => {
  let VD;
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    window.localStorage.clear();
    if (typeof Element.prototype.scrollIntoView !== 'function') {
      Element.prototype.scrollIntoView = () => {};
    }
    await loadDashboard();
    await window.VoltDashboard.ensureMapModule();
    VD = window.VoltDashboard;
  });

  function seed(opts) {
    const data = driveStorage(opts);
    VD.state.trips = data.trips;
    VD.state.storage = data.storage;
    VD.renderMap();
    return data.trips[0].id;
  }

  it('renders a Details button per stored drive carrying its route key', () => {
    const id = seed();
    const detail = document.querySelector('#mapSessionList [data-trip-detail]');
    expect(detail).not.toBeNull();
    expect(detail.dataset.tripDetail).toBe(id);
    expect(detail.textContent).toBe('Details');
  });

  it('opens the sheet with headline stats from the route + rollup', () => {
    const id = seed({ label: 'Commute home', distanceMeters: 18000 });
    expect(VD.openTripDetail(id)).toBe(true);
    expect(document.getElementById('tripDetailSheet').hidden).toBe(false);
    expect(document.getElementById('tripDetailTitle').textContent).toBe('Commute home');
    // Distance ~ 18 km → "11 mi" imperial; just assert a non-empty figure.
    expect(document.getElementById('tripDetailDistance').textContent).not.toBe('--');
    expect(document.getElementById('tripDetailDuration').textContent).not.toBe('--');
    expect(document.getElementById('tripDetailAvgSpeed').textContent).not.toBe('--');
    // Max speed is the fastest point (70 mph).
    expect(document.getElementById('tripDetailMaxSpeed').textContent).not.toBe('--');
    // Efficiency averages the per-point eff values.
    expect(document.getElementById('tripDetailEfficiency').textContent).not.toBe('--');
    expect(document.getElementById('tripDetailPoints').textContent).toBe('8');
    expect(document.getElementById('tripDetailStart').textContent).not.toBe('--');
    expect(document.getElementById('tripDetailEnd').textContent).not.toBe('--');
  });

  it('shows the EV split and energy rows when the trip rollup carries them', () => {
    const data = driveStorage({});
    data.trips[0].evShare = 0.716;
    data.trips[0].energyKwh = 4.23;
    VD.state.trips = data.trips;
    VD.state.storage = data.storage;
    VD.renderMap();
    VD.openTripDetail(data.trips[0].id);

    expect(document.getElementById('tripDetailEvRow').hidden).toBe(false);
    expect(document.getElementById('tripDetailEvShare').textContent).toBe('72% electric');
    expect(document.getElementById('tripDetailEnergyRow').hidden).toBe(false);
    expect(document.getElementById('tripDetailEnergy').textContent).toBe('4.2 kWh');
  });

  it('hides the EV split and energy rows for drives without classified power data', () => {
    const id = seed();
    VD.openTripDetail(id);
    expect(document.getElementById('tripDetailEvRow').hidden).toBe(true);
    expect(document.getElementById('tripDetailEnergyRow').hidden).toBe(true);
  });

  it('draws the elevation profile with a climb/descent headline', () => {
    const id = seed();
    VD.openTripDetail(id);
    const card = document.getElementById('tripDetailElevationCard');
    expect(card.hidden).toBe(false);
    const svg = document.querySelector('#tripDetailElevation svg');
    expect(svg).not.toBeNull();
    expect(svg.getAttribute('role')).toBe('img');
    expect(svg.querySelectorAll('path').length).toBeGreaterThanOrEqual(2);
    // The seeded route descends 4 m per point across 8 points (28 m ≈ 92 ft)
    // with no climb (imperial default units).
    const head = document.getElementById('tripDetailElevationHead').textContent;
    expect(head).toContain('0 ft climb');
    expect(head).toContain('92 ft descent');
  });

  it('hides the elevation card when the route has no altitude fixes', () => {
    const data = driveStorage({});
    for (const point of data.storage.recentRoutes[0].points) delete point.altM;
    VD.state.trips = data.trips;
    VD.state.storage = data.storage;
    VD.renderMap();
    VD.openTripDetail(data.trips[0].id);
    expect(document.getElementById('tripDetailElevationCard').hidden).toBe(true);
  });

  it('shares a drive card built from the stat strings the sheet shows', () => {
    const data = driveStorage({ label: 'Commute home' });
    data.trips[0].evShare = 0.72;
    data.trips[0].energyKwh = 4.2;
    VD.state.trips = data.trips;
    VD.state.storage = data.storage;
    VD.renderMap();
    VD.openTripDetail(data.trips[0].id);

    const calls = [];
    VD.bridge.shareTripCard = (json) => {
      calls.push(JSON.parse(json));
      return '{"ok":true}';
    };
    expect(VD.shareTripCard()).toBe(true);
    expect(calls).toHaveLength(1);
    const payload = calls[0];
    expect(payload.routeKey).toBe(data.trips[0].id);
    expect(payload.title).toBe('Commute home');
    // Absolute date, not the sheet's relative "Xd ago".
    expect(payload.subtitle).not.toContain('ago');
    const labels = payload.stats.map((s) => s.label);
    expect(labels).toContain('Distance');
    expect(labels).toContain('Electric');
    // Values are the exact formatted strings from the sheet.
    const electric = payload.stats.find((s) => s.label === 'Electric');
    expect(electric.value).toBe('72% electric');
    // Placeholder stats ("--") never reach the card.
    for (const stat of payload.stats) expect(stat.value).not.toBe('--');
  });

  it('surfaces a native {ok:false} share result instead of reporting success', () => {
    const id = seed();
    VD.openTripDetail(id);
    VD.bridge.shareTripCard = () => '{"ok":false,"error":"storage_unavailable"}';
    expect(VD.shareTripCard()).toBe(false);
  });

  it('plots an efficiency-vs-speed scatter scoped to that drive', () => {
    const id = seed();
    VD.openTripDetail(id);
    const svg = document.querySelector('#tripDetailScatter svg');
    expect(svg).not.toBeNull();
    expect(svg.getAttribute('role')).toBe('img');
    // One circle per point above the 10 mph floor (all 8 qualify).
    const circles = svg.querySelectorAll('circle');
    expect(circles.length).toBeGreaterThanOrEqual(6);
    expect(document.getElementById('tripDetailScatterEmpty').hidden).toBe(true);
  });

  it('shows the scatter empty hint when too few efficiency samples exist', () => {
    const startedAtMs = BASE;
    VD.state.trips = [{ id: 'd2', sessionId: 'd2', startedAtMs, endedAtMs: startedAtMs + 60_000, pointCount: 2, hasRoute: true, distanceMeters: 1000, adapterName: 'Adapter' }];
    VD.state.storage = {
      recentRoutes: [{
        _effDone: true,
        session: { id: 'd2', sessionId: 'd2', mode: 'drive', adapterName: 'Adapter', startedAtMs, endedAtMs: startedAtMs + 60_000 },
        // No eff on the points → no scatter samples.
        points: [{ lat: 34.05, lng: -118.25, atMs: startedAtMs, speedMps: 20 }, { lat: 34.16, lng: -118.32, atMs: startedAtMs + 30_000, speedMps: 22 }],
        pointCount: 2,
        distanceMeters: 1000,
      }],
    };
    VD.renderMap();
    expect(VD.openTripDetail('d2')).toBe(true);
    expect(document.querySelector('#tripDetailScatter svg')).toBeNull();
    expect(document.getElementById('tripDetailScatterEmpty').hidden).toBe(false);
  });

  it('opens via the Details button click and closes via the close action', () => {
    seed();
    document.querySelector('#mapSessionList [data-trip-detail]').click();
    expect(document.getElementById('tripDetailSheet').hidden).toBe(false);

    VD.handleAction('closeTripDetail');
    expect(document.getElementById('tripDetailSheet').hidden).toBe(true);
  });

  it('returns false (and does not open) for an unknown route key', () => {
    seed();
    expect(VD.openTripDetail('nope')).toBe(false);
    expect(document.getElementById('tripDetailSheet').hidden).toBe(true);
  });

  it('renders the title with textContent only (no markup injection)', () => {
    const id = seed({ label: '<img src=x onerror=alert(1)>' });
    VD.openTripDetail(id);
    const title = document.getElementById('tripDetailTitle');
    // The hostile label is rendered as text, not parsed into an element.
    expect(title.querySelector('img')).toBeNull();
    expect(title.textContent).toBe('<img src=x onerror=alert(1)>');
  });

  // M3 — per-trip estimated cost + savings vs gas, computed from the SAME shared
  // cost model the lifetime Insights figure uses (cost-model.ts), just fed this
  // drive's distance.
  describe('per-trip cost + savings (M3)', () => {
    const METERS_PER_MILE = 1609.344;
    const ASSUMED_MI_PER_KWH = 3.5;

    it('hides the cost/savings rows and prompts for prefs until MPG, gas price, and rate are set', () => {
      const id = seed({ distanceMeters: 18000 });
      VD.openTripDetail(id);
      // No prefs yet → rows hidden, no fabricated numbers, a tap-through prompt.
      expect(document.getElementById('tripDetailCostRow').hidden).toBe(true);
      expect(document.getElementById('tripDetailSavingsRow').hidden).toBe(true);
      expect(document.getElementById('tripDetailCost').textContent).toBe('--');
      const note = document.getElementById('tripDetailCostNote');
      expect(note.textContent).toContain('Set your MPG, gas price, and rate in Settings');
      const jump = note.querySelector('[data-nav-jump="settings"]');
      expect(jump).not.toBeNull();
      jump.click();
      expect(window.VoltDashboard.state.view).toBe('settings');
    });

    it('shows the estimated cost + savings for this drive once all prefs are set', () => {
      const distanceMeters = 18000;
      const id = seed({ distanceMeters });
      VD.prefs.set('mpg', 30);
      VD.prefs.set('gasPricePerGal', 4);
      VD.prefs.set('pricePerKwh', 0.14);
      VD.openTripDetail(id);

      expect(document.getElementById('tripDetailCostRow').hidden).toBe(false);
      expect(document.getElementById('tripDetailSavingsRow').hidden).toBe(false);

      const miles = distanceMeters / METERS_PER_MILE;
      const gasCost = (miles / 30) * 4;
      const evCost = (miles / ASSUMED_MI_PER_KWH) * 0.14;
      const expectedCost = '$' + evCost.toFixed(2);
      const expectedSavings = '$' + (gasCost - evCost).toFixed(2);
      expect(document.getElementById('tripDetailCost').textContent).toBe(expectedCost);
      expect(document.getElementById('tripDetailSavings').textContent).toBe(expectedSavings);
      // Note reads as an estimate, with the assumptions, and no stale prompt link.
      const note = document.getElementById('tripDetailCostNote');
      expect(note.querySelector('[data-nav-jump="settings"]')).toBeNull();
      expect(note.textContent).toContain('Estimated');
      expect(note.textContent).toContain('30 mpg');
      expect(note.textContent).toContain('3.5 mi/kWh');
    });

    it('builds the prompt with createElement only (no markup injection)', () => {
      const id = seed();
      VD.openTripDetail(id);
      const note = document.getElementById('tripDetailCostNote');
      // Only a text node + a real <button>; no smuggled markup.
      expect(note.querySelector('script')).toBeNull();
      expect(note.querySelector('button[data-nav-jump="settings"]')).not.toBeNull();
    });
  });
});
