// Last-connected badge + adapter health pill.
//
// Reads recent session summaries via VoltTrackerAndroid.getRecentSessions(n)
// on dashboard load and on every status broadcast, then renders into the
// topbar. The low-voltage hint also lives here (the hint element is in
// connection-tools.html but the status payload is the same).
import { asDataState, setDataState, setDataTone } from "./dataset-state";
import { units } from "./prefs";
import { formatDuration } from "./telemetry";

type RecentSession = {
  adapter?: string;
  startMs?: number;
  endMs?: number;
  outcome?: string;
};

type LowVoltageStatus = {
  lastVoltage?: number;
};

type StatusHandler = (payload: unknown) => unknown;

const VD = (window.VoltDashboard = window.VoltDashboard || ({} as VoltDashboard));
const bridge = window.VoltTrackerAndroid || null;
const el = (id: string) => document.getElementById(id);

function isRecentSession(value: unknown): value is RecentSession {
  return value != null && typeof value === "object";
}

// Recent-session summaries change only when a session ends — i.e. on a status broadcast. But the
// status popover re-renders on every telemetry sample while open (installTelemetryObserver), and
// connectionRows/tripRows each call parseSessions(8) → a synchronous SQLite read + JSON.parse. That
// was up to two blocking bridge round-trips per ~1 Hz sample purely to repaint a "Last connected"
// line. Cache the parsed result and refetch only after a status broadcast (invalidateSessionsCache,
// called from noteStatus) or a short TTL backstop.
let cachedSessions: RecentSession[] | null = null;
let cachedSessionsN = 0;
let cachedSessionsAtMs = 0;
const SESSIONS_CACHE_TTL_MS = 4000;

function invalidateSessionsCache() {
  cachedSessions = null;
}

function parseSessions(n: number): RecentSession[] {
  const now = Date.now();
  if (cachedSessions && cachedSessionsN === n && now - cachedSessionsAtMs < SESSIONS_CACHE_TTL_MS) {
    return cachedSessions;
  }
  let result: RecentSession[] = [];
  if (bridge && typeof bridge.getRecentSessions === "function") {
    try {
      const raw = bridge.getRecentSessions(n);
      const arr: unknown = JSON.parse(raw || "[]");
      result = Array.isArray(arr) ? arr.filter(isRecentSession) : [];
    } catch (ignored) {
      result = [];
    }
  }
  cachedSessions = result;
  cachedSessionsN = n;
  cachedSessionsAtMs = now;
  return result;
}

function formatRelative(ms: number | undefined) {
  if (!ms) return "--";
  const now = Date.now();
  const delta = now - ms;
  if (delta < 60_000) return "just now";
  if (delta < 3_600_000) return Math.floor(delta / 60_000) + " min ago";
  if (delta < 86_400_000) return Math.floor(delta / 3_600_000) + "h ago";
  const days = Math.floor(delta / 86_400_000);
  if (days === 1) return "yesterday";
  if (days < 7) return days + " days ago";
  return new Date(ms).toLocaleDateString();
}

// A demo run is not a real adapter connection. New demo sessions are no longer written to the
// summary store, but defend against any legacy "Demo stream" rows so the last-connected line
// never shows the demo as if it were the last adapter (it would double up with the live
// "Demo preview" chip).
function isDemoSession(s: RecentSession) {
  return /^demo/i.test(String((s && s.adapter) || ""));
}

// Render the "last connected" badge from the most recent REAL session.
function renderLastConnected() {
  const badge = el("lastConnectedBadge");
  const label = el("lastConnectedLabel");
  const at = el("lastConnectedAt");
  if (!badge || !label || !at) return;
  const s = parseSessions(8).find((session) => !isDemoSession(session));
  if (!s) {
    badge.hidden = true;
    return;
  }
  label.textContent = s.adapter || "OBD adapter";
  at.textContent = formatRelative(s.endMs || s.startMs);
  setDataState(badge, asDataState(s.outcome || "unknown"));
  badge.hidden = false;
}

// Low-voltage hint keyed off the lastVoltage field on status payloads.
function renderLowVoltageHint(status: LowVoltageStatus | null | undefined) {
  const hint = el("lowVoltageHint");
  if (!hint || !status) return;
  const v = typeof status.lastVoltage === "number" ? status.lastVoltage : null;
  if (v == null) {
    hint.hidden = true;
    return;
  }
  // Thresholds mirror VehicleStateClassifier.LOW_BATTERY_VOLTS (= 12.7) so the dashboard
  // hint stays aligned with the backend's "parked / low_voltage" classification - without
  // this, a 12.5-12.7 V reading would silently leave the hint hidden while the backend
  // already considers the battery low. The "bad" floor at 12.2 V keeps a tighter band for
  // the more urgent copy.
  if (v < 12.2) {
    setDataTone(hint, "bad");
    hint.textContent =
      "Battery voltage looks low (" +
      v.toFixed(2) +
      " V). The OBD port may sleep - start the car before the next probe.";
    hint.hidden = false;
  } else if (v < 12.7) {
    setDataTone(hint, "warn");
    hint.textContent = "Battery voltage is borderline (" + v.toFixed(2) + " V).";
    hint.hidden = false;
  } else {
    hint.hidden = true;
  }
}

// ---- Status popover (opened from the topbar state badge) -------------------
//
// Tapping the state badge answers "what is going on?" in place: the live
// connection picture (adapter, Bluetooth readiness, logging, GPS, last
// connected) plus the current trip while logging — or the last trip when idle.
// connection-status owns it because the data sources are the same status
// broadcasts and session summaries this module already reads.

type StatusRow = [string, string];

const ACTIVE_TRIP_STATES = ["connected", "connecting", "initializing", "scanning", "scan-complete", "demo"];

let popoverDismiss: AbortController | null = null;

// The popover reads the shared state bag through its real DashboardState type
// (and the VoltAppState sub-blocks declared in dashboard-globals.d.ts) instead
// of recasting everything to Record<string, unknown>, so field access below is
// compiler-checked against the bridge payload shapes.
function dashboardState(): DashboardState {
  return VD.state;
}

function statusPopoverEl() {
  return el("statusPopover");
}

function renderRows(target: HTMLElement | null, rows: StatusRow[]) {
  if (!target) return;
  const nodes: HTMLElement[] = [];
  rows.forEach(([label, value]) => {
    if (!value) return;
    const dt = document.createElement("dt");
    dt.textContent = label;
    const dd = document.createElement("dd");
    dd.textContent = value;
    nodes.push(dt, dd);
  });
  target.replaceChildren(...nodes);
}

function bluetoothSummary(permissions: NonNullable<VoltAppState["permissions"]>, status: VoltStatus) {
  if (permissions.bluetoothPermission === false) return "Permission needed — tap Connect to grant";
  if (permissions.bluetoothEnabled === false) return "Off — turn it on in Android settings";
  if (permissions.bluetooth === true || status.bluetoothReady === true) return "Ready";
  if (permissions.bluetooth === false) return "Not ready";
  return "";
}

function connectionRows(status: VoltStatus): StatusRow[] {
  const state = dashboardState();
  const app: VoltAppState = state.appState || {};
  const adapter: VoltAdapterState = app.adapter || {};
  const session: VoltSessionState = app.session || {};
  const gps: VoltGpsState = app.gps || {};
  const lastDevice: VoltDevice = state.lastDevice || {};
  const demo = Boolean(state.demoActive);

  const adapterName = demo
    ? "Demo telemetry"
    : String(adapter.name || status.lastName || lastDevice.name || "") || "None selected";
  const address = demo ? "" : String(adapter.address || "");
  const samples = Number(session.sampleCount || 0);
  const sessionState = String(status.state || session.state || "idle");
  const logging = ACTIVE_TRIP_STATES.includes(sessionState.toLowerCase())
    ? (samples ? `${samples.toLocaleString()} samples` : "Waiting for data")
    : "Not logging";
  const last = parseSessions(8).find((s) => !isDemoSession(s));

  return [
    ["Adapter", address ? `${adapterName} (${address})` : adapterName],
    ["Bluetooth", bluetoothSummary(app.permissions || {}, status)],
    ["Logging", logging],
    ["GPS", String(gps.state || "")],
    ["Last connected", last ? `${last.adapter || "OBD adapter"} · ${formatRelative(last.endMs || last.startMs)}` : ""]
  ];
}

function tripRows(status: VoltStatus): StatusRow[] {
  const state = dashboardState();
  const app: VoltAppState = state.appState || {};
  const session: VoltSessionState = app.session || {};
  const telemetry: VoltTelemetry = state.telemetry || {};
  const stateName = String(status.state || session.state || "").toLowerCase();
  const active = Boolean(state.demoActive) || ACTIVE_TRIP_STATES.includes(stateName);

  if (!active) {
    const last = parseSessions(8).find((s) => !isDemoSession(s));
    const lastMs = Number(last && last.endMs) - Number(last && last.startMs);
    if (last && Number.isFinite(lastMs) && lastMs > 0) {
      return [["Last trip", `${formatDuration(lastMs)} · ${formatRelative(last.endMs)}`]];
    }
    return [["Trip", "No trip yet — connect to start logging."]];
  }

  const rows: StatusRow[] = [];
  const distanceM = Number(state.sessionDistanceM || 0);
  if (distanceM > 0) rows.push(["Distance", units.distanceText(distanceM / 1000)]);
  const durationMs = Number(telemetry.sessionMs || session.sessionMs || 0);
  if (durationMs > 0) {
    rows.push(["Duration", formatDuration(durationMs)]);
  }
  const startSoc = Number(state.sessionStartSoc);
  const soc = Number(telemetry.soc);
  if (Number.isFinite(soc)) {
    rows.push([
      "Battery",
      Number.isFinite(startSoc) && Math.round(startSoc) !== Math.round(soc)
        ? `${Math.round(startSoc)}% → ${Math.round(soc)}%`
        : `${Math.round(soc)}%`
    ]);
  }
  const samples = Number(session.sampleCount || telemetry.sampleCount || 0);
  if (samples > 0) rows.push(["Samples", samples.toLocaleString()]);
  if (!rows.length) rows.push(["Trip", "Waiting for the first samples..."]);
  return rows;
}

function renderStatusPopover() {
  const popover = statusPopoverEl();
  if (!popover || popover.hidden) return;
  const status: VoltStatus = dashboardState().status || {};
  const stateName = String(status.state || "idle");
  setDataState(el("statusPopoverState"), asDataState(stateName));
  const pillText = el("statusPopoverStateText");
  if (pillText) pillText.textContent = stateName;
  const detail = el("statusPopoverDetail");
  if (detail) detail.textContent = String(status.detail || "Ready.");
  renderRows(el("statusPopoverConnection"), connectionRows(status));
  renderRows(el("statusPopoverTrip"), tripRows(status));
}

function setBadgeExpanded(expanded: boolean) {
  ["stateBadge", "lastConnectedBadge"].forEach((id) => {
    const badge = el(id);
    if (badge) badge.setAttribute("aria-expanded", expanded ? "true" : "false");
  });
}

function closeStatusPopover(): boolean {
  const popover = statusPopoverEl();
  if (!popover || popover.hidden) return false;
  popover.hidden = true;
  setBadgeExpanded(false);
  popoverDismiss?.abort();
  popoverDismiss = null;
  return true;
}

function openStatusPopover() {
  const popover = statusPopoverEl();
  if (!popover) return;
  popover.hidden = false;
  setBadgeExpanded(true);
  renderStatusPopover();
  popoverDismiss?.abort();
  const controller = new AbortController();
  popoverDismiss = controller;
  // Light-dismiss: tap anywhere outside (the openers handle their own toggle)
  // or Escape. The Android Back gesture goes through VD.closeStatusPopover.
  document.addEventListener(
    "click",
    (event) => {
      const target = event.target as Node | null;
      if (!target || popover.contains(target)) return;
      const badge = el("stateBadge");
      const line = el("lastConnectedBadge");
      if ((badge && badge.contains(target)) || (line && line.contains(target))) return;
      closeStatusPopover();
    },
    { signal: controller.signal }
  );
  document.addEventListener(
    "keydown",
    (event) => {
      if (event.key === "Escape") closeStatusPopover();
    },
    { signal: controller.signal }
  );
  if (typeof (popover as HTMLElement).focus === "function") (popover as HTMLElement).focus();
}

function toggleStatusPopover() {
  if (!closeStatusPopover()) openStatusPopover();
}

function bindStatusPopover() {
  const badge = el("stateBadge");
  if (badge) badge.addEventListener("click", toggleStatusPopover);
  const line = el("lastConnectedBadge");
  if (line) line.addEventListener("click", toggleStatusPopover);
}

// Re-render on every status broadcast - session summaries can change when
// a session ends, and lastVoltage updates inline.
function noteStatus(payload: LowVoltageStatus | null | undefined) {
  // A status broadcast is the moment a session can have changed (e.g. one just ended), so drop the
  // cached recent-sessions snapshot before re-rendering the badge/popover from fresh data.
  invalidateSessionsCache();
  renderLastConnected();
  renderLowVoltageHint(payload || {});
  renderStatusPopover();
}

function installStatusObserver() {
  const wrap = (prior: StatusHandler | undefined) =>
    function (payload: unknown) {
      let result;
      if (typeof prior === "function") {
        result = prior(payload);
      }
      try {
        const parsed = VD.parsePayload
          ? VD.parsePayload<LowVoltageStatus>(payload, {})
          : (payload as LowVoltageStatus | null | undefined);
        noteStatus(parsed);
      } catch (ignored) {
        // Observer must never break the underlying setStatus call.
      }
      return result;
  };
  VD.setStatus = wrap(VD.setStatus as StatusHandler | undefined);
  if (window.VoltTrackerNative) {
    window.VoltTrackerNative.setStatus = wrap(window.VoltTrackerNative.setStatus as StatusHandler | undefined);
  }
}

// Trip numbers (duration / SoC / samples) move with telemetry, not status, so
// keep an open popover live by re-rendering on telemetry pushes too.
function installTelemetryObserver() {
  const wrap = (prior: StatusHandler | undefined) =>
    function (payload: unknown) {
      let result;
      if (typeof prior === "function") {
        result = prior(payload);
      }
      try {
        renderStatusPopover();
      } catch (ignored) {
        // Observer must never break the underlying updateTelemetry call.
      }
      return result;
    };
  VD.updateTelemetry = wrap(VD.updateTelemetry as StatusHandler | undefined) as typeof VD.updateTelemetry;
  if (window.VoltTrackerNative) {
    window.VoltTrackerNative.updateTelemetry =
      wrap(window.VoltTrackerNative.updateTelemetry as StatusHandler | undefined) as typeof window.VoltTrackerNative.updateTelemetry;
  }
}

// Initial render on load.
renderLastConnected();
installStatusObserver();
installTelemetryObserver();
bindStatusPopover();

// Exposed for the Android Back handler (core.ts) and tests.
VD.toggleStatusPopover = toggleStatusPopover;
VD.closeStatusPopover = closeStatusPopover;

export {};
