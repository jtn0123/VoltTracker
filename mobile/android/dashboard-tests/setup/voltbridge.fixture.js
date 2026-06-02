// Fake of the `VoltTrackerAndroid` JS bridge that the WebView normally gets
// from `MainActivity#addJavascriptInterface`. Each method here mirrors a
// `@JavascriptInterface` method on `VoltBridge.java`. Keep this list in sync
// with that file — if Java adds or renames a method, update both places.
//
// Return values default to JSON strings (the real bridge returns Strings),
// or `undefined` for void methods. Test code can override any method with
// `vi.fn()` to assert how the dashboard JS calls in.
export function createVoltBridgeFixture(overrides = {}) {
  const stub = (returnValue) => () => returnValue;
  const voidStub = () => undefined;
  const base = {
    // String returners (real bridge returns JSON-encoded Strings).
    listDevices: stub('[]'),
    getLastDevice: stub('{}'),
    getDeviceHistory: stub('[]'),
    getStorageSummary: stub('{}'),
    exportDebugBundle: stub('{"ok":true,"path":"/tmp/debug"}'),
    getTrips: stub('[]'),
    getInsights: stub('{}'),
    getTripRoute: stub('{}'),

    // void methods that hand work off to MainActivity.
    dashboardReady: voidStub,
    requestPermissions: voidStub,
    refreshDevices: voidStub,
    connect: voidStub,
    scan: voidStub,
    shareBackup: voidStub,
    shareEncryptedBackup: voidStub,
    restoreBackup: voidStub,
    restoreEncryptedBackup: voidStub,
    clearStoredData: voidStub,
    rememberDevice: voidStub,
    connectLast: voidStub,
    scanLast: voidStub,
    demo: voidStub,
    disconnect: voidStub,
    logClientError: voidStub,
    clearVehicleDtcCodes: voidStub,
    openExternalSearch: voidStub,
    getRecentSessions: stub('[]'),
    forceStopPackage: stub(false),
    cancelRetry: voidStub,
    tryReconnectNow: voidStub,
    openBluetoothSettings: voidStub,
    shareDiagnostics: voidStub,
    startTestConnection: voidStub,
    scheduleAdapterReadyNotify: voidStub,
    cancelAdapterReadyNotify: voidStub,
  };
  return { ...base, ...overrides };
}

// Names of every method the real VoltBridge.java exposes via
// @JavascriptInterface. The ABI test cross-references this list with what
// VoltBridge.java actually advertises.
export const VOLT_BRIDGE_METHODS = Object.freeze([
  'listDevices',
  'dashboardReady',
  'requestPermissions',
  'refreshDevices',
  'connect',
  'scan',
  'getLastDevice',
  'getDeviceHistory',
  'getStorageSummary',
  'exportDebugBundle',
  'shareBackup',
  'shareEncryptedBackup',
  'restoreBackup',
  'restoreEncryptedBackup',
  'getTrips',
  'getInsights',
  'getTripRoute',
  'clearStoredData',
  'rememberDevice',
  'connectLast',
  'scanLast',
  'demo',
  'disconnect',
  'logClientError',
  'clearVehicleDtcCodes',
  'openExternalSearch',
  'getRecentSessions',
  'forceStopPackage',
  'cancelRetry',
  'tryReconnectNow',
  'openBluetoothSettings',
  'shareDiagnostics',
  'startTestConnection',
  'scheduleAdapterReadyNotify',
  'cancelAdapterReadyNotify',
]);
