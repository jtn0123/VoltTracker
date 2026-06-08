import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

describe('telemetry.ts — stale live data and session reset regressions', () => {
  beforeEach(async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-06-03T12:00:00Z'));
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('does not accept ancient latestTelemetry just because the database has rows', () => {
    const VD = window.VoltDashboard;
    VD.state.storage = { sampleCount: 12 };

    VD.setAppState({
      storage: { sampleCount: 12 },
      latestTelemetry: {
        source: 'obd',
        speedKph: 88,
        updatedAt: Date.now() - 10 * 60_000,
      },
      session: { state: 'idle' },
    });

    expect(VD.state.telemetry.speedKph).toBeNull();
    expect(VD.state.lastSampleAt).toBe(0);
  });

  it('resets live counters when a new real session restarts sample numbering', () => {
    const VD = window.VoltDashboard;
    VD.updateTelemetry({
      source: 'obd',
      sampleCount: 50,
      soc: 72,
      latitude: 32.7,
      longitude: -117.1,
      updatedAt: Date.now(),
    });
    VD.state.sessionDistanceM = 1234;
    VD.state.sessionStartSoc = 72;

    VD.updateTelemetry({
      source: 'obd',
      sampleCount: 1,
      soc: 70,
      latitude: 32.8,
      longitude: -117.2,
      updatedAt: Date.now() + 1000,
    });

    expect(VD.state.sessionDistanceM).toBe(0);
    expect(VD.state.sessionStartSoc).toBe(70);
    expect(VD.state.telemetry.sampleCount).toBe(1);
  });
});
