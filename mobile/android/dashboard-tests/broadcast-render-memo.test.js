// Regression guards for the ~1 Hz storage-broadcast render storms in
// storage-status.ts. setStorage fires from setAppState on every app-state push,
// and these renderers used to tear down and rebuild their DOM every time even
// when nothing changed — wasted churn plus focus/selection loss and (for the
// maintenance delete button) the risk of recreating a control under a finger
// mid-tap. Each is now memoized on a content signature.
import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

describe('storage-broadcast render memoization', () => {
  let VD;
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
    VD = window.VoltDashboard;
  });

  it('does not rebuild the post-session review lists on an unchanged review', () => {
    const review = {
      session: { id: 's1', mode: 'obd', adapterName: 'ELM327' },
      maxSpeedKph: 88,
      locationSampleCount: 10,
      parsedPidCount: 100,
      unknownPidCount: 2,
      avgSampleIntervalMs: 500,
      warnings: [{ title: 'GPS gaps', detail: 'Some gaps' }],
      timeline: [{ label: 'start', atMs: 1000 }],
      recentPidFrames: [{ command: '0105', response: '41 05 5A' }],
    };
    VD.setStorage({ latestReview: review });
    const warnNode = document.getElementById('reviewWarnings').firstElementChild;
    const timelineNode = document.getElementById('reviewTimeline').firstElementChild;
    expect(warnNode).not.toBeNull();

    // Identical review — the list nodes must be the SAME instances.
    VD.updateReviewUi();
    expect(document.getElementById('reviewWarnings').firstElementChild).toBe(warnNode);
    expect(document.getElementById('reviewTimeline').firstElementChild).toBe(timelineNode);

    // A changed warning rebuilds.
    VD.state.storage.latestReview.warnings = [{ title: 'Parser errors', detail: 'Bad frames' }];
    VD.updateReviewUi();
    expect(document.getElementById('reviewWarnings').firstElementChild).not.toBe(warnNode);
  });

  it('does not rebuild the pack-stat row on an unchanged battery snapshot', () => {
    const snapshot = (packVoltage) => ({
      batterySummary: {
        latestBatterySnapshot: { soc: 64, packVoltage, batteryTempC: 23, sohPct: 92, packPowerKw: -7.4 },
      },
    });
    VD.setStorage(snapshot(364));
    const row = document.getElementById('realPackStats');
    const firstCell = row.firstElementChild;
    expect(firstCell).not.toBeNull();

    // Identical broadcast — same cells.
    VD.setStorage(snapshot(364));
    expect(row.firstElementChild).toBe(firstCell);

    // Changed voltage rebuilds.
    VD.setStorage(snapshot(380));
    expect(row.firstElementChild).not.toBe(firstCell);
    expect(row.textContent).toContain('380 V');
  });

  it('does not rebuild the maintenance list when the entries are unchanged', () => {
    const entry = (note) => ({ id: 7, createdAtMs: 1_700_000_000_000, odometerKm: 1609, type: 'Oil change', note });
    VD.state.maintenanceLog = [entry('Synthetic')];
    VD.renderMaintenanceList();
    const list = document.getElementById('maintenanceList');
    const firstRow = list.firstElementChild;
    expect(firstRow).not.toBeNull();

    // Identical entries — same node (the delete button is not recreated).
    VD.renderMaintenanceList();
    expect(list.firstElementChild).toBe(firstRow);

    // An edited note rebuilds.
    VD.state.maintenanceLog = [entry('Rotated tires too')];
    VD.renderMaintenanceList();
    expect(list.firstElementChild).not.toBe(firstRow);
  });
});
