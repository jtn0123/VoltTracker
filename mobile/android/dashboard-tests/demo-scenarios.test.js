import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

// Guards the demo scenario switcher used for systematic dogfooding. Each
// scenario must produce its characteristic shape (and not throw).
describe('demo scenarios', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
    await window.VoltDashboard.ensureMapModule();
  });

  it('empty clears all data (exercises empty states)', () => {
    window.VoltDashboard.loadDemoScenario('empty');
    const s = window.VoltDashboard.state.storage;
    expect(s.sessionCount).toBe(0);
    expect(s.recentRoutes).toHaveLength(0);
    expect(s.chargeSummary.chargeSessionCount).toBe(0);
    expect(window.VoltDashboard.state.demoScenario).toBe('empty');
  });

  it('keeps the Insights first-run guide visible for non-trip storage rows', () => {
    const VD = window.VoltDashboard;
    VD.state.storage = {
      pidObservationCount: 5,
      locationSampleCount: 3,
      sessionCount: 1,
      sampleCount: 5,
      overview: {},
      batterySummary: {},
      chargeSummary: {},
    };
    VD.state.insights = {};

    VD.renderRealV2Ui();
    VD.flushRender();

    expect(document.getElementById('appEmptyState').hidden).toBe(true);
    expect(document.getElementById('insightsEmptyState').hidden).toBe(false);

    VD.state.insights = { tripCount: 1, totalDistanceMeters: 1200 };
    VD.renderRealV2Ui();

    expect(document.getElementById('insightsEmptyState').hidden).toBe(true);
  });

  it('hides the Drive "waiting for your car" empty state while samples stream, even with no stored rows', () => {
    const VD = window.VoltDashboard;
    VD.loadDemoScenario('empty');
    VD.flushRender();
    const empty = document.getElementById('appEmptyState');
    expect(empty.hidden).toBe(false);

    VD.setStatus({ state: 'connected', detail: 'Browser-only demo is running.' });
    VD.updateTelemetry({ source: 'demo', connected: true, sampleCount: 1, updatedAt: Date.now(), speedKph: 40 });
    VD.flushRender();
    expect(empty.hidden).toBe(true);

    VD.setStatus({ state: 'idle', detail: 'Stopped.' });
    VD.flushRender();
    expect(empty.hidden).toBe(false);
  });

  it('typical is the rich happy path', () => {
    window.VoltDashboard.loadDemoScenario('typical');
    const s = window.VoltDashboard.state.storage;
    expect(s.chargeSummary.recentSessions.length).toBeGreaterThan(0);
    expect(s.enhancedCapabilities.length).toBeGreaterThan(0);
    expect(s.latestDiagnosticCodes.length).toBeGreaterThan(0);
    expect(window.VoltDashboard.state.demoScenario).toBe('typical');
  });

  it('power-user packs dense data', () => {
    window.VoltDashboard.loadDemoScenario('power-user');
    const s = window.VoltDashboard.state.storage;
    expect(s.chargeSummary.recentSessions.length).toBeGreaterThanOrEqual(10);
    expect(s.sampleCount).toBeGreaterThan(100000);
  });

  it('fault surfaces multiple DTCs and a blocked status', () => {
    window.VoltDashboard.loadDemoScenario('fault');
    const s = window.VoltDashboard.state.storage;
    expect(s.latestDiagnosticCodes.length).toBeGreaterThanOrEqual(4);
    expect(window.VoltDashboard.state.status.state).toBe('blocked');
  });

  it('extreme stresses values and long strings', () => {
    window.VoltDashboard.loadDemoScenario('extreme');
    const vehicle = window.VoltDashboard.state.appState.vehicle;
    expect(vehicle.odometerMiles).toBeGreaterThan(1000000);
    expect(vehicle.model.length).toBeGreaterThan(20);
    expect(window.VoltDashboard.state.storage.sampleCount).toBeGreaterThan(1000000);
  });
});
