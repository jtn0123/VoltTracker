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
    // C3: with the map/seed chunk not yet loaded, demo mode must NOT read
    // active while the async seed is still in flight — flipping it early left
    // a window where a native storage push could overwrite the demo view.
    expect(VD.state.demoActive).toBe(false);
    await VD.pendingLazyLoads();
    await Promise.resolve();
    expect(VD.state.demoActive).toBe(true);
    expect(VD.data.trips.some((trip) => trip.label === 'Home -> Office')).toBe(true);
    expect(VD.data.insights.some((insight) => /Best month yet/.test(insight.title))).toBe(true);
  }, 10_000);

  it('rejects malformed demo payloads instead of marking demo data loaded', async () => {
    await loadDashboard();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    window.VoltDashboardDemoData = () => ({
      trips: null,
      sessions: [],
      hourly: [],
      insights: [],
    });

    let callbackError = null;
    const loaded = window.VoltDashboard.ensureDemoData((error) => {
      callbackError = error;
    });

    expect(loaded).toBe(false);
    expect(callbackError).toBeInstanceOf(Error);
    expect(window.VoltDashboard.data.demoLoaded).toBe(false);
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('trips must be an array'));
    warn.mockRestore();
  });

  it('seeds the selected sample scenario before starting the native demo stream', async () => {
    const bridge = await loadDashboard({ extras: ['demo-data.js', 'insights-panel.js'] });
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
    await VD.ensureMapModule();
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
    await VD.pendingLazyLoads();

    expect(bridge.disconnect).toHaveBeenCalledTimes(1);
    expect(VD.state.demoActive).toBe(false);
    expect(VD.state.storage.sessionCount).toBe(0);
    expect(VD.state.trips).toHaveLength(0);
    expect(VD.state.insights).toEqual({});
  });

  it('startDemo() stays inactive and surfaces the retry toast when the seed chunk fails to load (C3)', async () => {
    await loadDashboard({ extras: ['demo-data.js'] });
    const VD = window.VoltDashboard;
    const toasts = [];
    // telemetry.ts owns VD.showToast; core + actions call it late-bound
    // through the registry (lazy-chunk-toast.test.js convention).
    VD.showToast = (message, urgent) => {
      toasts.push({ message: String(message), urgent: Boolean(urgent) });
    };
    // The map/seed chunk never arrives.
    window.__VoltDashboardLoadScript = () => Promise.reject(new Error('chunk gone'));

    VD.actions.startDemo();
    // demoActive must not flip on while the seed is (still) in flight…
    expect(VD.state.demoActive).toBe(false);
    await VD.pendingLazyLoads();
    await Promise.resolve();
    await Promise.resolve();

    // …and after the failure the demo is NOT left "running" with no data
    // (the old silent .catch(() => {}) did exactly that).
    expect(VD.state.demoActive).toBe(false);
    expect(document.body.classList.contains('demo-active')).toBe(false);
    expect(VD.state.status).toMatchObject({ state: 'blocked' });
    expect(VD.state.status.detail).toMatch(/demo/i);
    expect(toasts).toHaveLength(1);
    expect(toasts[0].urgent).toBe(true);
    expect(toasts[0].message).toMatch(/load this panel/i);
  });

  it('stopDemo() restores the real app-state parked during demo so the fake vehicle cannot linger (C4)', async () => {
    const bridge = await loadDashboard({ extras: ['demo-data.js', 'insights-panel.js'] });
    bridge.demo = vi.fn();
    bridge.disconnect = vi.fn();
    const VD = window.VoltDashboard;
    await VD.ensureMapModule();

    VD.loadDemoScenario('extreme');
    expect(VD.state.demoActive).toBe(true);
    expect(VD.state.appState.vehicle.vin).toBe('1G1RC6S52HU1234567');

    // A live native app-state push lands mid-demo: parked behind the preview,
    // not painted over the demo view.
    VD.setAppState({ vehicle: { vin: 'REALVIN000000001', make: 'Chevrolet' } });
    expect(VD.state.appState.vehicle.vin).toBe('1G1RC6S52HU1234567');
    expect(VD.state.realAppState.vehicle.vin).toBe('REALVIN000000001');

    VD.actions.stopDemo();
    await VD.pendingLazyLoads();

    // The parked push is restored immediately — no waiting for the next
    // native broadcast to flush the demo VIN/odometer.
    expect(VD.state.demoActive).toBe(false);
    expect(VD.state.appState.vehicle.vin).toBe('REALVIN000000001');
    expect(VD.state.realAppState).toBe(null);
    expect(VD.state.realStorage).toBe(null);
    expect(VD.state.realTrips).toBe(null);
    expect(VD.state.realInsights).toBe(null);
  });

  it('stopDemo() flushes the demo vehicle even when no native push arrived during demo (C4)', async () => {
    const bridge = await loadDashboard({ extras: ['demo-data.js', 'insights-panel.js'] });
    bridge.demo = vi.fn();
    bridge.disconnect = vi.fn();
    const VD = window.VoltDashboard;
    await VD.ensureMapModule();

    VD.loadDemoScenario('extreme');
    expect(VD.state.appState.vehicle.vin).toBe('1G1RC6S52HU1234567');

    VD.actions.stopDemo();
    await VD.pendingLazyLoads();

    expect(VD.state.demoActive).toBe(false);
    expect(VD.state.appState.vehicle).toBe(null);
    expect(VD.state.appState.latestTelemetry).toBe(null);
  });

  it('browser-preview Start demo seeds and clears sample data without a native bridge', async () => {
    await loadDashboard({ withBridge: false, extras: ['demo-data.js', 'insights-panel.js'] });

    const VD = window.VoltDashboard;
    await VD.ensureMapModule();
    VD.actions.startDemo();

    expect(VD.state.demoActive).toBe(true);
    expect(document.body.classList.contains('demo-active')).toBe(true);
    expect(document.getElementById('demoBanner').hidden).toBe(false);
    expect(VD.state.storage.recentRoutes.length).toBeGreaterThan(0);
    expect(VD.state.storage.latestDiagnosticCodes.length).toBeGreaterThan(0);
    expect(VD.state.trips.length).toBeGreaterThan(0);
    expect(VD.state.insights.tripCount).toBeGreaterThan(0);

    const scatter = vi.spyOn(VD, 'renderInsightScatter');
    VD.actions.stopDemo();

    expect(scatter).toHaveBeenCalled();
    expect(VD.state.demoActive).toBe(false);
    expect(document.body.classList.contains('demo-active')).toBe(false);
    expect(VD.state.storage.recentRoutes).toHaveLength(0);
    expect(VD.state.storage.latestDiagnosticCodes).toHaveLength(0);
    expect(VD.state.trips).toHaveLength(0);
    expect(VD.state.insights).toEqual({});
  });
});
