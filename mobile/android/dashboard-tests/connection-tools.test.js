// connection-tools.ts — proactive-tools + event-notification/auto-scan toggle wiring.
//
// Each toggle reflects a native-owned setting (read once via getAutoConnectState /
// getEventNotificationState) and writes back through its own bridge setter. This exercises every
// toggle/button so the M1/M3 alert + auto-scan binders (bindEventNotifications/bindBoolToggle/
// bindThresholdToggle) and the existing proactive-tools handlers are covered, not just bound.
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

function fire(node, type) {
  node.dispatchEvent(new window.Event(type, { bubbles: true }));
}

describe('connection-tools toggles and buttons', () => {
  let bridge;

  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    bridge = createVoltBridgeFixture({
      setChargeCompleteNotify: vi.fn(),
      setNewDtcNotify: vi.fn(),
      setLowSocNotify: vi.fn(),
      setHighPackTempNotify: vi.fn(),
      setAutoScanOnConnect: vi.fn(),
      setAutoConnectEnabled: vi.fn(),
      startTestConnection: vi.fn(),
      shareDiagnostics: vi.fn(),
      shareDiagnosticsDigest: vi.fn(),
      scheduleAdapterReadyNotify: vi.fn(),
      cancelAdapterReadyNotify: vi.fn(),
    });
    await loadDashboard({ bridge });
  });

  it('charge-complete and new-DTC toggles write through their bridge setters', () => {
    const charge = document.getElementById('notifyChargeCompleteToggle');
    charge.checked = false;
    fire(charge, 'change');
    expect(bridge.setChargeCompleteNotify).toHaveBeenCalledWith(false);

    const dtc = document.getElementById('notifyNewDtcToggle');
    dtc.checked = true;
    fire(dtc, 'change');
    expect(bridge.setNewDtcNotify).toHaveBeenCalledWith(true);
  });

  it('low-SOC threshold toggle sends enabled + value, and re-sends when the value changes', () => {
    const toggle = document.getElementById('notifyLowSocToggle');
    const select = document.getElementById('notifyLowSocThreshold');
    toggle.checked = true;
    fire(toggle, 'change');
    expect(bridge.setLowSocNotify).toHaveBeenCalledWith(true, expect.any(Number));

    // Changing the threshold while enabled re-sends with the new value.
    if (select && select.options.length > 1) {
      select.value = select.options[select.options.length - 1].value;
      fire(select, 'change');
      const last = bridge.setLowSocNotify.mock.calls.at(-1);
      expect(last[0]).toBe(true);
      expect(last[1]).toBe(Number(select.value));
    }
  });

  it('high-pack-temp toggle and auto-scan-on-connect toggle write through the bridge', () => {
    const temp = document.getElementById('notifyHighTempToggle');
    temp.checked = true;
    fire(temp, 'change');
    expect(bridge.setHighPackTempNotify).toHaveBeenCalledWith(true, expect.any(Number));

    const autoScan = document.getElementById('autoScanOnConnectToggle');
    autoScan.checked = true;
    fire(autoScan, 'change');
    expect(bridge.setAutoScanOnConnect).toHaveBeenCalledWith(true);
  });

  it('auto-connect toggle writes the enabled flag', () => {
    const toggle = document.getElementById('autoConnectToggle');
    toggle.checked = false;
    fire(toggle, 'change');
    expect(bridge.setAutoConnectEnabled).toHaveBeenCalledWith(false);
  });

  it('test-connection and diagnostics buttons funnel into their bridge calls', () => {
    document.getElementById('testConnectionBtn')?.click();
    expect(bridge.startTestConnection).toHaveBeenCalled();

    document.getElementById('sendDiagnosticsBtn')?.click();
    expect(bridge.shareDiagnostics).toHaveBeenCalled();

    document.getElementById('sendAiDigestBtn')?.click();
    expect(bridge.shareDiagnosticsDigest).toHaveBeenCalled();
  });

  it('notify-when-ready schedules when enabled', () => {
    const toggle = document.getElementById('notifyWhenReadyToggle');
    expect(toggle).toBeTruthy();
    toggle.checked = true;
    fire(toggle, 'change');
    expect(bridge.scheduleAdapterReadyNotify).toHaveBeenCalled();
  });

  it('notify-when-ready cancels when disabled', () => {
    const toggle = document.getElementById('notifyWhenReadyToggle');
    expect(toggle).toBeTruthy();
    toggle.checked = false;
    fire(toggle, 'change');
    expect(bridge.cancelAdapterReadyNotify).toHaveBeenCalled();
  });
});
