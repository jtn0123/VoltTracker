import { CURRENT_DRIVE_LABEL, LIVE_ROUTE_ID } from "./map-route-utils";
import type { MapRoute, MapRouteSession } from "./map-route-utils";

type MapSessionListFormatters = {
  selectedSessionId?: unknown;
  formatChipDate: (value: unknown) => string;
  formatDistance: (meters: number) => string;
  formatWhen: (value: unknown) => string;
};

export function renderMapSessionListInto(
  list: HTMLElement,
  routes: MapRoute[],
  formatters: MapSessionListFormatters,
) {
  if (!routes.length) {
    const p = document.createElement("p");
    p.className = "status-copy";
    p.textContent =
      "No route-bearing SQLite sessions yet. Start a real logged drive with GPS permission and the route will render here.";
    list.replaceChildren(p);
    return;
  }
  list.replaceChildren(...routes.map((route) => buildMapSessionEntry(route, formatters)));
}

export function sessionForRoute(route: MapRoute): MapRouteSession {
  // VoltRoute.session is the open `{ id?; [k]: unknown }` payload; narrow it to
  // the named fields the map UI reads. Field reads stay defensively coerced at
  // each use, so the narrowing is presentational only.
  return (route && route.session ? route.session : {}) as MapRouteSession;
}

export function routeIsLive(route: MapRoute) {
  return Boolean(route && (
    route.isLive ||
    String((route.session || {}).id || "") === LIVE_ROUTE_ID ||
    String((route.session || {}).status || "").toLowerCase() === "live"
  ));
}

function buildMapSessionEntry(
  route: MapRoute,
  formatters: MapSessionListFormatters,
) {
  const session = sessionForRoute(route);
  const live = routeIsLive(route);
  const entry = document.createElement("div");
  entry.className = "history-entry";
  entry.append(buildMapSessionRow(route, session, live, formatters));
  // Live drives have no finalized route key yet, so only stored trips get the GPX/CSV
  // export + rename affordances.
  if (!live) {
    const exportRow = buildExportActions(String(session.id || ""), labelOf(session));
    if (exportRow) {
      entry.append(exportRow);
    }
  }
  return entry;
}

// The user's trip label, defensively coerced (the native session field is open/untyped).
function labelOf(session: MapRouteSession): string {
  const label = session && session.label;
  return typeof label === "string" ? label.trim() : "";
}

function buildMapSessionRow(
  route: MapRoute,
  session: MapRouteSession,
  live: boolean,
  formatters: MapSessionListFormatters,
) {
  const active = String(session.id || "") === String(formatters.selectedSessionId || "");
  const button = document.createElement("button");
  button.type = "button";
  button.className = "history-row" + (active ? " is-active" : "");
  button.dataset.mapSession = String(session.id || "");
  if (!live) {
    button.title = "Press and hold to mark this route as not a trip.";
  }

  const center = document.createElement("span");
  const strong = document.createElement("strong");
  // A user label, when set, becomes the primary title; the mode/adapter line drops to a
  // subtitle so a renamed trip reads as its name first. textContent only — never innerHTML —
  // so a hostile label can't inject markup (DOM XSS-safe per the dom-sinks contract).
  const label = live ? "" : labelOf(session);
  const fallbackTitle = live
    ? `${session.adapterName || CURRENT_DRIVE_LABEL} · current`
    : `${session.mode || "session"} · ${session.adapterName || "OBD adapter"}`;
  strong.textContent = label || fallbackTitle;
  center.append(strong);
  if (label) {
    const sub = document.createElement("small");
    sub.className = "history-sub";
    sub.textContent = fallbackTitle;
    center.append(sub);
  }

  const small = document.createElement("small");
  const distance = formatters.formatDistance(Number(route.distanceMeters || 0));
  const points = Number(route.pointCount || 0);
  small.textContent = live
    ? `${formatters.formatChipDate(session.startedAtMs)} · ${distance} · ${points} pts`
    : `${formatters.formatWhen(session.startedAtMs)} · ${distance} · ${points} pts`;
  center.append(small);

  const right = document.createElement("b");
  right.textContent = live ? "live" : session.status || "stored";
  button.append(center, right);
  return button;
}

// The "Rename / Export GPX / Export CSV" buttons under a stored route row. Each carries the route
// key in a data attribute; actions.ts delegates the click to bridge.setTripLabel / exportTrip*.
// Built with createElement/textContent only — never innerHTML — so a hostile adapter name, route
// key, or label can't inject markup (DOM XSS-safe per the project's dom-sinks contract).
function buildExportActions(routeKey: string, currentLabel = "") {
  const clean = String(routeKey || "").trim();
  if (!clean) return null;
  const row = document.createElement("div");
  row.className = "history-export";
  row.append(
    buildRenameButton(clean, currentLabel),
    buildExportButton(clean, "gpx", "Export GPX"),
    buildExportButton(clean, "csv", "Export CSV"),
  );
  return row;
}

function buildRenameButton(routeKey: string, currentLabel: string) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = "history-export-btn";
  button.dataset.tripRename = routeKey;
  // Carry the existing label so the rename prompt can pre-fill it. textContent/dataset only.
  button.dataset.tripRenameLabel = currentLabel || "";
  button.title = currentLabel ? "Rename this drive." : "Name this drive.";
  button.textContent = currentLabel ? "Rename" : "Name";
  return button;
}

function buildExportButton(routeKey: string, format: "gpx" | "csv", label: string) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = "history-export-btn";
  button.dataset.tripExport = format;
  button.dataset.tripExportKey = routeKey;
  button.title = `Share this drive as a ${format.toUpperCase()} file.`;
  button.textContent = label;
  return button;
}
