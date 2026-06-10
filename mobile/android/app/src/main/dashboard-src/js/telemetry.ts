import { LIVE_ROUTE_ID, appendLiveRoutePoint, haversineMetersJs, liveSampleTimeMs } from "./map-route-utils";
import type { MapRoutePoint } from "./map-route-utils";

  const VD = window.VoltDashboard;
  const state = VD.state;
  const bridge = VD.bridge;
  const el = VD.el;

  type PayloadRecord = Record<string, unknown>;
  type LiveCellGroup = HTMLElement | Element | null;
  type ValidationTone = "ok" | "warn" | "bad";

  // Live-tile element ids that should pulse with a `.stale` class when the
  // adapter stops sending fresh samples (>3s since lastSampleAt). Derived at
  // boot from `[data-live-tile="true"]` so adding a tile to a partial is the
  // only change needed — no parallel JS edit. Tiles whose id is missing from
  // the assembled DOM are silently skipped by the stale loop, so a partial
  // that disappears won't throw.
  const LIVE_TILE_IDS = Array.from(
    document.querySelectorAll('[data-live-tile="true"]')
  )
    .map((el) => el.id)
    .filter(Boolean);
  // How long (ms) since the last accepted sample before we mark tiles stale.
  const STALE_THRESHOLD_MS = 3000;
  let rateChipReconnectBound = false;

  function average(values: number[]) {
    return values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : 0;
  }

  function pushBounded(values: number[], value: number, limit: number) {
    values.push(value);
    const overflow = values.length - limit;
    if (overflow > 0) values.splice(0, overflow);
  }

  function formatDistance(meters: unknown) {
    const m = Number(meters || 0);
    if (!Number.isFinite(m) || m <= 0) return "--";
    const d = VD.units.distanceMeters(m);
    return `${d.value} ${d.unit}`;
  }

  function escapeHtml(value: unknown) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  // Status detail lands in #statusCopy, which lives on the Settings tab — taps
  // on other tabs ("Scan car codes" on Insights, blocked-adapter explanations)
  // would otherwise appear to do nothing. Mirror new detail strings in a small
  // aria-live toast whenever the user can't see #statusCopy.
  let statusToastTimer: ReturnType<typeof setTimeout> | null = null;
  let lastToastDetail = "";
  let toastBaselineSeen = false;

  function showStatusToast(detail: unknown) {
    const node = el("statusToast");
    if (!node) return;
    const text = String(detail || "").trim();
    if (!text || text === lastToastDetail) return;
    lastToastDetail = text;
    // The very first detail is the boot-time status push ("Viewing local
    // data…"), not feedback on a user action — set the baseline silently.
    if (!toastBaselineSeen) {
      toastBaselineSeen = true;
      return;
    }
    if (text === "Ready.") return;
    if (state.view === "settings") return;
    node.textContent = text;
    node.hidden = false;
    if (statusToastTimer) clearTimeout(statusToastTimer);
    statusToastTimer = setTimeout(() => {
      node.hidden = true;
    }, 3200);
  }

  function setStatus(payload: unknown) {
    const wasActive = isActiveStatus();
    const status = VD.parsePayload<VoltStatus>(payload, {});
    state.status = status;
    const badge = el("stateBadge");
    const next = status.state || "idle";
    if (badge) badge.dataset.state = next;
    if (!wasActive && isActiveStatus() && !state.demoActive) resetTelemetry();
    VD.setText("stateText", next);
    VD.setText("statusCopy", status.detail || "Ready.");
    showStatusToast(status.detail);
    if (status.lastAddress) state.lastDevice = { address: status.lastAddress, name: status.lastName || "" };
    renderOperationalState();
    updateDiagnostics();
    updateValidationUi();
  }

  function setAppState(payload: unknown) {
    const parsed = VD.parsePayload<VoltAppState>(payload, {});
    if (state.demoActive && state.demoPreviewAppState) {
      // Park the real app-state behind the demo preview (cross-module demo invariant).
      VD.setState({ realAppState: parsed });
      if (parsed.storage) VD.setStorage(parsed.storage);
      renderOperationalState();
      updateLiveUi();
      updateValidationUi();
      return;
    }
    state.appState = parsed;
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

  function shouldAcceptTelemetry(sample: PayloadRecord) {
    if (!sample || !Object.keys(sample).length) return false;
    const source = String(sample.source || "").toLowerCase();
    if (source.includes("demo")) return state.demoActive || isActiveStatus();
    const updatedAt = Number(sample.updatedAt || 0);
    const ageMs = updatedAt > 0 ? Date.now() - updatedAt : Number.POSITIVE_INFINITY;
    return isActiveStatus() || ageMs < 30000;
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
    state.powerHistory = [];
    state.socHistory = [];
    state.sessionStartSoc = null;
    state.sessionDistanceM = 0;
    state.sessionLastLat = null;
    state.sessionLastLng = null;
    state.liveRouteStartedAtMs = null;
    state.liveRoutePoints = [];
    state.lastSampleAt = 0;
    if (typeof VD.clearLivePosition === "function") VD.clearLivePosition();
    applyStaleIndicator();
  }

  function liveRoutePoint(lat: number, lng: number): MapRoutePoint {
    const sample = state.telemetry || {};
    const updatedAt = liveSampleTimeMs(sample);
    const point: MapRoutePoint = { lat, lng, atMs: updatedAt };
    const speedKph = Number(sample.speedKph);
    if (Number.isFinite(speedKph)) point.speedKph = speedKph;
    const soc = Number(sample.soc);
    if (Number.isFinite(soc)) point.soc = soc;
    const powerKw = Number(sample.powerKw);
    if (Number.isFinite(powerKw)) point.powerKw = powerKw;
    return point;
  }

  function recordQueuedLivePosition(lat: number, lng: number) {
    const point = liveRoutePoint(lat, lng);
    const points: MapRoutePoint[] = Array.isArray(state.liveRoutePoints)
      ? state.liveRoutePoints as MapRoutePoint[]
      : [];
    const result = appendLiveRoutePoint(points, point);
    if (result === "skipped") return;
    if (result === "first") {
      state.liveRouteStartedAtMs = point.atMs;
      state.selectedMapSessionId = LIVE_ROUTE_ID;
    }
    state.liveRoutePoints = points;
  }

  function renderOperationalState() {
    const app = state.appState || {};
    const adapter = app.adapter || {};
    const session = app.session || {};
    const gps = app.gps || {};
    const storage = state.storage || {};
    const status = state.status || {};
    const selected = getSelectedDevice();
    const statusName = String(status.state || "").toLowerCase();
    const adapterName = state.demoActive
      ? "Demo telemetry"
      : (adapter.name || (selected && selected.name) || "No adapter selected");
    const remembered = adapter.remembered || Boolean((getLastDevice() || {}).address);
    const connecting = ["connecting", "initializing"].includes(statusName);
    const scanning = statusName === "scanning";
    const connected =
      state.demoActive ||
      Boolean(adapter.connected) ||
      ["connected", "connecting", "initializing", "scanning", "demo"].includes(statusName);
    const sessionState = session.state || status.state || "idle";
    const samples = Number(session.sampleCount || state.telemetry.sampleCount || 0);

    VD.setText("adapterSummary", adapterName);
    VD.setText(
      "appStateSummary",
      state.demoActive
        ? "Preview data is isolated from real OBD history."
        : (
            status.detail ||
            session.detail ||
            (
              remembered
                ? "Local data is available. Resume the remembered adapter only when you want live logging."
                : "Local data is available offline. Connect when you want live OBD logging."
            )
          )
    );
    // Compact status word, not a sample count — this tile is ~76px wide so
    // "1,911 samples" just clips to "1,911 sa…". The live count is shown in the
    // drive pill, the OBD-session card, and the database card.
    VD.setText("loggingState", connected ? (samples ? "live" : (sessionState || "ready")) : "idle");
    VD.setText("gpsState", gps.state || (state.telemetry.latitude ? "locked" : "waiting"));
    VD.setText("dataSourceState", state.demoActive ? "demo" : "real");
    VD.setText("dbState", dbRowCount(storage) ? `${dbRowCount(storage)} rows` : "ready");
    const appInfo = app.app || {};
    VD.setText("appVersionFooter", appInfo.version ? `Volt Tracker v${appInfo.version}` : "Volt Tracker");

    const primary = el("connectBtn");
    const lastButton = el("lastBtn") as HTMLButtonElement | null;
    if (lastButton) lastButton.hidden = connected && !state.demoActive;
    if (!primary) return;
    primary.classList.toggle("is-stop", connected);
    primary.classList.toggle("primary", !connected);
    primary.setAttribute("aria-busy", connecting || scanning ? "true" : "false");
    if (state.demoActive) {
      primary.dataset.primaryAction = "stopDemo";
      primary.setAttribute("aria-label", "Stop demo preview");
      primary.textContent = "Stop demo";
    } else if (connecting) {
      primary.dataset.primaryAction = "stop";
      primary.setAttribute("aria-label", "Connecting to OBD adapter. Tap to stop.");
      primary.textContent = "Connecting...";
    } else if (scanning) {
      primary.dataset.primaryAction = "stop";
      primary.setAttribute("aria-label", "Scanning with OBD adapter. Tap to stop.");
      primary.textContent = "Scanning...";
    } else if (connected) {
      primary.dataset.primaryAction = "stop";
      primary.setAttribute("aria-label", "Disconnect OBD adapter");
      primary.textContent = "Disconnect";
    } else if (!bridge) {
      primary.dataset.primaryAction = "demo";
      primary.setAttribute("aria-label", "Start demo preview");
      primary.textContent = "Start demo";
    } else if (remembered) {
      primary.dataset.primaryAction = "last";
      primary.setAttribute("aria-label", "Resume last OBD adapter");
      primary.textContent = "Resume";
    } else {
      primary.dataset.primaryAction = "connect";
      primary.setAttribute("aria-label", "Connect selected OBD adapter");
      primary.textContent = "Connect";
    }
  }

  function dbRowCount(storage: PayloadRecord) {
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

  function relativeTime(value: unknown) {
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

  function formatWhen(value: unknown) {
    const ts = Number(value);
    if (!Number.isFinite(ts) || ts <= 0) return "not yet";
    return relativeTime(ts);
  }

  function formatBytes(value: unknown) {
    const bytes = Number(value);
    if (!Number.isFinite(bytes) || bytes <= 0) return "0 B";
    if (bytes < 1024) return `${Math.round(bytes)} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }

  function formatShortDuration(ms: number) {
    const value = Math.max(0, Number(ms) || 0);
    if (value < 1000) return `${Math.round(value)}ms`;
    return `${(value / 1000).toFixed(value < 10000 ? 1 : 0)}s`;
  }

  function setOptionalLiveText(id: string, value: unknown) {
    VD.setText(id, value);
    const node = el(id);
    if (!node) return;
    const cell = node.closest("[data-live-cell]");
    if (!cell) return;
    const text = String(value == null || value === "" ? "--" : value).trim();
    cell.classList.toggle("is-empty", text === "--");
    syncOptionalLiveGroup(cell.closest("[data-optional-live-group]"));
  }

  function syncOptionalLiveGroup(group: LiveCellGroup) {
    if (!group) return;
    const cells = Array.from(group.querySelectorAll("[data-live-cell]"));
    group.classList.toggle("is-empty", cells.length > 0 && cells.every((cell) => cell.classList.contains("is-empty")));
  }

  function selectDevice(address: unknown, name?: unknown) {
    if (!address) return;
    const select = el("deviceSelect") as HTMLSelectElement | null;
    if (!select) return;
    const addressText = String(address);
    const nameText = String(name || "OBD adapter");
    let option = Array.from(select.options).find((item) => item.value === addressText);
    if (!option) {
      option = document.createElement("option");
      option.value = addressText;
      option.dataset.name = nameText;
      option.textContent = `${nameText} · remembered`;
      select.append(option);
    }
    select.value = addressText;
    renderOperationalState();
  }

  function getSelectedDevice() {
    const select = el("deviceSelect") as HTMLSelectElement | null;
    const option = select?.selectedOptions[0];
    if (!option || !option.value) return null;
    return {
      address: option.value,
      name: option.dataset.name || option.textContent || "OBD adapter"
    };
  }

  // Stash the latest sample; defer the heavy renders (updateLiveUi,
  // drawTrace, renderOperationalState, updateValidationUi) to the next animation
  // frame so a high-rate OBD source can't cause render thrash.
  function updateTelemetry(payload: unknown) {
    const sample = VD.parsePayload(payload, {});
    const source = String(sample.source || "").toLowerCase();
    const isDemoSample = source.includes("demo");
    if (isDemoSample && !state.demoActive) VD.setDemoActive(true);
    if (sample.source && !isDemoSample && state.demoActive) {
      VD.clearDemoTelemetry();
      VD.setDemoActive(false);
    }
    const sampleCount = Number(sample.sampleCount);
    const previousCount = Number(state.telemetry && state.telemetry.sampleCount);
    if (
      sample.source &&
      !isDemoSample &&
      Number.isFinite(sampleCount) &&
      sampleCount > 0 &&
      Number.isFinite(previousCount) &&
      previousCount > sampleCount
    ) {
      resetTelemetry();
    }
    state.telemetry = { ...state.telemetry, ...sample };
    state.lastSampleAt = Date.now();
    const kph = Number(sample.speedKph);
    if (Number.isFinite(kph)) {
      pushBounded(state.speedHistory, kph, 48);
    }
    // Drive-tab live charts: power bars and SOC trace. Same fixed-window
    // discipline as the speed history.
    const power = Number(sample.powerKw);
    if (Number.isFinite(power)) {
      pushBounded(state.powerHistory, power, 60);
    }
    const soc = Number(sample.soc);
    if (Number.isFinite(soc)) {
      // Capture the session-start SOC so the "Δ since session" chip on the
      // SOC micro-card always points at a stable baseline, independent of
      // whatever recent window the chart happens to be showing.
      if (!Number.isFinite(state.sessionStartSoc)) state.sessionStartSoc = soc;
      // Push every sample (no value-change throttle): keeping the latest
      // sample in history at all times means the chart and the "Δ" chip
      // can never disagree, and the fixed cap below bounds memory. The old
      // 0.1% threshold caused a stale-trace bug where the chart didn't
      // include the current SOC and looked like it was rising while the
      // delta said falling. Cap is 240 entries → ~4 min @ 1 Hz which is
      // plenty for a "current trend" widget.
      pushBounded(state.socHistory, soc, 240);
    }
    // Running session distance, ticked off accepted GPS samples.
    const lat = Number(sample.latitude);
    const lon = Number(sample.longitude);
    if (Number.isFinite(lat) && Number.isFinite(lon)) {
      if (
        Number.isFinite(state.sessionLastLat) &&
        Number.isFinite(state.sessionLastLng)
      ) {
        const step = haversineMetersJs(
          Number(state.sessionLastLat),
          Number(state.sessionLastLng),
          lat,
          lon
        );
        // Reject tiny GPS jitter and very large jumps (likely a fix
        // reacquisition) so the distance counter stays honest.
        if (step >= 2 && step < 250) state.sessionDistanceM += step;
      }
      state.sessionLastLat = lat;
      state.sessionLastLng = lon;
      if (typeof VD.updateLivePosition === "function") VD.updateLivePosition(lat, lon);
      else recordQueuedLivePosition(lat, lon);
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
    // (see below) plus renderDriveLive() at its tail, so the rAF burst is just
    // these two — keep them in lockstep with the tail of updateLiveUi() if
    // either ever needs to call something new.
    updateLiveUi();
    drawTrace();
  }

  // Toggle the `.stale` class on each live tile when no new sample has
  // arrived for STALE_THRESHOLD_MS. Called from updateLiveUi every render and
  // from a 1Hz tick so the indicator appears even without new samples.
  // Also append a screen-reader-only "(stale)" span so the aria-live
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
    updateRateChip(isStale);
  }

  // True once any live data has been observed this session — either a counted
  // sample or a populated history buffer. Lets the rate chip distinguish
  // "waiting for the first sample" from "samples arrived but went quiet".
  function hasLiveSamples() {
    const t = state.telemetry || {};
    const session = (state.appState && state.appState.session) || {};
    const sampleCount = Number(session.sampleCount || t.sampleCount || 0);
    return (
      // Any accepted frame stamps lastSampleAt (even optional-tile-only frames),
      // so the chip flips to live in lockstep with the tiles.
      Number(state.lastSampleAt || 0) > 0 ||
      sampleCount > 0 ||
      (state.speedHistory || []).length > 0 ||
      (state.powerHistory || []).length > 0 ||
      (state.socHistory || []).length > 0
    );
  }

  // Keep the Drive live-rate chip in lockstep with tile staleness:
  //   waiting → no samples observed yet
  //   live    → samples flowing and fresh
  //   stale   → samples seen but none for STALE_THRESHOLD_MS
  // `isStale` is passed in from applyStaleIndicator() so both share one clock read.
  function updateRateChip(isStale: boolean) {
    const chip = el("liveRateChip");
    if (!chip) return;
    const samples = hasLiveSamples();
    const label = samples && isStale ? "stale" : samples ? "live" : "waiting";
    chip.dataset.state = label;
    chip.dataset.reconnectActive = samples && isStale && bridge ? "true" : "false";
    if (samples && isStale) {
      chip.tabIndex = bridge ? 0 : -1;
      chip.setAttribute("role", bridge ? "button" : "status");
      chip.setAttribute(
        "aria-label",
        bridge
          ? "Telemetry stale. No samples have arrived for over three seconds. Press to reconnect."
          : "Telemetry stale. No samples have arrived for over three seconds."
      );
      chip.title = bridge ? "Reconnect telemetry" : "Telemetry stale";
      bindRateChipReconnect(chip);
    } else {
      chip.removeAttribute("tabindex");
      chip.setAttribute("role", "status");
      chip.setAttribute(
        "aria-label",
        label === "live"
          ? "Telemetry live. Samples are updating."
          : "Waiting for the first telemetry sample."
      );
      chip.title = "";
    }
    const rateLabel = el("liveRateLabel");
    if (rateLabel) rateLabel.textContent = label;
  }

  function bindRateChipReconnect(chip: HTMLElement) {
    if (rateChipReconnectBound) return;
    rateChipReconnectBound = true;
    const run = () => {
      if (chip.dataset.reconnectActive !== "true") return;
      if (bridge && typeof bridge.tryReconnectNow === "function") {
        bridge.tryReconnectNow();
      } else if (bridge && typeof bridge.connectLast === "function") {
        bridge.connectLast();
      }
    };
    chip.addEventListener("click", run);
    chip.addEventListener("keydown", (event) => {
      if (event.key !== "Enter" && event.key !== " ") return;
      event.preventDefault();
      run();
    });
  }

  function updateLiveUi() {
    const t = state.telemetry;
    const kph = Number(t.speedKph);
    const hasSpeed = Number.isFinite(kph);
    // Primary = the user's chosen unit; secondary readout = the other system so
    // both are always visible. Driven by the units preference (prefs.ts).
    const metric = VD.units.system() === "metric";
    const primary = hasSpeed ? VD.units.speed(kph) : null;
    const altValue = hasSpeed ? Math.round(metric ? kph * 0.621371 : kph) : null;
    const altUnit = metric ? "mph" : "km/h";
    VD.setText("speedValue", primary ? primary.value : null);
    VD.setText("speedUnitMain", (primary ? primary.unit : altUnit).toUpperCase());
    VD.setText("speedKph", hasSpeed ? `${altValue} ${altUnit}` : `-- ${altUnit}`);
    const speedMeter = el("speedValue")?.closest("[role='meter']");
    if (speedMeter) {
      // The markup's 120 ceiling is mph-shaped; aria-valuenow is written in the
      // selected unit, so widen the range when the primary unit is km/h.
      speedMeter.setAttribute("aria-valuemax", metric ? "200" : "120");
      if (primary) {
        speedMeter.setAttribute("aria-valuenow", String(primary.value));
        speedMeter.setAttribute("aria-label", `Vehicle speed in ${primary.unit}`);
      } else {
        speedMeter.removeAttribute("aria-valuenow");
      }
    }
    setOptionalLiveText("rpmValue", t.rpm == null || t.rpm === "" ? "--" : `${t.rpm}`);
    // voltageValue is the aux 12V (ATRV from the ELM adapter), labelled accordingly
    // in the partial. The HV traction-pack voltage is rendered via drivePackVoltage below.
    setOptionalLiveText(
      "voltageValue",
      t.voltage == null || t.voltage === "" ? "--" : `${Number(t.voltage).toFixed(1)} V`
    );
    setOptionalLiveText("coolantValue", t.coolantC != null ? VD.units.tempText(Number(t.coolantC)) : "--");
    setOptionalLiveText("loadValue", t.loadPct != null ? `${t.loadPct}%` : "--");
    setOptionalLiveText("throttleValue", t.throttlePct != null ? `${t.throttlePct}%` : "--");
    const lat = Number(t.latitude);
    const lon = Number(t.longitude);
    const acc = Number(t.accuracyM);
    // Surface fix quality, not just "locked": ±Nm tells the user whether the GPS
    // is precise enough to trust the route. accuracyM is already in the payload.
    const gpsTile = Number.isFinite(lat) && Number.isFinite(lon)
      ? Number.isFinite(acc) && acc > 0
        ? `±${Math.round(acc)} m`
        : "locked"
      : "--";
    setOptionalLiveText("gpsValue", gpsTile);

    // Enhanced Volt signals — decoded mode-22 PIDs that already ride to the
    // dashboard via the telemetry extras passthrough but were never shown. Each is
    // optional per vehicle; setOptionalLiveText hides empty cells and the whole
    // "More signals" card when none are reported.
    const finiteNum = (value: unknown) => {
      const n = Number(value);
      return value == null || value === "" || !Number.isFinite(n) ? null : n;
    };
    const motorA = finiteNum(t.motorAPowerKw);
    setOptionalLiveText("moreMotorA", motorA != null ? `${motorA.toFixed(1)} kW` : "--");
    const motorB = finiteNum(t.motorBPowerKw);
    setOptionalLiveText("moreMotorB", motorB != null ? `${motorB.toFixed(1)} kW` : "--");
    const gear = t.prndlState == null || t.prndlState === "" ? null : String(t.prndlState);
    setOptionalLiveText("moreGear", gear || "--");
    const evKm = finiteNum(t.evDistanceThisCycleKm);
    setOptionalLiveText("moreEvRange", evKm != null ? VD.units.distanceText(evKm) : "--");
    const transC = finiteNum(t.transmissionTempC);
    setOptionalLiveText("moreTransTemp", transC != null ? VD.units.tempText(transC) : "--");
    const ambientC = finiteNum(t.outsideTempC);
    setOptionalLiveText("moreAmbient", ambientC != null ? VD.units.tempText(ambientC) : "--");
    const oilLife = finiteNum(t.engineOilLifePct);
    setOptionalLiveText("moreOilLife", oilLife != null ? `${Math.round(oilLife)}%` : "--");
    const torque = finiteNum(t.engineTorqueNm);
    setOptionalLiveText("moreTorque", torque != null ? `${Math.round(torque)} Nm` : "--");

    VD.setText("rawFrames", t.raw || "Waiting for telemetry...");
    const soc = t.soc == null || t.soc === "" ? NaN : Number(t.soc);
    const batteryTemp = t.batteryTemp == null || t.batteryTemp === "" ? NaN : Number(t.batteryTemp);
    VD.setText("driveSocValue", Number.isFinite(soc) ? `${Math.round(soc)}%` : "--");
    // Pass the raw (possibly NaN) value through; setMeter clears the meter to an
    // indeterminate state for a missing reading rather than announcing a false 0%.
    VD.setMeter("driveSocMeter", soc);
    VD.setText("drivePackTempValue", Number.isFinite(batteryTemp) ? VD.units.tempText(batteryTemp) : "--");
    const power = t.powerKw == null || t.powerKw === "" ? NaN : Number(t.powerKw);
    VD.setText("powerValue", Number.isFinite(power) ? `${power.toFixed(1)} kW` : "--");
    // HV traction-pack live readings (mode-22 PIDs 222429 / 222414). When the
    // adapter hasn't responded yet these fall back to "--" exactly like the rest of
    // the live readout.
    const packV = t.packVoltage == null || t.packVoltage === "" ? NaN : Number(t.packVoltage);
    setOptionalLiveText("drivePackVoltage", Number.isFinite(packV) ? `${packV.toFixed(1)} V` : "--");
    const packA = t.packCurrentA == null || t.packCurrentA === "" ? NaN : Number(t.packCurrentA);
    // Sign convention: discharge is positive (Volt mode-22 222414), so "+" means
    // current flowing OUT of the pack (driving), "-" means INTO it (regen / charging).
    setOptionalLiveText(
      "drivePackCurrent",
      Number.isFinite(packA) ? `${packA >= 0 ? "+" : ""}${packA.toFixed(1)} A` : "--"
    );
    setOptionalLiveText(
      "drivePackPower",
      Number.isFinite(power) ? `${power >= 0 ? "+" : ""}${power.toFixed(1)} kW` : "--"
    );
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
    const powerMeter = el("powerMeter");
    if (powerMeter) {
      if (Number.isFinite(power)) powerMeter.setAttribute("aria-valuenow", String(Math.round(power)));
      else powerMeter.removeAttribute("aria-valuenow");
    }
    renderOperationalState();
    updateDiagnostics();
    updateValidationUi();
    applyStaleIndicator();
    // Drive-tab chip strip + micro-charts. Driven from updateLiveUi so every
    // path that refreshes the dashboard (setAppState, scheduled render,
    // setDemoActive) also keeps the Drive polish in sync.
    if (typeof VD.renderDriveLive === "function") VD.renderDriveLive();
  }

  function updateDiagnostics() {
    const t = state.telemetry || {};
    const app = state.appState || {};
    const vehicle = app.vehicle || {};
    const status = state.status || {};
    const samples = Number(t.sampleCount || 0);
    // The Android classifier writes vehicle.state on the app-state payload as
    // the source of truth; fall back to the telemetry-level mirror only if a
    // bridge call hasn't delivered the app-state payload yet.
    const vehicleState = vehicle.state || t.vehicleState || "unknown";
    VD.setText("diagState", status.detail || (t.updatedAt ? "Live OBD data received." : "Waiting for adapter"));
    VD.setText("diagSamples", samples ? `${samples} samples` : "0 samples");
    VD.setText("diagAdapter", t.adapter || status.adapter || "OBD adapter");
    // Surface the classifier's confidence inline and its reason codes (the "why"
    // behind driving/charging/parked) as a tooltip — both already reach JS via the
    // app-state payload (vehicle.confidence / vehicle.reasons).
    const stateConfidence = String(vehicle.confidence || t.vehicleStateConfidence || "").toLowerCase();
    const stateReasons = Array.isArray(vehicle.reasons)
      ? vehicle.reasons.filter(Boolean).map(String)
      : [];
    VD.setText("diagVehicleState", stateConfidence ? `${vehicleState} · ${stateConfidence}` : vehicleState);
    const stateEl = el("diagVehicleState");
    if (stateEl) {
      if (stateReasons.length) stateEl.setAttribute("title", `Why: ${stateReasons.join("; ")}`);
      else stateEl.removeAttribute("title");
    }
    VD.setText("diagSession", t.sessionMs ? formatDuration(Number(t.sessionMs)) : "--");
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
    // Read the classifier output (state + confidence) from app.vehicle.*; the
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
      String(gps.state || "idle")
    );
    setValidationRow(
      "validateDb",
      dbRows > 0 ? "ok" : "warn",
      "Database writes",
      dbRows > 0 ? `${dbRows} rows saved on device` : "Waiting for stored rows",
      dbRows ? "writing" : "ready"
    );
    // "weak" replaces the legacy "inferred" tone — both mean "soft signal only".
    const lowConfidence = confidence === "inferred" || confidence === "weak" || confidence === "unknown";
    setValidationRow(
      "validateParser",
      hasParsed ? (lowConfidence ? "warn" : "ok") : "warn",
      "PID parsing",
      hasParsed ? `${vehicleState || "telemetry"} ${confidence ? "- " + confidence : ""}` : "Waiting for parsed values",
      hasParsed ? "active" : "unknown"
    );
    // Android 13+ POST_NOTIFICATIONS: the background-logging foreground service
    // needs it to keep its notice visible. Treat a missing field as granted
    // (older payloads / pre-13 devices auto-grant) so we don't false-warn.
    const notificationsGranted = ((app.permissions || {}).notifications) !== false;
    const notifBlocked = foregroundService && !notificationsGranted;
    const backgroundDetail = backgroundSamples
      ? `${backgroundSamples} samples while minimized${gapCount ? `, ${gapCount} gaps` : ""}`
      : (isBackgroundCandidate ? "Minimize during a drive; confirm sample counts keep rising" : "Start logging before testing minimized behavior");
    setValidationRow(
      "validateBackground",
      notifBlocked ? "warn" : (backgroundSamples ? "ok" : "warn"),
      "Background test",
      notifBlocked
        ? `${backgroundDetail} · notifications are off — turn them on to keep the logging notice visible`
        : backgroundDetail,
      foregroundService ? (appForeground ? "armed" : "active") : "manual"
    );
    const okCount = document.querySelectorAll(".validation-row[data-tone='ok']").length;
    VD.setText("validationSummary", okCount ? `${okCount}/5 ok` : "waiting");
  }

  function setValidationRow(
    id: string,
    tone: ValidationTone,
    label: string,
    detail: string,
    value: string
  ) {
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

  function formatAge(ms: number) {
    const clean = Math.max(0, Number(ms) || 0);
    if (clean < 1000) return "now";
    if (clean < 60000) return `${Math.round(clean / 1000)}s`;
    return `${Math.round(clean / 60000)}m`;
  }

  function summarizePidLine(value: unknown) {
    const clean = String(value || "").replace(/SEARCHING\.+/gi, "").trim();
    const match = clean.match(/4100[0-9A-F]+/i);
    return match ? "01-00 ok" : (clean ? "adapter ok" : "unknown");
  }

  function formatDuration(ms: number) {
    const seconds = Math.max(0, Math.round(Number(ms) / 1000));
    if (seconds < 60) return `${seconds}s`;
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes}m ${String(seconds % 60).padStart(2, "0")}s`;
    // Roll into hours so multi-hour spans (e.g. charge sessions) read "3h 24m"
    // instead of "204m 00s".
    const hours = Math.floor(minutes / 60);
    return `${hours}h ${String(minutes % 60).padStart(2, "0")}m`;
  }

  function drawTrace() {
    const canvas = el("speedCanvas") as HTMLCanvasElement | null;
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.max(1, rect.width * dpr);
    canvas.height = Math.max(1, rect.height * dpr);
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
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
    samples.forEach((value: number, index: number) => {
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

  // 1Hz heartbeat so the .stale class is applied even when no new sample
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

export {};
