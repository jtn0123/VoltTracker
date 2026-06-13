import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

// The battery cell-balance graphic (telemetry.ts#renderCellBalance) renders the
// pack-balance slice of Phase E: lowest/highest cell-group voltage, their spread,
// which cells, and SOC variation — live from telemetry, on the Battery tab.
describe('battery cell-balance graphic', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  it('graphs the spread, cells, and SOC variation from live telemetry', () => {
    const VD = window.VoltDashboard;
    VD.updateTelemetry({
      source: 'obd',
      connected: true,
      sampleCount: 1,
      updatedAt: Date.now(),
      minCellVoltage: 3.95,
      maxCellVoltage: 3.995,
      cellBalanceMv: 45,
      minCellNumber: 12,
      maxCellNumber: 48,
      socVariationPct: 1.2,
    });
    VD.updateLiveUi();

    expect(document.getElementById('cellBalanceGraphic').hidden).toBe(false);
    expect(document.getElementById('cellBalanceEmpty').hidden).toBe(true);

    const delta = document.getElementById('cellBalanceDelta');
    expect(delta.textContent).toBe('45 mV spread');
    expect(delta.dataset.tone).toBe('warn'); // 30 <= 45 < 60

    expect(document.getElementById('cellBalanceMin').textContent).toBe('3.950 V · #12');
    expect(document.getElementById('cellBalanceMax').textContent).toBe('3.995 V · #48');
    expect(document.getElementById('cellBalanceSoc').textContent).toBe('1.2%');

    // The spread is plotted on the track.
    const fill = document.getElementById('cellBalanceFill');
    expect(fill.style.left).toMatch(/%$/);
    expect(fill.style.width).toMatch(/%$/);
  });

  it('tones the spread badge green when cells are tightly balanced', () => {
    const VD = window.VoltDashboard;
    VD.updateTelemetry({
      source: 'obd',
      connected: true,
      sampleCount: 1,
      updatedAt: Date.now(),
      minCellVoltage: 3.98,
      maxCellVoltage: 3.99,
      cellBalanceMv: 10,
    });
    VD.updateLiveUi();
    expect(document.getElementById('cellBalanceDelta').dataset.tone).toBe('ok');
  });

  it('shows the empty prompt when the car is not reporting cells', () => {
    const VD = window.VoltDashboard;
    VD.updateTelemetry({ source: 'obd', connected: true, sampleCount: 1, updatedAt: Date.now(), speedKph: 30 });
    VD.updateLiveUi();

    expect(document.getElementById('cellBalanceGraphic').hidden).toBe(true);
    expect(document.getElementById('cellBalanceEmpty').hidden).toBe(false);
    expect(document.getElementById('cellBalanceTitle').textContent).toBe('No live cell data yet');
    expect(document.getElementById('cellBalanceDelta').textContent).toBe('-- mV');
  });
});
