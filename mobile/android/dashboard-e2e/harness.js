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
    detailProbe: noop,
    exportDetailedSignalLog: json('{"ok":true,"item":{"id":5}}'),
    exportDetailedSignalLogs: json('{"ok":true,"items":[{"id":1,"name":"TPMS candidate"}]}'),
    deleteDetailedSignalLog: noop,
    startTestConnection: noop,
    scheduleAdapterReadyNotify: noop,
    cancelAdapterReadyNotify: noop,
  };
}

async function waitForDashboardReady(page, timeout) {
  await page.waitForFunction(
    () =>
      typeof window.VoltDashboard === 'object' &&
      window.VoltDashboard &&
      window.VoltDashboard.state &&
      typeof window.VoltDashboard.setView === 'function' &&
      typeof window.VoltDashboard.setStatus === 'function',
    undefined,
    { timeout },
  );
}

/**
 * Loads the dashboard and waits until window.VoltDashboard is wired up.
 * @param {import('@playwright/test').Page} page
 * @param {{ fixedTime?: string | number | Date, withBridge?: boolean }} [opts] when fixedTime is set, Date.now()/new
 *   Date() return that instant for the whole page — required for visual snapshots so relative
 *   timestamps ("2 days ago") don't drift the baseline. Must be set before the page renders.
 */
async function openDashboard(page, opts = {}) {
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err && err.message ? err.message : String(err)));
  if (opts.fixedTime !== undefined) {
    await page.clock.setFixedTime(new Date(opts.fixedTime));
  }
  if (opts.withBridge !== false) {
    await page.addInitScript(installMockBridge);
  }
  await page.goto('/index.html');
  try {
    await waitForDashboardReady(page, 15_000);
  } catch (firstError) {
    // The local static server can occasionally deliver the HTML/CSS while Chromium never executes
    // the bundled app script for that page. A single cache-busted retry keeps the smoke suite from
    // failing before it reaches the UI assertion, while persistent script errors still fail below.
    await page.goto(`/index.html?e2eRetry=${Date.now()}`);
    try {
      await waitForDashboardReady(page, 20_000);
    } catch (secondError) {
      const detail = await page
        .evaluate(() => ({
          readyState: document.readyState,
          hasVoltDashboard: typeof window.VoltDashboard,
          dashboardKeys:
            window.VoltDashboard && typeof window.VoltDashboard === 'object'
              ? Object.keys(window.VoltDashboard).slice(0, 12)
              : [],
          title: document.getElementById('screenTitle')?.textContent || '',
        }))
        .catch(() => ({}));
      throw new Error(
        `Dashboard did not become ready after reload. First wait: ${firstError.message}; second wait: ${
          secondError.message
        }; pageErrors=${JSON.stringify(pageErrors)}; detail=${JSON.stringify(detail)}`,
      );
    }
  }
}

/**
 * Switches the active view (drive/map/charge/insights/diagnostics/settings).
 * @param {import('@playwright/test').Page} page
 */
async function setView(page, view) {
  await page.evaluate((v) => window.VoltDashboard.setView(v), view);
}

module.exports = { openDashboard, setView };
