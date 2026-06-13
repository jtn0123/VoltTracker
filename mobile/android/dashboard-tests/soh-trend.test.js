import { beforeEach, describe, expect, it, vi } from 'vitest';

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
});
