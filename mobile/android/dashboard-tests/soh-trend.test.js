import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

const DAY = 86_400_000;

// Battery state-of-health trend chart on the Battery tab
// (storage-status.ts#renderBatterySohTrend), fed by bridge.getBatterySohHistory().
describe('battery SOH trend', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
  });

  afterEach(() => vi.useRealTimers());

  it('charts the SOH history and summary stats when readings exist', async () => {
    const t0 = Date.now() - 30 * DAY;
    const history = [
      { capturedAtMs: t0, sohPct: 96, capacityAh: 44.2 },
      { capturedAtMs: t0 + 15 * DAY, sohPct: 95.1, capacityAh: 43.8 },
      { capturedAtMs: t0 + 30 * DAY, sohPct: 94.5, capacityAh: 43.5 },
    ];
    await loadDashboard({
      bridge: createVoltBridgeFixture({ getBatterySohHistory: () => JSON.stringify(history) }),
    });
    const VD = window.VoltDashboard;

    VD.renderRealV2Ui();

    expect(document.getElementById('sohTrendChart').hidden).toBe(false);
    expect(document.getElementById('sohTrendStats').hidden).toBe(false);
    expect(document.getElementById('sohTrendEmpty').hidden).toBe(true);
    expect(document.getElementById('sohTrendTitle').textContent).toBe('Pack state-of-health over time');
    expect(document.getElementById('sohTrendLatest').textContent).toBe('94.5%');
    expect(document.getElementById('sohTrendCapacity').textContent).toBe('43.5 Ah');
    expect(document.getElementById('sohTrendCount').textContent).toBe('3');
    // An SVG line was drawn from the points.
    expect(document.querySelector('#sohTrendChart .soh-line')).not.toBeNull();
  });

  it('drops capacity-only rows (null soh) and shows -- for a missing capacity', async () => {
    const t0 = Date.now() - 20 * DAY;
    // Native emits JSON null for soh_pct on capacity-only rows and for
    // capacity_ah on soh-only rows. Number(null) === 0 would otherwise plant a
    // spurious 0% SOH point and a "0.0 Ah" capacity readout for a healthy pack.
    const history = [
      { capturedAtMs: t0, sohPct: 94, capacityAh: 43.5 },
      { capturedAtMs: t0 + 5 * DAY, sohPct: null, capacityAh: 43.0 }, // capacity-only -> dropped
      { capturedAtMs: t0 + 10 * DAY, sohPct: 93, capacityAh: null }, // latest soh, no capacity
    ];
    await loadDashboard({
      bridge: createVoltBridgeFixture({ getBatterySohHistory: () => JSON.stringify(history) }),
    });
    const VD = window.VoltDashboard;

    VD.renderRealV2Ui();

    expect(document.getElementById('sohTrendChart').hidden).toBe(false);
    // The null-soh row is excluded: two real readings, latest 93.0% (no fake 0.0%).
    expect(document.getElementById('sohTrendCount').textContent).toBe('2');
    expect(document.getElementById('sohTrendLatest').textContent).toBe('93.0%');
    // The latest reading has no capacity, so the readout falls back to -- not "0.0 Ah".
    expect(document.getElementById('sohTrendCapacity').textContent).toBe('--');
  });

  it('drops malformed native rows without breaking the dashboard callback', async () => {
    const t0 = Date.now() - 10 * DAY;
    await loadDashboard({
      bridge: createVoltBridgeFixture({ getBatterySohHistory: () => '[]' }),
    });
    const history = [
      null,
      12,
      'bad row',
      [{ capturedAtMs: t0, sohPct: 1, capacityAh: 1 }],
      { capturedAtMs: t0, sohPct: 96, capacityAh: 44.2 },
      { capturedAtMs: t0 + 10 * DAY, sohPct: 95.5, capacityAh: 43.9 },
    ];

    expect(() => window.VoltTrackerNative.setBatterySohHistory(JSON.stringify(history))).not.toThrow();
    expect(document.getElementById('sohTrendCount').textContent).toBe('2');
    expect(document.getElementById('sohTrendLatest').textContent).toBe('95.5%');
  });

  it('requests SOH history asynchronously when the native bridge supports it', async () => {
    const t0 = Date.now() - 10 * DAY;
    const history = [
      { capturedAtMs: t0, sohPct: 96, capacityAh: 44.2 },
      { capturedAtMs: t0 + 10 * DAY, sohPct: 95.5, capacityAh: 43.9 },
    ];
    const getBatterySohHistory = vi.fn(() => '[]');
    const requestBatterySohHistory = vi.fn(() => true);
    await loadDashboard({
      bridge: createVoltBridgeFixture({ getBatterySohHistory, requestBatterySohHistory }),
    });
    const VD = window.VoltDashboard;

    window.VoltTrackerNative.setBatterySohHistory('[]');
    requestBatterySohHistory.mockClear();
    getBatterySohHistory.mockClear();
    VD.setStorage({ batterySnapshotCount: 1 });
    VD.renderRealV2Ui();

    expect(requestBatterySohHistory).toHaveBeenCalledTimes(1);
    expect(getBatterySohHistory).not.toHaveBeenCalled();

    window.VoltTrackerNative.setBatterySohHistory(JSON.stringify(history));

    expect(document.getElementById('sohTrendChart').hidden).toBe(false);
    expect(document.getElementById('sohTrendLatest').textContent).toBe('95.5%');
    expect(document.querySelector('#sohTrendChart .soh-line')).not.toBeNull();
  });

  it('releases a lost SOH callback and retries after the normal refresh interval', async () => {
    const requestBatterySohHistory = vi.fn(() => true);
    await loadDashboard({
      bridge: createVoltBridgeFixture({ requestBatterySohHistory }),
    });
    const VD = window.VoltDashboard;
    window.VoltTrackerNative.setBatterySohHistory('[]');
    requestBatterySohHistory.mockClear();
    vi.useFakeTimers();

    VD.setStorage({ batterySnapshotCount: 1 });
    VD.renderRealV2Ui();
    expect(requestBatterySohHistory).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(5_000);
    await vi.advanceTimersByTimeAsync(30_000);
    VD.renderRealV2Ui();

    expect(requestBatterySohHistory).toHaveBeenCalledTimes(2);
  });

  it('shows the empty prompt until at least two readings exist', async () => {
    await loadDashboard({
      bridge: createVoltBridgeFixture({ getBatterySohHistory: () => '[]' }),
    });
    const VD = window.VoltDashboard;

    VD.renderRealV2Ui();

    expect(document.getElementById('sohTrendChart').hidden).toBe(true);
    expect(document.getElementById('sohTrendEmpty').hidden).toBe(false);
    expect(document.getElementById('sohTrendTitle').textContent).toBe('No battery-health readings yet');
    expect(document.getElementById('sohTrendLatest').textContent).toBe('--');
  });

  it('throttles the SQLite-backed history fetch across rapid storage refreshes', async () => {
    const getBatterySohHistory = vi.fn(() => '[]');
    await loadDashboard({ bridge: createVoltBridgeFixture({ getBatterySohHistory }) });
    const VD = window.VoltDashboard;

    // renderRealV2Ui runs on every app-state broadcast; the SOH fetch must not.
    VD.renderRealV2Ui();
    VD.renderRealV2Ui();
    VD.renderRealV2Ui();

    expect(getBatterySohHistory).toHaveBeenCalledTimes(1);
  });

  it('reports a blocked status when the SOH history bridge read throws', async () => {
    const logClientError = vi.fn();
    const getBatterySohHistory = vi.fn(() => {
      throw new Error('soh read denied');
    });
    await loadDashboard({
      bridge: createVoltBridgeFixture({ getBatterySohHistory, logClientError }),
    });
    const VD = window.VoltDashboard;

    expect(() => VD.renderRealV2Ui()).not.toThrow();
    expect(() => VD.renderRealV2Ui()).not.toThrow();

    expect(VD.state.status).toMatchObject({
      state: 'blocked',
      detail: 'Could not read battery health history.',
    });
    expect(getBatterySohHistory).toHaveBeenCalledTimes(1);
    expect(logClientError).toHaveBeenCalledWith(
      'battery_soh_history_failed',
      'Could not read battery health history.',
    );
  });
});
