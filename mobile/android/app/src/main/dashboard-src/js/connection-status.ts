// Last-connected badge + adapter health pill.
//
// Reads recent session summaries via VoltTrackerAndroid.getRecentSessions(n)
// on dashboard load and on every status broadcast, then renders into the
// topbar. The low-voltage hint also lives here (the hint element is in
// connection-tools.html but the status payload is the same).
import { asDataState, setDataState, setDataTone } from "./dataset-state";
import type { DataStateValue } from "./dataset-state";
import { resolveDeviceLocale, t } from "./i18n";
import { units } from "./prefs";
import { formatDuration, formatWhen, gpsText } from "./telemetry";

// Pick up the device locale once at module load so the demo-migrated copy below
// resolves to a registered translation when one matches navigator.language. The
// rest of the dashboard copy is still inlined English pending the full migration.
resolveDeviceLocale();

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
    } catch (_err) {
      result = [];
    }
  }
  cachedSessions = result;
  cachedSessionsN = n;
  cachedSessionsAtMs = now;
  return result;
}

// One relative-time vocabulary app-wide ("12m ago" / "2d ago"): defer to
// telemetry's formatWhen; only the missing-value token differs (tile "--").
function formatRelative(ms: number | undefined) {
  if (!ms) return "--";
  return formatWhen(ms);
}

// A demo run is not a real adapter connection. New demo sessions are no longer written to the
// summary store, but defend against any legacy "Demo stream" rows so the last-connected line
// never shows the demo as if it were the last adapter (it would double up with the live
// "Demo preview" chip).
function isDemoSession(s: RecentSession) {
  return /^demo/i.test(String((s && s.adapter) || ""));
}

// Most recent REAL (non-demo) session. Unifies the magic count 8 + the predicate used by the
// last-connected badge and the status popover; the sessions cache makes the repeated calls cheap.
function lastRealSession(): RecentSession | undefined {
  return parseSessions(8).find((s) => !isDemoSession(s));
}

// Render the "last connected" badge from the most recent REAL session.
function renderLastConnected() {
  const badge = el("lastConnectedBadge");
  const label = el("lastConnectedLabel");
  const at = el("lastConnectedAt");
  if (!badge || !label || !at) return;
  // While Demo / Testing runs, its header line (#topDemoInfo, drive.ts) takes
  // this slot — the last real adapter is stale context during a demo. The
  // stop-demo status broadcast re-runs this render and restores the line.
  if (dashboardState().demoActive) {
    badge.hidden = true;
    return;
  }
  const s = lastRealSession();
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
      " V). The OBD port may sleep — start the car before the next probe.";
    hint.hidden = false;
  } else if (v <= 12.7) {
    setDataTone(hint, "warn");
    hint.textContent = "Battery voltage is borderline (" + v.toFixed(2) + " V).";
    hint.hidden = false;
  } else {
    hint.hidden = true;
  }
}

// ---- Diagnostics recovery-first panel --------------------------------------
//
// The Diagnostics tab used to open straight into a 43-row signal console even
// when the adapter was blocked — the actual blocker only flashed as a toast. This
// renders a plain-language "what's wrong / what to do next / how ready / one
// action" summary at the top of Diagnostics from the SAME status model the
// topbar pill reads, so headline and badge can never disagree. The engineering
// consoles below stay put (progressive disclosure).

const RECOVERY_ACTIVE_STATES = ["connected", "scanning", "scan-complete"];

type RecoveryView = {
  tone: "idle" | "ok" | "warn" | "bad" | "live" | "demo";
  state: DataStateValue;
  kicker: string;
  title: string;
  next: string;
  pill: string;
  bt: string;
  source: string;
  actionLabel: string;
  actionTarget: "settings" | "drive";
};

// Imported lazily-typed: DataStateValue lives in dataset-state; re-declare the
// import alongside the existing setDataState import below isn't needed since the
// values are checked by setDataState at the call site.
function deriveRecoveryView(): RecoveryView {
  const state = dashboardState();
  const app: VoltAppState = state.appState || {};
  const adapter: VoltAdapterState = app.adapter || {};
  const session: VoltSessionState = app.session || {};
  const permissions: NonNullable<VoltAppState["permissions"]> = app.permissions || {};
  const status: VoltStatus = state.status || {};
  const lastDevice: VoltDevice = state.lastDevice || {};
  const telemetry: VoltTelemetry = state.telemetry || {};

  const demo = Boolean(state.demoActive);
  const stateName = String(status.state || session.state || "idle").toLowerCase();
  const samples = Number(session.sampleCount || telemetry.sampleCount || 0);
  const connecting = ["connecting", "initializing"].includes(stateName);
  const connected =
    adapter.connected === true || RECOVERY_ACTIVE_STATES.includes(stateName);
  const remembered = Boolean(adapter.remembered) || Boolean(lastDevice.address);
  const adapterName = demo
    ? "Demo telemetry"
    : String(adapter.name || status.lastName || lastDevice.name || "") || "None selected";
  const btPermNeeded = permissions.bluetoothPermission === false;
  const btOff = permissions.bluetoothEnabled === false;
  const btReady = permissions.bluetooth === true || status.bluetoothReady === true;
  const bt = btPermNeeded
    ? "Permission needed"
    : btOff
      ? "Off"
      : btReady
        ? "Ready"
        : demo
          ? "--"
          : "Not ready";
  const voltage = typeof status.lastVoltage === "number" ? status.lastVoltage : null;
  const blockedState = stateName === "blocked" || stateName === "error" || stateName === "failed";
  // One source label threaded through every branch so the panel never claims
  // "Real car" while a demo is running (e.g. the demo-fault scenario).
  const source = demo
    ? "Demo"
    : connected || connecting || remembered || blockedState || btPermNeeded || btOff || voltage != null
      ? "Real car"
      : "None";

  // An actively-failed status outranks demo so the demo-fault scenario (and a
  // real adapter failure) both surface the blocker, not the demo banner.
  if (blockedState && !btPermNeeded && !btOff) {
    return {
      tone: "bad", state: "blocked", kicker: "Needs attention",
      title: "Adapter isn't responding",
      next: String(status.detail || "Check the OBD dongle is seated and the car is awake, then try again."),
      pill: "blocked", bt, source, actionLabel: "Open connection settings", actionTarget: "settings"
    };
  }
  if (demo) {
    return {
      tone: "demo", state: "demo", kicker: "Demo / Testing",
      title: "Demo / Testing is running",
      next: "Sample data is isolated from your real OBD history. Stop it any time to return to live data.",
      pill: "demo", bt, source, actionLabel: "View live Drive", actionTarget: "drive"
    };
  }
  if (btPermNeeded) {
    return {
      tone: "bad", state: "blocked", kicker: "Blocked",
      title: "Bluetooth permission needed",
      next: "Grant Bluetooth (and location) access, then connect your adapter.",
      pill: "blocked", bt, source, actionLabel: "Fix in Settings", actionTarget: "settings"
    };
  }
  if (btOff) {
    return {
      tone: "bad", state: "blocked", kicker: "Blocked",
      title: "Bluetooth is off",
      next: "Turn Bluetooth on in Android settings, then reconnect your adapter.",
      pill: "blocked", bt, source, actionLabel: "Open connection settings", actionTarget: "settings"
    };
  }
  if (connecting) {
    return {
      tone: "live", state: "connecting", kicker: "Working",
      title: "Connecting to adapter…",
      next: "Handshaking with the OBD adapter. This usually takes a few seconds.",
      pill: "connecting", bt, source, actionLabel: "Open connection settings", actionTarget: "settings"
    };
  }
  if (connected && samples > 0) {
    return {
      tone: "ok", state: "live", kicker: "Healthy",
      title: "Live data is flowing",
      next: `${samples.toLocaleString()} samples logged this session. The consoles below show live signal detail.`,
      pill: "live", bt, source, actionLabel: "View live Drive", actionTarget: "drive"
    };
  }
  if (connected) {
    return {
      tone: "live", state: "waiting", kicker: "Connected",
      title: "Connected — waiting for data",
      next: "Adapter is linked. Start driving, or run a scan, to see live signals.",
      pill: "waiting", bt, source, actionLabel: "View live Drive", actionTarget: "drive"
    };
  }
  if (voltage != null && voltage <= 12.7) {
    return {
      tone: "warn", state: "idle", kicker: "Attention",
      title: "Battery voltage looks low",
      next: `Last reading was ${voltage.toFixed(2)} V — the OBD port may sleep. Start the car before the next probe.`,
      pill: "low V", bt, source, actionLabel: "Open connection settings", actionTarget: "settings"
    };
  }
  if (remembered) {
    return {
      tone: "ok", state: "ready", kicker: "Ready",
      title: "Ready to reconnect",
      next: `${adapterName} is remembered. Reconnect when you want live OBD logging.`,
      pill: "ready", bt, source, actionLabel: "Reconnect in Settings", actionTarget: "settings"
    };
  }
  return {
    tone: "idle", state: "idle", kicker: "Get started",
    title: "No adapter connected yet",
    next: "Connect an OBD adapter in Settings to start live logging — or run Demo / Testing below to explore the app with sample data.",
    pill: "idle", bt, source, actionLabel: "Open connection settings", actionTarget: "settings"
  };
}

// The panel is a polite live region (role="status"), so a blind textContent
// rewrite on each call re-announces it to screen readers even when nothing
// changed. Skip the whole render when the derived view is identical, and diff
// each node before writing so only genuinely-changed text/attrs touch the DOM.
let lastRecoverySignature = "";

function renderDiagnosticsRecovery() {
  const panel = el("diagRecovery");
  if (!panel) return;
  const view = deriveRecoveryView();
  const adapterName = adapterNameForRecovery();
  const signature = JSON.stringify({ ...view, adapterName });
  if (signature === lastRecoverySignature) return;
  lastRecoverySignature = signature;

  setDataTone(panel, view.tone);
  const setText = (id: string, value: string) => {
    const node = el(id);
    if (node && node.textContent !== value) node.textContent = value;
  };
  setText("diagRecoveryKicker", view.kicker);
  setText("diagRecoveryTitle", view.title);
  setText("diagRecoveryNext", view.next);
  setText("diagRecoveryAdapter", adapterName);
  setText("diagRecoveryBt", view.bt);
  setText("diagRecoverySource", view.source);
  const pill = el("diagRecoveryPill");
  if (pill) {
    if (pill.textContent !== view.pill) pill.textContent = view.pill;
    if (pill.dataset.state !== view.state) setDataState(pill, view.state);
  }
  const action = el("diagRecoveryAction") as HTMLButtonElement | null;
  if (action) {
    if (action.textContent !== view.actionLabel) action.textContent = view.actionLabel;
    if (action.dataset.navJump !== view.actionTarget) action.dataset.navJump = view.actionTarget;
    if (action.getAttribute("aria-label") !== view.actionLabel) {
      action.setAttribute("aria-label", view.actionLabel);
    }
  }
}

// Adapter display name resolved the same way as the popover row, exposed for the
// recovery panel without duplicating the demo/remembered fallbacks.
function adapterNameForRecovery(): string {
  const state = dashboardState();
  if (state.demoActive) return "Demo telemetry";
  const app: VoltAppState = state.appState || {};
  const adapter: VoltAdapterState = app.adapter || {};
  const status: VoltStatus = state.status || {};
  const lastDevice: VoltDevice = state.lastDevice || {};
  return String(adapter.name || status.lastName || lastDevice.name || "") || "None selected";
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
// Contains Tab within the popover and restores focus to the opening badge on close.
let popoverOpener: HTMLElement | null = null;

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
    ? (samples
        ? t("status.logging.samples", { count: samples.toLocaleString() })
        : t("status.logging.waitingForData"))
    : t("status.logging.notLogging");
  const last = lastRealSession();

  return [
    ["Adapter", address ? `${adapterName} (${address})` : adapterName],
    ["Bluetooth", bluetoothSummary(app.permissions || {}, status)],
    ["Logging", logging],
    ["GPS", gpsText(gps.state)],
    ["Last connected", last ? `${last.adapter || t("status.adapter.fallbackName")} · ${formatRelative(last.endMs || last.startMs)}` : ""]
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
    const last = lastRealSession();
    if (last) {
      const lastMs = Number(last.endMs) - Number(last.startMs);
      if (Number.isFinite(lastMs) && lastMs > 0) {
        return [["Last trip", `${formatDuration(lastMs)} · ${formatRelative(last.endMs)}`]];
      }
    }
    return [["Trip", t("trip.empty.noTripYet")]];
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
  if (!rows.length) rows.push(["Trip", t("trip.waitingForFirstSamples")]);
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
  if (detail) detail.textContent = String(status.detail || t("status.detail.ready"));
  renderRows(el("statusPopoverConnection"), connectionRows(status));
  renderRows(el("statusPopoverTrip"), tripRows(status));
}

// Every control that toggles the status popover: the state pill, the
// last-connected line, and the Demo / Testing line. Shared by the
// aria-expanded sync, the outside-click check, and the click bindings so a
// future opener only needs adding here.
const STATUS_POPOVER_OPENER_IDS = ["stateBadge", "lastConnectedBadge", "topDemoInfo"];

function setBadgeExpanded(expanded: boolean) {
  STATUS_POPOVER_OPENER_IDS.forEach((id) => {
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
  // Restore focus to the badge that opened the popover (it re-renders on every
  // telemetry sample, so a full focus-trap is the wrong tool here — restoring the
  // opener is the real a11y win; Tab containment is intentionally left light).
  if (popoverOpener && typeof popoverOpener.focus === "function" && document.contains(popoverOpener)) {
    popoverOpener.focus();
  }
  popoverOpener = null;
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
  // Remember the control that opened the popover so close() can return focus to it.
  popoverOpener = (document.activeElement as HTMLElement | null) ?? el("stateBadge");
  // Light-dismiss: tap anywhere outside (the openers handle their own toggle) or
  // Escape. The Android Back gesture goes through VD.closeStatusPopover.
  document.addEventListener(
    "click",
    (event) => {
      const target = event.target as Node | null;
      if (!target || popover.contains(target)) return;
      const openers = STATUS_POPOVER_OPENER_IDS.map((id) => el(id));
      if (openers.some((opener) => opener && opener.contains(target))) return;
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
  STATUS_POPOVER_OPENER_IDS.forEach((id) => {
    const opener = el(id);
    if (opener) opener.addEventListener("click", toggleStatusPopover);
  });
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
  renderDiagnosticsRecovery();
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
      } catch (_err) {
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
        // The recovery panel changes meaningfully only on status broadcasts
        // (handled in noteStatus). Re-rendering it on EVERY telemetry sample put
        // state-derivation + DOM lookups on the hot enqueue path and blew the
        // high-rate-burst budget (startup-budget.test.js). Only refresh per-sample
        // when the user is actually looking at Diagnostics — the one case where
        // the live sample count / "waiting → flowing" flip needs to be live.
        if (document.body.dataset.activeView === "diagnostics") {
          renderDiagnosticsRecovery();
        }
      } catch (_err) {
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

// Initial render on load. renderLastConnected() makes a synchronous getRecentSessions(n)
// bridge call into SQLite for the "last connected" badge — not needed for first paint, so
// defer it off the bootstrap critical path (it fills in a frame later), mirroring the
// loadDeferredPanels idle-defer pattern in actions.ts.
if (typeof window.requestIdleCallback === "function") {
  window.requestIdleCallback(() => renderLastConnected(), { timeout: 1500 });
} else {
  window.setTimeout(renderLastConnected, 0);
}
installStatusObserver();
installTelemetryObserver();
bindStatusPopover();
// Initial paint so Diagnostics opens with a recovery summary even before the
// first status broadcast (mirrors renderLastConnected's idle-defer).
renderDiagnosticsRecovery();

// Exposed for the Android Back handler (core.ts) and tests.
VD.toggleStatusPopover = toggleStatusPopover;
VD.closeStatusPopover = closeStatusPopover;

export {};
