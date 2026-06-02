// Shared harness for the dashboard Playwright suite.
//
// openDashboard() injects a minimal `window.VoltTrackerAndroid` bridge BEFORE the page scripts run
// (mirroring how MainActivity#addJavascriptInterface exposes it in the WebView), then loads the
// real index.html and waits for the dashboard to finish bootstrapping. Tests then seed
// `window.VoltDashboard.state` and call the real render entry points to drive a specific screen
// deterministically — the same VD surface the jsdom suite uses, but in real Chromium.
'use strict';

// Runs in the browser context. Keep it self-contained (no closures over Node values): an all-stub
// VoltTrackerAndroid so the dashboard boots in "connected app" mode without a device. Tests
// override state directly after load, so these only need to be present + return well-formed JSON.
function installMockBridge() {
  const json = (value) => () => value;
  const noop = () => undefined;
  window.VoltTrackerAndroid = {
    listDevices: json('[]'),
    getLastDevice: json('{}'),
    getDeviceHistory: json('[]'),
    getStorageSummary: json('{}'),
    exportDebugBundle: json('{"ok":true,"path":"/tmp/debug"}'),
    getTrips: json('[]'),
    getInsights: json('{}'),
    getTripRoute: json('{}'),
    getRecentSessions: json('[]'),
    forceStopPackage: () => false,
    dashboardReady: noop,
    requestPermissions: noop,
    refreshDevices: noop,
    connect: noop,
    scan: noop,
    shareBackup: noop,
    shareEncryptedBackup: noop,
    restoreBackup: noop,
    restoreEncryptedBackup: noop,
    clearStoredData: noop,
    rememberDevice: noop,
    connectLast: noop,
    scanLast: noop,
    demo: noop,
    disconnect: noop,
    logClientError: noop,
    clearVehicleDtcCodes: noop,
    openExternalSearch: noop,
    cancelRetry: noop,
    tryReconnectNow: noop,
    openBluetoothSettings: noop,
    shareDiagnostics: noop,
    startTestConnection: noop,
    scheduleAdapterReadyNotify: noop,
    cancelAdapterReadyNotify: noop,
  };
}

/**
 * Loads the dashboard and waits until window.VoltDashboard is wired up.
 * @param {import('@playwright/test').Page} page
 */
async function openDashboard(page) {
  await page.addInitScript(installMockBridge);
  // Silence dashboard client-error logging from blowing up the page during tests.
  await page.goto('/index.html');
  await page.waitForFunction(
    () =>
      typeof window.VoltDashboard === 'object' &&
      window.VoltDashboard &&
      window.VoltDashboard.state &&
      typeof window.VoltDashboard.renderRealTrips === 'function',
    undefined,
    { timeout: 15_000 },
  );
}

/**
 * Switches the active view (drive/trips/map/charge/insights/settings).
 * @param {import('@playwright/test').Page} page
 */
async function setView(page, view) {
  await page.evaluate((v) => window.VoltDashboard.setView(v), view);
}

module.exports = { openDashboard, setView };
