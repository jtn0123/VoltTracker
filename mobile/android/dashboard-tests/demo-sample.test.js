import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

// The browser demo / preview leans on loadSampleData() to populate the UI without
// a device. This guards that the sample dataset stays comprehensive enough to
// dogfood every feature surface — Charge session list, Insights HV-pack detail,
// and the Signals workspace — not just map + trips.
describe('demo sample data', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  it('populates charge, battery, and signals so the demo exercises every tab', () => {
    window.VoltDashboard.loadSampleData();
    const storage = window.VoltDashboard.state.storage;

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
});
