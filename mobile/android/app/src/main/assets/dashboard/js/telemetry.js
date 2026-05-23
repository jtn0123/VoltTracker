(function () {
  "use strict";

  const VD = window.VoltDashboard;
  const state = VD.state;
  const bridge = VD.bridge;
  const el = VD.el;

  // C6: live-tile element ids that should pulse with a `.stale` class when the
  // adapter stops sending fresh samples (>3s since lastSampleAt). Kept in one
  // place so partials and CSS stay in sync.
  const LIVE_TILE_IDS = [
    "speedValue", "speedKph", "rpmValue", "voltageValue", "coolantValue",
    "loadValue", "throttleValue", "gpsValue", "gpsDetail", "gpsMetricValue",
    "gpsMetricSub", "updatedValue", "socValue", "rangeValue", "packTempValue",
    "driveSocValue", "drivePackTempValue", "powerValue"
  ];
  // C6: how long (ms) since the last accepted sample before we mark tiles stale.
  const STALE_THRESHOLD_MS = 3000;

  function average(values) {
    return values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : 0;
  }

  function formatDistance(meters) {
    const miles = Number(meters || 0) / 1609.344;
    if (!Number.isFinite(miles) || miles <= 0) return "--";
    return miles < 10 ? `${miles.toFixed(1)} mi` : `${Math.round(miles)} mi`;
  }

  function escapeHtml(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function setStatus(payload) {
    const status = VD.parsePayload(payload, {});
    state.status = status;
    const badge = el("stateBadge");
    const next = status.state || "idle";
    badge.dataset.state = next;
    VD.setText("stateText", next);
    VD.setText("statusCopy", status.detail || "Ready.");
    if (status.lastAddress) state.lastDevice = { address: status.lastAddress, name: status.lastName || "" };
    renderOperationalState();
    updateDiagnostics();
    updateValidationUi();
  }

  function setAppState(payload) {
    state.appState = VD.parsePayload(payload, {});
    const nextTelemetry = state.appState.latestTelemetry || {};
    if (shouldAcceptTelemetry(nextTelemetry)) {
      state.telemetry = { ...state.telemetry, ...nextTelemetry };
      state.lastSampleAt = Date.now();
    } else if (!isActiveStatus()) {
      resetTelemetry();
    }
    if (state.appState.storage) {
      // Route through setStorage so the sample-data fallback / preserve
      // logic (in panels.js) applies here too — otherwise a later
      // appState push with empty storage wipes the sample we just loaded.
      VD.setStorage(state.appState.storage);
    }
    renderOperationalState();
    updateLiveUi();
    updateValidationUi();
  }

  function shouldAcceptTelemetry(sample) {
    if (!sample || !Object.keys(sample).length) return false;
    const source = String(sample.source || "").toLowerCase();
    if (source.includes("demo")) return state.demoActive || isActiveStatus();
    const updatedAt = Number(sample.updatedAt || 0);
    const ageMs = updatedAt > 0 ? Date.now() - updatedAt : Number.POSITIVE_INFINITY;
    return isActiveStatus() || ageMs < 30000 || dbRowCount(state.storage || {}) > 0;
  }

  function isActiveStatus() {
    const status = String((state.status || {}).state || (state.appState.session || {}).state || "").toLowerCase();
    return ["connected", "connecting", "initializing", "scanning", "scan-complete", "demo"].includes(status);
  }

  function resetTelemetry() {
    state.telemetry = {
      speedKph: null,
      rpm: null,
      voltage: null,
      coolantC: null,
      loadPct: null,
      throttlePct: null,
      soc: null,
      batteryTemp: null,
      powerKw: null,
      updatedAt: null,
      raw: ""
    };
    state.speedHistory = [];
    state.lastSampleAt = 0;
    applyStaleIndicator();
  }

  function renderOperationalState() {
    const app = state.appState || {};
    const adapter = app.adapter || {};
    const session = app.session || {};
    const gps = app.gps || {};
    const storage = state.storage || {};
    const status = state.status || {};
    const selected = getSelectedDevice();
    const adapterName = adapter.name || (selected && selected.name) || "No adapter selected";
    const remembered = adapter.remembered || Boolean((getLastDevice() || {}).address);
    const connected = Boolean(adapter.connected) || ["connected", "connecting", "initializing", "scanning", "demo"].includes(String(status.state || "").toLowerCase());
    const sessionState = session.state || status.state || "idle";
    const samples = Number(session.sampleCount || state.telemetry.sampleCount || 0);

    VD.setText("adapterSummary", adapterName);
    VD.setText("appStateSummary", status.detail || session.detail || (remembered ? "Ready to resume the remembered adapter." : "Pick a paired adapter to start logging."));
    VD.setText("loggingState", connected ? (samples ? `${samples} samples` : sessionState) : "idle");
    VD.setText("gpsState", gps.state || (state.telemetry.latitude ? "locked" : "waiting"));
    VD.setText("dataSourceState", state.demoActive ? "demo" : "real");
    VD.setText("dbState", dbRowCount(storage) ? `${dbRowCount(storage)} rows` : "ready");

    const primary = el("connectBtn");
    if (!primary) return;
    primary.classList.toggle("is-stop", connected);
    primary.classList.toggle("primary", !connected);
    if (connected) {
      primary.dataset.primaryAction = "stop";
      primary.textContent = "Stop";
    } else if (remembered) {
      primary.dataset.primaryAction = "last";
      primary.textContent = "Resume";
    } else {
      primary.dataset.primaryAction = "connect";
      primary.textContent = "Connect";
    }
  }

  function dbRowCount(storage) {
    const keys = [
      "sampleCount",
      "eventCount",
      "pidObservationCount",
      "diagnosticCodeCount",
      "locationSampleCount",
      "tripSegmentCount",
      "chargeSessionCount",
      "batterySnapshotCount",
      "cellSnapshotCount",
      "fieldCapabilityCount",
      "exportCount"
    ];
    return keys.reduce((total, key) => total + Number(storage[key] || 0), 0);
  }

  function getLastDevice() {
    if (bridge && typeof bridge.getLastDevice === "function") {
      state.lastDevice = VD.parsePayload(bridge.getLastDevice(), state.lastDevice || {});
    }
    return state.lastDevice || {};
  }

  function relativeTime(value) {
    const ts = Number(value);
    if (!Number.isFinite(ts) || ts <= 0) return "saved";
    const seconds = Math.max(1, Math.round((Date.now() - ts) / 1000));
    if (seconds < 60) return `${seconds}s ago`;
    const minutes = Math.round(seconds / 60);
    if (minutes < 60) return `${minutes}m ago`;
    const hours = Math.round(minutes / 60);
    if (hours < 48) return `${hours}h ago`;
    return `${Math.round(hours / 24)}d ago`;
  }

  function formatWhen(value) {
    const ts = Number(value);
    if (!Number.isFinite(ts) || ts <= 0) return "not yet";
    return relativeTime(ts);
  }

  function formatBytes(value) {
    const bytes = Number(value);
    if (!Number.isFinite(bytes) || bytes <= 0) return "0 B";
    if (bytes < 1024) return `${Math.round(bytes)} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }

  function formatShortDuration(ms) {
    const value = Math.max(0, Number(ms) || 0);
    if (value < 1000) return `${Math.round(value)}ms`;
    return `${(value / 1000).toFixed(value < 10000 ? 1 : 0)}s`;
  }

  function selectDevice(address, name) {
    if (!address) return;
    const select = el("deviceSelect");
    let option = Array.from(select.options).find((item) => item.value === address);
    if (!option) {
      option = document.createElement("option");
      option.value = address;
      option.dataset.name = name || "OBD adapter";
      option.textContent = `${name || "OBD adapter"} - remembered`;
      select.append(option);
    }
    select.value = address;
    renderOperationalState();
  }

  function getSelectedDevice() {
    const option = el("deviceSelect").selectedOptions[0];
    if (!option || !option.value) return null;
    return {
      address: option.value,
      name: option.dataset.name || option.textContent || "OBD adapter"
    };
  }

  // C1: stash the latest sample; defer the heavy renders (updateLiveUi,
  // drawTrace, renderOperationalState, updateValidationUi) to the next animation
  // frame so a high-rate OBD source can't cause render thrash.
  function updateTelemetry(payload) {
    const sample = VD.parsePayload(payload, {});
    const source = String(sample.source || "").toLowerCase();
    const isDemoSample = source.includes("demo");
    if (isDemoSample && !state.demoActive) VD.setDemoActive(true);
    if (sample.source && !isDemoSample && state.demoActive) {
      VD.clearDemoTelemetry();
      VD.setDemoActive(false);
    }
    state.telemetry = { ...state.telemetry, ...sample };
    state.lastSampleAt = Date.now();
    const kph = Number(sample.speedKph);
    if (Number.isFinite(kph)) {
      state.speedHistory.push(kph);
      // G2: fixed 48-sample window (~12 s at 4 Hz). shift() is O(n) on a JS
      // array but n=48 makes the cost negligible (~µs); a circular buffer
      // would be cleaner but requires changes to every reader. Revisit if
      // speed of render becomes a hot path. `if` (not `while`) is correct
      // because we push exactly one sample per call.
      if (state.speedHistory.length > 48) state.speedHistory.shift();
    }
    scheduleRender();
  }

  function scheduleRender() {
    if (state.rafPending) return;
    state.rafPending = window.requestAnimationFrame(() => {
      state.rafPending = 0;
      flushRender();
    });
  }

  function flushRender() {
    // updateLiveUi() already calls renderOperationalState() + updateValidationUi()
    // (see below), so the rAF burst is just these two — keep them in lockstep with
    // the tail of updateLiveUi() if either ever needs to call something new.
    updateLiveUi();
    drawTrace();
  }

  // C6: toggle the `.stale` class on each live tile when no new sample has
  // arrived for STALE_THRESHOLD_MS. Called from updateLiveUi every render and
  // from a 1Hz tick so the indicator appears even without new samples.
  // C3: also append a screen-reader-only "(stale)" span so the aria-live
  // wrappers around the tile clusters announce when readings have gone quiet.
  function applyStaleIndicator() {
    const last = Number(state.lastSampleAt || 0);
    const isStale = last > 0 ? Date.now() - last > STALE_THRESHOLD_MS : true;
    LIVE_TILE_IDS.forEach((id) => {
      const node = el(id);
      if (!node) return;
      node.classList.toggle("stale", isStale);
      const existing = node.querySelector(":scope > span.visually-hidden[data-stale-marker]");
      if (isStale) {
        if (!existing) {
          const marker = document.createElement("span");
          marker.className = "visually-hidden";
          marker.dataset.staleMarker = "1";
          marker.textContent = " (stale)";
          node.append(marker);
        }
      } else if (existing) {
        existing.remove();
      }
    });
  }

  function updateLiveUi() {
    const t = state.telemetry;
    const kph = Number(t.speedKph);
    const mph = Number.isFinite(kph) ? Math.round(kph * 0.621371) : null;
    VD.setText("speedValue", mph);
    VD.setText("speedKph", Number.isFinite(kph) ? `${Math.round(kph)} km/h` : "-- km/h");
    VD.setText("rpmValue", t.rpm ? `${t.rpm}` : "--");
    VD.setText("voltageValue", t.voltage ? `${Number(t.voltage).toFixed(1)}V` : "--");
    VD.setText("coolantValue", t.coolantC != null ? `${t.coolantC} deg C` : "--");
    VD.setText("loadValue", t.loadPct != null ? `${t.loadPct}%` : "--");
    VD.setText("throttleValue", t.throttlePct != null ? `${t.throttlePct}%` : "--");
    const lat = Number(t.latitude);
    const lon = Number(t.longitude);
    const acc = Number(t.accuracyM);
    VD.setText("gpsValue", Number.isFinite(lat) && Number.isFinite(lon) ? "locked" : "--");
    VD.setText("gpsDetail", Number.isFinite(acc) ? `${Math.round(acc)}m accuracy` : "location trace");
    VD.setText("gpsMetricValue", Number.isFinite(lat) && Number.isFinite(lon) ? "locked" : "--");
    VD.setText("gpsMetricSub", Number.isFinite(acc)
      ? `${Math.round(acc)}m accuracy - ${t.locationAgeMs ? formatShortDuration(Number(t.locationAgeMs)) + " old" : "fresh"}`
      : "waiting for location samples");
    VD.setText("updatedValue", t.updatedAt ? "now" : "waiting");
    VD.setText("rawFrames", t.raw || "Waiting for telemetry...");
    const soc = t.soc == null || t.soc === "" ? NaN : Number(t.soc);
    VD.setText("socValue", Number.isFinite(soc) ? `${soc.toFixed(1)}%` : "--");
    VD.setText("rangeValue", "--");
    const batteryTemp = t.batteryTemp == null || t.batteryTemp === "" ? NaN : Number(t.batteryTemp);
    VD.setText("packTempValue", Number.isFinite(batteryTemp) ? `${batteryTemp.toFixed(1)} deg C` : "--");
    VD.setText("driveSocValue", Number.isFinite(soc) ? `${Math.round(soc)}%` : "--");
    VD.setMeter("driveSocMeter", Number.isFinite(soc) ? soc : 0);
    VD.setText("drivePackTempValue", Number.isFinite(batteryTemp) ? `${Math.round(batteryTemp)} deg C` : "--");
    const power = t.powerKw == null || t.powerKw === "" ? NaN : Number(t.powerKw);
    VD.setText("powerValue", Number.isFinite(power) ? `${power.toFixed(1)} kW` : "--");
    const powerState = !Number.isFinite(power) ? "coast"
      : power < -0.5 ? "regen"
      : power > 0.5 ? "drive"
      : "coast";
    const powerDetail = el("powerDetail");
    if (powerDetail) {
      powerDetail.textContent = powerState;
      powerDetail.dataset.state = powerState;
    }
    const pct = Number.isFinite(power) ? Math.min(50, Math.abs(power / 80) * 50) : 0;
    const fill = el("powerFill");
    if (fill) {
      fill.style.width = pct + "%";
      fill.style.left = Number.isFinite(power) && power < 0 ? (50 - pct) + "%" : "50%";
      fill.classList.toggle("is-regen", powerState === "regen");
    }
    VD.setMeter("loadMeter", t.loadPct);
    renderOperationalState();
    updateDiagnostics();
    updateValidationUi();
    applyStaleIndicator();
  }

  function updateDiagnostics() {
    const t = state.telemetry || {};
    const app = state.appState || {};
    const vehicle = app.vehicle || {};
    const status = state.status || {};
    const samples = Number(t.sampleCount || 0);
    // A1: the Android classifier writes vehicle.state on the app-state payload as
    // the source of truth; fall back to the telemetry-level mirror only if a
    // bridge call hasn't delivered the app-state payload yet.
    const vehicleState = vehicle.state || t.vehicleState || "unknown";
    VD.setText("diagState", status.detail || (t.updatedAt ? "Live OBD data received." : "Waiting for adapter"));
    VD.setText("diagSamples", samples ? `${samples} samples` : "0 samples");
    VD.setText("diagAdapter", t.adapter || status.adapter || "OBD adapter");
    VD.setText("diagVehicleState", vehicleState);
    VD.setText("diagSession", t.sessionMs ? formatDuration(t.sessionMs) : "--");
    VD.setText("diagSupported", t.supportedPids ? summarizePidLine(t.supportedPids) : "unknown");
  }

  function updateValidationUi() {
    const t = state.telemetry || {};
    const app = state.appState || {};
    const gps = app.gps || {};
    const lifecycle = app.lifecycle || {};
    const storage = state.storage || {};
    const status = String((state.status || {}).state || "").toLowerCase();
    const samples = Number((app.session || {}).sampleCount || t.sampleCount || 0);
    const pidRows = Number(storage.pidObservationCount || 0);
    const locationRows = Number(storage.locationSampleCount || 0);
    const dbRows = dbRowCount(storage);
    const updatedAt = Number(t.updatedAt || 0);
    const ageMs = updatedAt > 0 ? Date.now() - updatedAt : NaN;
    const hasFreshSample = samples > 0 && Number.isFinite(ageMs) && ageMs < 10000;
    const hasAnySample = samples > 0 || pidRows > 0;
    const hasGps = locationRows > 0 || gps.state === "locked" || (Number.isFinite(Number(t.latitude)) && Number.isFinite(Number(t.longitude)));
    // A1: read the classifier output (state + confidence) from app.vehicle.*; the
    // telemetry-level fields are kept as a fallback for the first frame, but the
    // JS no longer derives any of these values.
    const vehicle = app.vehicle || {};
    const vehicleState = vehicle.state || t.vehicleState || "";
    const confidence = String(vehicle.confidence || t.vehicleStateConfidence || "").toLowerCase();
    const hasParsed = Boolean(vehicleState || t.soc != null || t.voltage != null || t.rpm != null || t.speedKph != null);
    const isBackgroundCandidate = hasAnySample && (status === "connected" || status === "scan-complete" || status === "scanning");
    const backgroundSamples = Number(lifecycle.backgroundSampleCount || t.backgroundSampleCount || 0);
    const gapCount = Number(lifecycle.sampleGapCount || t.sampleGapCount || 0);
    const foregroundService = Boolean(lifecycle.foregroundServiceActive || t.foregroundServiceActive);
    const appForeground = lifecycle.appForeground !== false && t.appForeground !== false;

    setValidationRow(
      "validateObd",
      hasFreshSample ? "ok" : (hasAnySample ? "warn" : "warn"),
      "OBD stream",
      hasFreshSample ? `Fresh sample ${formatAge(ageMs)} ago` : (hasAnySample ? "Samples stored, waiting for a fresh update" : "Waiting for adapter samples"),
      samples ? `${samples}x` : "idle"
    );
    setValidationRow(
      "validateGps",
      hasGps ? "ok" : (gps.state === "blocked" ? "bad" : "warn"),
      "GPS trace",
      hasGps ? `${locationRows || "live"} location samples available` : (gps.state === "blocked" ? "Location permission blocked" : "Waiting for location samples"),
      gps.state || "idle"
    );
    setValidationRow(
      "validateDb",
      dbRows > 0 ? "ok" : "warn",
      "Database writes",
      dbRows > 0 ? `${dbRows} rows saved on device` : "Waiting for stored rows",
      dbRows ? "writing" : "ready"
    );
    // A1: "weak" replaces the legacy "inferred" tone — both mean "soft signal only".
    const lowConfidence = confidence === "inferred" || confidence === "weak" || confidence === "unknown";
    setValidationRow(
      "validateParser",
      hasParsed ? (lowConfidence ? "warn" : "ok") : "warn",
      "PID parsing",
      hasParsed ? `${vehicleState || "telemetry"} ${confidence ? "- " + confidence : ""}` : "Waiting for parsed values",
      hasParsed ? "active" : "unknown"
    );
    setValidationRow(
      "validateBackground",
      backgroundSamples ? "ok" : (foregroundService ? "warn" : "warn"),
      "Background test",
      backgroundSamples
        ? `${backgroundSamples} samples while minimized${gapCount ? `, ${gapCount} gaps` : ""}`
        : (isBackgroundCandidate ? "Minimize during a drive; confirm sample counts keep rising" : "Start logging before testing minimized behavior"),
      foregroundService ? (appForeground ? "armed" : "active") : "manual"
    );
    const okCount = document.querySelectorAll(".validation-row[data-tone='ok']").length;
    VD.setText("validationSummary", okCount ? `${okCount}/5 ok` : "waiting");
  }

  function setValidationRow(id, tone, label, detail, value) {
    const row = el(id);
    if (!row) return;
    row.dataset.tone = tone;
    const strong = row.querySelector("strong");
    const small = row.querySelector("small");
    const tag = row.querySelector("b");
    if (strong) strong.textContent = label;
    if (small) small.textContent = detail;
    if (tag) tag.textContent = value;
  }

  function formatAge(ms) {
    const clean = Math.max(0, Number(ms) || 0);
    if (clean < 1000) return "now";
    if (clean < 60000) return `${Math.round(clean / 1000)}s`;
    return `${Math.round(clean / 60000)}m`;
  }

  function summarizePidLine(value) {
    const clean = String(value || "").replace(/SEARCHING\\.\\.\\./g, "").trim();
    const match = clean.match(/4100[0-9A-F]+/i);
    return match ? "01-00 ok" : (clean ? "adapter ok" : "unknown");
  }

  function formatDuration(ms) {
    const seconds = Math.max(0, Math.round(Number(ms) / 1000));
    if (seconds < 60) return `${seconds}s`;
    const minutes = Math.floor(seconds / 60);
    return `${minutes}m ${String(seconds % 60).padStart(2, "0")}s`;
  }

  function drawTrace() {
    const canvas = el("speedCanvas");
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.max(1, rect.width * dpr);
    canvas.height = Math.max(1, rect.height * dpr);
    const ctx = canvas.getContext("2d");
    ctx.scale(dpr, dpr);
    const w = rect.width;
    const h = rect.height;
    ctx.clearRect(0, 0, w, h);
    ctx.strokeStyle = "rgba(255,255,255,0.08)";
    ctx.lineWidth = 1;
    for (let i = 1; i < 4; i++) {
      const y = (h / 4) * i;
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(w, y);
      ctx.stroke();
    }
    const samples = state.speedHistory;
    if (samples.length < 2) return;
    const max = Math.max(120, ...samples);
    ctx.beginPath();
    samples.forEach((value, index) => {
      const x = (index / (samples.length - 1)) * w;
      const y = h - (value / max) * (h - 18) - 8;
      if (index === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    });
    ctx.strokeStyle = "#ff7a45";
    ctx.lineWidth = 4;
    ctx.lineJoin = "round";
    ctx.lineCap = "round";
    ctx.shadowColor = "rgba(255,122,69,0.32)";
    ctx.shadowBlur = 14;
    ctx.stroke();
    ctx.shadowBlur = 0;
  }

  // C6: a 1Hz heartbeat so the .stale class is applied even when no new sample
  // arrives (and removed promptly once one does). Cheap; touches a handful of
  // DOM nodes.
  setInterval(applyStaleIndicator, 1000);

  Object.assign(VD, {
    average,
    formatDistance,
    escapeHtml,
    setStatus,
    setAppState,
    shouldAcceptTelemetry,
    isActiveStatus,
    resetTelemetry,
    renderOperationalState,
    dbRowCount,
    getLastDevice,
    relativeTime,
    formatWhen,
    formatBytes,
    formatShortDuration,
    selectDevice,
    getSelectedDevice,
    updateTelemetry,
    scheduleRender,
    flushRender,
    applyStaleIndicator,
    updateLiveUi,
    updateDiagnostics,
    updateValidationUi,
    setValidationRow,
    formatAge,
    summarizePidLine,
    formatDuration,
    drawTrace
  });
})();
