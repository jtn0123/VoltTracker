// backfillTelemetry — the resume catch-up path (docs/bridge-abi.md).
//
// While the Activity is paused the WebView is suspended and the broadcast receiver is
// unregistered, so live samples never reach the charts; the native side buffers them in
// LiveDashboardSnapshot's ring and replays them here as one batch on resume/handshake.
// These tests pin the contract: histories/distance/route rebuild in timestamp order,
// replay is idempotent, and the batch never impersonates fresh live data (no lastSampleAt
// bump, no live-tile writes).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

const T0 = Date.parse('2026-06-03T12:00:00Z');

function sample(offsetMs, fields = {}) {
  return { source: 'obd', updatedAt: T0 + offsetMs, ...fields };
}

describe('telemetry.ts — backfillTelemetry resume catch-up', () => {
  beforeEach(async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(T0));
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

  it('rebuilds the speed/power/SOC histories in timestamp order, even from a shuffled batch', () => {
    const VD = window.VoltDashboard;
    VD.backfillTelemetry({
      samples: [
        sample(3000, { speedKph: 113, powerKw: 30, soc: 70 }),
        sample(1000, { speedKph: 32, powerKw: 10, soc: 72 }),
        sample(2000, { speedKph: 80, powerKw: 20, soc: 71 }),
      ],
    });

    expect(VD.state.speedHistory).toEqual([32, 80, 113]);
    expect(VD.state.powerHistory).toEqual([10, 20, 30]);
    expect(VD.state.socHistory).toEqual([72, 71, 70]);
    // Session-start SOC anchors to the oldest replayed sample, same as the live path.
    expect(VD.state.sessionStartSoc).toBe(72);
  });

  it('is idempotent: re-delivering the same batch (double resume) adds nothing', () => {
    const VD = window.VoltDashboard;
    const batch = {
      samples: [sample(1000, { speedKph: 32 }), sample(2000, { speedKph: 80 })],
    };
    VD.backfillTelemetry(batch);
    VD.backfillTelemetry(batch);

    expect(VD.state.speedHistory).toEqual([32, 80]);
  });

  it('never impersonates fresh live data: tiles and the staleness clock stay untouched', () => {
    const VD = window.VoltDashboard;
    VD.backfillTelemetry({ samples: [sample(1000, { speedKph: 32, soc: 72 })] });

    expect(VD.state.lastSampleAt).toBe(0);
    expect(VD.state.telemetry.speedKph == null).toBe(true);
  });

  it('a backfilled window then the fresh updateTelemetry replay yields exactly one extra point', () => {
    const VD = window.VoltDashboard;
    VD.backfillTelemetry({
      samples: [sample(1000, { speedKph: 32 }), sample(2000, { speedKph: 80 })],
    });
    VD.updateTelemetry(sample(3000, { speedKph: 113 }));

    expect(VD.state.speedHistory).toEqual([32, 80, 113]);
    expect(VD.state.lastSampleAt).toBeGreaterThan(0);

    // Samples at or before the live sample's timestamp are re-deliveries, not history.
    VD.backfillTelemetry({
      samples: [sample(2000, { speedKph: 80 }), sample(3000, { speedKph: 113 })],
    });
    expect(VD.state.speedHistory).toEqual([32, 80, 113]);
  });

  it('accumulates session distance and advances the live route across the batch', () => {
    const VD = window.VoltDashboard;
    const updateLivePosition = vi.fn();
    VD.updateLivePosition = updateLivePosition;

    // ~111m per 0.001° latitude step: three accepted hops.
    VD.backfillTelemetry({
      samples: [
        sample(1000, { latitude: 34.05, longitude: -118.25 }),
        sample(2000, { latitude: 34.051, longitude: -118.25 }),
        sample(3000, { latitude: 34.052, longitude: -118.25 }),
      ],
    });

    expect(VD.state.sessionDistanceM).toBeGreaterThan(200);
    expect(VD.state.sessionDistanceM).toBeLessThan(250);
    expect(updateLivePosition).toHaveBeenCalledTimes(3);
    expect(updateLivePosition).toHaveBeenLastCalledWith(34.052, -118.25);
  });

  it('is isolated from demo mode: no-op while demo is active, demo-sourced samples dropped', () => {
    const VD = window.VoltDashboard;
    VD.state.demoActive = true;
    VD.backfillTelemetry({ samples: [sample(1000, { speedKph: 32 })] });
    expect(VD.state.speedHistory).toEqual([]);

    VD.state.demoActive = false;
    VD.backfillTelemetry({
      samples: [
        { source: 'demo', updatedAt: T0 + 1000, speedKph: 200 },
        sample(2000, { speedKph: 80 }),
      ],
    });
    expect(VD.state.speedHistory).toEqual([80]);
  });

  it('fails soft on malformed payloads and garbage entries', () => {
    const VD = window.VoltDashboard;
    vi.spyOn(console, 'warn').mockImplementation(() => {});

    expect(() => VD.backfillTelemetry(null)).not.toThrow();
    expect(() => VD.backfillTelemetry('null')).not.toThrow();
    expect(() => VD.backfillTelemetry('{"samples":')).not.toThrow();
    expect(() => VD.backfillTelemetry({ samples: 'nope' })).not.toThrow();
    expect(() =>
      VD.backfillTelemetry({
        samples: [null, 42, 'x', [], { speedKph: 50 }, sample(1000, { speedKph: 32 })],
      })
    ).not.toThrow();

    // Only the one valid, timestamped sample lands.
    expect(VD.state.speedHistory).toEqual([32]);
  });

  it('respects the bounded chart windows on oversized batches', () => {
    const VD = window.VoltDashboard;
    const samples = [];
    for (let i = 1; i <= 320; i += 1) {
      samples.push(sample(i * 1000, { speedKph: i, soc: i / 4 }));
    }
    VD.backfillTelemetry({ samples });

    // speedHistory window is 48, socHistory 240 — both hold only the newest values.
    expect(VD.state.speedHistory).toHaveLength(48);
    expect(VD.state.speedHistory[47]).toBe(320);
    expect(VD.state.speedHistory[0]).toBe(320 - 47);
    expect(VD.state.socHistory).toHaveLength(240);
  });

  it('is reachable through the native callback surface', () => {
    const native = window.VoltTrackerNative;
    expect(typeof native.backfillTelemetry).toBe('function');

    native.backfillTelemetry(JSON.stringify({ samples: [sample(1000, { speedKph: 32 })] }));
    expect(window.VoltDashboard.state.speedHistory).toEqual([32]);
  });
});
