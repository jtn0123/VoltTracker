// Behavioral coverage for actions.js.
//
// actions.js wires every "click" path in the dashboard to a bridge call.
// These tests poke the exported functions (VD.actions.*) directly so we
// can drive the bridge fixture without having to simulate DOM clicks.
//
// We override individual bridge methods with vi.fn() per test rather than
// extending the shared fixture — keeps the fixture surface tiny and lets
// each test assert exactly the args it cares about.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

// Seed a single fake adapter into the deviceSelect so getSelectedDevice()
// (which reads the DOM) returns something. Returns the {address,name}
// we just injected.
function seedSelectedDevice(VD, { address = 'AA:BB:CC:DD:EE:FF', name = 'TestOBD' } = {}) {
  VD.setDevices([{ address, name, obdCandidate: true }]);
  return { address, name };
}

// Reset every global the dashboard IIFEs touch so loadDashboard() can
// re-bootstrap cleanly. Mirrors the pattern in the other test files.
async function freshLoad(bridge) {
  document.body.innerHTML = '';
  delete window.VoltDashboard;
  delete window.VoltTrackerNative;
  delete window.VoltTrackerAndroid;
  return loadDashboard({ bridge });
}

describe('actions.js — bridge dispatch', () => {
  let bridge;
  let VD;
  let button;

  beforeEach(async () => {
    // Fake timers because every withBusy()-guarded path schedules a 600 ms
    // setTimeout to release the button. Without fake timers the timer fires
    // mid-suite and emits "release after dashboard teardown" warnings.
    vi.useFakeTimers();
    bridge = createVoltBridgeFixture({
      connect: vi.fn(),
      scan: vi.fn(),
      rememberDevice: vi.fn(),
      clearStoredData: vi.fn(),
      shareBackup: vi.fn(),
      shareEncryptedBackup: vi.fn(),
      restoreBackup: vi.fn(),
      restoreEncryptedBackup: vi.fn(),
    });
    await freshLoad(bridge);
    VD = window.VoltDashboard;
    // Every withBusy-guarded action needs a button — withBusy bails out
    // immediately if `button` is falsy, so dispatch tests would otherwise
    // be silent no-ops.
    button = document.createElement('button');
    document.body.appendChild(button);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('connectSelected(false) routes to bridge.connect with the selected adapter', () => {
    const device = seedSelectedDevice(VD);
    VD.actions.connectSelected(false, button);
    expect(bridge.connect).toHaveBeenCalledTimes(1);
    expect(bridge.connect).toHaveBeenCalledWith(device.address, device.name);
    expect(bridge.scan).not.toHaveBeenCalled();
    // rememberDevice always fires before the connect/scan so the adapter
    // shows up in history even if the connection attempt fails.
    expect(bridge.rememberDevice).toHaveBeenCalledWith(device.address, device.name);
  });

  it('connectSelected(true) routes to bridge.scan, not bridge.connect', () => {
    const device = seedSelectedDevice(VD);
    VD.actions.connectSelected(true, button);
    expect(bridge.scan).toHaveBeenCalledTimes(1);
    expect(bridge.scan).toHaveBeenCalledWith(device.address, device.name);
    expect(bridge.connect).not.toHaveBeenCalled();
  });

  it('connectSelected with no device sets a blocked status and skips the bridge call', () => {
    // setDevices([]) renders the "No paired adapters found" placeholder
    // option whose value is "" — getSelectedDevice() then returns null.
    VD.setDevices([]);
    VD.actions.connectSelected(false, button);
    expect(bridge.connect).not.toHaveBeenCalled();
    expect(bridge.scan).not.toHaveBeenCalled();
    expect(VD.state.status).toMatchObject({ state: 'blocked' });
    expect(VD.state.status.detail).toMatch(/adapter/i);
  });

  it('clearStorage() bails when the user cancels the confirm dialog', () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false);
    VD.actions.clearStorage(button);
    expect(confirmSpy).toHaveBeenCalledTimes(1);
    expect(bridge.clearStoredData).not.toHaveBeenCalled();
  });

  it('clearStorage() invokes the bridge after a confirmed prompt', () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    VD.actions.clearStorage(button);
    expect(bridge.clearStoredData).toHaveBeenCalledTimes(1);
  });

  it('shareBackup() invokes bridge.shareBackup after a confirmed prompt', () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    VD.actions.shareBackup(button);
    expect(bridge.shareBackup).toHaveBeenCalledTimes(1);
    expect(confirmSpy.mock.calls[0][0]).toMatch(/plaintext backup/i);
    expect(confirmSpy.mock.calls[0][0]).toMatch(/encrypted backup/i);
  });

  it('shareEncryptedBackup() passes the chosen passphrase to the bridge', () => {
    vi.spyOn(window, 'prompt').mockReturnValue('secret-pass');
    VD.actions.shareEncryptedBackup(button);
    expect(bridge.shareEncryptedBackup).toHaveBeenCalledWith('secret-pass');
  });

  it('previewDtcCodes() lazy-loads dictionaries before staging examples', async () => {
    expect(VD.dtcSampleCodes).toBeUndefined();
    await VD.actions.previewDtcCodes();
    expect(VD.dtcSampleCodes.length).toBeGreaterThan(0);
    expect(VD.state.storage.latestDiagnosticCodes).toHaveLength(VD.dtcSampleCodes.length);
    expect(VD.state.status.detail).toMatch(/example data loaded/i);
  });

  it('shareBackup() cancel path sets a ready status and skips the bridge', () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false);
    VD.actions.shareBackup(button);
    expect(bridge.shareBackup).not.toHaveBeenCalled();
    expect(VD.state.status).toMatchObject({ state: 'ready' });
    expect(VD.state.status.detail).toMatch(/cancel/i);
  });

  it('restoreBackup() invokes bridge.restoreBackup after a confirmed prompt', () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    VD.actions.restoreBackup(button);
    expect(bridge.restoreBackup).toHaveBeenCalledTimes(1);
  });

  it('restoreEncryptedBackup() requires a passphrase and confirm before picker launch', () => {
    vi.spyOn(window, 'prompt').mockReturnValue('secret-pass');
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    VD.actions.restoreEncryptedBackup(button);
    expect(bridge.restoreEncryptedBackup).toHaveBeenCalledWith('secret-pass');
  });
});

describe('actions.js — withBusy guard (double-tap suppression)', () => {
  // The guard lives inside actions.js as a closure (`withBusy`). It's
  // not directly exported, but every guard-protected entry point uses it,
  // so we drive it through connectSelected with a fake button passed via
  // the underlying handleAction path.
  let bridge;
  let VD;
  let button;

  beforeEach(async () => {
    vi.useFakeTimers();
    bridge = createVoltBridgeFixture({
      connect: vi.fn(),
      rememberDevice: vi.fn(),
    });
    await freshLoad(bridge);
    VD = window.VoltDashboard;
    seedSelectedDevice(VD);
    button = document.createElement('button');
    document.body.appendChild(button);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('disables the button for the 600 ms cooldown, then re-enables it', () => {
    VD.actions.connectSelected(false, button);
    // Bridge call fired synchronously inside withBusy.
    expect(bridge.connect).toHaveBeenCalledTimes(1);
    // Button is locked immediately and stays locked just under the deadline.
    expect(button.disabled).toBe(true);
    expect(button.dataset.busy).toBe('1');
    expect(button.classList.contains('busy')).toBe(true);
    vi.advanceTimersByTime(599);
    expect(button.disabled).toBe(true);
    // Release fires on the 600 ms boundary.
    vi.advanceTimersByTime(1);
    expect(button.disabled).toBe(false);
    expect(button.dataset.busy).toBe('0');
    expect(button.classList.contains('busy')).toBe(false);
  });

  it('rejects a second connect on the same button while the cooldown is active', () => {
    VD.actions.connectSelected(false, button);
    expect(bridge.connect).toHaveBeenCalledTimes(1);
    // Second tap mid-cooldown: should be a no-op — button still busy, so
    // withBusy bails before invoking the wrapped fn.
    VD.actions.connectSelected(false, button);
    expect(bridge.connect).toHaveBeenCalledTimes(1);
    // After the cooldown elapses, a third tap is allowed again.
    vi.advanceTimersByTime(600);
    VD.actions.connectSelected(false, button);
    expect(bridge.connect).toHaveBeenCalledTimes(2);
  });

  it('releases the button immediately when the wrapped call throws', () => {
    // Make the bridge call throw so withBusy hits its catch branch and
    // releases without waiting for the 600 ms timer.
    bridge.connect = vi.fn(() => { throw new Error('boom'); });
    expect(() => VD.actions.connectSelected(false, button)).toThrow(/boom/);
    // No timer advance — release fired synchronously from the catch.
    expect(button.disabled).toBe(false);
    expect(button.dataset.busy).toBe('0');
    expect(button.classList.contains('busy')).toBe(false);
  });
});

describe('actions.js — map privacy controls', () => {
  beforeEach(async () => {
    vi.useRealTimers();
    document.body.innerHTML = '';
    window.localStorage.clear();
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  it('keeps remote basemap tiles off by default and persists an explicit opt-in', () => {
    const VD = window.VoltDashboard;
    const button = document.getElementById('mapTilesBtn');

    expect(VD.state.mapRemoteTilesEnabled).toBe(false);
    expect(button.getAttribute('aria-pressed')).toBe('false');

    button.click();

    expect(VD.state.mapRemoteTilesEnabled).toBe(true);
    expect(window.localStorage.getItem('volttracker.map.remoteTiles')).toBe('1');
    expect(button.getAttribute('aria-pressed')).toBe('true');
    expect(button.getAttribute('aria-label')).toMatch(/disable remote map tiles/i);
  });
});
