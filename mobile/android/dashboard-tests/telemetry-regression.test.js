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
      latitude: 34.05,
      longitude: -118.25,
      updatedAt: Date.now(),
    });
    VD.state.sessionDistanceM = 1234;
    VD.state.sessionStartSoc = 72;

    VD.updateTelemetry({
      source: 'obd',
      sampleCount: 1,
      soc: 70,
      latitude: 34.16,
      longitude: -118.32,
      updatedAt: Date.now() + 1000,
    });

    expect(VD.state.sessionDistanceM).toBe(0);
    expect(VD.state.sessionStartSoc).toBe(70);
    expect(VD.state.telemetry.sampleCount).toBe(1);
  });

  it('resetTelemetry restores the exact boot-time telemetry shape', async () => {
    // Both core.ts (state seed / clearDemoTelemetry) and telemetry.ts
    // (resetTelemetry) build the empty sample from the shared factory in
    // telemetry-state.ts. This pins that reset deep-equals init — the copies
    // had drifted before (reset dropped the `source` key entirely).
    const { initialTelemetryState } = await import(
      '../app/src/main/dashboard-src/js/telemetry-state.ts'
    );
    const VD = window.VoltDashboard;

    VD.updateTelemetry({
      source: 'obd',
      sampleCount: 7,
      speedKph: 42,
      soc: 68,
      raw: '41 0D 2A',
      updatedAt: Date.now(),
    });
    expect(VD.state.telemetry.speedKph).toBe(42);

    VD.resetTelemetry();

    expect(VD.state.telemetry).toEqual(initialTelemetryState());
    expect(VD.state.speedHistory).toEqual([]);
    expect(VD.state.lastSampleAt).toBe(0);
  });
});
