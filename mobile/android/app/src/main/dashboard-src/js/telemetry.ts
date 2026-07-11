import {
  bridge,
  clearDemoTelemetry,
  el,
  formatRowCount,
  parsePayload,
  renderMapIfLoaded,
  setDemoActive,
  setMeter,
  setState,
  setText,
  state
} from "./core";
import { asDataState, setDataState, setDataTone } from "./dataset-state";
import { LIVE_ROUTE_ID, appendLiveRoutePoint, haversineMetersJs, liveSampleTimeMs } from "./map-route-utils";
import type { MapRoutePoint } from "./map-route-utils";
import { validatePayload } from "./payload-validators";
import { prefs, units } from "./prefs";
import { setStorage } from "./storage-status";
import { initialTelemetryState } from "./telemetry-state";
import { VD } from "./vd-registry";

  type PayloadRecord = Record<string, unknown>;
  type LiveCellGroup = HTMLElement | Element | null;
  type ValidationTone = "ok" | "warn" | "bad";

  // Native live samples are authoritative snapshots, not partial patches. A field disappears when
  // its PID ages out or a location fix is no longer current; carrying the prior JS value across that
  // omission is how an old road speed could remain visible after foreground catch-up. Metadata and
  // forward-compatible unknown keys still merge normally, while every known sensor field is cleared
  // before the new sample is applied.
  const AUTHORITATIVE_READING_KEYS = [
    "speedKph", "speedRejectedKph", "rpm", "voltage", "coolantC", "loadPct", "throttlePct",
    "soc", "batteryTemp", "powerKw", "powerKwStaleMs", "packVoltage", "packCurrentA",
    "packEnergyKwh", "chargerPowerKw", "capacityAh", "sohPct", "cellBalanceMv", "odometerMiles",
    "minCellVoltage", "maxCellVoltage", "minCellNumber", "maxCellNumber", "socVariationPct", "cellVoltages",
    "latitude", "longitude", "accuracyM", "gpsSpeedMps", "bearingDeg", "locationAgeMs",
    "locationProvider", "chargeTransitionHint", "vehicleState", "vehicleStateConfidence",
    "vehicleStateReasons", "throttleSource", "vin"
  ] as const;

  function asPayloadRecord(value: unknown): PayloadRecord {
    return value != null && typeof value === "object" && !Array.isArray(value)
      ? value as PayloadRecord
      : {};
  }

  function applyAuthoritativeSample(current: VoltTelemetry, sample: PayloadRecord): VoltTelemetry {
    const next: PayloadRecord = { ...current };
    for (const key of AUTHORITATIVE_READING_KEYS) next[key] = null;
    return { ...next, ...sample } as VoltTelemetry;
  }

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
  // Max age (ms) of a GPS fix before the Drive tile treats it as no-current-fix.
  // The native side keeps stamping the last accepted lat/lon into every sample
  // during a signal outage (tunnel/garage), so age — not lat/lon presence — is
  // what tells us the fix is stale. Generous enough to ride out ordinary ~1 Hz
  // gaps without flapping.
  const GPS_FIX_STALE_MS = 15000;
  // Below this duration, formatShortDuration shows one decimal (e.g. "1.5s").
  const SHORT_DURATION_DECIMAL_CUTOFF_MS = 10000;
  const LIVE_ROUTE_HYDRATION_RETRY_MS = 5_000;
  const LIVE_ROUTE_HYDRATION_MAX_ATTEMPTS = 3;
  let rateChipReconnectBound = false;
  // C2: the converted speed readout (#speedKph) is hidden by default — the hero
  // shows only the preferred unit. Tapping the speed row reveals the conversion
  // for a few seconds, then it hides again.
  const ALT_SPEED_REVEAL_MS = 3000;
  let speedAltRevealBound = false;
  let speedAltRevealTimer: ReturnType<typeof setTimeout> | null = null;
  // One-shot guard so we only ask the backend to rehydrate the live track once per
  // session activation (reset in resetTelemetry). Recovers the in-progress drive's
  // route after the WebView is torn down and recreated mid-drive.
  let liveRouteHydrated = false;
  let liveRouteHydrationAttempts = 0;
  let liveRouteHydrationRetryTimer: ReturnType<typeof setTimeout> | null = null;
  // Highest telemetry `updatedAt` observed so far. Native re-delivers the LAST
  // sample on every status broadcast, so setAppState must only treat a sample
  // as fresh when its updatedAt actually advances past this marker — otherwise
  // a wedged adapter keeps the stale indicator and rate chip reporting "live".
  let lastSeenSampleUpdatedAt = 0;
  // A genuinely new drive should reset the JS session baseline (distance / SOC
  // delta / history buffers) on the inactive→active status edge, but a mid-drive
  // Bluetooth blip that dips through disconnected/error/reconnecting before
  // re-'connected' must NOT — it is the same native session. Only re-arm this
  // reset after a genuine terminal/idle STOP is observed. A truly renumbered
  // native session (sampleCount restarting) is still caught independently by the
  // guard in updateTelemetry, so a real new session always resets either way.
  // Starts armed so the first connect from a cold boot resets.
  let resetArmed = true;

  function pushBounded(values: number[], value: number, limit: number) {
    values.push(value);
    const overflow = values.length - limit;
    if (overflow > 0) values.splice(0, overflow);
  }

  export function formatDistance(meters: unknown) {
    const m = Number(meters || 0);
    if (!Number.isFinite(m) || m <= 0) return "--";
    const d = units.distanceMeters(m);
    // Group thousands ("12,345 mi") to match the odometer readout. Only rewrite
    // all-digit values: short distances come back as "4.3" (toFixed) and must
    // keep their trailing decimal.
    const text = /^\d{4,}$/.test(d.value) ? Number(d.value).toLocaleString() : d.value;
    return `${text} ${d.unit}`;
  }

  // Status detail lands in #statusCopy, which lives on the Settings tab — taps
  // on other tabs ("Scan car codes" on Insights, blocked-adapter explanations)
  // would otherwise appear to do nothing. Mirror new detail strings in a small
  // aria-live toast whenever the user can't see #statusCopy.
  // X1 adaptive power track: normal driving lives within ±40 kW, so a fixed
  // ±80 wasted half the bar's resolution. The bound starts at 40 and ratchets
  // up (80 → 120, the Volt's propulsion ceiling) the first time a sample
  // exceeds it; it never shrinks mid-run so the fill doesn't jitter between
  // tiers on a spirited on-ramp.
  let powerScaleKw = 40;

  let statusToastTimer: ReturnType<typeof setTimeout> | null = null;
  let lastToastDetail = "";
  let toastBaselineSeen = false;

  // Connection states that represent an actionable failure rather than routine
  // progress. A screen reader should interrupt for these (assertive) instead of
  // waiting for a pause (polite), so the user hears "adapter blocked" / "connect
  // failed" the moment it happens rather than after whatever it was reading.
  const URGENT_TOAST_STATES = new Set(["error", "blocked", "failed"]);

  // Shared presentation for both toast paths (status stream + direct action
  // confirmations): points the live region's politeness — assertive for
  // failures, set before the text lands so assistive tech picks the right
  // urgency — writes the pill, and (re)arms the hide timer on the shared
  // module state. Callers own their gating; this owns the DOM.
  function presentToast(node: HTMLElement, text: string, hideAfterMs: number, urgent: boolean) {
    node.setAttribute("aria-live", urgent ? "assertive" : "polite");
    node.textContent = text;
    node.hidden = false;
    lastToastDetail = text;
    if (statusToastTimer) clearTimeout(statusToastTimer);
    statusToastTimer = setTimeout(() => {
      node.hidden = true;
      // Reset the dedupe baseline once the toast is gone so a later repeat of
      // the SAME action detail (e.g. tapping "Scan car codes" twice) gives
      // feedback again instead of silently doing nothing.
      lastToastDetail = "";
    }, hideAfterMs);
  }

  function showStatusToast(detail: unknown, statusState?: unknown) {
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
    presentToast(node, text, 3200, URGENT_TOAST_STATES.has(String(statusState == null ? "" : statusState)));
  }

  // Direct action-confirmation toast (v2 design): CSV exported, favorite
  // toggled, report copied, units changed. Same pill + timer as the status
  // toast (via presentToast) but none of the status-stream gating (baseline
  // consumption, Settings-tab suppression, dedupe) — a user action always
  // deserves immediate visible feedback, on any tab. `urgent` flags a failure
  // so it announces assertively like failed status pushes do.
  export function showToast(message: unknown, urgent = false) {
    const node = el("statusToast");
    if (!node) return;
    const text = String(message == null ? "" : message).trim();
    if (!text) return;
    presentToast(node, text, 2600, urgent);
  }

  function setStatus(payload: unknown) {
    const wasActive = isActiveStatus();
    const parsed = parsePayload<unknown>(payload, {});
    validatePayload("setStatus", parsed);
    const status = asPayloadRecord(parsed) as VoltStatus;
    state.status = status;
    const badge = el("stateBadge");
    const next = status.state || "idle";
    setDataState(badge, asDataState(next));
    if (!wasActive && isActiveStatus() && !state.demoActive && resetArmed) {
      resetTelemetry();
      resetArmed = false;
    }
    // Re-arm the inactive→active reset only after a genuine terminal/idle STOP —
    // a transient disconnect/error/reconnect blip mid-drive keeps the same native
    // session, so it must not re-zero the JS session baseline.
    if (isTerminalStopStatus(next)) resetArmed = true;
    hydrateLiveRouteIfActive();
    setText("stateText", next);
    setText("statusCopy", status.detail || "Ready.");
    showStatusToast(status.detail, next);
    if (status.lastAddress) state.lastDevice = { address: status.lastAddress, name: status.lastName || "" };
    renderOperationalState();
    updateDiagnostics();
    updateValidationUi();
  }

  function setAppState(payload: unknown) {
    const candidate = parsePayload<unknown>(payload, {});
    validatePayload("setAppState", candidate);
    const parsed = asPayloadRecord(candidate) as VoltAppState;
    if (state.demoActive && state.demoPreviewAppState) {
      // Park the real app-state behind the demo preview (cross-module demo invariant).
      setState({ realAppState: parsed });
      if (parsed.storage) setStorage(parsed.storage);
      renderOperationalState();
      updateLiveUi();
      updateValidationUi();
      return;
    }
    state.appState = parsed;
    const nextTelemetry = state.appState.latestTelemetry || {};
    if (shouldAcceptTelemetry(nextTelemetry)) {
      state.telemetry = applyAuthoritativeSample(state.telemetry, nextTelemetry);
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
      setStorage(state.appState.storage);
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

  // Status states that mark a session as genuinely ended (native stop() → IDLE),
  // as opposed to a transient disconnect/error/reconnect blip mid-drive. Only a
  // terminal stop re-arms the inactive→active reset in setStatus. "disconnected"
  // is deliberately excluded: a BT blip is a disconnect, and treating it as a
  // stop would re-arm the reset and re-zero the baseline on reconnect.
  const TERMINAL_STOP_STATES = new Set(["idle", "ready", "stopped", "stop"]);
  function isTerminalStopStatus(next: unknown) {
    return TERMINAL_STOP_STATES.has(String(next == null ? "" : next).toLowerCase());
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
    liveRouteHydrationAttempts = 0;
    if (liveRouteHydrationRetryTimer !== null) {
      clearTimeout(liveRouteHydrationRetryTimer);
      liveRouteHydrationRetryTimer = null;
    }
    // The POWER bar auto-scale only ratchets up (40→80→120) during a drive; without
    // this reset a high-power drive leaves it over-scaled for the next gentle drive
    // until reload. Reset to the initial ceiling so each session re-scales from 40.
    powerScaleKw = 40;
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
  function applyCurrentSessionRoutePayload(payload: unknown): boolean {
    let parsed: PayloadRecord;
    try {
      const candidate = parsePayload<unknown>(payload, {});
      validatePayload("setCurrentSessionRoute", candidate);
      if (!Object.keys(asPayloadRecord(candidate)).length) {
        armLiveRouteHydrationRetry();
        return false;
      }
      parsed = asPayloadRecord(candidate);
    } catch (_err) {
      armLiveRouteHydrationRetry();
      return false;
    }
    liveRouteHydrated = true;
    if (parsed.ok === false && parsed.error) {
      armLiveRouteHydrationRetry();
      return false;
    }
    const rawPoints = Array.isArray(parsed.points) ? parsed.points : [];
    const mapped: MapRoutePoint[] = [];
    for (const raw of rawPoints) {
      if (raw == null || typeof raw !== "object" || Array.isArray(raw)) continue;
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
    if (!mapped.length) {
      armLiveRouteHydrationRetry();
      return false;
    }
    if (liveRouteHydrationRetryTimer !== null) {
      clearTimeout(liveRouteHydrationRetryTimer);
      liveRouteHydrationRetryTimer = null;
    }
    const firstPoint = mapped[0]!;
    let recoveredDistanceM = 0;
    for (let i = 1; i < mapped.length; i += 1) {
      const previous = mapped[i - 1]!;
      const current = mapped[i]!;
      recoveredDistanceM += haversineMetersJs(previous.lat, previous.lng, current.lat, current.lng);
    }
    const lastPoint = mapped[mapped.length - 1]!;
    state.sessionDistanceM = recoveredDistanceM;
    state.sessionLastLat = lastPoint.lat;
    state.sessionLastLng = lastPoint.lng;
    state.liveRouteStartedAtMs = firstPoint.atMs;
    state.selectedMapSessionId = LIVE_ROUTE_ID;
    // If the map module is already loaded, route through its setter so its module-local
    // liveRoutePoints reference is updated too — a bare `state.liveRoutePoints = mapped`
    // reassignment would leave map.ts pointing at the old (empty) array, so the recovered
    // mid-drive route would never draw. When the map module isn't loaded yet, set state
    // directly; map.ts seeds its local from state.liveRoutePoints on load.
    if (typeof VD.setLiveRoutePoints === "function") {
      VD.setLiveRoutePoints(mapped, firstPoint.atMs);
    } else {
      state.liveRoutePoints = mapped;
    }
    renderMapIfLoaded();
    return true;
  }

  function armLiveRouteHydrationRetry() {
    if (
      liveRouteHydrationRetryTimer !== null ||
      liveRouteHydrationAttempts >= LIVE_ROUTE_HYDRATION_MAX_ATTEMPTS ||
      // A hydration attempt can resolve asynchronously after a vitest file's jsdom
      // environment (and its timer globals) has been torn down; arming the retry then
      // throws an unhandled "setTimeout is not a function" that fails the whole run.
      // In the WebView setTimeout always exists, so this only disarms dead test envs.
      typeof setTimeout !== "function"
    ) {
      return;
    }
    liveRouteHydrationRetryTimer = setTimeout(() => {
      liveRouteHydrationRetryTimer = null;
      if (!isActiveStatus()) return;
      const existing = Array.isArray(state.liveRoutePoints) ? state.liveRoutePoints : [];
      if (existing.length) {
        liveRouteHydrated = true;
        return;
      }
      liveRouteHydrated = false;
      hydrateLiveRouteIfActive();
    }, LIVE_ROUTE_HYDRATION_RETRY_MS);
  }

  function hydrateLiveRouteIfActive() {
    if (state.demoActive || liveRouteHydrated || !isActiveStatus()) return;
    const existing = Array.isArray(state.liveRoutePoints) ? state.liveRoutePoints : [];
    if (existing.length) {
      liveRouteHydrated = true;
      if (liveRouteHydrationRetryTimer !== null) {
        clearTimeout(liveRouteHydrationRetryTimer);
        liveRouteHydrationRetryTimer = null;
      }
      return;
    }
    if (
      !bridge ||
      (typeof bridge.getCurrentSessionRoute !== "function" &&
        typeof bridge.requestCurrentSessionRoute !== "function")
    ) {
      return;
    }
    liveRouteHydrationAttempts += 1;
    if (typeof bridge.requestCurrentSessionRoute === "function" && bridge.requestCurrentSessionRoute()) {
      liveRouteHydrated = true;
      armLiveRouteHydrationRetry();
      return;
    }
    if (typeof bridge.getCurrentSessionRoute === "function") {
      applyCurrentSessionRoutePayload(bridge.getCurrentSessionRoute());
    }
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
    if (typeof VD.refreshConnectionToolsAvailability === "function") {
      VD.refreshConnectionToolsAvailability();
    }

    setText("adapterSummary", adapterName);
    setText(
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
    setText("loggingState", connected ? (samples ? "live" : (sessionState || "ready")) : "idle");
    // Number.isFinite guard (same as the gpsValue tile below) so a legitimate
    // coordinate of exactly 0 still reads as locked; both axes must be valid.
    const hasFix =
      Number.isFinite(Number(state.telemetry.latitude)) &&
      Number.isFinite(Number(state.telemetry.longitude));
    setText("gpsState", gps.state || (hasFix ? "locked" : "waiting"));
    setText("dataSourceState", state.demoActive ? "demo" : "real");
    setText("dbState", formatRowCount(dbRowCount(storage)));
    const appInfo = app.app || {};
    setText("appVersionFooter", appInfo.version ? `Volt Tracker v${appInfo.version}` : "Volt Tracker");

    const primary = el("connectBtn");
    const lastButton = el("lastBtn") as HTMLButtonElement | null;
    if (lastButton) lastButton.hidden = connected && !state.demoActive;
    if (!primary) return;
    primary.classList.toggle("is-stop", connected);
    primary.classList.toggle("primary", !connected);
    primary.setAttribute("aria-busy", connecting || scanning ? "true" : "false");
    if (state.demoActive) {
      primary.dataset.primaryAction = "stopDemo";
      primary.setAttribute("aria-label", "Stop Demo / Testing");
      primary.textContent = "Stop demo";
    } else if (connecting) {
      primary.dataset.primaryAction = "stop";
      primary.setAttribute("aria-label", "Connecting to OBD adapter. Tap to stop.");
      primary.textContent = "Connecting…";
    } else if (scanning) {
      primary.dataset.primaryAction = "stop";
      primary.setAttribute("aria-label", "Scanning with OBD adapter. Tap to stop.");
      primary.textContent = "Scanning…";
    } else if (connected) {
      primary.dataset.primaryAction = "stop";
      primary.setAttribute("aria-label", "Disconnect OBD adapter");
      primary.textContent = "Disconnect";
    } else if (!bridge) {
      primary.dataset.primaryAction = "demo";
      primary.setAttribute("aria-label", "Start Demo / Testing");
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

  export function dbRowCount(storage: PayloadRecord) {
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

  export function getLastDevice() {
    if (bridge && typeof bridge.getLastDevice === "function") {
      state.lastDevice = parsePayload(bridge.getLastDevice(), state.lastDevice || {});
    }
    return state.lastDevice || {};
  }

  export function relativeTime(value: unknown) {
    const ts = Number(value);
    if (!Number.isFinite(ts) || ts <= 0) return "saved";
    const seconds = Math.max(1, Math.round((Date.now() - ts) / 1000));
    if (seconds < 60) return `${seconds}s ago`;
    // Gate on the unrounded span so e.g. 59m40s stays "60m ago" (not "1h ago") and
    // 23.5–23.99h stays "Nh ago" instead of rounding up across the boundary into
    // "1d ago"; round only for the displayed number.
    if (seconds < 3600) return `${Math.round(seconds / 60)}m ago`;
    const hours = seconds / 3600;
    if (hours < 24) return `${Math.round(hours)}h ago`;
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
    setText(id, value);
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

  export function getSelectedDevice() {
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
    const parsed = parsePayload<unknown>(payload, {});
    validatePayload("updateTelemetry", parsed);
    const sample = asPayloadRecord(parsed);
    const source = String(sample.source || "").toLowerCase();
    const isDemoSample = source.includes("demo");
    if (isDemoSample && !state.demoActive) setDemoActive(true);
    if (sample.source && !isDemoSample && state.demoActive) {
      clearDemoTelemetry();
      setDemoActive(false);
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
    state.telemetry = applyAuthoritativeSample(state.telemetry, sample);
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
    // The data-state / logic key stays the short token so the CSS state selectors
    // and the checks below keep working; the visible label appends the ~1 Hz poll
    // cadence when live to match the v2 design chip ("LIVE · 1 HZ").
    const state = samples && isStale ? "stale" : samples ? "live" : "waiting";
    const label = state === "live" ? "live · 1 Hz" : state;
    setDataState(chip, state);
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
        state === "live"
          ? "Telemetry live. Samples are updating."
          : "Waiting for the first telemetry sample."
      );
      chip.title = "";
    }
    const rateLabel = el("liveRateLabel");
    if (rateLabel) rateLabel.textContent = label;
  }

  // C2: tap-to-convert on the speed hero. The secondary readout (#speedKph,
  // the other unit system) stays hidden so the hero reads as ONE number; a tap
  // anywhere on the speed row shows the conversion for ALT_SPEED_REVEAL_MS.
  // The speed row had no other click handler, so a plain listener is safe.
  function bindSpeedAltReveal(row: Element) {
    if (speedAltRevealBound) return;
    speedAltRevealBound = true;
    row.addEventListener("click", () => {
      const alt = el("speedKph");
      if (!alt) return;
      alt.hidden = false;
      if (speedAltRevealTimer != null) clearTimeout(speedAltRevealTimer);
      speedAltRevealTimer = setTimeout(() => {
        speedAltRevealTimer = null;
        alt.hidden = true;
      }, ALT_SPEED_REVEAL_MS);
    });
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

  export function updateLiveUi() {
    const t = state.telemetry;
    const kph = Number(t.speedKph);
    // Guard null/"" explicitly (like the rpm/voltage/soc tiles below) BEFORE the
    // finite check: Number(null) === 0 is finite, so a seeded/reset speedKph:null
    // otherwise rendered a real-looking "0 mph" with a filled meter while every
    // neighboring tile correctly showed "--", then jumped to the true value on
    // the first sample. A genuine stopped-car 0 (numeric) still passes.
    const hasSpeed = t.speedKph != null && t.speedKph !== "" && Number.isFinite(kph);
    // Primary = the user's chosen unit (prefs.ts). The secondary readout keeps
    // tracking the other system but stays hidden until the hero is tapped (C2:
    // preferred-unit-only, conversion on tap — see bindSpeedAltReveal).
    const metric = units.system() === "metric";
    const primary = hasSpeed ? units.speed(kph) : null;
    const altValue = hasSpeed ? Math.round(metric ? kph * 0.621371 : kph) : null;
    const altUnit = metric ? "mph" : "km/h";
    setText("speedValue", primary ? primary.value : null);
    setText("speedUnitMain", primary ? primary.unit : (metric ? "km/h" : "mph"));
    setText("speedKph", hasSpeed ? `${altValue} ${altUnit}` : `-- ${altUnit}`);
    const speedMeter = el("speedValue")?.closest("[role='meter']");
    if (speedMeter) {
      bindSpeedAltReveal(speedMeter);
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
    // v2 design shows "RPM 0" while in EV mode (engine off is a real reading on
    // a Volt, and the visible 0 keeps the 3×2 tile grid even); only a missing
    // value collapses the tile.
    setOptionalLiveText("rpmValue", t.rpm == null || t.rpm === "" ? "--" : t.rpm);
    // voltageValue is the aux 12V (ATRV from the ELM adapter), labelled accordingly
    // in the partial. The HV traction-pack voltage is rendered via drivePackVoltage below.
    setOptionalLiveText(
      "voltageValue",
      t.voltage == null || t.voltage === "" ? "--" : `${Number(t.voltage).toFixed(2)} V`
    );
    setOptionalLiveText("coolantValue", t.coolantC != null ? units.tempText(Number(t.coolantC)) : "--");
    setOptionalLiveText("loadValue", t.loadPct != null ? `${t.loadPct}%` : "--");
    setOptionalLiveText("throttleValue", t.throttlePct != null ? `${t.throttlePct}%` : "--");
    const lat = Number(t.latitude);
    const lon = Number(t.longitude);
    const acc = Number(t.accuracyM);
    const gpsState = String((state.appState.gps || {}).state || "");
    // A stale fix must not read as current: the native side keeps re-stamping the
    // last accepted lat/lon during a signal outage, so a finite fix age past
    // GPS_FIX_STALE_MS means "no current fix" even though lat/lon are present.
    const gpsAgeMs = Number((state.appState.gps || {}).ageMs);
    const gpsStale = Number.isFinite(gpsAgeMs) && gpsAgeMs > GPS_FIX_STALE_MS;
    // Surface fix quality, not just "locked": ±Nm tells the user whether the GPS
    // is precise enough to trust the route. accuracyM is already in the payload.
    // No fix yet: reuse the status-popover's shared gpsText() wording so the tile
    // and the popover agree. A "blocked" permission is an actionable status worth
    // showing in-cell; the plain waiting/unknown/stale case stays the "--" empty
    // sentinel so the cell (and group empty-text) can collapse as before.
    const gpsTile = Number.isFinite(lat) && Number.isFinite(lon) && !gpsStale
      ? Number.isFinite(acc) && acc > 0
        // Match the rest of the Drive screen's unit system: feet for imperial,
        // meters for metric (same inline 3.28084 conversion map.ts/scrubber.ts use).
        ? units.system() === "metric"
          ? `±${Math.round(acc)} m`
          : `±${Math.round(acc * 3.28084)} ft`
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

    setText("rawFrames", t.raw || "Waiting for telemetry…");
    const soc = finiteNum(t.soc);
    const batteryTemp = finiteNum(t.batteryTemp);
    setText("driveSocValue", soc != null ? `${Math.round(soc)}%` : "--");
    // v2 design: the SOC caption doubles as the EV-range note ("≈ 26 mi EV
    // range") once the enhanced range signal reports; otherwise it stays the
    // static "state of charge" label so the number is never unexplained.
    const evRangeKm = finiteNum(t.evDistanceThisCycleKm);
    setText(
      "driveSocSub",
      evRangeKm != null && evRangeKm > 0
        ? `≈ ${units.distanceText(evRangeKm)} EV range`
        : "state of charge"
    );
    // Pass the raw (possibly NaN) value through; setMeter clears the meter to an
    // indeterminate state for a missing reading rather than announcing a false 0%.
    setMeter("driveSocMeter", soc ?? NaN);
    // Color the SOC bar by charge level (amber when low, red when nearly empty)
    // so a depleted pack is obvious at a glance instead of a full-green bar.
    const socMeterEl = el("driveSocMeter");
    if (socMeterEl) {
      socMeterEl.dataset.level = soc == null ? "" : soc <= 15 ? "bad" : soc <= 30 ? "warn" : "ok";
    }
    setText("drivePackTempValue", batteryTemp != null ? units.tempText(batteryTemp) : "--");
    const power = finiteNum(t.powerKw);
    // Typographic minus (U+2212) for negatives so the hero POWER readout matches
    // the signed pack-current/pack-power tiles below it — the ASCII hyphen has a
    // narrower advance width, so the same regen reading rendered two glyphs.
    setText("powerValue", power != null ? `${power < 0 ? "−" : ""}${Math.abs(power).toFixed(1)} kW` : "--");
    // HV traction-pack live readings (mode-22 PIDs 222429 / 222414). When the
    // adapter hasn't responded yet these fall back to "--" exactly like the rest of
    // the live readout.
    const packV = finiteNum(t.packVoltage);
    setOptionalLiveText("drivePackVoltage", packV != null ? `${packV.toFixed(1)} V` : "--");
    const packA = finiteNum(t.packCurrentA);
    // Sign convention: discharge is positive (Volt mode-22 222414), so "+" means
    // current flowing OUT of the pack (driving), "-" means INTO it (regen / charging).
    // Typographic minus (U+2212) to match fmtSocDelta and the power micro tag —
    // the ASCII hyphen has a different advance width, so the same regen reading
    // rendered two different sign glyphs on adjacent Drive tiles.
    setOptionalLiveText(
      "drivePackCurrent",
      packA != null ? `${packA >= 0 ? "+" : "−"}${Math.abs(packA).toFixed(1)} A` : "--"
    );
    setOptionalLiveText(
      "drivePackPower",
      power != null ? `${power >= 0 ? "+" : "−"}${Math.abs(power).toFixed(1)} kW` : "--"
    );
    const powerState = power == null ? "coast"
      : power < -0.5 ? "regen"
      : power > 0.5 ? "drive"
      : "coast";
    const powerDetail = el("powerDetail");
    if (powerDetail) {
      // Mirror liveHeroCard below: gray "idle" means "no data", NOT "no torque".
      // A real coast (power ≈ 0) still reads "coast"; only a MISSING reading
      // (power == null) is "idle", so the pill and the hero card never disagree.
      const detailState = power == null ? "idle" : powerState;
      powerDetail.textContent = detailState;
      setDataState(powerDetail, detailState);
    }
    // State-reactive hero (X1): the whole speed+power cluster tints from one
    // accent — orange under drive power, green in regen, neutral coasting —
    // via the --hero-accent custom property keyed off this attribute
    // (components.css). The speed-trace canvas reads the same property.
    const heroCard = el("liveHeroCard");
    // "idle" (no reading) keeps the neutral gray hero; "coast" is a live state
    // and gets its own warm tint in CSS — gray must always mean "no data",
    // never "no torque".
    if (heroCard) heroCard.dataset.powerState = power == null ? "idle" : powerState;
    // Ratchet the adaptive track bound before computing the fill fraction.
    if (power != null) {
      while (Math.abs(power) > powerScaleKw && powerScaleKw < 120) {
        powerScaleKw = powerScaleKw === 40 ? 80 : 120;
      }
    }
    // The visible scale drops the +/- sign — the "REGEN"/"DRIVE" words already carry
    // the direction (v2 design: "◄ REGEN 40 … DRIVE 40 ►"). The signed range stays on
    // the meter's aria-valuemin/max below for assistive tech.
    setText("powerScaleMin", `${powerScaleKw}`);
    setText("powerScaleMax", `${powerScaleKw}`);
    // Fraction of the half-track (0..1); the fill's CSS spans the right half
    // and scaleX stretches it from the zero-line — a negative scale mirrors it
    // left for regen. Transform-only so this per-sample update stays on the
    // compositor (no layout), unlike the old left/width mutation.
    const frac = power != null ? Math.min(1, Math.abs(power) / powerScaleKw) : 0;
    const fill = el("powerFill");
    if (fill) {
      // Mirror the fill left only when the reading is actually classified regen
      // (power < -0.5), matching the is-regen tint and the coast/regen pill. Using
      // a bare `power < 0` here pushed a light-regen coast (e.g. -0.3 kW) onto the
      // regen half while keeping the drive colour, so direction and colour disagreed.
      fill.style.transform = `scaleX(${powerState === "regen" ? -frac : frac})`;
      fill.classList.toggle("is-regen", powerState === "regen");
    }
    const powerMeter = el("powerMeter");
    if (powerMeter) {
      powerMeter.setAttribute("aria-valuemin", String(-powerScaleKw));
      powerMeter.setAttribute("aria-valuemax", String(powerScaleKw));
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

  export function updateDiagnostics() {
    const t = state.telemetry || {};
    const app = state.appState || {};
    const vehicle = app.vehicle || {};
    const status = state.status || {};
    const samples = Number(t.sampleCount || 0);
    // The Android classifier writes vehicle.state on the app-state payload as
    // the source of truth; fall back to the telemetry-level mirror only if a
    // bridge call hasn't delivered the app-state payload yet.
    const vehicleState = vehicle.state || t.vehicleState || "unknown";
    // Status details are toast-style sentences ("Demo scenario: typical.");
    // as a card title the trailing period clashes with every other headline.
    const diagTitle = String(status.detail || (t.updatedAt ? "Live OBD data received" : "Waiting for adapter")).replace(/\.\s*$/, "");
    setText("diagState", diagTitle);
    setText("diagSamples", samples ? `${samples} sample${samples === 1 ? "" : "s"}` : "0 samples");
    // Drive's slim session/health footer (v2 design) mirrors this card's state
    // in one line; the full card itself now lives on Diagnostics.
    // "demo scenario: typical" compresses to the design's "demo typical" so the
    // one-line footer fits without ellipsizing the samples count off-screen.
    setText(
      "sessionFooterLine",
      `OBD session · ${diagTitle.toLowerCase().replace(/^demo scenario:\s*/, "demo ")} · ${samples} sample${samples === 1 ? "" : "s"}`
    );
    setText("diagAdapter", t.adapter || status.adapter || "--");
    // Surface the classifier's confidence inline and its reason codes (the "why"
    // behind driving/charging/parked) as a tooltip — both already reach JS via the
    // app-state payload (vehicle.confidence / vehicle.reasons).
    const stateConfidence = String(vehicle.confidence || t.vehicleStateConfidence || "").toLowerCase();
    const stateReasons = Array.isArray(vehicle.reasons)
      ? vehicle.reasons.filter(Boolean).map(String)
      : [];
    setText("diagVehicleState", stateConfidence ? `${vehicleState} · ${stateConfidence}` : vehicleState);
    const stateEl = el("diagVehicleState");
    if (stateEl) {
      if (stateReasons.length) stateEl.setAttribute("title", `Why: ${stateReasons.join("; ")}`);
      else stateEl.removeAttribute("title");
    }
    setText("diagSession", t.sessionMs ? formatDuration(Number(t.sessionMs)) : "--");
    setText("diagSupported", t.supportedPids ? summarizePidLine(t.supportedPids) : "unknown");
    // Footnote tier: a placeholder printed at value weight ("unknown", "--")
    // is noise, not information — hide the cell until it has a real reading.
    ["diagAdapter", "diagVehicleState", "diagSession", "diagSupported"].forEach((id) => {
      const node = el(id);
      if (!node) return;
      const cell = node.closest("div");
      if (!cell) return;
      const text = (node.textContent || "").trim().toLowerCase();
      (cell as HTMLElement).hidden = !text || text === "--" || text === "unknown";
    });
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
    // Gate each tier on the ROUNDED value, not the raw ms: rounding first and
    // range-checking after meant ~59.6s printed "60s ago" (and ~59.6m "60m ago")
    // instead of rolling into the next unit.
    const secs = Math.round(ms / 1000);
    if (secs < 60) return `${secs}s ago`;
    const mins = Math.round(ms / 60_000);
    if (mins < 60) return `${mins}m ago`;
    return `${Math.round(ms / 3_600_000)}h ago`;
  }

  // Derived signal values (pack power = V×A/1000, unit-converted temps) arrive as
  // raw floats and would otherwise paint with full double precision
  // ("-1.4462624380… kW"). Clamp numeric values to a readable precision:
  // 3 decimals under 10 (cell voltages need millivolt resolution), 2 above.
  // Number(toFixed) drops trailing zeros so integers stay "13.8", not "13.80".
  function formatSignalValue(raw: unknown): string {
    const n = Number(raw);
    if (!Number.isFinite(n) || (typeof raw === "string" && raw.trim() === "")) return String(raw);
    if (Number.isInteger(n)) return String(n);
    return String(Number(n.toFixed(Math.abs(n) < 10 ? 3 : 2)));
  }

  // Signature of the last renderLiveSignals() paint. A parked/flat car pushes the
  // same telemetry every rAF, and renderLiveSignals() otherwise rebuilds ~45 rows
  // each frame; this dirty-check (mirroring renderCellGrid's lastCellGridSig)
  // hashes the (value, staleAge) tuples for every LIVE_SIGNALS key plus the filter
  // mode + hasLiveData, and early-returns when nothing changed.
  let lastLiveSignalsSig = "";

  function renderLiveSignals() {
    const list = el("liveSignalsList");
    if (!list) return;
    const t = state.telemetry || {};
    const hasLiveData = isActiveStatus() || Number(t.sampleCount || 0) > 0;
    // "reporting" (default) shows only PIDs the car is answering; "missing" only
    // the ones it isn't; "all" shows the full catalog.
    const filter = String(state.liveSignalsFilter || "reporting");
    const missingOnly = filter === "missing";
    const reportingOnly = filter === "reporting";
    // Dirty-check: build a signature from each signal's value + stale age, plus the
    // inputs that change the rendered output (filter mode, live-data state). Skip
    // the full rebuild when it matches the previous paint.
    const sig =
      `${filter}|${hasLiveData ? 1 : 0}|` +
      LIVE_SIGNALS
        .map((spec) => {
          const raw = t[spec.key];
          const v = raw === undefined || raw === null ? "" : String(raw);
          const age = spec.staleKey ? String(t[spec.staleKey] ?? "") : "";
          return `${v}:${age}`;
        })
        .join(",");
    if (sig === lastLiveSignalsSig) return;
    lastLiveSignalsSig = sig;
    // Sync the filter buttons' active state with the current filter.
    document.querySelectorAll("[data-live-signal-filter]").forEach((node) => {
      const button = node as HTMLElement;
      const active = (button.dataset.liveSignalFilter || "reporting") === filter;
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
        if (reportingOnly && !has) continue;
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
        // Degree units hug the number ("85°C", matching units.tempText); all
        // other units get the usual space ("3.4 kW").
        value.textContent = has
          ? (spec.text
              ? String(raw)
              : `${formatSignalValue(raw)}${spec.unit ? (spec.unit.startsWith("°") ? "" : " ") + spec.unit : ""}`)
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
    if (reportingOnly && reporting === 0 && hasLiveData) {
      const none = document.createElement("p");
      none.className = "status-copy";
      none.textContent = "No metrics reporting yet — the car hasn't answered any PIDs.";
      frag.appendChild(none);
    }
    list.replaceChildren(frag);
    setText("liveSignalsBadge", `${reporting}/${total}`);
    setText(
      "liveSignalsTitle",
      hasLiveData ? `${reporting} of ${total} metrics reporting` : "Connect to see live metrics",
    );
  }


  // Working cell-voltage window for the balance graphic's horizontal scale. The
  // Gen-2 Volt cell groups sit ~3.4-4.1 V in use; a slightly wider track keeps
  // both markers comfortably inside their gutters.
  function cellBalanceTone(mv: number) {
    if (!Number.isFinite(mv)) return "none";
    if (mv < 30) return "ok";
    if (mv < 60) return "warn";
    return "bad";
  }

  // Memoized like renderCellGrid/renderLiveSignals: updateLiveUi calls this on
  // every rAF flush and app-state broadcast, but the cell min/max move slowly —
  // skip the style/text writes when nothing in the readout changed.
  let lastCellBalanceSig = "";
  // Whether the 96-group heatmap is revealed (toggled by the "Read 96 cells" /
  // "Hide cells" button). Kept in module state so both renders and the toggle
  // action agree, and so a data refresh alone never forces the grid open.
  let cellGridOpen = false;

  // Summary word for the spread, keyed off the same tone thresholds as the grid.
  function cellHealthWord(tone: string): string {
    return tone === "bad" ? "imbalanced" : tone === "warn" ? "drifting" : "healthy";
  }

  function renderCellBalance() {
    const card = el("cellBalanceCard");
    if (!card) return;
    const t = state.telemetry || {};
    const sig = [
      t.minCellVoltage, t.maxCellVoltage, t.cellBalanceMv,
      t.minCellNumber, t.maxCellNumber, t.socVariationPct, cellGridOpen
    ].join(":");
    if (sig === lastCellBalanceSig) return;
    lastCellBalanceSig = sig;
    const minV = Number(t.minCellVoltage);
    const maxV = Number(t.maxCellVoltage);
    const has = t.minCellVoltage != null && t.maxCellVoltage != null &&
      Number.isFinite(minV) && Number.isFinite(maxV);
    const toggle = el("cellToggleBtn");
    if (!has) {
      setText("cellBalanceCopy", "No cell readings yet — connect while the car is awake.");
      // No data → hide the toggle and keep the heatmap collapsed. renderCellGrid
      // (called right after in updateLiveUi) then hides the grid section.
      if (toggle) toggle.hidden = true;
      cellGridOpen = false;
      return;
    }
    card.hidden = false;
    const mv = t.cellBalanceMv != null && Number.isFinite(Number(t.cellBalanceMv))
      ? Number(t.cellBalanceMv)
      : Math.round((maxV - minV) * 1000);
    const tone = cellBalanceTone(mv);
    setText(
      "cellBalanceCopy",
      `Δ ${mv} mV across ${CELL_GRID_COUNT} groups — ${cellHealthWord(tone)}`,
    );
    if (toggle) {
      toggle.hidden = false;
      toggle.textContent = cellGridOpen ? "Hide cells" : "Read 96 cells";
      toggle.setAttribute("aria-expanded", cellGridOpen ? "true" : "false");
    }
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
    const socRound = Math.round(soc);
    let etaText: string;
    if (nearlyFull) {
      // SOC is essentially at target but the charger is still drawing a trickle.
      etaText = "Topping off — nearly full";
    } else if (etaMs > MAX_LIVE_CHARGE_ETA_MS) {
      // Implausibly long (low power into a large gap); commit to no number.
      etaText = "Estimating…";
    } else {
      // v2 design: lead with the current SOC and give a wall-clock finish time
      // ("71% — full around 9:40 PM"), which answers the question the user is
      // actually asking; a custom target names the target instead of "full".
      const finish = fmtWallClock(Date.now() + etaMs);
      etaText =
        targetSoc >= LIVE_CHARGE_TARGET_SOC_MAX
          ? `${socRound}% — full around ${finish}`
          : `${socRound}% — ${targetSoc}% around ${finish}`;
    }
    setText("liveChargeEta", etaText);
    setText("liveChargeSoc", `${socRound}%`);
    setText("liveChargeRemaining", `${remainingKwh.toFixed(1)} kWh`);
    const powerBadge = el("liveChargePower");
    if (powerBadge) {
      const label = powerBadge.querySelector("span:last-child");
      // v2 design: the hero chip carries the state word; the live kW figure
      // lives in the "Level 2 · 3.4 kW · started 38m ago" sub-line below.
      if (label) label.textContent = "charging";
    }
    // Progress toward the target (v2): SOC-wide green bar with the session's
    // from→to on the left and the target caption on the right.
    setMeter("liveChargeMeter", soc);
    const session = liveChargeSession();
    const startSoc = session ? Number(session.startSoc) : NaN;
    setText(
      "liveChargeFromTo",
      Number.isFinite(startSoc) && Math.round(startSoc) !== socRound
        ? `${Math.round(startSoc)}% → ${socRound}%`
        : `${socRound}%`
    );
    setText("liveChargeTargetLabel", `target ${targetSoc}%`);
    // "Level 2 · 3.4 kW · started 38m ago" — charger type + live power +
    // session age. Hidden when the in-progress session hasn't landed yet.
    const subEl = el("liveChargeSub");
    if (subEl) {
      const parts: string[] = [];
      const chargerType = session ? String(session.chargerType || "") : "";
      const chargerNames: Record<string, string> = {
        level1: "Level 1",
        level2: "Level 2",
        dc_fast: "DC fast",
        dcfast: "DC fast"
      };
      const chargerKey = chargerType.trim().toLowerCase().replace(/[\s-]+/g, "_");
      if (chargerKey && chargerKey !== "unknown") {
        parts.push(chargerNames[chargerKey] || chargerType.charAt(0).toUpperCase() + chargerType.slice(1));
      }
      parts.push(`${powerKw.toFixed(1)} kW`);
      const startedAtMs = session ? Number(session.startedAtMs) : NaN;
      if (Number.isFinite(startedAtMs) && startedAtMs > 0) {
        parts.push(`started ${relativeTime(startedAtMs)}`);
      }
      subEl.textContent = parts.join(" · ");
      // Always shown: parts always carries at least the live kW figure, which
      // moved here now that the hero chip is the state word ("charging").
      subEl.hidden = false;
    }
  }

  // The in-progress charge session (endedAtMs still null) from the storage
  // summary — carries startSoc/chargerType/startedAtMs for the hero's
  // from→to and sub line. Newest-first, so the first open session wins.
  function liveChargeSession(): PayloadRecord | null {
    const charge = ((state.storage || {}) as PayloadRecord).chargeSummary as PayloadRecord | undefined;
    const sessions = charge && Array.isArray(charge.recentSessions) ? (charge.recentSessions as PayloadRecord[]) : [];
    for (const session of sessions) {
      if (session && session.endedAtMs == null && session.startedAtMs) return session;
    }
    return null;
  }

  // "9:40 PM" — locale hour:minute for the charge-finish estimate.
  function fmtWallClock(ms: number): string {
    return new Date(ms).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
  }

  const CELL_GRID_COUNT = 96;
  // A probe pass can miss a few cells (bus noise, one unanswered DID) and the map is
  // still trustworthy; below this many known cells fall back to the min/max highlight.
  const CELL_GRID_FULL_MIN = 90;
  let lastCellGridSig = "";

  // Latest persisted full-pack probe result (bridge getCellSnapshot / setCellSnapshot push),
  // used when the live telemetry sample doesn't carry a cellVoltages array — i.e. any time
  // after the probe session ended, including app restarts.
  let probeCellSlots: Array<number | null> | null = null;
  let probeCellCapturedAtMs = 0;

  /**
   * Accepts the persisted cell-snapshot payload `{capturedAtMs, cellCount, cells:[{index, voltage}]}`
   * (index 1-based) from the storage bridge and re-renders the cell map. An empty/error payload
   * clears the stored map (storage was wiped).
   */
  function applyCellSnapshot(payload: unknown) {
    const snap = asPayloadRecord(parsePayload<unknown>(payload, {}));
    const rawCells = Array.isArray(snap.cells) ? (snap.cells as unknown[]) : [];
    const slots: Array<number | null> = new Array(CELL_GRID_COUNT).fill(null);
    for (const item of rawCells) {
      if (typeof item !== "object" || item === null) continue;
      const idx = Number((item as PayloadRecord).index);
      const v = Number((item as PayloadRecord).voltage);
      if (Number.isFinite(idx) && idx >= 1 && idx <= CELL_GRID_COUNT && Number.isFinite(v)) {
        slots[idx - 1] = v;
      }
    }
    const hasAny = slots.some((v) => v !== null);
    probeCellSlots = hasAny ? slots : null;
    probeCellCapturedAtMs = hasAny ? Number(snap.capturedAtMs) || 0 : 0;
    lastCellGridSig = "";
    renderCellGrid();
  }

  // Tints a cell-group bar by how far its voltage deviates from the pack mean, so
  // outliers pop (v2 handoff heatmap): green when near the mean, amber past ~2.6 mV,
  // red past ~6 mV. Green brightens toward the mean. Uses the app's --ev/--warn/--bad
  // rgb tokens (inline var() is theme-aware) instead of the design's fixed hex.
  function cellHeatColor(v: number, mean: number): string {
    const d = Math.abs(v - mean);
    if (d > 0.006) return "rgba(var(--bad-rgb), 0.9)";
    if (d > 0.0026) return "rgba(var(--warn-rgb), 0.85)";
    const alpha = (0.45 + Math.max(0, 1 - d / 0.0026) * 0.4).toFixed(2);
    return `rgba(var(--ev-rgb), ${alpha})`;
  }

  /**
   * 96-group voltage heatmap on the Charge tab (v2 handoff). Revealed by the
   * "Read 96 cells" toggle (cellGridOpen). A full per-cell probe (a live
   * `cellVoltages` array or the persisted probe snapshot) tints every group by its
   * deviation off the pack mean; before that it seeds only the live lowest/highest
   * groups the car reports and leaves the rest as faint "awaiting probe"
   * placeholders. Memoized so we don't rebuild 96 nodes every sample.
   */
  function renderCellGrid() {
    const grid = el("cellGrid");
    if (!grid) return;
    // The heatmap section (grid + min/max/Δ footer) — hidden unless the toggle is
    // open and there's at least one known group.
    const card = el("cellGridCard");
    const t = state.telemetry || {};
    const rawCells = Array.isArray(t.cellVoltages) ? (t.cellVoltages as unknown[]) : [];
    // Positional slots (null = group didn't answer): live sample first, then the
    // persisted probe snapshot so the map survives disconnects and app restarts.
    let slots: Array<number | null> | null = null;
    let fromProbeAtMs = 0;
    if (rawCells.length) {
      const live: Array<number | null> = new Array(CELL_GRID_COUNT).fill(null);
      for (let i = 0; i < Math.min(rawCells.length, CELL_GRID_COUNT); i += 1) {
        const c = rawCells[i];
        const v = typeof c === "object" && c !== null ? Number((c as PayloadRecord).voltage) : Number(c);
        if (Number.isFinite(v)) live[i] = v;
      }
      slots = live;
    } else if (probeCellSlots) {
      slots = probeCellSlots;
      fromProbeAtMs = probeCellCapturedAtMs;
    }
    const minCell = Number(t.minCellNumber);
    const maxCell = Number(t.maxCellNumber);
    const minV = Number(t.minCellVoltage);
    const maxV = Number(t.maxCellVoltage);
    if (!slots) {
      // No full read — seed the two groups the car reports live so the heatmap
      // isn't empty while the probe runs.
      const partial: Array<number | null> = new Array(CELL_GRID_COUNT).fill(null);
      if (Number.isFinite(minCell) && minCell >= 1 && minCell <= CELL_GRID_COUNT && Number.isFinite(minV)) {
        partial[minCell - 1] = minV;
      }
      if (Number.isFinite(maxCell) && maxCell >= 1 && maxCell <= CELL_GRID_COUNT && Number.isFinite(maxV)) {
        partial[maxCell - 1] = maxV;
      }
      slots = partial.some((v) => v !== null) ? partial : null;
    }
    const known: number[] = slots ? (slots.filter((v) => v !== null) as number[]) : [];
    // Section visible only while the toggle is open AND there's something to show.
    if (!cellGridOpen || known.length === 0 || !slots) {
      if (card) card.hidden = true;
      grid.hidden = true;
      grid.replaceChildren();
      lastCellGridSig = `hidden:${cellGridOpen}:${known.length}`;
      return;
    }
    const full = known.length >= CELL_GRID_FULL_MIN;
    const sig = `open:${slots.map((v) => (v === null ? "" : v.toFixed(3))).join(",")}:${fromProbeAtMs}`;
    if (sig === lastCellGridSig) return;
    // First transition into a shown grid gets a left-to-right reveal sweep (each
    // box staggers ~8ms apart, reading as the probe "filling in"); later refreshes
    // of an already-open grid repaint in place so live updates don't flicker.
    const revealSweep = !lastCellGridSig.startsWith("open:");
    lastCellGridSig = sig;

    const lo = Math.min(...known);
    const hi = Math.max(...known);
    const mean = known.reduce((a, b) => a + b, 0) / known.length;
    // With only a couple of groups (pre-probe) deviation coloring is noise — paint
    // the known groups a nominal green until a full read gives a real mean.
    const heat = known.length >= 4;
    const frag = document.createDocumentFragment();
    for (let i = 0; i < CELL_GRID_COUNT; i += 1) {
      const v = slots[i];
      const box = document.createElement("span");
      box.className = "cell-grid-box";
      if (v == null) {
        box.title = `Group ${i + 1}: awaiting probe`;
      } else {
        box.style.background = heat ? cellHeatColor(v, mean) : "rgba(var(--ev-rgb), 0.6)";
        box.title = `Group ${i + 1}: ${v.toFixed(3)} V`;
      }
      if (revealSweep) {
        box.classList.add("cell-reveal");
        box.style.animationDelay = `${i * 8}ms`;
      }
      frag.appendChild(box);
    }
    if (card) card.hidden = false;
    grid.hidden = false;
    grid.replaceChildren(frag);
    // Footer: min / max / Δ (green when tight, amber when wide) / note.
    setText("cellGridMin", `${lo.toFixed(3)} V`);
    setText("cellGridMax", `${hi.toFixed(3)} V`);
    const mv = Math.round((hi - lo) * 1000);
    setText("cellGridDelta", `${mv} mV`);
    const deltaEl = el("cellGridDelta");
    if (deltaEl) deltaEl.style.color = mv > 14 ? "var(--warn)" : "var(--ev)";
    setText(
      "cellGridNote",
      full
        ? t.minCellNumber != null && Number.isFinite(minCell)
          ? `group ${minCell} runs low`
          : "full read"
        : `${known.length} of ${CELL_GRID_COUNT} read`,
    );
  }

  // Reveal/hide the 96-group heatmap. Called from the "Read 96 cells" / "Hide
  // cells" toggle (actions.ts). Forces a grid re-render since the memo key depends
  // on the open state.
  export function setCellGridOpen(open: boolean) {
    cellGridOpen = open;
    const toggle = el("cellToggleBtn");
    if (toggle) {
      toggle.textContent = open ? "Hide cells" : "Read 96 cells";
      toggle.setAttribute("aria-expanded", open ? "true" : "false");
    }
    lastCellGridSig = "";
    renderCellGrid();
  }

  export function isCellGridOpen(): boolean {
    return cellGridOpen;
  }

  // True when a full per-cell read is already loaded (live array or stored probe),
  // so the toggle can skip firing a fresh probe.
  export function cellGridHasFull(): boolean {
    const t = state.telemetry || {};
    const rawCells = Array.isArray(t.cellVoltages) ? (t.cellVoltages as unknown[]) : [];
    let liveKnown = 0;
    for (let i = 0; i < Math.min(rawCells.length, CELL_GRID_COUNT); i += 1) {
      const c = rawCells[i];
      const v = typeof c === "object" && c !== null ? Number((c as PayloadRecord).voltage) : Number(c);
      if (Number.isFinite(v)) liveKnown += 1;
    }
    const probeKnown = probeCellSlots ? probeCellSlots.filter((v) => v !== null).length : 0;
    return Math.max(liveKnown, probeKnown) >= CELL_GRID_FULL_MIN;
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
      hasGps ? `${locationRows || "live"} location sample${locationRows === 1 ? "" : "s"} available` : (gps.state === "blocked" ? "Location permission blocked" : "Waiting for location samples"),
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
    setText("validationSummary", okCount ? `${okCount}/${total} ok` : "waiting");
    // Drive's slim footer mirrors the same summary ("N/5 checks ok") and tints
    // its dot green only once most checks pass — same threshold the design uses.
    setText("sessionFooterHealth", okCount ? `${okCount}/${total} checks ok` : "waiting");
    const footerDot = el("sessionFooterDot");
    if (footerDot) footerDot.dataset.tone = okCount >= Math.max(1, total - 1) ? "ok" : "warn";
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
    setCurrentSessionRoute: applyCurrentSessionRoutePayload,
    scheduleRender,
    flushRender,
    applyStaleIndicator,
    updateLiveUi,
    updateDiagnostics,
    applyCellSnapshot,
    setCellGridOpen,
    isCellGridOpen,
    cellGridHasFull,
    renderLiveCharge,
    updateValidationUi,
    setValidationRow,
    formatAge,
    summarizePidLine,
    formatDuration,
    showToast
  });

export {};
