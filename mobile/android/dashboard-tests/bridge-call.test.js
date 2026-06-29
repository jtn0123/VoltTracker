// VD.callBridge — graceful invocation of dashboard->native bridge methods that
// may be missing on an older native build (the bridge ABI grows over time; see
// docs/bridge-abi.md). A missing method must not throw mid-tap: it warns once
// per method, reports through logClientError, and returns undefined.
//
// Also pins the lazy-chunk failure surfacing: when the DTC dictionary chunk
// fails to load, ensureDtcData() must console.warn AND push a status detail
// (which the toast mirrors) instead of failing silently.
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

describe('VD.callBridge', () => {
  let bridge;

  beforeEach(async () => {
    vi.restoreAllMocks();
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    // The fixture ships plain function stubs; swap in spies for the methods
    // these tests assert on.
    bridge = createVoltBridgeFixture();
    bridge.connect = vi.fn();
    bridge.logClientError = vi.fn();
    await loadDashboard({ bridge });
  });

  it('invokes an available bridge method with its arguments', () => {
    const VD = window.VoltDashboard;
    VD.callBridge('connect', 'AA:BB:CC:DD:EE:FF', 'OBDII');
    expect(bridge.connect).toHaveBeenCalledWith('AA:BB:CC:DD:EE:FF', 'OBDII');
  });

  it('returns the bridge value for string-returning methods', () => {
    const VD = window.VoltDashboard;
    const result = VD.callBridge('listDevices');
    expect(typeof result).toBe('string');
    expect(() => JSON.parse(result)).not.toThrow();
  });

  it('warns once and returns undefined for a method this native build lacks', () => {
    const VD = window.VoltDashboard;
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    delete bridge.tryReconnectNow;

    expect(() => VD.callBridge('tryReconnectNow')).not.toThrow();
    expect(VD.callBridge('tryReconnectNow')).toBeUndefined();

    const missingWarns = warn.mock.calls.filter(([msg]) =>
      String(msg).includes('bridge.tryReconnectNow'));
    expect(missingWarns).toHaveLength(1); // deduped across repeat calls
    expect(bridge.logClientError).toHaveBeenCalledWith(
      'bridge.missing',
      expect.stringContaining('tryReconnectNow'),
    );
    warn.mockRestore();
  });

  it('logs and returns undefined when an available bridge method throws', () => {
    const VD = window.VoltDashboard;
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    bridge.connect = vi.fn(() => {
      throw new Error('native connect failed');
    });

    let result;
    expect(() => {
      result = VD.callBridge('connect', 'AA:BB:CC:DD:EE:FF', 'OBDII');
    }).not.toThrow();
    expect(result).toBeUndefined();

    expect(bridge.logClientError).toHaveBeenCalledWith(
      'bridge.call_failed',
      expect.stringContaining('connect'),
    );
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('bridge.connect failed'));
    warn.mockRestore();
  });
});

describe('lazy DTC chunk failure surfacing', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  it('warns and pushes a blocked status detail when the DTC scripts fail to load', async () => {
    const VD = window.VoltDashboard;
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    window.__VoltDashboardLoadScript = () => Promise.reject(new Error('chunk fetch failed'));

    await expect(VD.ensureDtcData()).rejects.toThrow('chunk fetch failed');

    const dtcWarns = warn.mock.calls.filter(([msg]) =>
      String(msg).includes('DTC data chunk failed to load'));
    expect(dtcWarns).toHaveLength(1);
    // The status mechanism (statusCopy inline + toast mirror) carries the
    // user-facing message.
    expect(document.getElementById('statusCopy').textContent)
      .toContain('Diagnostic code database failed to load.');
    expect(VD.state.status.state).toBe('blocked');
    warn.mockRestore();
  });
});
