import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

// The explicit demo preview leans on loadSampleData() to populate the UI. It
// must never happen silently during browser-preview boot, otherwise fake rows
// look like real logged data.
describe('demo sample data', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('does not auto-populate fake metrics when the browser preview has no native bridge', async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard({ withBridge: false });

    const state = window.VoltDashboard.state;
    expect(state.demoActive).toBe(false);
    expect(state.storage.recentRoutes || []).toHaveLength(0);
    expect(state.storage.latestDiagnosticCodes || []).toHaveLength(0);
    expect(state.insights.tripCount || 0).toBe(0);
    expect(document.getElementById('effScatterCard').hidden).toBe(true);
    expect(document.getElementById('dtcTitle').textContent).toBe('No car-code scan yet');
  });

  it('populates charge, battery, and signals so the demo exercises every tab', async () => {
    await window.VoltDashboard.ensureMapModule();
    window.VoltDashboard.loadSampleData();
    const storage = window.VoltDashboard.state.storage;

    expect(window.VoltDashboard.state.demoActive).toBe(true);
    expect(window.VoltDashboard.state.status.state).toBe('demo');
    // Data is present in the shapes each feature reads.
    expect(storage.chargeSummary.recentSessions.length).toBeGreaterThan(0);
    expect(storage.batterySummary.latestBatterySnapshot.packVoltage).toBeGreaterThan(0);
    expect(storage.batterySummary.latestBatterySnapshot.sohPct).toBeGreaterThan(0);
    expect(storage.enhancedCapabilities.length).toBeGreaterThan(0);
    expect(storage.detailedSignalCatalog.length).toBeGreaterThan(0);

    // And it actually renders those surfaces.
    expect(
      document.querySelectorAll('#chargeSessionsList .charge-session-row').length,
    ).toBeGreaterThan(0);
    expect(document.getElementById('realPackStats').hidden).toBe(false);
    expect(document.getElementById('realPackStats').textContent).toContain('364 V');
    expect(
      document.querySelectorAll('#enhancedCapabilityList .enhanced-capability-item').length,
    ).toBeGreaterThan(0);
  });

  it('streams browser-demo battery temp in °C like the native demo loop', async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard({ withBridge: false });

    const VD = window.VoltDashboard;
    await VD.actions.runBrowserDemo();
    try {
      // DemoPollingLoop.kt emits 24.0 + sin(t/8) °C; telemetry.ts renders the
      // field through units.tempText, which converts °C→°F. A Fahrenheit-shaped
      // value here (the old 72 + sin) displayed as ~162 °F pack temp.
      const temp = Number(VD.state.telemetry.batteryTemp);
      expect(temp).toBeGreaterThan(22);
      expect(temp).toBeLessThan(26);
      VD.updateLiveUi();
      expect(document.getElementById('drivePackTempValue').textContent)
        .toBe(VD.units.tempText(temp));
    } finally {
      window.clearInterval(window.__voltDemoTimer ?? undefined);
    }
  });

  it('clears browser-preview demo rows when demo stops', async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard({ withBridge: false });

    const VD = window.VoltDashboard;
    await VD.ensureMapModule();
    VD.loadDemoScenario('typical');
    expect(VD.state.demoActive).toBe(true);
    expect(VD.state.storage.recentRoutes.length).toBeGreaterThan(0);

    VD.actions.stopDemo();

    expect(VD.state.demoActive).toBe(false);
    expect(VD.state.storage.recentRoutes).toHaveLength(0);
    expect(VD.state.storage.latestDiagnosticCodes).toHaveLength(0);
    expect(VD.state.trips).toHaveLength(0);
    expect(VD.state.insights).toEqual({});
  });

  it('keeps the browser demo live GPS in the sample route region', async () => {
    vi.useFakeTimers();
    const livePosition = vi.fn();
    window.VoltDashboard.updateLivePosition = livePosition;

    await window.VoltDashboard.actions.runBrowserDemo();
    vi.advanceTimersByTime(1000);

    expect(livePosition).toHaveBeenCalled();
    const [lat, lng] = livePosition.mock.calls.at(-1);
    expect(lat).toBeGreaterThan(34.0);
    expect(lat).toBeLessThan(34.2);
    expect(lng).toBeGreaterThan(-118.45);
    expect(lng).toBeLessThan(-118.2);
  });

  it('feeds the raw-frame panel with demo ELM327-style responses', async () => {
    vi.useFakeTimers();
    const VD = window.VoltDashboard;

    await VD.actions.runBrowserDemo();
    VD.flushRender();

    const raw = document.getElementById('rawFrames').textContent;
    expect(raw).toContain('>010D');
    expect(raw).toContain('41 0D');
    expect(raw).toContain('>010C');
    expect(raw).not.toMatch(/Waiting for telemetry/i);
  });
});
