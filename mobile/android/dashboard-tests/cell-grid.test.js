import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

// 96-cell voltage map scaffold (telemetry.ts#renderCellGrid). Highlights the live
// lowest/highest cell groups until a full per-cell probe is available, and renders
// a full heatmap if telemetry ever carries a cellVoltages array.
describe('cell voltage map', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  it('renders a 96-cell grid highlighting the known lowest/highest cells', () => {
    const VD = window.VoltDashboard;
    VD.updateTelemetry({
      source: 'obd',
      connected: true,
      sampleCount: 1,
      updatedAt: Date.now(),
      minCellNumber: 12,
      maxCellNumber: 80,
      minCellVoltage: 3.95,
      maxCellVoltage: 4.0,
    });
    VD.updateLiveUi();

    const boxes = document.querySelectorAll('#cellGrid .cell-grid-box');
    expect(boxes).toHaveLength(96);
    // Cells are 1-indexed; #12 and #80 are highlighted.
    expect(boxes[11].classList.contains('is-min')).toBe(true);
    expect(boxes[79].classList.contains('is-max')).toBe(true);
    expect(boxes[0].classList.contains('is-unknown')).toBe(true);
    expect(document.getElementById('cellGridBadge').textContent).toBe('2 of 96 known');
    // The card article is visible when there is data to show.
    expect(document.getElementById('cellGridCard').hidden).toBe(false);
  });

  it('hides the whole card (not just the inner grid) when no cell data is known (C5)', () => {
    const VD = window.VoltDashboard;
    // Connected but the car reports no per-cell data and no lowest/highest groups yet.
    VD.updateTelemetry({ source: 'obd', connected: true, sampleCount: 1, updatedAt: Date.now() });
    VD.updateLiveUi();

    const grid = document.getElementById('cellGrid');
    expect(grid.querySelectorAll('.cell-grid-box')).toHaveLength(0);
    expect(grid.hidden).toBe(true);
    // The entire scaffold (header, badge, note) is hidden too — not a perpetual
    // empty card.
    expect(document.getElementById('cellGridCard').hidden).toBe(true);
    expect(document.getElementById('cellGridBadge').textContent).toBe('awaiting probe');
    expect(document.getElementById('cellGridNote').textContent).toContain('full per-cell probe');
  });

  it('re-shows the card once cell data arrives after an empty sample (C5)', () => {
    const VD = window.VoltDashboard;
    // First: no cell data → card hidden.
    VD.updateTelemetry({ source: 'obd', connected: true, sampleCount: 1, updatedAt: Date.now() });
    VD.updateLiveUi();
    expect(document.getElementById('cellGridCard').hidden).toBe(true);

    // Then: the car reports a lowest/highest group → card reappears.
    VD.updateTelemetry({
      source: 'obd',
      connected: true,
      sampleCount: 2,
      updatedAt: Date.now(),
      minCellNumber: 5,
      maxCellNumber: 88,
      minCellVoltage: 3.9,
      maxCellVoltage: 4.0,
    });
    VD.updateLiveUi();
    expect(document.getElementById('cellGridCard').hidden).toBe(false);
    expect(document.getElementById('cellGrid').hidden).toBe(false);
    expect(document.querySelectorAll('#cellGrid .cell-grid-box')).toHaveLength(96);
  });

  it('renders a full heatmap when per-cell voltages are present', () => {
    const VD = window.VoltDashboard;
    const cellVoltages = Array.from({ length: 96 }, (_v, i) => 3.9 + (i % 8) * 0.01);
    VD.updateTelemetry({
      source: 'obd',
      connected: true,
      sampleCount: 1,
      updatedAt: Date.now(),
      cellVoltages,
    });
    VD.updateLiveUi();

    const boxes = document.querySelectorAll('#cellGrid .cell-grid-box');
    expect(boxes).toHaveLength(96);
    // Full mode colors every cell by voltage (inline background-color set).
    expect(boxes[0].style.backgroundColor).not.toBe('');
    expect(document.getElementById('cellGridBadge').textContent).toBe('96 cells');
    expect(document.getElementById('cellGridNote').textContent).toContain('latest cell probe');
    expect(document.getElementById('cellGridCard').hidden).toBe(false);
  });
});
