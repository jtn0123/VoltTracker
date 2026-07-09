import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

// The battery cell-balance summary (telemetry.ts#renderCellBalance) renders the
// one-line spread summary + "Read 96 cells" toggle at the top of the redesigned
// Battery cell balance card (v2 handoff). The full 96-group heatmap it reveals is
// covered in cell-grid.test.js.
describe('battery cell-balance summary', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  it('summarizes the spread and offers the read toggle from live telemetry', () => {
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

    // 30 <= 45 < 60 → "drifting".
    expect(document.getElementById('cellBalanceCopy').textContent).toBe(
      'Δ 45 mV across 96 groups — drifting',
    );
    const toggle = document.getElementById('cellToggleBtn');
    expect(toggle.hidden).toBe(false);
    expect(toggle.textContent).toBe('Read 96 cells');
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
  });

  it('reads "healthy" when cells are tightly balanced', () => {
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
    expect(document.getElementById('cellBalanceCopy').textContent).toBe(
      'Δ 10 mV across 96 groups — healthy',
    );
  });

  it('reads "imbalanced" when the spread is wide', () => {
    const VD = window.VoltDashboard;
    VD.updateTelemetry({
      source: 'obd',
      connected: true,
      sampleCount: 1,
      updatedAt: Date.now(),
      minCellVoltage: 3.9,
      maxCellVoltage: 3.99,
      cellBalanceMv: 90,
    });
    VD.updateLiveUi();
    expect(document.getElementById('cellBalanceCopy').textContent).toBe(
      'Δ 90 mV across 96 groups — imbalanced',
    );
  });

  it('derives the spread from min/max when cellBalanceMv is absent', () => {
    const VD = window.VoltDashboard;
    VD.updateTelemetry({
      source: 'obd',
      connected: true,
      sampleCount: 1,
      updatedAt: Date.now(),
      minCellVoltage: 3.95,
      maxCellVoltage: 3.975,
    });
    VD.updateLiveUi();
    // round((3.975 - 3.95) * 1000) = 25 → healthy (< 30).
    expect(document.getElementById('cellBalanceCopy').textContent).toBe(
      'Δ 25 mV across 96 groups — healthy',
    );
  });

  it('shows the empty prompt and hides the toggle when no cells are reported', () => {
    const VD = window.VoltDashboard;
    VD.updateTelemetry({ source: 'obd', connected: true, sampleCount: 1, updatedAt: Date.now(), speedKph: 30 });
    VD.updateLiveUi();

    expect(document.getElementById('cellBalanceCopy').textContent).toBe(
      'No cell readings yet — connect while the car is awake.',
    );
    expect(document.getElementById('cellToggleBtn').hidden).toBe(true);
  });
});
