// Verifies the window.VoltTrackerNative surface — the WebView callback ABI
// that the Android side invokes via WebView#evaluateJavascript. A typo or
// rename here silently no-ops on the native side, which is why this is the
// first smoke check.
import { describe, it, expect, beforeEach, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

// Frozen list of method names actions.js MUST expose on window.VoltTrackerNative.
// Keep in sync with the literal object at the bottom of actions.js.
const NATIVE_METHODS = ['setDevices', 'setHistory', 'setStatus', 'setStorage', 'setAppState', 'updateTelemetry'];

describe('window.VoltTrackerNative ABI', () => {
  beforeEach(async () => {
    // Each test gets a fresh dashboard load — the IIFEs mutate globals, so
    // we cannot share state across tests safely.
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  it('exposes the 6 documented callback methods', () => {
    expect(window.VoltTrackerNative).toBeDefined();
    for (const name of NATIVE_METHODS) {
      expect(typeof window.VoltTrackerNative[name], `VoltTrackerNative.${name}`).toBe('function');
    }
  });

  it('exposes exactly the documented methods — no extras, no missing', () => {
    const actual = Object.keys(window.VoltTrackerNative).sort();
    const expected = [...NATIVE_METHODS].sort();
    expect(actual).toEqual(expected);
  });

  it('signals Android readiness after the native callback surface exists', async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;

    const dashboardReady = vi.fn(() => {
      expect(window.VoltTrackerNative).toBeDefined();
      for (const name of NATIVE_METHODS) {
        expect(typeof window.VoltTrackerNative[name], `VoltTrackerNative.${name}`).toBe('function');
      }
    });

    await loadDashboard({ bridge: createVoltBridgeFixture({ dashboardReady }) });

    expect(dashboardReady).toHaveBeenCalledTimes(1);
  });
});
