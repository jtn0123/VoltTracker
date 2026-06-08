import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const DASHBOARD_ASSETS = resolve(HERE, '../app/src/main/assets/dashboard');
const DASHBOARD_SRC = resolve(HERE, '../app/src/main/dashboard-src');

function sourceFor(name) {
  const ts = resolve(DASHBOARD_SRC, `js/${name}.ts`);
  return existsSync(ts) ? ts : resolve(DASHBOARD_SRC, `js/${name}.js`);
}

describe('dashboard demo data', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltDashboardDemoData;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
  });

  it('keeps demo fixture rows out of core.ts and the eager template', () => {
    const core = readFileSync(sourceFor('core'), 'utf8');
    const template = readFileSync(resolve(DASHBOARD_SRC, 'index.template.html'), 'utf8');

    expect(core).not.toContain('Home -> Office');
    expect(core).not.toContain('Best month yet for EV ratio');
    expect(template).not.toContain('js/demo-data.js');
  });

  it('lazy-loads the demo-data asset as the gate before demo mode activates', async () => {
    await loadDashboard({ extras: ['demo-data.js'] });

    const VD = window.VoltDashboard;
    expect(VD.data.demoLoaded).toBe(false);

    VD.actions.startDemo();

    // startDemo gates on ensureDemoData() resolving before flipping demo mode
    // on. The unified UI no longer swaps in mockup cards — it streams demo
    // telemetry through the real components — so we assert the data backing
    // store loaded and demo activated, not any (now-deleted) mockup DOM.
    expect(VD.data.demoLoaded).toBe(true);
    expect(VD.state.demoActive).toBe(true);
    expect(VD.data.trips.some((trip) => trip.label === 'Home -> Office')).toBe(true);
    expect(VD.data.insights.some((insight) => /Best month yet/.test(insight.title))).toBe(true);
  }, 10_000);

  it('seeds the selected sample scenario before starting the native demo stream', async () => {
    const bridge = await loadDashboard({ extras: ['demo-data.js'] });
    bridge.demo = vi.fn();
    bridge.disconnect = vi.fn();
    bridge.getStorageSummary = vi.fn(() => JSON.stringify({
      sessionCount: 0,
      sampleCount: 0,
      recentRoutes: [],
      chargeSummary: { chargeSessionCount: 0, chargingHintCount: 0 },
      batterySummary: {},
      detailedSignalCatalog: [],
      enhancedCapabilities: [],
      latestDiagnosticCodes: [],
      diagnosticCodeCount: 0,
    }));
    bridge.getTrips = vi.fn(() => '[]');
    bridge.getInsights = vi.fn(() => '{}');

    const VD = window.VoltDashboard;
    VD.state.demoScenario = 'power-user';
    VD.state.storage = {};
    VD.state.trips = [];
    VD.state.insights = {};

    VD.actions.startDemo();

    expect(bridge.demo).toHaveBeenCalledTimes(1);
    expect(VD.state.demoActive).toBe(true);
    expect(VD.state.demoScenario).toBe('power-user');
    expect(VD.state.storage.chargeSummary.recentSessions.length).toBeGreaterThanOrEqual(10);
    expect(document.querySelectorAll('#chargeSessionsList .charge-session-row').length).toBeGreaterThan(0);
    expect(document.getElementById('realPackStats').hidden).toBe(false);

    VD.setStorage(bridge.getStorageSummary());
    VD.loadTrips();
    VD.loadInsights();
    VD.setAppState({ storage: JSON.parse(bridge.getStorageSummary()) });

    expect(VD.state.realStorage.sessionCount).toBe(0);
    expect(VD.state.realTrips).toHaveLength(0);
    expect(VD.state.realInsights).toEqual({});
    expect(VD.state.storage.chargeSummary.recentSessions.length).toBeGreaterThanOrEqual(10);
    expect(VD.state.trips.length).toBeGreaterThan(0);
    expect(VD.state.insights.tripCount).toBeGreaterThan(0);
    expect(VD.state.appState.vehicle.make).toBe('Chevrolet');
    expect(document.querySelectorAll('#chargeSessionsList .charge-session-row').length).toBeGreaterThan(0);
    expect(document.getElementById('realPackStats').hidden).toBe(false);

    VD.actions.stopDemo();

    expect(bridge.disconnect).toHaveBeenCalledTimes(1);
    expect(VD.state.demoActive).toBe(false);
    expect(VD.state.storage.sessionCount).toBe(0);
    expect(VD.state.trips).toHaveLength(0);
    expect(VD.state.insights).toEqual({});
  });

  it('browser-preview Start demo seeds and clears sample data without a native bridge', async () => {
    await loadDashboard({ withBridge: false, extras: ['demo-data.js'] });

    const VD = window.VoltDashboard;
    VD.actions.startDemo();

    expect(VD.state.demoActive).toBe(true);
    expect(document.body.classList.contains('demo-active')).toBe(true);
    expect(document.getElementById('demoBanner').hidden).toBe(false);
    expect(VD.state.storage.recentRoutes.length).toBeGreaterThan(0);
    expect(VD.state.storage.latestDiagnosticCodes.length).toBeGreaterThan(0);
    expect(VD.state.trips.length).toBeGreaterThan(0);
    expect(VD.state.insights.tripCount).toBeGreaterThan(0);

    VD.actions.stopDemo();

    expect(VD.state.demoActive).toBe(false);
    expect(document.body.classList.contains('demo-active')).toBe(false);
    expect(VD.state.storage.recentRoutes).toHaveLength(0);
    expect(VD.state.storage.latestDiagnosticCodes).toHaveLength(0);
    expect(VD.state.trips).toHaveLength(0);
    expect(VD.state.insights).toEqual({});
  });
});
