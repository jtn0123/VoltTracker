import { el } from "./core";
import { asDataState, setDataState, setDataTone } from "./dataset-state";
import { LIVE_ROUTE_ID, appendLiveRoutePoint, haversineMetersJs, liveSampleTimeMs } from "./map-route-utils";
import type { MapRoutePoint } from "./map-route-utils";
import { validatePayload } from "./payload-validators";
import { prefs, units } from "./prefs";
import { initialTelemetryState } from "./telemetry-state";

  const VD = window.VoltDashboard;
  const state = VD.state;
  const bridge = VD.bridge;

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
  // Max age (ms) of an inactive-status sample we'll still accept as recent.
  const RECENT_SAMPLE_ACCEPT_MS = 30000;
  // Below this duration, formatShortDuration shows one decimal (e.g. "1.5s").
  const SHORT_DURATION_DECIMAL_CUTOFF_MS = 10000;
  let rateChipReconnectBound = false;
  // One-shot guard so we only ask the backend to rehydrate the live track once per
  // session activation (reset in resetTelemetry). Recovers the in-progress drive's
  // route after the WebView is torn down and recreated mid-drive.
  let liveRouteHydrated = false;
  // Highest telemetry `updatedAt` observed so far. Native re-delivers the LAST
  // sample on every status broadcast, so setAppState must only treat a sample
  // as fresh when its updatedAt actually advances past this marker — otherwise
  // a wedged adapter keeps the stale indicator and rate chip reporting "live".
  let lastSeenSampleUpdatedAt = 0;

  function pushBounded(values: number[], value: number, limit: number) {
    values.push(value);
    const overflow = values.length - limit;
    if (overflow > 0) values.splice(0, overflow);
  }

  export function formatDistance(meters: unknown) {
    const m = Number(meters || 0);
    if (!Number.isFinite(m) || m <= 0) return "--";
    const d = units.distanceMeters(m);
    return `${d.value} ${d.unit}`;
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
    // The very first setStatus call is the boot-time status push ("Viewing
    // local data…"), not feedback on a user action — consume the baseline
    // silently even when its detail is empty, so the first user-triggered
    // detail after a detail-less boot push still toasts.
    const isBaseline = !toastBaselineSeen;
    toastBaselineSeen = true;
    const text = String(detail || "").trim();
    if (!text || text === lastToastDetail) return;
    lastToastDetail = text;
    if (isBaseline) return;
    if (text === "Ready.") return;
    if (state.view === "settings") return;
    node.textContent = text;
    node.hidden = false;
    if (statusToastTimer) clearTimeout(statusToastTimer);
    statusToastTimer = setTimeout(() => {
      node.hidden = true;
      // Reset the dedupe baseline once the toast is gone so a later repeat of
      // the SAME action detail (e.g. tapping "Scan car codes" twice) gives
      // feedback again instead of silently doing nothing.
      lastToastDetail = "";
    }, 3200);
  }

  function setStatus(payload: unknown) {
    const wasActive = isActiveStatus();
    const status = VD.parsePayload<VoltStatus>(payload, {});
    validatePayload("setStatus", status);
    state.status = status;
    const badge = el("stateBadge");
    const next = status.state || "idle";
    setDataState(badge, asDataState(next));
    if (!wasActive && isActiveStatus() && !state.demoActive) resetTelemetry();
    hydrateLiveRouteIfActive();
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
    validatePayload("setAppState", parsed);
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
      // Status broadcasts re-deliver the last sample verbatim; only an
      // updatedAt that advances counts as freshness, and we stamp the sample's
      // own clock (the same wall clock updateTelemetry's Date.now() uses on
      // live delivery) rather than the broadcast arrival time.
      const updatedAt = Number(nextTelemetry.updatedAt || 0);
      if (Number.isFinite(updatedAt) && updatedAt > lastSeenSampleUpdatedAt) {
        lastSeenSampleUpdatedAt = updatedAt;
        state.lastSampleAt = updatedAt;
      }
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
    return isActiveStatus() || ageMs < RECENT_SAMPLE_ACCEPT_MS;
  }

  function isActiveStatus() {
    const status = String((state.status || {}).state || (state.appState.session || {}).state || "").toLowerCase();
    return ["connected", "connecting", "initializing", "scanning", "scan-complete", "demo"].includes(status);
  }

  function resetTelemetry() {
    // Shared factory (telemetry-state.ts) so this reset can never drift from
    // the boot-time shape core.ts seeds.
    state.telemetry = initialTelemetryState();
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
    lastSeenSampleUpdatedAt = 0;
    liveRouteHydrated = false;
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

  /**
   * After a mid-drive WebView teardown the in-memory live route is gone, but the foreground service
   * kept writing GPS to the active session's SQLite row. When we learn a session is active and have
   * no live points yet, pull the in-progress track from the backend and seed it as the live route so
   * the map shows the real drive instead of a blank "new run". A genuinely fresh drive returns no
   * points, so this is a harmless no-op then. Guarded to run once per activation.
   */
  function hydrateLiveRouteIfActive() {
    if (state.demoActive || liveRouteHydrated || !isActiveStatus()) return;
    const existing = Array.isArray(state.liveRoutePoints) ? state.liveRoutePoints : [];
    if (existing.length) {
      liveRouteHydrated = true;
      return;
    }
    if (!bridge || typeof bridge.getCurrentSessionRoute !== "function") return;
    let parsed: PayloadRecord;
    try {
      parsed = VD.parsePayload(bridge.getCurrentSessionRoute(), {});
    } catch (_err) {
      return;
    }
    liveRouteHydrated = true;
    const rawPoints = Array.isArray(parsed.points) ? parsed.points : [];
    const mapped: MapRoutePoint[] = [];
    for (const raw of rawPoints) {
      const p = raw as PayloadRecord;
      const lat = Number(p.lat);
      const lng = Number(p.lng);
      if (!Number.isFinite(lat) || !Number.isFinite(lng)) continue;
      const point: MapRoutePoint = { lat, lng, atMs: Number(p.atMs) || Date.now() };
      const speedMps = Number(p.speedMps);
      if (Number.isFinite(speedMps)) {
        point.speedMps = speedMps;
        point.speedKph = speedMps * 3.6;
      }
      const soc = Number(p.soc);
      if (Number.isFinite(soc)) point.soc = soc;
      mapped.push(point);
    }
    if (!mapped.length) return;
    state.liveRouteStartedAtMs = mapped[0].atMs;
    state.selectedMapSessionId = LIVE_ROUTE_ID;
    // If the map module is already loaded, route through its setter so its module-local
    // liveRoutePoints reference is updated too — a bare `state.liveRoutePoints = mapped`
    // reassignment would leave map.ts pointing at the old (empty) array, so the recovered
    // mid-drive route would never draw. When the map module isn't loaded yet, set state
    // directly; map.ts seeds its local from state.liveRoutePoints on load.
    if (typeof VD.setLiveRoutePoints === "function") {
      VD.setLiveRoutePoints(mapped, mapped[0].atMs);
    } else {
      state.liveRoutePoints = mapped;
    }
    if (typeof VD.renderMapIfLoaded === "function") VD.renderMapIfLoaded();
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
    // Number.isFinite guard (same as the gpsValue tile below) so a legitimate
    // coordinate of exactly 0 still reads as locked; both axes must be valid.
    const hasFix =
      Number.isFinite(Number(state.telemetry.latitude)) &&
      Number.isFinite(Number(state.telemetry.longitude));
    VD.setText("gpsState", gps.state || (hasFix ? "locked" : "waiting"));
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

  export function formatWhen(value: unknown) {
    const ts = Number(value);
    if (!Number.isFinite(ts) || ts <= 0) return "not yet";
    return relativeTime(ts);
  }

  export function formatBytes(value: unknown) {
    const bytes = Number(value);
    if (!Number.isFinite(bytes) || bytes <= 0) return "0 B";
    if (bytes < 1024) return `${Math.round(bytes)} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }

  // Map an unfriendly GPS enum state to plain copy. Raw "blocked" reads as bare jargon and an
  // empty/unknown state would otherwise drop the status-popover row (renderRows skips empty
  // values). Shared by the status popover and the Drive GPS tile. (Lives here, not in
  // connection-status, so telemetry doesn't import connection-status — that would be a cycle.)
  export function gpsText(rawState: unknown) {
    const g = String(rawState || "");
    if (g === "blocked") return "Off — location permission needed";
    if (g === "waiting" || g === "") return "Waiting for fix";
    return g;
  }

  function formatShortDuration(ms: number) {
    const value = Math.max(0, Number(ms) || 0);
    if (value < 1000) return `${Math.round(value)}ms`;
    return `${(value / 1000).toFixed(value < SHORT_DURATION_DECIMAL_CUTOFF_MS ? 1 : 0)}s`;
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
    const isEmpty = cells.length > 0 && cells.every((cell) => cell.classList.contains("is-empty"));
    group.classList.toggle("is-empty", isEmpty);
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
  // renderOperationalState, updateValidationUi) to the next animation
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
    // Record the sample's own timestamp so a later status broadcast that
    // re-delivers this exact sample (setAppState) is not mistaken for a fresh
    // one and cannot move lastSampleAt backwards.
    const sampleUpdatedAt = Number(sample.updatedAt || 0);
    if (Number.isFinite(sampleUpdatedAt) && sampleUpdatedAt > lastSeenSampleUpdatedAt) {
      lastSeenSampleUpdatedAt = sampleUpdatedAt;
    }
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
    // (see below) plus renderDriveLive() at its tail (which draws the shipped
    // #liveTraceCanvas speed trace), so the rAF burst is just this one call —
    // keep it in lockstep with the tail of updateLiveUi() if either ever needs
    // to call something new.
    updateLiveUi();
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
    setDataState(chip, label);
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
    const metric = units.system() === "metric";
    const primary = hasSpeed ? units.speed(kph) : null;
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
        speedMeter.setAttribute("aria-valuetext", `${primary.value} ${primary.unit}`);
        speedMeter.setAttribute("aria-label", `Vehicle speed in ${primary.unit}`);
      } else {
        // Without valuenow the meter is indeterminate; valuetext keeps screen
        // readers announcing "no data yet" instead of a bare "--". Reset the
        // unit-specific label too so a reader doesn't keep announcing the stale
        // "Vehicle speed in mph" for a tile that now has no reading.
        speedMeter.removeAttribute("aria-valuenow");
        speedMeter.setAttribute("aria-valuetext", "no data yet");
        speedMeter.setAttribute("aria-label", "Vehicle speed");
      }
    }
    setOptionalLiveText("rpmValue", t.rpm);
    // voltageValue is the aux 12V (ATRV from the ELM adapter), labelled accordingly
    // in the partial. The HV traction-pack voltage is rendered via drivePackVoltage below.
    setOptionalLiveText(
      "voltageValue",
      t.voltage == null || t.voltage === "" ? "--" : `${Number(t.voltage).toFixed(1)} V`
    );
    setOptionalLiveText("coolantValue", t.coolantC != null ? units.tempText(Number(t.coolantC)) : "--");
    setOptionalLiveText("loadValue", t.loadPct != null ? `${t.loadPct}%` : "--");
    setOptionalLiveText("throttleValue", t.throttlePct != null ? `${t.throttlePct}%` : "--");
    const lat = Number(t.latitude);
    const lon = Number(t.longitude);
    const acc = Number(t.accuracyM);
    const gpsState = String((state.appState.gps || {}).state || "");
    // Surface fix quality, not just "locked": ±Nm tells the user whether the GPS
    // is precise enough to trust the route. accuracyM is already in the payload.
    // No fix yet: reuse the status-popover's shared gpsText() wording so the tile
    // and the popover agree. A "blocked" permission is an actionable status worth
    // showing in-cell; the plain waiting/unknown case stays the "--" empty
    // sentinel so the cell (and group empty-text) can collapse as before.
    const gpsTile = Number.isFinite(lat) && Number.isFinite(lon)
      ? Number.isFinite(acc) && acc > 0
        ? `±${Math.round(acc)} m`
        : "locked"
      : gpsState === "blocked"
        ? gpsText(gpsState)
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
    const liveNum = (id: string, value: unknown, fmt: (n: number) => string) => {
      const n = finiteNum(value);
      setOptionalLiveText(id, n != null ? fmt(n) : "--");
    };
    liveNum("moreMotorA", t.motorAPowerKw, (n) => `${n.toFixed(1)} kW`);
    liveNum("moreMotorB", t.motorBPowerKw, (n) => `${n.toFixed(1)} kW`);
    const gear = t.prndlState == null || t.prndlState === "" ? null : String(t.prndlState);
    setOptionalLiveText("moreGear", gear || "--");
    liveNum("moreEvRange", t.evDistanceThisCycleKm, (n) => units.distanceText(n));
    liveNum("moreTransTemp", t.transmissionTempC, (n) => units.tempText(n));
    liveNum("moreAmbient", t.outsideTempC, (n) => units.tempText(n));
    liveNum("moreOilLife", t.engineOilLifePct, (n) => `${Math.round(n)}%`);
    liveNum("moreTorque", t.engineTorqueNm, (n) => `${Math.round(n)} Nm`);

    VD.setText("rawFrames", t.raw || "Waiting for telemetry...");
    const soc = finiteNum(t.soc);
    const batteryTemp = finiteNum(t.batteryTemp);
    VD.setText("driveSocValue", soc != null ? `${Math.round(soc)}%` : "--");
    // Pass the raw (possibly NaN) value through; setMeter clears the meter to an
    // indeterminate state for a missing reading rather than announcing a false 0%.
    VD.setMeter("driveSocMeter", soc ?? NaN);
    VD.setText("drivePackTempValue", batteryTemp != null ? units.tempText(batteryTemp) : "--");
    const power = finiteNum(t.powerKw);
    VD.setText("powerValue", power != null ? `${power.toFixed(1)} kW` : "--");
    // HV traction-pack live readings (mode-22 PIDs 222429 / 222414). When the
    // adapter hasn't responded yet these fall back to "--" exactly like the rest of
    // the live readout.
    const packV = finiteNum(t.packVoltage);
    setOptionalLiveText("drivePackVoltage", packV != null ? `${packV.toFixed(1)} V` : "--");
    const packA = finiteNum(t.packCurrentA);
    // Sign convention: discharge is positive (Volt mode-22 222414), so "+" means
    // current flowing OUT of the pack (driving), "-" means INTO it (regen / charging).
    setOptionalLiveText(
      "drivePackCurrent",
      packA != null ? `${packA >= 0 ? "+" : ""}${packA.toFixed(1)} A` : "--"
    );
    setOptionalLiveText(
      "drivePackPower",
      power != null ? `${power >= 0 ? "+" : ""}${power.toFixed(1)} kW` : "--"
    );
    const powerState = power == null ? "coast"
      : power < -0.5 ? "regen"
      : power > 0.5 ? "drive"
      : "coast";
    const powerDetail = el("powerDetail");
    if (powerDetail) {
      powerDetail.textContent = powerState;
      setDataState(powerDetail, powerState);
    }
    const pct = power != null ? Math.min(50, Math.abs(power / 80) * 50) : 0;
    const fill = el("powerFill");
    if (fill) {
      fill.style.width = pct + "%";
      fill.style.left = power != null && power < 0 ? (50 - pct) + "%" : "50%";
      fill.classList.toggle("is-regen", powerState === "regen");
    }
    const powerMeter = el("powerMeter");
    if (powerMeter) {
      if (power != null) {
        powerMeter.setAttribute("aria-valuenow", String(Math.round(power)));
        powerMeter.setAttribute("aria-valuetext", `${Math.round(power)} kilowatts`);
      } else {
        powerMeter.removeAttribute("aria-valuenow");
        powerMeter.setAttribute("aria-valuetext", "no data yet");
      }
    }
    renderOperationalState();
    updateDiagnostics();
    renderLiveCharge();
    renderCellBalance();
    renderCellGrid();
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
    renderLiveSignals();
  }

  // Live-signals diagnostic catalog: every metric the polling engine surfaces in
  // a telemetry sample, mapped to its value field, freshness field (`*StaleMs`),
  // unit, and group. The panel renders this list against state.telemetry so the
  // user can see exactly what the device is pulling and — crucially — what it is
  // NOT (a scheduled PID that never returns a usable value, e.g. an odometer the
  // car answers NO DATA on, shows "no data" instead of silently disappearing).
  // Keep in sync with LiveSampleReader.kt's emitted keys.
  type LiveSignalSpec = {
    key: string;
    label: string;
    group: string;
    unit?: string;
    staleKey?: string;
    text?: boolean;
    enhanced?: boolean;
  };
  const LIVE_SIGNAL_GROUPS = ["Core", "Battery", "Motor & drive", "Charging"];
  const LIVE_SIGNALS: LiveSignalSpec[] = [
    { key: "speedKph", label: "Speed", group: "Core", unit: "km/h", staleKey: "speedKphStaleMs" },
    { key: "rpm", label: "Engine RPM", group: "Core", staleKey: "rpmStaleMs" },
    { key: "soc", label: "State of charge", group: "Core", unit: "%", staleKey: "socStaleMs" },
    { key: "voltage", label: "Adapter 12V", group: "Core", unit: "V", staleKey: "voltageStaleMs" },
    { key: "coolantC", label: "Coolant temp", group: "Core", unit: "°C", staleKey: "coolantCStaleMs" },
    { key: "loadPct", label: "Engine load", group: "Core", unit: "%", staleKey: "loadPctStaleMs" },
    { key: "throttlePct", label: "Throttle / pedal", group: "Core", unit: "%", staleKey: "throttlePctStaleMs" },
    { key: "odometerKm", label: "Odometer", group: "Core", unit: "km", staleKey: "odometerStaleMs" },
    { key: "fuelLevelPct", label: "Fuel level", group: "Core", unit: "%", staleKey: "fuelLevelStaleMs" },
    { key: "engineRunTimeSec", label: "Engine run time", group: "Core", unit: "s", staleKey: "engineRunTimeStaleMs" },
    { key: "controlModuleVoltage", label: "Module voltage", group: "Core", unit: "V", staleKey: "controlModuleVoltageStaleMs" },
    { key: "intakeAirTempC", label: "Intake air temp", group: "Core", unit: "°C", staleKey: "intakeAirTempStaleMs" },
    { key: "engineOilTempC", label: "Engine oil temp", group: "Core", unit: "°C", staleKey: "engineOilTempStaleMs" },
    { key: "packVoltage", label: "HV pack voltage", group: "Battery", unit: "V", staleKey: "packVoltageStaleMs", enhanced: true },
    { key: "packCurrentA", label: "HV pack current", group: "Battery", unit: "A", staleKey: "packCurrentAStaleMs", enhanced: true },
    { key: "powerKw", label: "HV pack power", group: "Battery", unit: "kW", staleKey: "powerKwStaleMs", enhanced: true },
    { key: "batteryTemp", label: "HV battery temp", group: "Battery", unit: "°C", staleKey: "batteryTempStaleMs", enhanced: true },
    { key: "sohPct", label: "Battery health", group: "Battery", unit: "%", staleKey: "sohPctStaleMs", enhanced: true },
    { key: "capacityAh", label: "Pack capacity", group: "Battery", unit: "Ah", staleKey: "capacityAhStaleMs", enhanced: true },
    { key: "packEnergyKwh", label: "Pack energy", group: "Battery", unit: "kWh", staleKey: "packEnergyKwhStaleMs", enhanced: true },
    { key: "hvBatteryRawSoc", label: "Raw SOC", group: "Battery", unit: "%", staleKey: "hvBatteryRawSocStaleMs", enhanced: true },
    { key: "minCellVoltage", label: "Min cell voltage", group: "Battery", unit: "V", staleKey: "minCellVoltageStaleMs", enhanced: true },
    { key: "maxCellVoltage", label: "Max cell voltage", group: "Battery", unit: "V", staleKey: "maxCellVoltageStaleMs", enhanced: true },
    { key: "cellBalanceMv", label: "Cell spread", group: "Battery", unit: "mV", staleKey: "cellBalanceStaleMs", enhanced: true },
    { key: "socVariationPct", label: "Cell SOC variation", group: "Battery", unit: "%", staleKey: "socVariationStaleMs", enhanced: true },
    { key: "minCellNumber", label: "Min cell number", group: "Battery", staleKey: "minCellNumberStaleMs", enhanced: true },
    { key: "maxCellNumber", label: "Max cell number", group: "Battery", staleKey: "maxCellNumberStaleMs", enhanced: true },
    { key: "motorACurrentA", label: "Motor A current", group: "Motor & drive", unit: "A", staleKey: "motorAStaleMs", enhanced: true },
    { key: "motorBCurrentA", label: "Motor B current", group: "Motor & drive", unit: "A", staleKey: "motorBStaleMs", enhanced: true },
    { key: "motorAPowerKw", label: "Motor A power", group: "Motor & drive", unit: "kW", enhanced: true },
    { key: "motorBPowerKw", label: "Motor B power", group: "Motor & drive", unit: "kW", enhanced: true },
    { key: "prndlState", label: "Gear (PRNDL)", group: "Motor & drive", text: true, staleKey: "prndlStateStaleMs", enhanced: true },
    { key: "evDistanceThisCycleKm", label: "EV distance (cycle)", group: "Motor & drive", unit: "km", staleKey: "evDistanceThisCycleStaleMs", enhanced: true },
    { key: "engineTorqueNm", label: "Engine torque", group: "Motor & drive", unit: "Nm", staleKey: "engineTorqueStaleMs", enhanced: true },
    { key: "transmissionTempC", label: "Transmission temp", group: "Motor & drive", unit: "°C", staleKey: "transmissionTempStaleMs", enhanced: true },
    { key: "outsideTempC", label: "Outside temp", group: "Motor & drive", unit: "°C", staleKey: "outsideTempStaleMs", enhanced: true },
    { key: "chargingMode", label: "Charging mode", group: "Charging", text: true, staleKey: "chargingModeStaleMs", enhanced: true },
    { key: "chargingLevel", label: "Charging level", group: "Charging", text: true, staleKey: "chargingLevelStaleMs", enhanced: true },
    { key: "chargerHvVoltage", label: "Charger HV voltage", group: "Charging", unit: "V", staleKey: "chargerHvVoltageStaleMs", enhanced: true },
    { key: "chargerHvCurrent", label: "Charger HV current", group: "Charging", unit: "A", staleKey: "chargerHvCurrentStaleMs", enhanced: true },
    { key: "chargerPowerKw", label: "Charger power", group: "Charging", unit: "kW", staleKey: "chargerPowerStaleMs", enhanced: true },
    { key: "lastChargeEnergyWh", label: "Last charge energy", group: "Charging", unit: "Wh", staleKey: "lastChargeEnergyStaleMs", enhanced: true },
    { key: "hvBatteryChargeCount", label: "Charge count", group: "Charging", staleKey: "hvBatteryChargeCountStaleMs", enhanced: true },
  ];

  function formatSignalAge(ms: number) {
    if (!Number.isFinite(ms) || ms < 0) return "live";
    if (ms < 1500) return "now";
    if (ms < 60_000) return `${Math.round(ms / 1000)}s ago`;
    if (ms < 3_600_000) return `${Math.round(ms / 60_000)}m ago`;
    return `${Math.round(ms / 3_600_000)}h ago`;
  }

  function renderLiveSignals() {
    const list = el("liveSignalsList");
    if (!list) return;
    const t = state.telemetry || {};
    const hasLiveData = isActiveStatus() || Number(t.sampleCount || 0) > 0;
    const missingOnly = String(state.liveSignalsFilter || "all") === "missing";
    // Sync the filter buttons' active state with the current filter.
    document.querySelectorAll("[data-live-signal-filter]").forEach((node) => {
      const button = node as HTMLElement;
      const active = (button.dataset.liveSignalFilter || "all") === (missingOnly ? "missing" : "all");
      button.classList.toggle("is-active", active);
      button.setAttribute("aria-pressed", active ? "true" : "false");
    });
    let reporting = 0;
    const frag = document.createDocumentFragment();
    for (const group of LIVE_SIGNAL_GROUPS) {
      const groupRows: HTMLElement[] = [];
      for (const spec of LIVE_SIGNALS) {
        if (spec.group !== group) continue;
        const raw = t[spec.key];
        const has = raw !== undefined && raw !== null && raw !== "";
        if (has) reporting += 1;
        if (missingOnly && has) continue;
        const row = document.createElement("div");
        row.className = "live-signal-row";
        row.dataset.status = has ? "live" : "missing";

        const name = document.createElement("span");
        name.className = "live-signal-name";
        name.textContent = spec.label;
        if (spec.enhanced) {
          const tag = document.createElement("b");
          tag.className = "live-signal-tag";
          tag.textContent = "Volt";
          name.appendChild(tag);
        }

        const value = document.createElement("strong");
        value.className = "live-signal-value";
        value.textContent = has
          ? (spec.text ? String(raw) : `${String(raw)}${spec.unit ? " " + spec.unit : ""}`)
          : "no data";

        const age = document.createElement("small");
        age.className = "live-signal-age";
        const ageMs = spec.staleKey ? Number(t[spec.staleKey]) : Number.NaN;
        age.textContent = has ? formatSignalAge(ageMs) : "";

        row.append(name, value, age);
        groupRows.push(row);
      }
      // Skip empty group headers (e.g. the "missing" filter hid every row).
      if (!groupRows.length) continue;
      const header = document.createElement("div");
      header.className = "live-signals-group";
      header.textContent = group;
      frag.appendChild(header);
      for (const row of groupRows) frag.appendChild(row);
    }
    const total = LIVE_SIGNALS.length;
    if (missingOnly && reporting === total) {
      const allGood = document.createElement("p");
      allGood.className = "status-copy";
      allGood.textContent = "Every polled metric is reporting.";
      frag.appendChild(allGood);
    }
    list.replaceChildren(frag);
    VD.setText("liveSignalsBadge", `${reporting}/${total}`);
    VD.setText(
      "liveSignalsTitle",
      hasLiveData ? `${reporting} of ${total} metrics reporting` : "Connect to see live metrics",
    );
  }


  // Working cell-voltage window for the balance graphic's horizontal scale. The
  // Gen-2 Volt cell groups sit ~3.4-4.1 V in use; a slightly wider track keeps
  // both markers comfortably inside their gutters.
  const CELL_SCALE_MIN_V = 3.3;
  const CELL_SCALE_MAX_V = 4.25;

  function cellBalanceTone(mv: number) {
    if (!Number.isFinite(mv)) return "none";
    if (mv < 30) return "ok";
    if (mv < 60) return "warn";
    return "bad";
  }

  function renderCellBalance() {
    const card = el("cellBalanceCard");
    if (!card) return;
    const t = state.telemetry || {};
    const minV = Number(t.minCellVoltage);
    const maxV = Number(t.maxCellVoltage);
    const has = Number.isFinite(minV) && Number.isFinite(maxV);
    const graphic = el("cellBalanceGraphic");
    const empty = el("cellBalanceEmpty");
    if (graphic) graphic.hidden = !has;
    if (empty) empty.hidden = has;
    const deltaEl = el("cellBalanceDelta");
    if (!has) {
      VD.setText("cellBalanceTitle", "No live cell data yet");
      if (deltaEl) {
        deltaEl.textContent = "-- mV";
        deltaEl.dataset.tone = "none";
      }
      return;
    }
    const mv = Number.isFinite(Number(t.cellBalanceMv))
      ? Number(t.cellBalanceMv)
      : Math.round((maxV - minV) * 1000);
    VD.setText("cellBalanceTitle", "Cell-group balance");
    if (deltaEl) {
      deltaEl.textContent = `${mv} mV spread`;
      deltaEl.dataset.tone = cellBalanceTone(mv);
    }
    const span = CELL_SCALE_MAX_V - CELL_SCALE_MIN_V;
    const pos = (v: number) => Math.max(0, Math.min(100, ((v - CELL_SCALE_MIN_V) / span) * 100));
    const minPos = pos(minV);
    const maxPos = pos(maxV);
    const fill = el("cellBalanceFill");
    if (fill) {
      fill.style.left = minPos + "%";
      fill.style.width = Math.max(0, maxPos - minPos) + "%";
    }
    const minMarker = el("cellBalanceMinMarker");
    if (minMarker) minMarker.style.left = minPos + "%";
    const maxMarker = el("cellBalanceMaxMarker");
    if (maxMarker) maxMarker.style.left = maxPos + "%";
    const minCell = Number(t.minCellNumber);
    const maxCell = Number(t.maxCellNumber);
    VD.setText("cellBalanceMin", `${minV.toFixed(3)} V${Number.isFinite(minCell) ? ` · #${minCell}` : ""}`);
    VD.setText("cellBalanceMax", `${maxV.toFixed(3)} V${Number.isFinite(maxCell) ? ` · #${maxCell}` : ""}`);
    const soc = Number(t.socVariationPct);
    VD.setText("cellBalanceSoc", Number.isFinite(soc) ? `${soc}%` : "--");
  }

  // Usable HV-pack energy for a Gen-2 Chevy Volt. The pack is ~18.4 kWh
  // nameplate but only ~14 kWh is usable between the buffered SOC limits; the
  // car reports SOC against that usable window, so charge-energy math uses it.
  // Used only as a fallback when the live SOH-derived capacity isn't available.
  const VOLT_USABLE_KWH = 14;
  // Charge target (M2). Defaults to a full 100% charge; the user can set a daily
  // target (e.g. 80%) on the Charge tab, which both shrinks the remaining-energy
  // estimate here and drives the native "target reached" notification. Stored as
  // the "chargeTargetSoc" preference and clamped to [50, 100] to match the input.
  const LIVE_CHARGE_TARGET_SOC_DEFAULT = 100;
  const LIVE_CHARGE_TARGET_SOC_MIN = 50;
  const LIVE_CHARGE_TARGET_SOC_MAX = 100;

  function chargeTargetSoc(): number {
    const raw = Number(prefs.get<number>("chargeTargetSoc", LIVE_CHARGE_TARGET_SOC_DEFAULT));
    if (!Number.isFinite(raw)) return LIVE_CHARGE_TARGET_SOC_DEFAULT;
    return Math.min(LIVE_CHARGE_TARGET_SOC_MAX, Math.max(LIVE_CHARGE_TARGET_SOC_MIN, Math.round(raw)));
  }
  // Minimum plausible charger power (kW) to treat the car as actively charging.
  // A noisy/balancing reading (e.g. 0.2 kW) divided into the remaining energy
  // produces an absurd multi-hour ETA on the most prominent live card, so the
  // gate requires a real Level-1-or-better draw before showing an estimate.
  const MIN_LIVE_CHARGE_POWER_KW = 0.5;
  // ETA ceiling (ms). Above this the estimate is too uncertain to commit to a
  // number, so the card shows "Estimating…" instead of e.g. "~70h to 100%".
  const MAX_LIVE_CHARGE_ETA_MS = 24 * 3600 * 1000;
  // Treat the pack as "topping off" when the remaining energy or the SOC gap to
  // the target rounds to roughly nothing — avoids a flickering "~0m to 100%"
  // while the charger is still drawing a trickle to balance the final cells.
  const LIVE_CHARGE_NEARLY_FULL_KWH = 0.05;
  const LIVE_CHARGE_NEARLY_FULL_SOC_GAP = 1;

  // Estimated usable pack capacity (kWh). When the car reports state-of-health,
  // scale the nominal usable energy by it (a degraded pack holds less); else
  // fall back to the Volt nominal. SOH is a percentage (0–100).
  function estimateUsablePackKwh(sohPct: number): number {
    if (Number.isFinite(sohPct) && sohPct > 0 && sohPct <= 100) {
      return VOLT_USABLE_KWH * (sohPct / 100);
    }
    return VOLT_USABLE_KWH;
  }

  // Live time-to-full while charging (Charge tab). Active charge = a plausible
  // live charger power (>= MIN_LIVE_CHARGE_POWER_KW, so a balancing trickle
  // doesn't pass the gate) AND a known SOC below the target. Shows the estimated
  // time to the target SOC and the energy still needed; hides entirely otherwise
  // so a parked or discharging car never shows a stale ETA. The ETA is clamped:
  // a near-full pack shows "Topping off / nearly full" and an implausibly long
  // estimate shows "Estimating…" rather than an absurd "~70h to 100%".
  //   remaining_kWh = usable_kWh * (target - soc) / 100
  //   time_to_full  = remaining_kWh / charger_power_kW
  function renderLiveCharge() {
    const card = el("liveChargeCard");
    if (!card) return;
    const t = state.telemetry || {};
    const targetSoc = chargeTargetSoc();
    const powerKw = Number(t.chargerPowerKw);
    const soc = Number(t.soc);
    const charging =
      Number.isFinite(powerKw) && powerKw >= MIN_LIVE_CHARGE_POWER_KW &&
      Number.isFinite(soc) && soc >= 0 && soc < targetSoc;
    if (!charging) {
      card.hidden = true;
      return;
    }
    card.hidden = false;
    const usableKwh = estimateUsablePackKwh(Number(t.sohPct));
    const remainingKwh = Math.max(0, usableKwh * (targetSoc - soc) / 100);
    const socGap = targetSoc - soc;
    const nearlyFull =
      remainingKwh < LIVE_CHARGE_NEARLY_FULL_KWH || socGap <= LIVE_CHARGE_NEARLY_FULL_SOC_GAP;
    const etaMs = (remainingKwh / powerKw) * 3600 * 1000;
    let etaText: string;
    if (nearlyFull) {
      // SOC is essentially at target but the charger is still drawing a trickle.
      etaText = "Topping off — nearly full";
    } else if (etaMs > MAX_LIVE_CHARGE_ETA_MS) {
      // Implausibly long (low power into a large gap); commit to no number.
      etaText = "Estimating…";
    } else {
      etaText = `~${formatChargeEta(etaMs)} to ${targetSoc}%`;
    }
    VD.setText("liveChargeEta", etaText);
    VD.setText("liveChargeSoc", `${Math.round(soc)}%`);
    VD.setText("liveChargeRemaining", `${remainingKwh.toFixed(1)} kWh`);
    const powerBadge = el("liveChargePower");
    if (powerBadge) {
      const label = powerBadge.querySelector("span:last-child");
      if (label) label.textContent = `${powerKw.toFixed(1)} kW`;
    }
  }

  // "Xh Ym" / "Ym" for the charge ETA. formatDuration() rolls minutes into
  // "Xh YYm" but also emits seconds for short spans; a charge ETA is always at
  // least minutes, so round to whole minutes for a calmer readout.
  function formatChargeEta(ms: number): string {
    const totalMinutes = Math.max(0, Math.round(Number(ms) / 60000));
    if (totalMinutes < 60) return `${totalMinutes}m`;
    const hours = Math.floor(totalMinutes / 60);
    const minutes = totalMinutes % 60;
    return `${hours}h ${String(minutes).padStart(2, "0")}m`;
  }

  const CELL_GRID_COUNT = 96;
  let lastCellGridSig = "";

  // Maps a cell voltage to a blue→red color across the observed pack range, so an
  // outlier cell stands out in the grid.
  function cellGridColor(v: number, lo: number, hi: number): string {
    const span = hi - lo;
    const norm = span > 0.0005 ? Math.max(0, Math.min(1, (v - lo) / span)) : 0.5;
    const hue = 220 - norm * 220; // blue (low) → red (high)
    return `hsl(${hue.toFixed(0)}, 70%, 52%)`;
  }

  /**
   * 96-cell voltage map on the Battery tab. Phase E left the full per-cell read deferred until the
   * real car confirms the cell-PID layout, so this is a scaffold: if telemetry ever carries a
   * `cellVoltages` array (per-cell probe) it renders the full heatmap; until then it highlights the
   * lowest/highest cell groups the car DOES report live and greys the rest, with a note explaining a
   * probe is needed for the complete map. Memoized so we don't rebuild 96 nodes every sample.
   */
  function renderCellGrid() {
    const grid = el("cellGrid");
    if (!grid) return;
    // The whole card article (header, badge, note) — hidden alongside the inner
    // grid when there's nothing to show, so it isn't a perpetual empty scaffold.
    const card = el("cellGridCard");
    const t = state.telemetry || {};
    const rawCells = Array.isArray(t.cellVoltages) ? (t.cellVoltages as unknown[]) : [];
    const cells: number[] = rawCells
      .map((c) => (typeof c === "object" && c !== null ? Number((c as PayloadRecord).voltage) : Number(c)))
      .filter((v) => Number.isFinite(v));
    const minCell = Number(t.minCellNumber);
    const maxCell = Number(t.maxCellNumber);
    const minV = Number(t.minCellVoltage);
    const maxV = Number(t.maxCellVoltage);
    const full = cells.length >= CELL_GRID_COUNT;
    const sig = full
      ? `full:${cells.slice(0, CELL_GRID_COUNT).map((v) => v.toFixed(3)).join(",")}`
      : `hi:${minCell}:${maxCell}:${minV}:${maxV}`;
    if (sig === lastCellGridSig) return;
    lastCellGridSig = sig;

    const frag = document.createDocumentFragment();
    if (full) {
      if (card) card.hidden = false;
      const lo = Math.min(...cells);
      const hi = Math.max(...cells);
      for (let i = 0; i < CELL_GRID_COUNT; i += 1) {
        const v = cells[i];
        const box = document.createElement("span");
        box.className = "cell-grid-box";
        box.style.backgroundColor = cellGridColor(v, lo, hi);
        box.title = `Cell ${i + 1}: ${v.toFixed(3)} V`;
        frag.appendChild(box);
      }
      VD.setText("cellGridBadge", `${CELL_GRID_COUNT} cells`);
      VD.setText("cellGridTitle", "Per-cell voltage map");
      VD.setText("cellGridNote", "Per-cell voltages from the latest cell probe.");
    } else {
      const knownMin = Number.isFinite(minCell);
      const knownMax = Number.isFinite(maxCell);
      const known = (knownMin ? 1 : 0) + (knownMax ? 1 : 0);
      if (known === 0) {
        // M9 → C5: with no per-cell probe data AND no live lowest/highest groups yet, hide the WHOLE
        // card (header, badge, note, grid) rather than leaving a permanently-visible empty scaffold
        // that can only ever show "min/max or nothing". The card reappears the moment the car reports
        // any cell data. (Full per-cell probe is deferred pending on-car PID validation — see
        // sensor-expansion-plan.)
        if (card) card.hidden = true;
        grid.hidden = true;
        grid.replaceChildren();
        VD.setText("cellGridBadge", "awaiting probe");
        VD.setText("cellGridTitle", "Per-cell voltage map");
        VD.setText(
          "cellGridNote",
          "Connect to the car to highlight the lowest and highest cell groups; a full per-cell probe fills in the complete map.",
        );
        return;
      }
      if (card) card.hidden = false;
      grid.hidden = false;
      for (let i = 1; i <= CELL_GRID_COUNT; i += 1) {
        const box = document.createElement("span");
        box.className = "cell-grid-box";
        if (knownMin && i === minCell) {
          box.classList.add("is-min");
          box.title = `Cell ${i}: ${Number.isFinite(minV) ? minV.toFixed(3) + " V (lowest)" : "lowest"}`;
        } else if (knownMax && i === maxCell) {
          box.classList.add("is-max");
          box.title = `Cell ${i}: ${Number.isFinite(maxV) ? maxV.toFixed(3) + " V (highest)" : "highest"}`;
        } else {
          box.classList.add("is-unknown");
        }
        frag.appendChild(box);
      }
      // known is guaranteed > 0 here (the known === 0 case returned early above).
      VD.setText("cellGridBadge", `${known} of ${CELL_GRID_COUNT} known`);
      VD.setText("cellGridTitle", "Per-cell voltage map");
      VD.setText(
        "cellGridNote",
        "Live data reports only the lowest and highest cell groups (highlighted). A full per-cell probe on the car fills in the rest.",
      );
    }
    grid.replaceChildren(frag);
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
      // Fresh sample → ok; samples stored but stale → warn; nothing at all → bad (no OBD data
      // is a worse state than a stale stream, and the detail copy already distinguishes them).
      hasFreshSample ? "ok" : (hasAnySample ? "warn" : "bad"),
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
    const total = document.querySelectorAll(".validation-row").length;
    const okCount = document.querySelectorAll(".validation-row[data-tone='ok']").length;
    VD.setText("validationSummary", okCount ? `${okCount}/${total} ok` : "waiting");
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
    setDataTone(row, tone);
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

  export function formatDuration(ms: number) {
    const seconds = Math.max(0, Math.round(Number(ms) / 1000));
    if (seconds < 60) return `${seconds}s`;
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes}m ${String(seconds % 60).padStart(2, "0")}s`;
    // Roll into hours so multi-hour spans (e.g. charge sessions) read "3h 24m"
    // instead of "204m 00s".
    const hours = Math.floor(minutes / 60);
    return `${hours}h ${String(minutes % 60).padStart(2, "0")}m`;
  }

  // 1Hz heartbeat so the .stale class is applied even when no new sample
  // arrives (and removed promptly once one does). Cheap; touches a handful of
  // DOM nodes.
  setInterval(applyStaleIndicator, 1000);

  Object.assign(VD, {
    formatDistance,
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
    hydrateLiveRouteIfActive,
    scheduleRender,
    flushRender,
    applyStaleIndicator,
    updateLiveUi,
    updateDiagnostics,
    renderLiveCharge,
    updateValidationUi,
    setValidationRow,
    formatAge,
    summarizePidLine,
    formatDuration
  });

export {};
