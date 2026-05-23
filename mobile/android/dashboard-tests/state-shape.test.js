// Pins the initial shape of window.VoltDashboard.state. The Android side
// reads several of these keys through evaluateJavascript (e.g. lastSampleAt
// for the stale indicator, demoActive for telemetry routing), so a silent
// rename is a real bug.
import { describe, it, expect, beforeEach } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

// Keys core.js seeds into VD.state at IIFE time. If a refactor renames one,
// this test fires before behavioral bugs do.
const REQUIRED_STATE_KEYS = [
  'view',
  'mode',
  'tripFilter',
  'selectedTripId',
  'lastDevice',
  'deviceHistory',
  'storage',
  'trips',
  'insights',
  'appState',
  'demoActive',
  'mapLayer',
  'mapFull',
  'selectedMapSessionId',
  'status',
  'speedHistory',
  'lastSampleAt',
  'rafPending',
  'telemetry',
];

// Telemetry sub-shape from core.js. Same justification — the Android side
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
  beforeEach(() => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    loadDashboard();
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
    expect(state.tripFilter).toBe('all');
    expect(state.demoActive).toBe(false);
    expect(state.mapLayer).toBe('routes');
    expect(state.mapFull).toBe(false);
    // lastSampleAt is the C6 stale-tile clock; it starts at 0 so the first
    // tick reports stale until a real sample arrives.
    expect(state.lastSampleAt).toBe(0);
    expect(state.rafPending).toBe(0);
    expect(Array.isArray(state.speedHistory)).toBe(true);
    expect(state.speedHistory.length).toBe(0);
    expect(Array.isArray(state.deviceHistory)).toBe(true);
  });
});
