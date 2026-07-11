// G2 startup-headroom split — the lazy "detail" chunks (charge-history.js,
// maintenance-panel.js, dtc-detail.js) carved out of the eager
// storage-status.ts. These tests pin the SEAMS the split created:
//
//  1. The eager render paths (setStorage → renderRealV2Ui) must stay safe and
//     silent while a chunk is not loaded — optional-subscriber guards, no
//     throws, no half-rendered DOM.
//  2. Each ensure*Module() loads its chunk, registers the VD entry points, and
//     the chunk hydrates the CURRENT state on load, so broadcasts that landed
//     before the load are never lost.
//  3. The user-facing triggers load the chunk on demand: setView("charge") /
//     setView("insights"), and the maintenance/charge data-actions in
//     actions.ts load-then-call instead of dropping the tap.
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';
import { loadDashboard } from './setup/load-dashboard.js';

const DETAIL_CHUNKS = ['charge-history.js', 'maintenance-panel.js', 'dtc-detail.js'];

const CHARGE_SUMMARY = {
  chargeSessionCount: 2,
  chargingHintCount: 2,
  maxPowerKw: 7.2,
  recentSessions: [
    { id: 'c2', startedAtMs: 1_700_600_000_000, endedAtMs: 1_700_610_000_000, startSoc: 40, endSoc: 80, energyKwh: 6.2, chargerType: 'level2' },
    { id: 'c1', startedAtMs: 1_700_000_000_000, endedAtMs: 1_700_010_000_000, startSoc: 30, endSoc: 70, energyKwh: 5.4, chargerType: 'level2' },
  ],
};

describe('lazy detail chunks (G2 split from storage-status)', () => {
  let VD;
  let bridge;

  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    window.localStorage.clear();
    bridge = createVoltBridgeFixture({
      getMaintenanceLog: () => JSON.stringify([
        { id: 7, type: 'Oil change', createdAtMs: 1_700_000_000_000, odometerKm: 77000, note: '' },
      ]),
    });
    await loadDashboard({ bridge, skip: DETAIL_CHUNKS });
    VD = window.VoltDashboard;
  });

  it('keeps the eager storage render path safe before any detail chunk loads', () => {
    expect(typeof VD.renderChargeSessions).not.toBe('function');
    expect(typeof VD.renderMaintenanceList).not.toBe('function');
    expect(typeof VD.openDtcDetail).not.toBe('function');

    // A full storage broadcast must not throw and must still render the eager
    // sections (db counts) while skipping the chunk-owned ones.
    expect(() => VD.setStorage({ sessionCount: 3, sampleCount: 40, chargeSummary: CHARGE_SUMMARY })).not.toThrow();
    expect(document.getElementById('chargeSessionsList').children.length).toBe(0);
  });

  it('ensureChargeHistoryModule registers the renderers and hydrates the pre-load state', async () => {
    VD.setStorage({ sessionCount: 3, sampleCount: 40, chargeSummary: CHARGE_SUMMARY });
    await VD.ensureChargeHistoryModule();
    expect(typeof VD.renderChargeSessions).toBe('function');
    expect(typeof VD.buildMonthlyTrendSvg).toBe('function');
    // The chunk re-rendered from current state on load: the broadcast that
    // arrived before the chunk existed is now on screen.
    const list = document.getElementById('chargeSessionsList');
    expect(list.children.length).toBe(2);
    expect(document.getElementById('chargeSessionsCard').hidden).toBe(false);
  });

  it('ensureMaintenancePanelModule loads + renders the native log on arrival', async () => {
    await VD.ensureMaintenancePanelModule();
    expect(typeof VD.loadMaintenanceLog).toBe('function');
    const list = document.getElementById('maintenanceList');
    expect(list.textContent).toContain('Oil change');
  });

  it('ensureDtcDetailModule registers the sheet + scan narration entry points', async () => {
    await VD.ensureDtcDetailModule();
    expect(typeof VD.openDtcDetail).toBe('function');
    expect(typeof VD.closeDtcDetail).toBe('function');
    expect(typeof VD.startDtcScanProgress).toBe('function');
    // The sheet opens from a plain code row even without the DTC database.
    expect(() => VD.openDtcDetail({ dtc: 'P0AA6', status: 'stored' })).not.toThrow();
    expect(document.getElementById('dtcDetailSheet').hidden).toBe(false);
    VD.closeDtcDetail();
    expect(document.getElementById('dtcDetailSheet').hidden).toBe(true);
  });

  it('entering the Charge tab loads the charge-history chunk', async () => {
    VD.setView('charge');
    await VD.pendingLazyLoads();
    expect(typeof VD.renderChargeSessions).toBe('function');
  });

  it('entering the Insights tab loads the maintenance panel (and the trend chart it charts with)', async () => {
    VD.setView('insights');
    await VD.pendingLazyLoads();
    expect(typeof VD.renderMaintenanceList).toBe('function');
    // ensureInsightsModule chains charge-history first: the Insights charts
    // render through VD.buildMonthlyTrendSvg from that chunk.
    expect(typeof VD.buildMonthlyTrendSvg).toBe('function');
  });

  it('the exportChargeSessionsCsv action load-then-calls instead of dropping the tap', async () => {
    const exportSpy = vi.fn(() => '{"ok":true,"format":"csv","fileName":"volt-charges.csv","bytes":128,"chargeCount":1}');
    bridge.exportChargeSessionsCsv = exportSpy;
    expect(typeof VD.exportChargeSessionsCsv).not.toBe('function');

    // The real production button from the Charge partial — [data-action]
    // handlers bind to the DOM present at bootstrap, so use it, not a fixture.
    const button = document.querySelector('[data-action="exportChargeSessionsCsv"]');
    expect(button).not.toBeNull();
    button.click();
    await VD.pendingLazyLoads();
    // Flush the ensure().then(...) continuation queued behind the load.
    await Promise.resolve();
    expect(exportSpy).toHaveBeenCalledTimes(1);
  });

  it('the addMaintenance action load-then-opens the form instead of dropping the tap', async () => {
    expect(typeof VD.addMaintenanceEntry).not.toBe('function');
    const button = document.querySelector('[data-action="addMaintenance"]');
    expect(button).not.toBeNull();
    button.click();
    await VD.pendingLazyLoads();
    await Promise.resolve();
    expect(typeof VD.addMaintenanceEntry).toBe('function');
    expect(document.getElementById('maintenanceForm').hidden).toBe(false);
  });
});
