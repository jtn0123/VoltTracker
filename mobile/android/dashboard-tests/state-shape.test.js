// Pins the initial shape of window.VoltDashboard.state. The Android side
// reads several of these keys through evaluateJavascript (e.g. lastSampleAt
// for the stale indicator, demoActive for telemetry routing), so a silent
// rename is a real bug.
import { describe, it, expect, beforeEach } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

// Keys core.ts seeds into VD.state at IIFE time. If a refactor renames one,
// this test fires before behavioral bugs do.
const REQUIRED_STATE_KEYS = [
  'view',
  'mode',
  'lastDevice',
  'deviceHistory',
  'storage',
  'trips',
  'insights',
  'tripsReadError',
  'insightsReadError',
  'appState',
  'demoActive',
  'mapLayer',
  'mapFull',
  'mapBrowserOpen',
  'mapFavoritesOnly',
  'mapLongOnly',
  'mapSessionSort',
  'tripReceiptMode',
  'mapRemoteTilesEnabled',
  'selectedMapSessionId',
  'liveRouteStartedAtMs',
  'liveRoutePoints',
  'status',
  'speedHistory',
  'lastSampleAt',
  'rafPending',
  'telemetry',
];

// Telemetry sub-shape from core.ts. Same justification — the Android side
// rebuilds telemetry objects in JSON, and the JS side fans them out into
// these slots.
const REQUIRED_TELEMETRY_KEYS = [
  'speedKph',
  'rpm',
  'voltage',
  'coolantC',
  'loadPct',
  'throttlePct',
  'soc',
  'batteryTemp',
  'powerKw',
  'updatedAt',
  'raw',
];

describe('window.VoltDashboard.state shape', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  it('seeds the documented top-level keys', () => {
    expect(window.VoltDashboard).toBeDefined();
    const state = window.VoltDashboard.state;
    expect(state).toBeDefined();
    for (const key of REQUIRED_STATE_KEYS) {
      expect(state, `state.${key} missing`).toHaveProperty(key);
    }
  });

  it('seeds telemetry with the documented sub-shape', () => {
    const t = window.VoltDashboard.state.telemetry;
    expect(t).toBeDefined();
    for (const key of REQUIRED_TELEMETRY_KEYS) {
      expect(t, `telemetry.${key} missing`).toHaveProperty(key);
    }
  });

  it('seeds the documented initial primitive values', () => {
    const state = window.VoltDashboard.state;
    expect(state.view).toBe('drive');
    expect(state.mode).toBe('ev');
    expect(state.demoActive).toBe(false);
    // Default layer is "eff" so the route opens already colored by efficiency;
    // the user can still tap Routes / Heat / Stops in the layer tabs.
    expect(state.mapLayer).toBe('eff');
    expect(state.mapFull).toBe(false);
    expect(state.mapBrowserOpen).toBe(false);
    // Booleans, not just present. map.ts gates each filter on `=== true`, so seeding either
    // of these as undefined or a string would silently leave the filter permanently off —
    // and REQUIRED_STATE_KEYS above only checks that the key exists.
    expect(state.mapFavoritesOnly).toBe(false);
    expect(state.mapLongOnly).toBe(false);
    // Must match the sort button marked `is-active` in map.html — the class is now an
    // output of this value, so a mismatch would render the wrong button highlighted.
    expect(state.mapSessionSort).toBe('recent');
    expect(state.tripReceiptMode).toBe(false);
    expect(state.mapRemoteTilesEnabled).toBe(true);
    expect(state.liveRouteStartedAtMs).toBe(null);
    expect(Array.isArray(state.liveRoutePoints)).toBe(true);
    expect(state.liveRoutePoints.length).toBe(0);
    // lastSampleAt is the stale-tile clock; it starts at 0 so the first
    // tick reports stale until a real sample arrives.
    expect(state.lastSampleAt).toBe(0);
    // rafPending is the shared render pass's frame handle (core.ts). It is no longer
    // guaranteed to be 0 straight after load: a lazy chunk hydrating on boot
    // (maintenance-panel's loadMaintenanceLog) writes state, which legitimately queues a
    // repaint. The invariant that matters is that frames are not LEAKED — flushing settles
    // it back to 0 — so assert that rather than a value that now depends on chunk timing.
    expect(typeof state.rafPending).toBe('number');
    window.VoltDashboard.flushRender();
    expect(state.rafPending).toBe(0);
    expect(Array.isArray(state.speedHistory)).toBe(true);
    expect(state.speedHistory.length).toBe(0);
    expect(Array.isArray(state.deviceHistory)).toBe(true);
  });
});
