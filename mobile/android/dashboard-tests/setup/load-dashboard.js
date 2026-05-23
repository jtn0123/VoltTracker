// Loads the production dashboard JS bundle into the current jsdom window.
//
// The 5 files are pure side-effecting IIFEs that mutate `window`, so we
// `eval` them in the same order the WebView would (see
// `app/src/main/dashboard-src/index.template.html`). We use new Function()
// rather than Node's `require`/`import` because the files are written as
// browser scripts, not ESM modules.
//
// The DOM is a hand-picked subset of `index.html` covering every id the
// bootstrap path dereferences without a null check — see the comments in
// actions.js / core.js / map.js. If you add a new bare `el("...").foo` to
// the dashboard JS, add the corresponding placeholder here.
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { createVoltBridgeFixture } from './voltbridge.fixture.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const DASHBOARD_JS_DIR = resolve(HERE, '../../app/src/main/assets/dashboard/js');

// Loaded in the exact order that index.template.html lists them.
const DASHBOARD_JS_FILES = ['core.js', 'panels.js', 'map.js', 'telemetry.js', 'actions.js'];

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
  <select id="driveModeSelect"><option value="ev">EV</option></select>
  <div id="evRing"></div>
  <div id="evRatioValue"></div>

  <div id="historyCard" hidden><div id="historyHint"></div><div id="historyList"></div></div>
  <div id="homeTrips"></div>
  <div id="tripList"></div>
  <div id="hourBars"></div>
  <div id="sessionList"></div>
  <div id="homeInsights"></div>
  <div id="insightList"></div>
  <div id="cellGrid"></div>

  <div id="stateBadge"></div>
  <div id="stateText"></div>
  <div id="statusCopy"></div>
  <div id="adapterSummary"></div>
  <div id="appStateSummary"></div>
  <div id="loggingState"></div>
  <div id="gpsState"></div>
  <div id="dataSourceState"></div>
  <div id="dbState"></div>

  <!-- Live telemetry tiles (LIVE_TILE_IDS in telemetry.js). -->
  <div id="speedValue"></div>
  <div id="speedKph"></div>
  <div id="rpmValue"></div>
  <div id="voltageValue"></div>
  <div id="coolantValue"></div>
  <div id="loadValue"></div>
  <div id="throttleValue"></div>
  <div id="gpsValue"></div>
  <div id="updatedValue"></div>
  <div id="socValue"></div>
  <div id="rangeValue"></div>
  <div id="packTempValue"></div>
  <div id="driveSocValue"></div>
  <div id="drivePackTempValue"></div>
  <div id="powerValue"></div>

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
  <div id="realPackRing"></div>
  <div id="realPackValue"></div>
  <div id="realPackTitle"></div>
  <div id="realPackCopy"></div>
  <div id="maintenanceList"></div>

  <div id="vehicleName"></div>
  <div id="vehicleSummary"></div>
  <div id="vehicleVin"></div>
  <div id="vehicleYear"></div>
  <div id="vehicleOdometer"></div>
  <div id="vehicleLoggedDistance"></div>
  <div id="vehicleEvMix"></div>
  <div id="vehicleBatteryHealth"></div>

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
  <button id="mapFullBtn"></button>
  <div id="mapEmpty"></div>
  <div id="mapPointBadge"></div>
  <div id="mapTitle"></div>
  <div id="mapKicker"></div>
  <div id="mapDistance"></div>
  <div id="mapDuration"></div>
  <div id="mapAccuracy"></div>
  <div id="mapSessionList"></div>

  <div id="demoBanner" hidden></div>
  <button id="demoStopBtn" hidden></button>
  <button id="permissionBtn"></button>
  <button id="refreshBtn"></button>
  <button id="lastBtn"></button>
  <button id="scanBtn"></button>
  <button id="connectBtn"></button>
  <button id="disconnectBtn"></button>
  <button id="addChargeBtn"></button>
  <div id="tripTabs"></div>

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
 * Install the bridge fixture, mount the minimal DOM, and eval the
 * 5 dashboard JS files in load order. Returns the bridge so tests can
 * assert what the bootstrap path called.
 *
 * Re-entrant: tests typically call this from `beforeEach`. We track every
 * setInterval/setTimeout the bootstrap registers and clear them on the next
 * call so the previous run's stale-indicator poll (and any other recurring
 * timer the scripts wire up) doesn't leak into the next test.
 */
export function loadDashboard({ bridge } = {}) {
  clearOwnedTimers();
  installCanvasShim();
  installScrollShim();
  const bridgeImpl = bridge ?? createVoltBridgeFixture();
  // The Android side exposes the bridge as `window.VoltTrackerAndroid` before
  // the dashboard's scripts run, so do the same here.
  window.VoltTrackerAndroid = bridgeImpl;
  document.body.innerHTML = REQUIRED_DOM;

  const nativeSetInterval = window.setInterval.bind(window);
  const nativeSetTimeout = window.setTimeout.bind(window);
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
    for (const file of DASHBOARD_JS_FILES) {
      const source = readFileSync(resolve(DASHBOARD_JS_DIR, file), 'utf8');
      // `new Function` runs the script in the global scope of the current
      // realm (jsdom's window), which is the same shape a <script> tag would
      // produce. Using indirect eval here means each file's top-level "use
      // strict" only applies to that file, matching browser semantics.
      // eslint-disable-next-line no-new-func
      new Function(`${source}\n//# sourceURL=${file}`).call(window);
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

function clearOwnedTimers() {
  for (const id of OWNED_INTERVAL_IDS.splice(0)) {
    try { clearInterval(id); } catch (_ignored) { /* jsdom may be torn down */ }
  }
  for (const id of OWNED_TIMEOUT_IDS.splice(0)) {
    try { clearTimeout(id); } catch (_ignored) { /* jsdom may be torn down */ }
  }
}
