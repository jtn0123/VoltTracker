// Fake of the `VoltTrackerAndroid` JS bridge that the WebView normally gets
// from `MainActivity#addJavascriptInterface`. Each method here mirrors a
// `@JavascriptInterface` method on `VoltBridge.kt`. Keep this list in sync
// with that file — if Android adds or renames a method, update both places.
//
// Return values default to JSON strings (the real bridge returns Strings),
// or `undefined` for void methods. Test code can override any method with
// `vi.fn()` to assert how the dashboard calls into Android.
export function createVoltBridgeFixture(overrides = {}) {
  const stub = (returnValue) => () => returnValue;
  const voidStub = () => undefined;
  const base = {
    // String returners (real bridge returns JSON-encoded Strings).
    listDevices: stub('[]'),
    getLastDevice: stub('{}'),
    getDeviceHistory: stub('[]'),
    getAutoConnectState: stub('{"enabled":false,"available":false}'),
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
    tpmsScan: voidStub,
    detailProbe: voidStub,
    exportDetailedSignalLog: stub('{"ok":true,"item":{"id":1}}'),
    exportDetailedSignalLogs: stub('{"ok":true,"items":[]}'),
    deleteDetailedSignalLog: voidStub,
    markTripNotTrip: voidStub,
    shareBackup: voidStub,
    shareEncryptedBackup: voidStub,
    restoreBackup: voidStub,
    restoreEncryptedBackup: voidStub,
    clearStoredData: voidStub,
    rememberDevice: voidStub,
    setAutoConnectEnabled: voidStub,
    connectLast: voidStub,
    scanLast: voidStub,
    tpmsScanLast: voidStub,
    detailProbeLast: voidStub,
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

// Names of every method the real VoltBridge.kt exposes via
// @JavascriptInterface. The ABI test cross-references this list with what
// VoltBridge.kt actually advertises.
export const VOLT_BRIDGE_METHODS = Object.freeze([
  'listDevices',
  'dashboardReady',
  'requestPermissions',
  'refreshDevices',
  'connect',
  'scan',
  'tpmsScan',
  'detailProbe',
  'getLastDevice',
  'getDeviceHistory',
  'getAutoConnectState',
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
  'setAutoConnectEnabled',
  'connectLast',
  'scanLast',
  'tpmsScanLast',
  'detailProbeLast',
  'exportDetailedSignalLog',
  'exportDetailedSignalLogs',
  'deleteDetailedSignalLog',
  'markTripNotTrip',
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
