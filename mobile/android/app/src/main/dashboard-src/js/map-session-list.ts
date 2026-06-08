import { LIVE_ROUTE_ID } from "./map-route-utils";
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
  list.replaceChildren(...routes.map((route) => buildMapSessionRow(route, formatters)));
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

function buildMapSessionRow(
  route: MapRoute,
  formatters: MapSessionListFormatters,
) {
  const session = sessionForRoute(route);
  const active = String(session.id || "") === String(formatters.selectedSessionId || "");
  const live = routeIsLive(route);
  const button = document.createElement("button");
  button.type = "button";
  button.className = "history-row" + (active ? " is-active" : "");
  button.dataset.mapSession = String(session.id || "");

  const center = document.createElement("span");
  const strong = document.createElement("strong");
  strong.textContent = live
    ? `${session.adapterName || "Current drive"} · current`
    : `${session.mode || "session"} · ${session.adapterName || "OBD adapter"}`;

  const small = document.createElement("small");
  const distance = formatters.formatDistance(Number(route.distanceMeters || 0));
  const points = Number(route.pointCount || 0);
  small.textContent = live
    ? `${formatters.formatChipDate(session.startedAtMs)} · ${distance} · ${points} pts`
    : `${formatters.formatWhen(session.startedAtMs)} · ${distance} · ${points} pts`;
  center.append(strong, small);

  const right = document.createElement("b");
  right.textContent = live ? "live" : session.status || "stored";
  button.append(center, right);
  return button;
}
