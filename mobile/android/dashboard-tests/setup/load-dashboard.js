// Loads the production dashboard JS bundle into the current jsdom window.
//
// The dashboard files are pure side-effecting browser modules that mutate
// `window`, so we execute them in the same order the WebView would (see
// `app/src/main/dashboard-src/index.template.html`). Use Node's module loader
// instead of dynamic script evaluation so Vitest coverage can attribute execution to
// the real dashboard files.
//
// The DOM is a hand-picked subset of `index.html` covering every id the
// bootstrap path dereferences without a null check — see the comments in
// actions.js / core.js / map.js. If you add a new bare `el("...").foo` to
// the dashboard JS, add the corresponding placeholder here.
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import {
  clearInterval as nodeClearInterval,
  clearTimeout as nodeClearTimeout,
  setInterval as nodeSetInterval,
  setTimeout as nodeSetTimeout,
} from 'node:timers';
import { fileURLToPath } from 'node:url';

import { vi } from 'vitest';

import { createVoltBridgeFixture } from './voltbridge.fixture.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const DASHBOARD_INDEX_HTML = resolve(HERE, '../../app/src/main/assets/dashboard/index.html');

const DASHBOARD_MODULE_LOADERS = {
  'actions.js': () => import('../../app/src/main/dashboard-src/js/actions.js'),
  'connection-status.js': () => import('../../app/src/main/dashboard-src/js/connection-status.ts'),
  'connection-tools.js': () => import('../../app/src/main/dashboard-src/js/connection-tools.ts'),
  'core.js': () => import('../../app/src/main/dashboard-src/js/core.ts'),
  'demo-data.js': () => import('../../app/src/main/dashboard-src/js/demo-data.ts'),
  'drive.js': () => import('../../app/src/main/dashboard-src/js/drive.ts'),
  'dtc-causes.js': () => import('../../app/src/main/dashboard-src/js/dtc-causes.ts'),
  'dtc-lookup.js': () => import('../../app/src/main/dashboard-src/js/dtc-lookup.ts'),
  'map.js': () => import('../../app/src/main/dashboard-src/js/map.ts'),
  'panels.js': () => import('../../app/src/main/dashboard-src/js/panels.js'),
  'scrubber.js': () => import('../../app/src/main/dashboard-src/js/scrubber.ts'),
  'telemetry.js': () => import('../../app/src/main/dashboard-src/js/telemetry.ts'),
  'troubleshooter.js': () => import('../../app/src/main/dashboard-src/js/troubleshooter.ts'),
};

// Loaded in the exact order that index.template.html lists the production scripts.
const DASHBOARD_JS_FILES = [
  'core.js',
  'panels.js',
  'map.js',
  'scrubber.js',
  'drive.js',
  'telemetry.js',
  'actions.js',
  'troubleshooter.js',
  'connection-status.js',
  'connection-tools.js',
];

function dashboardDomHtml() {
  const html = readFileSync(DASHBOARD_INDEX_HTML, 'utf8');
  const body = html.match(/<body[^>]*>([\s\S]*?)<\/body>/i);
  return body ? body[1] : REQUIRED_DOM;
}

// Smallest DOM that lets every IIFE finish wiring. Covers:
// - actions.js bootstrap: addEventListener targets that have no null guard.
// - core.js setDevices/setHistory: query selectors and id lookups.
// - panels.js updateStorageUi: dbSessionList list root.
// - telemetry.js: every live tile id from LIVE_TILE_IDS so the stale check
//   has nodes to toggle the .stale class on.
const REQUIRED_DOM = `
  <div id="errorBanner" hidden><div id="errorBannerDetail"></div><button id="errorBannerDismiss"></button></div>
  <div class="view" data-view="drive"></div>
  <div class="view" data-view="trips"></div>
  <div class="view" data-view="map"></div>
  <div class="view" data-view="charge"></div>
  <div class="view" data-view="insights"></div>
  <div class="view" data-view="settings"></div>

  <button data-nav="drive"></button>
  <button data-nav="trips"></button>
  <button data-nav="map"></button>

  <div id="screenKicker"></div>
  <div id="screenTitle"></div>

  <select id="deviceSelect"></select>

  <div id="historyCard" hidden><div id="historyHint"></div><div id="historyList"></div></div>

  <div id="stateBadge"></div>
  <div id="stateText"></div>
  <div id="statusCopy"></div>
  <div id="adapterSummary"></div>
  <div id="appStateSummary"></div>
  <div id="loggingState"></div>
  <div id="gpsState"></div>
  <div id="dataSourceState"></div>
  <div id="dbState"></div>

  <!-- Live telemetry tiles. Source of truth is the data-live-tile attribute
       on the partial element; telemetry.js derives its LIVE_TILE_IDS from it.
       Mirror the production attribute here so the stale-indicator test
       exercises the same code path it does in the WebView. -->
  <div id="speedValue" data-live-tile="true"></div>
  <div id="speedKph" data-live-tile="true"></div>
  <div id="rpmValue" data-live-tile="true"></div>
  <div id="voltageValue" data-live-tile="true"></div>
  <div id="coolantValue" data-live-tile="true"></div>
  <div id="loadValue" data-live-tile="true"></div>
  <div id="throttleValue" data-live-tile="true"></div>
  <div id="gpsValue" data-live-tile="true"></div>
  <div id="updatedValue" data-live-tile="true"></div>
  <div id="socValue" data-live-tile="true"></div>
  <div id="rangeValue" data-live-tile="true"></div>
  <div id="packTempValue" data-live-tile="true"></div>
  <div id="driveSocValue" data-live-tile="true"></div>
  <div id="drivePackTempValue" data-live-tile="true"></div>
  <div id="powerValue" data-live-tile="true"></div>

  <div id="rawFrames"></div>
  <div id="powerDetail"></div>
  <div id="powerFill"></div>
  <div id="driveSocMeter"></div>
  <div id="loadMeter"></div>

  <div id="diagState"></div>
  <div id="diagSamples"></div>
  <div id="diagAdapter"></div>
  <div id="diagVehicleState"></div>
  <div id="diagSession"></div>
  <div id="diagSupported"></div>

  <div id="validationSummary"></div>
  <div id="validateObd" class="validation-row"><strong></strong><small></small><b></b></div>
  <div id="validateGps" class="validation-row"><strong></strong><small></small><b></b></div>
  <div id="validateDb" class="validation-row"><strong></strong><small></small><b></b></div>
  <div id="validateParser" class="validation-row"><strong></strong><small></small><b></b></div>
  <div id="validateBackground" class="validation-row"><strong></strong><small></small><b></b></div>

  <div id="dbSessionCount"></div>
  <div id="dbSampleCount"></div>
  <div id="dbEventCount"></div>
  <div id="dbPidCount"></div>
  <div id="dbDtcCount"></div>
  <div id="dbLocationCount"></div>
  <div id="dbTripCount"></div>
  <div id="dbChargeCount"></div>
  <div id="dbBatteryCount"></div>
  <div id="dbSize"></div>
  <div id="dbRawTelemetryCount"></div>
  <div id="dbEmptyTelemetryCount"></div>
  <div id="dbSummaryTitle"></div>
  <div id="dbSessionList"></div>
  <div id="dtcList"></div>
  <div id="dtcTitle"></div>
  <div id="dtcReportBadge"></div>
  <div id="dtcTotalCount"></div>
  <div id="dtcStoredCount"></div>
  <div id="dtcPendingCount"></div>
  <div id="dtcPermanentCount"></div>
  <div id="dtcFreezeCount"></div>
  <div id="dtcLastSeen"></div>

  <div id="reviewTitle"></div>
  <div id="reviewMaxSpeed"></div>
  <div id="reviewGpsCount"></div>
  <div id="reviewPidParse"></div>
  <div id="reviewInterval"></div>
  <div id="reviewBackground"></div>
  <div id="reviewGaps"></div>
  <div id="reviewUsefulSamples"></div>
  <div id="reviewEmptySamples"></div>
  <div id="pidFrameTitle"></div>
  <div id="reviewWarnings"></div>
  <div id="realInsightList"></div>
  <div id="reviewTimeline"></div>
  <div id="pidFrameList"></div>
  <div id="enhancedTitle"></div>
  <div id="enhancedBadge"></div>
  <div id="signalStageBar">
    <button data-signal-stage="passive"></button>
    <button data-signal-stage="low-risk"></button>
    <button data-signal-stage="tires" class="is-active"></button>
    <button data-signal-stage="experimental"></button>
  </div>
  <div id="signalStageLabel"></div>
  <div id="signalStageHint"></div>
  <button id="detailProbeBtn" data-action="detailProbe"></button>
  <button id="exportSignalLogsBtn"></button>
  <div id="enhancedConfirmedCount"></div>
  <div id="enhancedRejectedCount"></div>
  <div id="enhancedCandidateCount"></div>
  <div id="enhancedDeferredCount"></div>
  <div id="enhancedFilterBar">
    <button data-signal-filter="all"></button>
    <button data-signal-filter="confirmed"></button>
    <button data-signal-filter="candidate"></button>
    <button data-signal-filter="rejected"></button>
    <button data-signal-filter="deferred"></button>
    <button data-signal-filter="tpms"></button>
  </div>
  <div id="enhancedAllCount"></div>
  <div id="enhancedTiresTabCount"></div>
  <div id="enhancedNextList"></div>
  <div id="enhancedCapabilityList"></div>

  <div id="appEmptyState"></div>
  <div id="chargeEmptyState"></div>
  <div id="insightsEmptyState"></div>
  <div id="overviewDistance"></div>
  <div id="overviewDistanceSub"></div>
  <div id="overviewMaxSpeed"></div>
  <div id="overviewBattery"></div>
  <div id="overviewBatterySub"></div>
  <div id="overviewChargeHints"></div>
  <div id="realChargeSessions"></div>
  <div id="realChargeHints"></div>
  <div id="realChargePower"></div>
  <div id="realChargeStatus"></div>
  <div id="chargeSessionsCard"><div id="chargeSessionsTitle"></div><div id="chargeSessionsList"></div></div>
  <div id="realPackRing"></div>
  <div id="realPackValue"></div>
  <div id="realPackTitle"></div>
  <div id="realPackCopy"></div>
  <div id="realPackStats"></div>
  <div id="maintenanceList"></div>

  <div id="vehicleName"></div>
  <div id="vehicleSummary"></div>
  <div id="vehicleVin"></div>
  <div id="vehicleYear"></div>
  <div id="vehicleOdometer"></div>
  <div id="vehicleLoggedDistance"></div>

  <div id="realTripsCard"></div>
  <div id="tripsEmptyState"></div>
  <div id="realTripsList"></div>
  <div id="realTripsTitle"></div>
  <div id="insightTripCount"></div>
  <div id="insightTotalDistance"></div>
  <div id="insightDriveTime"></div>
  <div id="insightTopSpeed"></div>
  <div id="insightLongest"></div>
  <div id="insightGpsTrips"></div>

  <!-- Map: drawMapRoute bails when mapLeaflet has zero size, so an empty
       div with no layout is enough to keep map bootstrap quiet. -->
  <div id="mapLeaflet"></div>
  <div id="mapFrame"></div>
  <div id="mapCard"></div>
  <button id="mapTilesBtn"></button>
  <button id="mapFullBtn"></button>
  <div id="mapEmpty"></div>
  <div id="mapDriveChips"></div>
  <div id="mapPointBadge"></div>
  <div id="mapTitle"></div>
  <div id="mapKicker"></div>
  <div id="mapDistance"></div>
  <div id="mapDuration"></div>
  <div id="mapAccuracy"></div>
  <div id="mapDriveChips"></div>
  <div id="mapSessionList"></div>

  <div id="demoBanner" hidden></div>
  <button id="demoStopBtn" hidden></button>
  <button id="permissionBtn"></button>
  <button id="refreshBtn"></button>
  <button id="lastBtn"></button>
  <button id="scanBtn"></button>
  <button id="connectBtn"></button>
  <button id="disconnectBtn"></button>

  <canvas id="speedCanvas"></canvas>
`;

// jsdom ships with no Canvas implementation, so HTMLCanvasElement.getContext
// returns null and telemetry.js#drawTrace blows up at `ctx.scale(...)`. Patch
// a no-op 2D context onto the prototype just for the test runtime — the tile
// drawing path is irrelevant to what we're checking here. Idempotent so
// multiple loadDashboard() calls per file are safe.
// jsdom doesn't implement window.scrollTo; the bootstrap path calls it
// twice during init, which jsdom would otherwise log as a noisy "Not
// implemented" stderr line. A no-op keeps CI logs readable.
function installScrollShim() {
  if (typeof window.scrollTo === 'function' && window.scrollTo.__voltShim) return;
  const noop = () => {};
  noop.__voltShim = true;
  window.scrollTo = noop;
}

function installCanvasShim() {
  const proto = window.HTMLCanvasElement && window.HTMLCanvasElement.prototype;
  if (!proto || proto.__voltCanvasShim) return;
  const noop = () => {};
  const noopCtx = {
    canvas: null,
    scale: noop, clearRect: noop, beginPath: noop, moveTo: noop, lineTo: noop,
    stroke: noop, fill: noop, arc: noop, save: noop, restore: noop,
    translate: noop, rotate: noop, transform: noop, setTransform: noop,
    fillRect: noop, strokeRect: noop, measureText: () => ({ width: 0 }),
    fillText: noop, strokeText: noop, drawImage: noop, getImageData: noop,
    putImageData: noop, createLinearGradient: () => ({ addColorStop: noop }),
    createRadialGradient: () => ({ addColorStop: noop }),
    createPattern: () => null, closePath: noop, quadraticCurveTo: noop,
    bezierCurveTo: noop, rect: noop, clip: noop, isPointInPath: () => false,
  };
  Object.defineProperty(proto, '__voltCanvasShim', { value: true });
  // eslint-disable-next-line func-names
  proto.getContext = function () { return noopCtx; };
}

/**
 * Install the bridge fixture, mount the minimal DOM, and execute the
 * production dashboard JS files in load order. Returns the bridge so tests can
 * assert what the bootstrap path called.
 *
 * Re-entrant: tests typically call this from `beforeEach`. We track every
 * setInterval/setTimeout the bootstrap registers and clear them on the next
 * call so the previous run's stale-indicator poll (and any other recurring
 * timer the scripts wire up) doesn't leak into the next test.
 *
 * Opts:
 *   `bridge` — custom VoltBridge fixture (defaults to createVoltBridgeFixture())
 *   `extras` — additional JS files (filename relative to dashboard/js/) to
 *              execute AFTER the standard production bundle.
 *   `extraDom` — HTML appended to REQUIRED_DOM. Use for tests that need
 *                additional fixture nodes (chart hosts, etc.) without losing
 *                the bootstrap-required tiles.
 */
export async function loadDashboard({ bridge, extras, extraDom, withBridge = true } = {}) {
  clearDashboardTimers();
  installCanvasShim();
  installScrollShim();
  const bridgeImpl = withBridge ? (bridge ?? createVoltBridgeFixture()) : null;
  // The Android side exposes the bridge as `window.VoltTrackerAndroid` before
  // the dashboard's scripts run, so do the same here.
  if (bridgeImpl) window.VoltTrackerAndroid = bridgeImpl;
  else delete window.VoltTrackerAndroid;
  window.__VoltDashboardLoadScript = async (src) => {
    const file = String(src || '').replace(/^js\//, '');
    const loadModule = DASHBOARD_MODULE_LOADERS[file];
    if (!loadModule) {
      throw new Error(`No dashboard module loader registered for ${file}`);
    }
    await loadModule();
  };
  document.body.innerHTML = dashboardDomHtml() + (extraDom ?? '');

  const nativeSetInterval = (window.setInterval || globalThis.setInterval || nodeSetInterval).bind(window);
  const nativeSetTimeout = (window.setTimeout || globalThis.setTimeout || nodeSetTimeout).bind(window);
  window.setInterval = (...args) => {
    const id = nativeSetInterval(...args);
    OWNED_INTERVAL_IDS.push(id);
    return id;
  };
  window.setTimeout = (...args) => {
    const id = nativeSetTimeout(...args);
    OWNED_TIMEOUT_IDS.push(id);
    return id;
  };

  try {
    vi.resetModules();
    const allFiles = [...DASHBOARD_JS_FILES, ...(extras ?? [])];
    for (const file of allFiles) {
      const loadModule = DASHBOARD_MODULE_LOADERS[file];
      if (!loadModule) {
        throw new Error(`No dashboard module loader registered for ${file}`);
      }
      await loadModule();
    }
  } finally {
    window.setInterval = nativeSetInterval;
    window.setTimeout = nativeSetTimeout;
  }

  return bridgeImpl;
}

/** IDs of intervals/timeouts created during the last `loadDashboard()` call. */
const OWNED_INTERVAL_IDS = [];
const OWNED_TIMEOUT_IDS = [];

export function clearDashboardTimers() {
  for (const id of OWNED_INTERVAL_IDS.splice(0)) {
    try { (globalThis.clearInterval || nodeClearInterval)(id); } catch (_ignored) { /* jsdom may be torn down */ }
  }
  for (const id of OWNED_TIMEOUT_IDS.splice(0)) {
    try { (globalThis.clearTimeout || nodeClearTimeout)(id); } catch (_ignored) { /* jsdom may be torn down */ }
  }
}
