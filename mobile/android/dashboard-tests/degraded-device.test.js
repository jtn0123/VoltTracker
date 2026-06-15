// Degraded-device resilience.
//
// The dashboard ships into low-end / legacy System WebViews where the native bridge can be absent
// and lazy chunks may not load. core.ts / map.ts carry missing-bridge and missing-element fallback
// branches that a normal jsdom load (which always installs a bridge) never reaches — exactly the
// resilience code most likely to matter in the field. This exercises the no-bridge path so those
// branches are covered and can't silently start throwing. (Report item D8; complements
// legacy-webview.test.js and the Playwright error-paths/offline specs.)
import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

describe('degraded device — missing native bridge', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
  });

  it('boots with no VoltTrackerAndroid bridge and still exposes the native callback surface', async () => {
    // withBridge:false makes loadDashboard return null (no bridge), so assert on the globals the
    // bootstrap installs rather than the return value.
    await loadDashboard({ withBridge: false });
    expect(window.VoltTrackerAndroid).toBeUndefined();
    expect(window.VoltDashboard).toBeDefined();
    expect(window.VoltTrackerNative).toBeDefined();
  });

  it('native data pushes degrade gracefully (no throw) when no bridge is present', async () => {
    await loadDashboard({ withBridge: false });
    const native = window.VoltTrackerNative;
    expect(() => native.setStatus({ state: 'idle', detail: 'Viewing local data.' })).not.toThrow();
    expect(() => native.setStorage({})).not.toThrow();
    expect(() => native.updateTelemetry({})).not.toThrow();
    expect(() => native.setAppState({})).not.toThrow();
    expect(() => native.setDevices([])).not.toThrow();
    expect(() => native.setHistory([])).not.toThrow();
  });

  it('switching to every view with no bridge does not throw', async () => {
    await loadDashboard({ withBridge: false, extras: ['map.js'] });
    const VD = window.VoltDashboard;
    for (const view of ['map', 'charge', 'insights', 'diagnostics', 'settings', 'drive']) {
      expect(() => VD.setView(view), `setView(${view})`).not.toThrow();
    }
  });

  it('rendering the map with no bridge and no route data does not throw', async () => {
    await loadDashboard({ withBridge: false, extras: ['map.js'] });
    const VD = window.VoltDashboard;
    expect(typeof VD.renderMapIfLoaded).toBe('function');
    expect(() => VD.renderMapIfLoaded()).not.toThrow();
    // Hydration recovery must also no-op cleanly with no bridge to read the in-progress route from.
    if (typeof VD.hydrateLiveRouteIfActive === 'function') {
      expect(() => VD.hydrateLiveRouteIfActive()).not.toThrow();
    }
  });
});
