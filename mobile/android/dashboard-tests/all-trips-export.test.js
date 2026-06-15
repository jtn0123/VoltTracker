// all-trips-export.test.js — M6 bulk all-trips CSV export: the map-sheet "Export all (CSV)" button
// forwards to bridge.exportAllTripsCsv, and degrades gracefully when the bridge is absent.
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

describe('M6 — bulk all-trips CSV export', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
  });

  it('renders the Export all (CSV) button in the map sheet header', async () => {
    await loadDashboard();
    const btn = document.getElementById('exportAllTripsBtn');
    expect(btn).toBeTruthy();
    expect(btn.dataset.action).toBe('exportAllTripsCsv');
    expect(btn.textContent).toContain('Export all');
  });

  it('clicking forwards to bridge.exportAllTripsCsv', async () => {
    const exportAllTripsCsv = vi.fn(() => '{"ok":true,"tripCount":2}');
    await loadDashboard({ bridge: createVoltBridgeFixture({ exportAllTripsCsv }) });

    document.getElementById('exportAllTripsBtn').click();
    expect(exportAllTripsCsv).toHaveBeenCalledTimes(1);
  });

  it('degrades to a status hint when the bridge is absent (web preview)', async () => {
    // No bridge at all → the action surfaces an idle hint instead of throwing.
    await loadDashboard({ withBridge: false });
    const setStatus = vi.spyOn(window.VoltDashboard, 'setStatus');
    document.getElementById('exportAllTripsBtn').click();
    expect(setStatus).toHaveBeenCalled();
    const arg = setStatus.mock.calls[setStatus.mock.calls.length - 1][0];
    expect(String(arg.detail || '')).toContain('Android app');
    setStatus.mockRestore();
  });
});
