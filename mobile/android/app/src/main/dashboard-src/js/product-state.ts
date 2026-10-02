// product-state.ts — the user-facing state contract for the whole dashboard.
//
// Native status values describe implementation details (idle, connected,
// scanning, demo, blocked). ProductState answers the question an owner actually
// asks: "Is the app ready, recording, or asking me to do something?" Keep that
// vocabulary centralized so the top bar, Diagnostics, Drive, and empty states
// cannot invent competing labels.
import { setDataState } from "./dataset-state";
import type { DataStateValue } from "./dataset-state";

export type ProductStateKey =
  | "ready-to-connect"
  | "connecting"
  | "ready-to-drive"
  | "recording"
  | "needs-attention"
  | "preview";

export type ProductStateView = {
  key: ProductStateKey;
  label: string;
  detail: string;
  dataState: DataStateValue;
  source: "real" | "demo";
  freshness: "idle" | "waiting" | "fresh" | "stale";
};

export type DataAvailability = "loading" | "empty" | "ready" | "stale" | "unsupported";

const ACTIVE_STATES = new Set(["connected", "scanning", "scan-complete"]);
const BLOCKED_STATES = new Set(["blocked", "error", "failed"]);
const CONNECTING_STATES = new Set(["connecting", "initializing"]);
const STALE_AFTER_MS = 5000;

function sampleTimestamp(state: DashboardState): number {
  const payloadAt = Number((state.telemetry || {}).updatedAt || 0);
  if (Number.isFinite(payloadAt) && payloadAt > 0) return payloadAt;
  const receivedAt = Number(state.lastSampleAt || 0);
  return Number.isFinite(receivedAt) && receivedAt > 0 ? receivedAt : 0;
}

function sampleCount(state: DashboardState): number {
  const session = (state.appState && state.appState.session) || {};
  return Number(session.sampleCount || (state.telemetry || {}).sampleCount || 0);
}

export function deriveProductState(state: DashboardState, now = Date.now()): ProductStateView {
  const app = state.appState || {};
  const status = state.status || {};
  const session = app.session || {};
  const adapter = app.adapter || {};
  const permissions = app.permissions || {};
  const raw = String(status.state || session.state || "idle").toLowerCase();
  const samples = sampleCount(state);
  const sampleAt = sampleTimestamp(state);
  const ageMs = sampleAt > 0 ? Math.max(0, now - sampleAt) : 0;
  const blocked =
    BLOCKED_STATES.has(raw) ||
    permissions.bluetoothPermission === false ||
    permissions.bluetoothEnabled === false;
  const connecting = CONNECTING_STATES.has(raw);
  const connected = adapter.connected === true || ACTIVE_STATES.has(raw);

  // Preview is a separate source, not a degraded connection. Never append
  // "waiting" or "stale" to it: that was the most visible contradictory label.
  if (state.demoActive) {
    return {
      key: "preview",
      label: "Preview mode",
      detail: "Sample data only — your real driving history stays untouched.",
      dataState: "demo",
      source: "demo",
      freshness: samples > 0 ? "fresh" : "waiting",
    };
  }

  if (blocked) {
    const detail = permissions.bluetoothPermission === false
      ? "Bluetooth permission is needed before VoltTracker can connect."
      : permissions.bluetoothEnabled === false
        ? "Turn on Bluetooth, then reconnect your adapter."
        : String(status.detail || "Check the adapter and try connecting again.");
    return {
      key: "needs-attention",
      label: "Needs attention",
      detail,
      dataState: "blocked",
      source: "real",
      freshness: sampleAt > 0 ? "stale" : "idle",
    };
  }

  if (connecting) {
    return {
      key: "connecting",
      label: "Connecting",
      detail: "Checking the adapter and preparing live logging.",
      dataState: "connecting",
      source: "real",
      freshness: "waiting",
    };
  }

  if (connected && samples > 0 && sampleAt > 0 && ageMs > STALE_AFTER_MS) {
    return {
      key: "needs-attention",
      label: "Live data paused",
      detail: "The adapter is connected, but new car readings have stopped.",
      dataState: "blocked",
      source: "real",
      freshness: "stale",
    };
  }

  if (connected && samples > 0) {
    return {
      key: "recording",
      label: "Recording drive",
      detail: "Live car data is being saved on this phone.",
      dataState: "live",
      source: "real",
      freshness: "fresh",
    };
  }

  if (connected) {
    return {
      key: "ready-to-drive",
      label: "Ready to drive",
      detail: "Adapter connected — waiting for the first car reading.",
      dataState: "ready",
      source: "real",
      freshness: "waiting",
    };
  }

  return {
    key: "ready-to-connect",
    label: "Ready to connect",
    detail: "Connect your OBD adapter when you want to start logging.",
    dataState: "idle",
    source: "real",
    freshness: "idle",
  };
}

export function renderProductStatusBadge(
  state: DashboardState,
  badge: HTMLElement | null,
  label: HTMLElement | null,
  now = Date.now(),
): ProductStateView {
  const view = deriveProductState(state, now);
  if (badge) {
    setDataState(badge, view.dataState);
    badge.dataset.productState = view.key;
    badge.dataset.freshness = view.freshness;
    badge.setAttribute("aria-label", `${view.label}. ${view.detail}`);
  }
  if (label && label.textContent !== view.label) label.textContent = view.label;
  return view;
}

export function deriveDataAvailability(options: {
  loading?: boolean;
  supported?: boolean;
  hasData?: boolean;
  stale?: boolean;
}): DataAvailability {
  if (options.loading) return "loading";
  if (options.supported === false) return "unsupported";
  if (options.stale && options.hasData) return "stale";
  return options.hasData ? "ready" : "empty";
}

export function applyDataAvailability(
  target: HTMLElement | null,
  availability: DataAvailability,
): DataAvailability {
  if (target) target.dataset.availability = availability;
  return availability;
}
