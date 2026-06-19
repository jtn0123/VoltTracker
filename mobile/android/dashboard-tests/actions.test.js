// Behavioral coverage for actions.ts.
//
// actions.ts wires every "click" path in the dashboard to a bridge call.
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

function appDialog() {
  return document.getElementById('appDialog');
}

function appDialogMessage() {
  return document.getElementById('appDialogMessage').textContent;
}

async function clickAppDialogConfirm() {
  document.getElementById('appDialogConfirm').click();
  await Promise.resolve();
}

async function clickAppDialogCancel() {
  document.getElementById('appDialogCancel').click();
  await Promise.resolve();
}

function enterAppDialogInput(value) {
  const input = document.getElementById('appDialogInput');
  input.value = value;
  input.dispatchEvent(new window.Event('input', { bubbles: true }));
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

async function flushStartupReady() {
  await vi.advanceTimersByTimeAsync(50);
  await Promise.resolve();
}

describe('actions.ts — bridge dispatch', () => {
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
      connectLast: vi.fn(),
      scan: vi.fn(),
      scanProfile: vi.fn(),
      tpmsScan: vi.fn(),
      detailProbe: vi.fn(),
      exportDetailedSignalLog: vi.fn(() => '{"ok":true,"item":{"id":5}}'),
      exportDetailedSignalLogs: vi.fn(() => '{"ok":true,"items":[]}'),
      deleteDetailedSignalLog: vi.fn(),
      rememberDevice: vi.fn(),
      clearStoredData: vi.fn(),
      shareBackup: vi.fn(),
      shareEncryptedBackup: vi.fn(),
      restoreBackup: vi.fn(),
      restoreEncryptedBackup: vi.fn(),
      requestPermissions: vi.fn(),
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

  it('startup waits for dashboardReady native publish instead of pre-ready device refresh', async () => {
    const startupBridge = createVoltBridgeFixture({
      dashboardReady: vi.fn(),
      refreshDevices: vi.fn(),
      listDevices: vi.fn(() => '[]'),
      getDeviceHistory: vi.fn(() => '[]'),
    });

    await freshLoad(startupBridge);
    await flushStartupReady();

    expect(startupBridge.dashboardReady).toHaveBeenCalledTimes(1);
    expect(startupBridge.refreshDevices).not.toHaveBeenCalled();
    expect(startupBridge.listDevices).not.toHaveBeenCalled();
    expect(startupBridge.getDeviceHistory).not.toHaveBeenCalled();
  });

  it('startup refreshDevices falls back to synchronous getters for older native builds', async () => {
    const startupBridge = createVoltBridgeFixture({
      dashboardReady: undefined,
      refreshDevices: undefined,
      listDevices: vi.fn(() => '[{"address":"AA:BB:CC:DD:EE:FF","name":"TestOBD"}]'),
      getDeviceHistory: vi.fn(() => '[]'),
    });

    await freshLoad(startupBridge);

    expect(startupBridge.listDevices).toHaveBeenCalledTimes(1);
    expect(startupBridge.getDeviceHistory).toHaveBeenCalledTimes(1);
    expect(document.getElementById('deviceSelect').options).toHaveLength(1);
  });

  it('connectSelected(false) routes to bridge.connect with the selected adapter', () => {
    const device = seedSelectedDevice(VD);
    VD.actions.connectSelected(false, button);
    expect(bridge.connect).toHaveBeenCalledTimes(1);
    expect(bridge.connect).toHaveBeenCalledWith(device.address, device.name);
    expect(bridge.scan).not.toHaveBeenCalled();
    expect(VD.state.status).toMatchObject({
      state: 'connecting',
      detail: `Connecting to ${device.name}...`,
      lastAddress: device.address,
      lastName: device.name,
    });
    expect(document.getElementById('connectBtn').textContent).toBe('Connecting...');
    expect(document.getElementById('connectBtn').dataset.primaryAction).toBe('stop');
    // Native connect/scan remembers the adapter once; JS must not pre-remember or
    // history counts inflate on a single click.
    expect(bridge.rememberDevice).not.toHaveBeenCalled();
  });

  it('connectSelected(true) routes to bridge.scan, not bridge.connect', () => {
    const device = seedSelectedDevice(VD);
    VD.actions.connectSelected(true, button);
    expect(bridge.scan).toHaveBeenCalledTimes(1);
    expect(bridge.scan).toHaveBeenCalledWith(device.address, device.name);
    expect(bridge.scanProfile).not.toHaveBeenCalled();
    expect(bridge.connect).not.toHaveBeenCalled();
    expect(VD.state.status).toMatchObject({
      state: 'scanning',
      detail: `Starting scan with ${device.name}...`,
    });
    expect(document.getElementById('connectBtn').textContent).toBe('Scanning...');
  });

  it('quickScanSelected() routes to bridge.scanProfile with the quick DTC profile', () => {
    const device = seedSelectedDevice(VD);
    VD.actions.quickScanSelected(button);
    expect(bridge.scanProfile).toHaveBeenCalledTimes(1);
    expect(bridge.scanProfile).toHaveBeenCalledWith(device.address, device.name, 'quick_dtc');
    expect(bridge.scan).not.toHaveBeenCalled();
    expect(bridge.connect).not.toHaveBeenCalled();
    expect(VD.state.status).toMatchObject({
      state: 'scanning',
      detail: `Starting quick code scan with ${device.name}...`,
    });
  });

  it('tpmsScanSelected() routes to staged bridge.detailProbe with the selected adapter', () => {
    const device = seedSelectedDevice(VD);
    VD.actions.tpmsScanSelected(button);
    expect(bridge.detailProbe).toHaveBeenCalledTimes(1);
    expect(bridge.detailProbe).toHaveBeenCalledWith(device.address, device.name, 'tires');
    expect(bridge.tpmsScan).not.toHaveBeenCalled();
    expect(bridge.scan).not.toHaveBeenCalled();
    expect(bridge.connect).not.toHaveBeenCalled();
  });

  it('detailProbeSelected() sends the selected stage', () => {
    const device = seedSelectedDevice(VD);
    VD.state.signalProbeStage = 'experimental';
    VD.actions.detailProbeSelected(button);
    expect(bridge.detailProbe).toHaveBeenCalledWith(device.address, device.name, 'experimental');
  });

  it('connectSelected with no device sets a blocked status and skips the bridge call', () => {
    // setDevices([]) renders the "No paired adapters found" placeholder
    // option whose value is "" — getSelectedDevice() then returns null.
    VD.setDevices([]);
    VD.actions.connectSelected(false, button);
    expect(bridge.connect).not.toHaveBeenCalled();
    expect(bridge.scan).not.toHaveBeenCalled();
    expect(bridge.tpmsScan).not.toHaveBeenCalled();
    expect(bridge.detailProbe).not.toHaveBeenCalled();
    expect(VD.state.status).toMatchObject({ state: 'blocked' });
    expect(VD.state.status.detail).toMatch(/adapter/i);
    expect(document.getElementById('appStateSummary').textContent).toMatch(/adapter/i);
    expect(document.getElementById('statusCopy').textContent).toMatch(/adapter/i);
    expect(document.getElementById('reviewWarnings').textContent).toMatch(/adapter/i);
  });

  it('connectSelected with no device and a missing Bluetooth permission fires the Android prompt', () => {
    VD.setDevices([]);
    VD.setAppState(JSON.stringify({
      permissions: { bluetooth: false, bluetoothPermission: false, bluetoothEnabled: true },
    }));

    VD.actions.connectSelected(false, button);

    expect(bridge.requestPermissions).toHaveBeenCalledTimes(1);
    expect(bridge.connect).not.toHaveBeenCalled();
    expect(VD.state.status).toMatchObject({ state: 'blocked' });
    expect(VD.state.status.detail).toMatch(/nearby devices/i);
    expect(document.getElementById('statusCopy').textContent).toMatch(/nearby devices/i);
  });

  it('granting the Bluetooth permission auto-resumes the parked connect', () => {
    VD.setDevices([]);
    VD.setAppState(JSON.stringify({ permissions: { bluetoothPermission: false, bluetoothEnabled: true } }));
    VD.actions.connectSelected(false, button);
    expect(bridge.connect).not.toHaveBeenCalled();

    // Android grant flow: the device list repopulates first (auto-selecting the
    // OBD candidate), then the native status broadcast lands with bluetoothReady.
    VD.setDevices([{ address: 'AA:BB:CC:DD:EE:FF', name: 'TestOBD', obdCandidate: true }]);
    window.VoltTrackerNative.setStatus({
      state: 'ready',
      detail: 'Bluetooth permission granted.',
      bluetoothReady: true,
    });

    expect(bridge.connect).toHaveBeenCalledTimes(1);
    expect(bridge.connect).toHaveBeenCalledWith('AA:BB:CC:DD:EE:FF', 'TestOBD');
    expect(VD.state.status).toMatchObject({ state: 'connecting' });
  });

  it('a denied-permission broadcast disarms the parked connect', () => {
    VD.setDevices([]);
    VD.setAppState(JSON.stringify({ permissions: { bluetoothPermission: false, bluetoothEnabled: true } }));
    VD.actions.connectSelected(false, button);

    window.VoltTrackerNative.setStatus({
      state: 'blocked',
      detail: 'Bluetooth permission was denied.',
      blocked: true,
      bluetoothReady: false,
    });
    VD.setDevices([{ address: 'AA:BB:CC:DD:EE:FF', name: 'TestOBD', obdCandidate: true }]);
    window.VoltTrackerNative.setStatus({ state: 'ready', bluetoothReady: true });

    expect(bridge.connect).not.toHaveBeenCalled();
  });

  it('grant with nothing paired explains pairing instead of connecting', () => {
    VD.setDevices([]);
    VD.setAppState(JSON.stringify({ permissions: { bluetoothPermission: false, bluetoothEnabled: true } }));
    VD.actions.connectSelected(false, button);

    window.VoltTrackerNative.setStatus({ state: 'ready', bluetoothReady: true });

    expect(bridge.connect).not.toHaveBeenCalled();
    expect(VD.state.status.detail).toMatch(/pair the adapter/i);
  });

  it('connectSelected with Bluetooth turned off says so instead of a generic block', () => {
    VD.setDevices([]);
    VD.setAppState(JSON.stringify({
      permissions: { bluetooth: false, bluetoothPermission: true, bluetoothEnabled: false },
    }));

    VD.actions.connectSelected(false, button);

    expect(bridge.requestPermissions).not.toHaveBeenCalled();
    expect(bridge.connect).not.toHaveBeenCalled();
    expect(VD.state.status.detail).toMatch(/turned off/i);
  });

  it('connectSelected with permission granted but nothing paired explains pairing', () => {
    VD.setDevices([]);
    VD.setAppState(JSON.stringify({
      permissions: { bluetooth: true, bluetoothPermission: true, bluetoothEnabled: true },
    }));

    VD.actions.connectSelected(false, button);

    expect(bridge.requestPermissions).not.toHaveBeenCalled();
    expect(VD.state.status.detail).toMatch(/pair the adapter/i);
  });

  it('detailProbeSelected with no device mirrors the blocked reason into body copy', () => {
    VD.setDevices([]);
    VD.actions.detailProbeSelected(button);

    expect(bridge.detailProbe).not.toHaveBeenCalled();
    expect(VD.state.status).toMatchObject({ state: 'blocked' });
    expect(document.getElementById('appStateSummary').textContent).toMatch(/adapter/i);
    expect(document.getElementById('reviewWarnings').textContent).toMatch(/adapter/i);
    expect(document.getElementById('enhancedBadge').textContent).toBe('blocked');
    expect(document.getElementById('enhancedBadge').dataset.state).toBe('blocked');
  });

  it('Last with no remembered adapter gives visible body feedback instead of a silent bridge call', () => {
    bridge.getLastDevice = vi.fn(() => '{}');

    VD.actions.handleAction('last', button);

    expect(bridge.connectLast).not.toHaveBeenCalled();
    expect(VD.state.status).toMatchObject({ state: 'blocked' });
    expect(VD.state.status.detail).toMatch(/connect once/i);
    expect(document.getElementById('appStateSummary').textContent).toMatch(/connect once/i);
    expect(document.getElementById('statusCopy').textContent).toMatch(/connect once/i);
  });

  it('Last with a remembered adapter still calls bridge.connectLast', () => {
    bridge.getLastDevice = vi.fn(() => JSON.stringify({
      address: 'AA:BB:CC:DD:EE:FF',
      name: 'TestOBD',
    }));

    VD.actions.handleAction('last', button);

    expect(bridge.connectLast).toHaveBeenCalledTimes(1);
    expect(VD.state.status).toMatchObject({
      state: 'connecting',
      detail: 'Connecting to TestOBD...',
    });
    expect(document.getElementById('connectBtn').textContent).toBe('Connecting...');
  });

  it('the primary connection button becomes the disconnect action while active', () => {
    bridge.disconnect = vi.fn();

    VD.setStatus({ state: 'connected', detail: 'Logging live OBD data.' });

    const primary = document.getElementById('connectBtn');
    expect(primary.textContent).toBe('Disconnect');
    expect(primary.dataset.primaryAction).toBe('stop');
    expect(document.getElementById('lastBtn').hidden).toBe(true);

    primary.click();

    expect(bridge.disconnect).toHaveBeenCalledTimes(1);
  });

  it('deleteSignalLog() uses the app dialog before deleting one evidence row', async () => {
    await VD.actions.deleteSignalLog(5);
    expect(appDialog().hidden).toBe(false);
    expect(appDialogMessage()).toMatch(/delete this saved detailed signal/i);
    await clickAppDialogConfirm();
    expect(bridge.deleteDetailedSignalLog).toHaveBeenCalledWith('5');
  });

  it('exportSignalLog() copies one exported evidence row', async () => {
    const originalCreate = Object.getOwnPropertyDescriptor(window.URL, 'createObjectURL');
    Object.defineProperty(window.URL, 'createObjectURL', {
      configurable: true,
      value: undefined,
    });
    const writeText = vi.fn(() => Promise.resolve());
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    });

    try {
      await VD.actions.exportSignalLog(5);
      await Promise.resolve();

      expect(bridge.exportDetailedSignalLog).toHaveBeenCalledWith('5');
      expect(writeText).toHaveBeenCalledWith(expect.stringContaining('"id"'));
    } finally {
      if (originalCreate) Object.defineProperty(window.URL, 'createObjectURL', originalCreate);
      else delete window.URL.createObjectURL;
    }
  });

  it('exportSignalLogs() downloads all evidence rows when browser downloads are available', async () => {
    const originalCreate = Object.getOwnPropertyDescriptor(window.URL, 'createObjectURL');
    const originalRevoke = Object.getOwnPropertyDescriptor(window.URL, 'revokeObjectURL');
    const createObjectURL = vi.fn(() => 'blob:volt-logs');
    const revokeObjectURL = vi.fn();
    let downloadedName = '';
    const clickSpy = vi.spyOn(window.HTMLAnchorElement.prototype, 'click').mockImplementation(function click() {
      downloadedName = this.download;
    });
    Object.defineProperty(window.URL, 'createObjectURL', {
      configurable: true,
      value: createObjectURL,
    });
    Object.defineProperty(window.URL, 'revokeObjectURL', {
      configurable: true,
      value: revokeObjectURL,
    });

    try {
      await VD.actions.exportSignalLogs();

      expect(bridge.exportDetailedSignalLogs).toHaveBeenCalledTimes(1);
      expect(createObjectURL).toHaveBeenCalledTimes(1);
      expect(clickSpy).toHaveBeenCalledTimes(1);
      expect(downloadedName).toBe('volttracker-detailed-signal-logs.json');
      expect(VD.state.status.detail).toBe('Detailed signal logs exported.');
    } finally {
      if (originalCreate) Object.defineProperty(window.URL, 'createObjectURL', originalCreate);
      else delete window.URL.createObjectURL;
      if (originalRevoke) Object.defineProperty(window.URL, 'revokeObjectURL', originalRevoke);
      else delete window.URL.revokeObjectURL;
    }
  });

  it('clearStorage() bails when the user cancels the app dialog', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm');
    await VD.actions.clearStorage(button);
    expect(appDialog().hidden).toBe(false);
    await clickAppDialogCancel();
    expect(confirmSpy).not.toHaveBeenCalled();
    expect(bridge.clearStoredData).not.toHaveBeenCalled();
  });

  it('clearStorage() invokes the bridge after app-dialog confirmation', async () => {
    await VD.actions.clearStorage(button);
    expect(appDialogMessage()).toMatch(/clear local obd sessions/i);
    await clickAppDialogConfirm();
    expect(bridge.clearStoredData).toHaveBeenCalledTimes(1);
  });

  it('shareBackup() invokes bridge.shareBackup after app-dialog confirmation', async () => {
    await VD.actions.shareBackup(button);
    expect(appDialogMessage()).toMatch(/plaintext backup/i);
    expect(appDialogMessage()).toMatch(/encrypted backup/i);
    await clickAppDialogConfirm();
    expect(bridge.shareBackup).toHaveBeenCalledTimes(1);
  });

  it('shareEncryptedBackup() passes the app-dialog passphrase to the bridge', async () => {
    await VD.actions.shareEncryptedBackup(button);
    expect(appDialog().hidden).toBe(false);
    enterAppDialogInput('secret-pass');
    await clickAppDialogConfirm();
    expect(bridge.shareEncryptedBackup).toHaveBeenCalledWith('secret-pass');
  });

  it('previewDtcCodes() lazy-loads dictionaries before staging examples', async () => {
    expect(VD.dtcSampleCodes).toBeUndefined();
    await VD.actions.previewDtcCodes();
    expect(VD.dtcSampleCodes.length).toBeGreaterThan(0);
    expect(VD.state.storage.latestDiagnosticCodes).toHaveLength(VD.dtcSampleCodes.length);
    expect(VD.state.status.detail).toMatch(/example data loaded/i);
  });

  it('shareBackup() cancel path sets a ready status and skips the bridge', async () => {
    await VD.actions.shareBackup(button);
    await clickAppDialogCancel();
    expect(bridge.shareBackup).not.toHaveBeenCalled();
    expect(VD.state.status).toMatchObject({ state: 'ready' });
    expect(VD.state.status.detail).toMatch(/cancel/i);
  });

  it('restoreBackup() invokes bridge.restoreBackup without a pre-pick browser dialog', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm');
    await VD.actions.restoreBackup(button);
    expect(confirmSpy).not.toHaveBeenCalled();
    expect(bridge.restoreBackup).toHaveBeenCalledTimes(1);
  });

  it('restoreEncryptedBackup() requires an app-dialog passphrase before picker launch', async () => {
    await VD.actions.restoreEncryptedBackup(button);
    enterAppDialogInput('secret-pass');
    await clickAppDialogConfirm();
    expect(bridge.restoreEncryptedBackup).toHaveBeenCalledWith('secret-pass');
  });
});

describe('actions.ts — withBusy guard (double-tap suppression)', () => {
  // The guard lives inside actions.ts as a closure (`withBusy`). It's
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

describe('actions.ts — desktop drag scrolling', () => {
  // Capture the original descriptors so the dimension overrides below don't
  // leak into later suites (vi.restoreAllMocks() does not undo defineProperty).
  let originalInnerHeight;
  let originalScrollHeight;

  beforeEach(async () => {
    await freshLoad();
    originalInnerHeight = Object.getOwnPropertyDescriptor(window, 'innerHeight');
    originalScrollHeight = Object.getOwnPropertyDescriptor(document.documentElement, 'scrollHeight');
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 800 });
    Object.defineProperty(document.documentElement, 'scrollHeight', { configurable: true, value: 1800 });
  });

  afterEach(() => {
    if (originalInnerHeight) Object.defineProperty(window, 'innerHeight', originalInnerHeight);
    else delete window.innerHeight;
    if (originalScrollHeight) Object.defineProperty(document.documentElement, 'scrollHeight', originalScrollHeight);
    else delete document.documentElement.scrollHeight;
    vi.restoreAllMocks();
  });

  function pointerEvent(type, target, { x, y }) {
    const event = new window.MouseEvent(type, {
      bubbles: true,
      cancelable: true,
      button: 0,
      clientX: x,
      clientY: y,
    });
    Object.defineProperty(event, 'pointerId', { value: 1 });
    Object.defineProperty(event, 'pointerType', { value: 'mouse' });
    target.dispatchEvent(event);
    return event;
  }

  it('lets desktop preview users drag page chrome to scroll vertically', () => {
    const scrollBy = vi.fn();
    window.VoltDashboard.canScrollApp = vi.fn(() => true);
    window.VoltDashboard.scrollAppBy = scrollBy;
    const target = document.getElementById('view-drive');

    pointerEvent('pointerdown', target, { x: 240, y: 620 });
    pointerEvent('pointermove', target, { x: 240, y: 500 });

    expect(scrollBy).toHaveBeenCalledWith(120);
    expect(document.body.classList.contains('is-page-dragging')).toBe(true);

    pointerEvent('pointerup', target, { x: 240, y: 500 });
    expect(document.body.classList.contains('is-page-dragging')).toBe(false);
  });

  it('does not turn button drags into page scrolls', () => {
    const scrollBy = vi.fn();
    window.VoltDashboard.canScrollApp = vi.fn(() => true);
    window.VoltDashboard.scrollAppBy = scrollBy;
    const button = document.querySelector('[data-nav="drive"]');

    pointerEvent('pointerdown', button, { x: 40, y: 760 });
    pointerEvent('pointermove', button, { x: 40, y: 620 });

    expect(scrollBy).not.toHaveBeenCalled();
  });
});

describe('actions.ts — map tile policy', () => {
  beforeEach(async () => {
    vi.useRealTimers();
    document.body.innerHTML = '';
    window.localStorage.setItem('volttracker.map.remoteTiles', '0');
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  it('keeps remote basemap tiles always enabled and removes the opt-out control', () => {
    const VD = window.VoltDashboard;
    const button = document.getElementById('mapTilesBtn');

    expect(VD.state.mapRemoteTilesEnabled).toBe(true);
    expect(button).toBeNull();
  });
});

describe('actions.ts — browser preview controls', () => {
  beforeEach(async () => {
    vi.useRealTimers();
    document.body.innerHTML = '';
    window.localStorage.clear();
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard({ withBridge: false });
  });

  it('turns the primary drive action into Start demo when no Android bridge exists', () => {
    const button = document.getElementById('connectBtn');
    expect(window.VoltDashboard.bridge).toBeNull();
    expect(button.dataset.primaryAction).toBe('demo');
    expect(button.textContent).toMatch(/start demo/i);
  });
});
