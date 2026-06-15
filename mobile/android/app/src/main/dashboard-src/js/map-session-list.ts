import { CURRENT_DRIVE_LABEL, LIVE_ROUTE_ID } from "./map-route-utils";
import type { MapRoute, MapRouteSession } from "./map-route-utils";

// The client-side search/sort/favorites filter state (M4). Driven by the
// controls above #mapSessionList; map.ts reads the control DOM and passes this.
export type MapSessionSort = "recent" | "distance";
export type MapSessionFilter = {
  query: string;
  sort: MapSessionSort;
  favoritesOnly: boolean;
};

type MapSessionListFormatters = {
  selectedSessionId?: unknown;
  formatChipDate: (value: unknown) => string;
  formatDistance: (meters: number) => string;
  formatWhen: (value: unknown) => string;
  filter?: MapSessionFilter;
};

// The searchable text for a stored route: its user label, the mode/adapter
// fallback title, and the formatted date — so a search matches by name OR by
// when the drive happened. `formatWhen` (when provided) supplies the same
// human date string the row shows, so searching "today" / "may" works. The
// standalone (formatter-less) path still matches label + mode/adapter.
function searchableText(route: MapRoute, formatWhen?: (value: unknown) => string): string {
  const session = sessionForRoute(route);
  const label = labelOf(session);
  const fallback = `${session.mode || "session"} ${session.adapterName || "OBD adapter"}`;
  const date = formatWhen ? formatWhen(session.startedAtMs) : "";
  return `${label} ${fallback} ${date}`.toLowerCase();
}

// Apply the M4 search / favorites / sort over the routes. The live (current)
// drive is always kept and pinned first — it represents "now", carries no label
// or favorite flag, and shouldn't vanish behind a stale filter. Stored routes
// arrive newest-first already, so "recent" preserves order; "distance" re-sorts
// by logged distance descending. `formatWhen` is optional so this stays pure and
// unit-testable; render passes the real unit-aware formatter so date search
// matches the on-screen text.
export function filterAndSortRoutes(
  routes: MapRoute[],
  filter?: MapSessionFilter,
  formatWhen?: (value: unknown) => string,
): MapRoute[] {
  if (!filter) return routes;
  const query = String(filter.query || "").trim().toLowerCase();
  const live: MapRoute[] = [];
  let stored: MapRoute[] = [];
  for (const route of routes) {
    if (routeIsLive(route)) live.push(route);
    else stored.push(route);
  }
  if (filter.favoritesOnly) {
    stored = stored.filter((route) => favoriteOf(sessionForRoute(route)));
  }
  if (query) {
    stored = stored.filter((route) => searchableText(route, formatWhen).includes(query));
  }
  if (filter.sort === "distance") {
    stored = stored
      .slice()
      .sort((a, b) => Number(b.distanceMeters || 0) - Number(a.distanceMeters || 0));
  }
  return [...live, ...stored];
}

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
  const visible = filterAndSortRoutes(routes, formatters.filter, formatters.formatWhen);
  if (!visible.length) {
    // Routes exist but the filter hid them all — distinguish from "no drives yet".
    const filter = formatters.filter;
    const p = document.createElement("p");
    p.className = "status-copy";
    p.textContent = filter && filter.favoritesOnly && !String(filter.query || "").trim()
      ? "No favorite drives yet. Tap a drive's star to add it here."
      : "No drives match your search.";
    list.replaceChildren(p);
    return;
  }
  list.replaceChildren(...visible.map((route) => buildMapSessionEntry(route, formatters)));
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
  // Live drives have no finalized route key yet, so only stored trips get the favorite + GPX/CSV
  // export + rename affordances.
  if (!live) {
    const exportRow = buildExportActions(String(session.id || ""), labelOf(session), favoriteOf(session));
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

// The trip's favorite flag (M4 favorites half), defensively coerced (the native field is open).
function favoriteOf(session: MapRouteSession): boolean {
  return Boolean(session && session.favorite === true);
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

// The "Favorite / Rename / Export GPX / Export CSV" buttons under a stored route row. Each carries
// the route key in a data attribute; actions.ts delegates the click to bridge.setTripFavorite /
// setTripLabel / exportTrip*. Built with createElement/textContent only — never innerHTML — so a
// hostile adapter name, route key, or label can't inject markup (DOM XSS-safe per the project's
// dom-sinks contract).
function buildExportActions(routeKey: string, currentLabel = "", favorite = false) {
  const clean = String(routeKey || "").trim();
  if (!clean) return null;
  const row = document.createElement("div");
  row.className = "history-export";
  row.append(
    buildFavoriteButton(clean, favorite),
    buildDetailButton(clean),
    buildRenameButton(clean, currentLabel),
    buildExportButton(clean, "gpx", "Export GPX"),
    buildExportButton(clean, "csv", "Export CSV"),
  );
  return row;
}

// The per-row "Details" affordance (M7). A distinct action from select / rename /
// export / favorite: it carries the route key on data-trip-detail so actions.ts
// can open the trip-detail sheet for that one drive. textContent only (XSS-safe).
function buildDetailButton(routeKey: string) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = "history-export-btn history-detail-btn";
  button.dataset.tripDetail = routeKey;
  button.title = "See this drive's stats and efficiency.";
  button.textContent = "Details";
  return button;
}

// The per-row favorite toggle (M4 favorites half). A filled star ("★") when favorited, an outline
// ("☆") otherwise; the current state rides on data-trip-favorite so actions.ts can flip it.
function buildFavoriteButton(routeKey: string, favorite: boolean) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = "history-export-btn history-favorite-btn" + (favorite ? " is-favorite" : "");
  button.dataset.tripFavorite = routeKey;
  button.dataset.tripFavoriteState = favorite ? "1" : "0";
  button.setAttribute("aria-pressed", favorite ? "true" : "false");
  button.title = favorite ? "Remove this drive from favorites." : "Add this drive to favorites.";
  button.setAttribute("aria-label", favorite ? "Unfavorite this drive" : "Favorite this drive");
  button.textContent = favorite ? "★" : "☆";
  return button;
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
