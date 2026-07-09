import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

// 96-group voltage heatmap (telemetry.ts#renderCellGrid), revealed by the
// "Read 96 cells" toggle. Closed by default; seeds the live lowest/highest groups
// until a full per-cell probe (a live cellVoltages array or a persisted snapshot)
// tints every group by its deviation from the pack mean.
describe('cell voltage heatmap', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  it('stays collapsed until the toggle opens it', () => {
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

    // Toggle is offered, but the heatmap section is hidden with no boxes rendered.
    expect(document.getElementById('cellToggleBtn').hidden).toBe(false);
    expect(document.getElementById('cellGridCard').hidden).toBe(true);
    expect(document.getElementById('cellGrid').hidden).toBe(true);
    expect(document.querySelectorAll('#cellGrid .cell-grid-box')).toHaveLength(0);
  });

  it('reveals a 96-group heatmap seeding the known lowest/highest groups when opened', () => {
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
    VD.setCellGridOpen(true);

    const boxes = document.querySelectorAll('#cellGrid .cell-grid-box');
    expect(boxes).toHaveLength(96);
    expect(document.getElementById('cellGridCard').hidden).toBe(false);
    expect(document.getElementById('cellGrid').hidden).toBe(false);
    // Only the two live groups (1-indexed #12, #80) are tinted; the rest are faint
    // "awaiting probe" placeholders with no inline background.
    expect(boxes[11].style.background).not.toBe('');
    expect(boxes[79].style.background).not.toBe('');
    expect(boxes[0].style.background).toBe('');
    // Footer: min / max / Δ / "N of 96 read" while the full probe is pending.
    expect(document.getElementById('cellGridMin').textContent).toBe('3.950 V');
    expect(document.getElementById('cellGridMax').textContent).toBe('4.000 V');
    expect(document.getElementById('cellGridDelta').textContent).toBe('50 mV');
    expect(document.getElementById('cellGridNote').textContent).toBe('2 of 96 read');
    // A spread past 14 mV paints the Δ amber.
    expect(document.getElementById('cellGridDelta').style.color).toBe('var(--warn)');
    // Opening the grid flips the toggle to its "Hide cells" / expanded state.
    const toggle = document.getElementById('cellToggleBtn');
    expect(toggle.textContent).toBe('Hide cells');
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
  });

  it('keeps the heatmap hidden when opened but no cell data is known', () => {
    const VD = window.VoltDashboard;
    VD.setCellGridOpen(true);
    VD.updateTelemetry({ source: 'obd', connected: true, sampleCount: 1, updatedAt: Date.now() });
    VD.updateLiveUi();

    expect(document.getElementById('cellGridCard').hidden).toBe(true);
    expect(document.getElementById('cellGrid').hidden).toBe(true);
    expect(document.querySelectorAll('#cellGrid .cell-grid-box')).toHaveLength(0);
    // Losing all cell data also collapses the toggle back to its closed label.
    expect(document.getElementById('cellToggleBtn').hidden).toBe(true);
  });

  it('renders a full deviation heatmap from live cellVoltages when opened', () => {
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
    VD.setCellGridOpen(true);

    const boxes = document.querySelectorAll('#cellGrid .cell-grid-box');
    expect(boxes).toHaveLength(96);
    // A full read (>= 90 known) tints every group by its deviation from the mean.
    expect(boxes[0].style.background).not.toBe('');
    expect(boxes[95].style.background).not.toBe('');
    expect(document.getElementById('cellGridNote').textContent).toBe('full read');
    expect(document.getElementById('cellGridCard').hidden).toBe(false);
  });

  it('renders the heatmap from a persisted probe snapshot when opened', () => {
    const VD = window.VoltDashboard;
    VD.setCellGridOpen(true);
    const cells = Array.from({ length: 96 }, (_v, i) => ({ index: i + 1, voltage: 3.9 + (i % 8) * 0.01 }));
    VD.applyCellSnapshot({ capturedAtMs: Date.now() - 3600_000, cellCount: 96, cells });

    const boxes = document.querySelectorAll('#cellGrid .cell-grid-box');
    expect(boxes).toHaveLength(96);
    expect(boxes[0].style.background).not.toBe('');
    expect(document.getElementById('cellGridCard').hidden).toBe(false);
  });

  it('renders a partial probe with placeholder groups for the cells that did not answer', () => {
    const VD = window.VoltDashboard;
    VD.setCellGridOpen(true);
    // Cells 5, 41, 77 missing — still >= the 90-group confidence floor.
    const cells = Array.from({ length: 96 }, (_v, i) => ({ index: i + 1, voltage: 3.9 + (i % 8) * 0.01 }))
      .filter((c) => c.index !== 5 && c.index !== 41 && c.index !== 77);
    VD.applyCellSnapshot({ capturedAtMs: Date.now(), cellCount: cells.length, cells });

    const boxes = document.querySelectorAll('#cellGrid .cell-grid-box');
    expect(boxes).toHaveLength(96);
    expect(boxes[4].style.background).toBe(''); // group 5 — no answer
    expect(boxes[5].style.background).not.toBe(''); // group 6 — answered
  });

  it('clears the heatmap when the snapshot payload is empty (storage wiped)', () => {
    const VD = window.VoltDashboard;
    VD.setCellGridOpen(true);
    const cells = Array.from({ length: 96 }, (_v, i) => ({ index: i + 1, voltage: 3.9 }));
    VD.applyCellSnapshot({ capturedAtMs: Date.now(), cellCount: 96, cells });
    expect(document.getElementById('cellGridCard').hidden).toBe(false);

    VD.applyCellSnapshot({});
    expect(document.getElementById('cellGridCard').hidden).toBe(true);
    expect(document.querySelectorAll('#cellGrid .cell-grid-box')).toHaveLength(0);
  });

  it('exposes the open state and full-read status to the toggle action', () => {
    const VD = window.VoltDashboard;
    expect(VD.isCellGridOpen()).toBe(false);
    VD.setCellGridOpen(true);
    expect(VD.isCellGridOpen()).toBe(true);

    expect(VD.cellGridHasFull()).toBe(false);
    const cells = Array.from({ length: 96 }, (_v, i) => ({ index: i + 1, voltage: 3.9 + (i % 8) * 0.01 }));
    VD.applyCellSnapshot({ capturedAtMs: Date.now(), cellCount: 96, cells });
    expect(VD.cellGridHasFull()).toBe(true);
  });
});
