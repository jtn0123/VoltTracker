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

  it('normalizes null object payloads at every telemetry state boundary', () => {
    const VD = window.VoltDashboard;

    expect(() => VD.setStatus('null')).not.toThrow();
    expect(() => VD.setAppState('null')).not.toThrow();
    expect(() => VD.updateTelemetry('null')).not.toThrow();
    expect(VD.state.status).toEqual({});
    expect(VD.state.appState).toEqual({});
    expect(VD.state.telemetry).toEqual(expect.any(Object));
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

  it('renders the speed hero as "--" (not a false 0) for a missing reading', async () => {
    const { initialTelemetryState } = await import(
      '../app/src/main/dashboard-src/js/telemetry-state.ts'
    );
    const VD = window.VoltDashboard;
    // Boot/disconnect seed: speedKph is null. Number(null) === 0 is finite, which
    // used to render a real-looking "0 mph" with a filled meter while every
    // neighboring tile showed "--".
    VD.state.telemetry = initialTelemetryState();
    VD.updateLiveUi();

    // The tile appends a visually-hidden "(stale)" marker; assert the value only.
    const speedText = () => document.getElementById('speedValue').firstChild.textContent;
    expect(speedText()).toBe('--');
    expect(document.getElementById('speedKph').textContent).toMatch(/^-- (mph|km\/h)/);
    const meter = document.getElementById('speedValue').closest("[role='meter']");
    expect(meter).not.toBeNull();
    expect(meter.getAttribute('aria-valuenow')).toBeNull();
    expect(meter.getAttribute('aria-valuetext')).toBe('no data yet');

    // A genuine stopped-car zero (numeric) must still read "0".
    VD.state.telemetry = { ...initialTelemetryState(), speedKph: 0 };
    VD.updateLiveUi();
    expect(speedText()).toBe('0');
    expect(meter.getAttribute('aria-valuenow')).toBe('0');
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

  it('preserves the session baseline across a mid-drive Bluetooth blip', () => {
    const VD = window.VoltDashboard;
    // Enter an active drive (this first activation resets), then stamp a baseline.
    VD.setStatus({ state: 'connected', detail: 'Live OBD data.' });
    VD.state.sessionDistanceM = 4200;
    VD.state.sessionStartSoc = 76;
    VD.state.speedHistory = [10, 20, 30];

    // A transient blip drops through an inactive error state then re-connects.
    // The same native session is resuming, so the JS baseline must survive — only
    // a genuine terminal stop (or a renumbered native session) may wipe it.
    VD.setStatus({ state: 'error', detail: 'Adapter dropped.' });
    VD.setStatus({ state: 'connected', detail: 'Reconnected.' });

    expect(VD.state.sessionDistanceM).toBe(4200);
    expect(VD.state.sessionStartSoc).toBe(76);
    expect(VD.state.speedHistory).toEqual([10, 20, 30]);
  });

  it('resets the session baseline after a genuine stop then reconnect', () => {
    const VD = window.VoltDashboard;
    VD.setStatus({ state: 'connected', detail: 'Live OBD data.' });
    VD.state.sessionDistanceM = 4200;
    VD.state.sessionStartSoc = 76;

    // A genuine terminal stop (idle) followed by a fresh connect is a new drive.
    VD.setStatus({ state: 'idle', detail: 'Disconnected.' });
    VD.setStatus({ state: 'connected', detail: 'Live OBD data.' });

    expect(VD.state.sessionDistanceM).toBe(0);
    expect(VD.state.sessionStartSoc).toBeNull();
  });
});
