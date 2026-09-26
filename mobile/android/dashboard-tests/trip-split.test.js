// "Split trip here" on an in-trip Park stop + merge back (trip-split.ts + the
// trip-detail wiring in map.ts). Native persists the split and answers with
// VoltTrackerNative.tripSplitChanged; Demo / Testing re-cuts its in-memory
// sample drives and never reaches the bridge.
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';
import {
  mergeDemoTrip,
  parseTripSplitChange,
  splitDemoTrip,
  userSplitKey,
} from '../app/src/main/dashboard-src/js/trip-split.ts';

const BASE = 1_700_000_000_000;
const STEP = 30_000;
// 20 samples (10 min); Park from sample 8 to sample 12 (2 min) — an in-trip stop.
const STOP = { startMs: BASE + 8 * STEP, endMs: BASE + 12 * STEP };

function points() {
  return Array.from({ length: 20 }, (_, i) => ({
    lat: 34.05 + i * 0.002,
    lng: -118.25,
    atMs: BASE + i * STEP,
    speedMps: 12,
    altM: 100,
    eff: 3.5,
  }));
}

function route(id, extra = {}) {
  const pts = points();
  return {
    _effDone: true,
    session: { id, mode: 'drive', adapterName: 'Adapter', startedAtMs: BASE, endedAtMs: BASE + 19 * STEP },
    points: pts,
    socTrack: pts.map((p) => ({ atMs: p.atMs, soc: 80 })),
    pointCount: pts.length,
    distanceMeters: 4000,
    parkStops: [{ ...STOP, durationMs: STOP.endMs - STOP.startMs, doorOpened: false }],
    ...extra,
  };
}

function trip(id, extra = {}) {
  return {
    id,
    sessionId: id,
    startedAtMs: BASE,
    endedAtMs: BASE + 19 * STEP,
    pointCount: 20,
    hasRoute: true,
    distanceMeters: 4000,
    adapterName: 'Adapter',
    energyKwh: 2,
    label: 'Commute',
    favorite: true,
    ...extra,
  };
}

// Each point is 0.002 deg of latitude apart; count the gaps so the split
// distances are deterministic without pulling in haversine.
const pointGaps = (pts) => Math.max(0, pts.length - 1) * 100;

describe('trip-split.ts (pure)', () => {
  it('parses only a usable native tripSplitChanged answer', () => {
    expect(parseTripSplitChange({ splitKey: '7:1:2', merged: false, routeKeys: ['7:0:1', ' 7:3:4 ', ''] })).toEqual({
      splitKey: '7:1:2',
      merged: false,
      routeKeys: ['7:0:1', '7:3:4'],
    });
    expect(parseTripSplitChange({ splitKey: '7:1:2', merged: true })).toEqual({ splitKey: '7:1:2', merged: true, routeKeys: [] });
    expect(parseTripSplitChange({ routeKeys: ['7:0:1'] })).toBeNull();
    expect(parseTripSplitChange(null)).toBeNull();
    expect(parseTripSplitChange('7:1:2')).toBeNull();
  });

  it('reads the merge key on either side of a route', () => {
    const split = { key: '7:1:2', startMs: 1, endMs: 2 };
    expect(userSplitKey({ userSplitAfter: split }, 'after')).toBe('7:1:2');
    expect(userSplitKey({ userSplitAfter: split }, 'before')).toBe('');
    expect(userSplitKey(null, 'before')).toBe('');
  });

  it('splits at the stop: the first half keeps label + favorite, the second starts fresh', () => {
    const other = route('9');
    const result = splitDemoTrip([route('42'), other], [trip('42'), trip('9', { label: 'Gym' })], '42', STOP, pointGaps);

    expect(result).not.toBeNull();
    const [first, second, untouched] = result.routes;
    const splitKey = `42:${STOP.startMs}:${STOP.endMs}`;
    expect(result.change).toEqual({
      splitKey,
      merged: false,
      routeKeys: [`42:${BASE}:${STOP.startMs}`, `42:${STOP.endMs + STEP}:${BASE + 19 * STEP}`],
    });
    expect(untouched).toBe(other);
    // The native window rule: first ends at the stop start, second starts after its end.
    expect(first.points.at(-1).atMs).toBe(STOP.startMs);
    expect(second.points[0].atMs).toBeGreaterThan(STOP.endMs);
    expect(first.socTrack.every((s) => s.atMs <= STOP.startMs)).toBe(true);
    expect(first.parkStops).toEqual([]);
    expect(second.parkStops).toEqual([]);
    expect(first.userSplitAfter).toEqual({ key: splitKey, ...STOP });
    expect(second.userSplitBefore).toEqual({ key: splitKey, ...STOP });
    expect(first.userSplitBefore).toBeUndefined();
    expect(second.userSplitAfter).toBeUndefined();

    const [firstTrip, secondTrip, otherTrip] = result.trips;
    expect(firstTrip).toMatchObject({ id: result.change.routeKeys[0], label: 'Commute', favorite: true, endedAtMs: STOP.startMs });
    expect(secondTrip).toMatchObject({ id: result.change.routeKeys[1], label: '', favorite: false });
    expect(firstTrip.energyKwh + secondTrip.energyKwh).toBeCloseTo(2, 1);
    expect(otherTrip.label).toBe('Gym');
  });

  it('refuses an unknown trip or a span that is not one of its Park stops', () => {
    expect(splitDemoTrip([route('42')], [trip('42')], '41', STOP, pointGaps)).toBeNull();
    expect(splitDemoTrip([route('42')], [trip('42')], '42', { ...STOP, endMs: STOP.endMs + 1 }, pointGaps)).toBeNull();
    // A stop at the very start would leave an empty first half.
    const edge = { startMs: BASE - STEP, endMs: BASE + 19 * STEP };
    expect(splitDemoTrip([route('42', { parkStops: [edge] })], [trip('42')], '42', edge, pointGaps)).toBeNull();
  });

  it('merges back: one trip again, with the first half label and the stop restored', () => {
    const split = splitDemoTrip([route('42')], [trip('42')], '42', STOP, pointGaps);
    const [firstKey, secondKey] = split.change.routeKeys;
    const renamed = split.trips.map((t) => (t.id === firstKey ? { ...t, label: 'Morning', favorite: false } : { ...t, label: 'Errand', favorite: true }));

    const merged = mergeDemoTrip(split.routes, renamed, split.change.splitKey, pointGaps);

    expect(merged.change.merged).toBe(true);
    expect(merged.routes).toHaveLength(1);
    expect(merged.trips).toHaveLength(1);
    const [only] = merged.routes;
    expect(only.session.id).toBe(merged.change.routeKeys[0]);
    expect(only.parkStops).toEqual([{ ...STOP, durationMs: STOP.endMs - STOP.startMs, doorOpened: false }]);
    expect(only.userSplitAfter).toBeUndefined();
    expect(merged.trips[0]).toMatchObject({ label: 'Morning', favorite: false });
    expect(merged.trips[0].id).not.toBe(secondKey);
    expect(mergeDemoTrip(split.routes, renamed, '42:1:2', pointGaps)).toBeNull();
  });
});

describe('trip detail: Split trip here / merge back', () => {
  let VD;

  async function boot(bridgeOverrides = {}) {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    window.localStorage.clear();
    if (typeof Element.prototype.scrollIntoView !== 'function') {
      Element.prototype.scrollIntoView = () => {};
    }
    const bridge = createVoltBridgeFixture(bridgeOverrides);
    await loadDashboard({ bridge });
    await window.VoltDashboard.ensureMapModule();
    VD = window.VoltDashboard;
    return bridge;
  }

  function seed(routes, trips) {
    VD.state.trips = trips;
    VD.state.storage = { recentRoutes: routes };
    VD.renderMap();
  }

  const splitButtons = () => Array.from(document.querySelectorAll('#tripDetailStops .trip-detail-stop-split'));

  beforeEach(() => {
    VD = null;
  });

  it('offers the split on Park stop rows only and sends the stop to the bridge', async () => {
    const splitTripAtStop = vi.fn();
    await boot({ splitTripAtStop });
    seed([route('42')], [trip('42')]);
    VD.openTripDetail('42');

    const buttons = splitButtons();
    expect(buttons).toHaveLength(1);
    expect(buttons[0].closest('.trip-detail-stop-row').dataset.stopKind).toBe('park');
    expect(buttons[0].textContent).toBe('Split trip here');
    buttons[0].click();
    expect(splitTripAtStop).toHaveBeenCalledWith('42', String(STOP.startMs), String(STOP.endMs));
    expect(document.getElementById('tripDetailMerge').hidden).toBe(true);
  });

  it('hides the split when the bridge cannot split (older app) and demo is off', async () => {
    await boot({ splitTripAtStop: undefined });
    seed([route('42')], [trip('42')]);
    VD.openTripDetail('42');
    expect(document.querySelectorAll('#tripDetailStops .trip-detail-stop-row')).toHaveLength(1);
    expect(splitButtons()).toHaveLength(0);
  });

  it('shows merge buttons for the user splits bounding a trip and sends the split key', async () => {
    const mergeTripSplit = vi.fn();
    await boot({ mergeTripSplit });
    const edge = { key: '42:500:600', startMs: 500, endMs: 600 };
    seed([route('42', { userSplitAfter: edge, parkStops: [] })], [trip('42')]);
    VD.openTripDetail('42');

    expect(document.getElementById('tripDetailMerge').hidden).toBe(false);
    expect(document.getElementById('tripDetailMergePrev').hidden).toBe(true);
    const next = document.getElementById('tripDetailMergeNext');
    expect(next.hidden).toBe(false);
    next.click();
    expect(mergeTripSplit).toHaveBeenCalledWith('42:500:600');
  });

  it('moves the open sheet onto the first half when native answers tripSplitChanged', async () => {
    const requestTrips = vi.fn(() => true);
    await boot({ requestTrips });
    seed([route('42')], [trip('42')]);
    VD.openTripDetail('42');
    const split = splitDemoTrip([route('42')], [trip('42')], '42', STOP, pointGaps);
    VD.state.storage = { recentRoutes: split.routes };
    VD.state.trips = split.trips;

    window.VoltTrackerNative.tripSplitChanged(JSON.stringify(split.change));
    await vi.waitFor(() => expect(requestTrips).toHaveBeenCalled());

    expect(document.getElementById('tripDetailSheet').hidden).toBe(false);
    expect(document.getElementById('tripDetailMergeNext').hidden).toBe(false);
    expect(splitButtons()).toHaveLength(0);
  });

  it('closes the sheet when the trip that now holds it is not loaded yet, and ignores junk', async () => {
    await boot();
    seed([route('42')], [trip('42')]);
    VD.openTripDetail('42');

    window.VoltTrackerNative.tripSplitChanged('not json');
    expect(document.getElementById('tripDetailSheet').hidden).toBe(false);

    window.VoltTrackerNative.tripSplitChanged(JSON.stringify({ splitKey: '42:1:2', merged: false, routeKeys: ['42:0:1'] }));
    expect(document.getElementById('tripDetailSheet').hidden).toBe(true);
  });

  it('Demo / Testing splits and merges its own sample drive without the bridge', async () => {
    const splitTripAtStop = vi.fn();
    const mergeTripSplit = vi.fn();
    await boot({ splitTripAtStop, mergeTripSplit });
    VD.loadDemoScenario('typical');
    expect(VD.isDemoActive()).toBe(true);
    const before = VD.state.trips.length;
    const parked = VD.state.storage.recentRoutes.find((r) => Array.isArray(r.parkStops) && r.parkStops.length);
    expect(parked).toBeDefined();
    const key = String(parked.session.id);
    VD.openTripDetail(key);

    splitButtons()[0].click();

    expect(splitTripAtStop).not.toHaveBeenCalled();
    expect(VD.state.trips).toHaveLength(before + 1);
    expect(VD.state.demoPreviewTrips).toBe(VD.state.trips);
    expect(document.getElementById('tripDetailSheet').hidden).toBe(false);
    const next = document.getElementById('tripDetailMergeNext');
    expect(next.hidden).toBe(false);

    next.click();

    expect(mergeTripSplit).not.toHaveBeenCalled();
    expect(VD.state.trips).toHaveLength(before);
    expect(document.getElementById('tripDetailMergeNext').hidden).toBe(true);
    expect(splitButtons()).toHaveLength(1);
  });
});
