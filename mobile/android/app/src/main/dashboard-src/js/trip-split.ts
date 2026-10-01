// "Split trip here" on an in-trip Park stop, and merging a split back — the
// dashboard side. Native persists the split (ObdTripSplits) and answers with
// VoltTrackerNative.tripSplitChanged; Demo / Testing never touches the bridge
// and re-cuts its in-memory sample routes here instead, with the same keys and
// the same label/favorite rule: the first half keeps the trip's label and
// favorite, the second half starts fresh, and a merge takes the first half's.
//
// Pure functions only (no DOM, no VD) so they are unit-testable in isolation;
// map.ts owns the trip-detail buttons and the state writes.
import type { MapRoute, MapRoutePoint } from "./map-route-utils";

export type TripSplitSpan = { startMs: number; endMs: number };

export type TripSplitChange = {
  splitKey: string;
  merged: boolean;
  /** Split: the first and second halves' trip keys. Merge: the merged trip's key. */
  routeKeys: string[];
};

export type DemoTripRow = VoltTrip;

export type DemoTripSplitResult = {
  routes: MapRoute[];
  trips: DemoTripRow[];
  change: TripSplitChange;
};

/** `sessionId:startMs:endMs` — the shape of every native trip and split key. */
export function tripSplitKey(sessionId: string, startMs: number, endMs: number): string {
  return `${sessionId}:${startMs}:${endMs}`;
}

/** The session id at the head of a trip key (`2002` or `2002:start:end`). */
export function baseSessionId(id: unknown): string {
  return String(id ?? "").split(":")[0] ?? "";
}

/** The merge key a route carries on one side, or "" when that side is not a user split. */
export function userSplitKey(route: MapRoute | null | undefined, side: "before" | "after"): string {
  const split = route ? (side === "before" ? route.userSplitBefore : route.userSplitAfter) : null;
  const key = split && typeof split === "object" ? String((split as VoltUserSplit).key || "").trim() : "";
  return key;
}

/** Parses the native tripSplitChanged payload; null when it is not a usable answer. */
export function parseTripSplitChange(raw: unknown): TripSplitChange | null {
  if (!raw || typeof raw !== "object") return null;
  const value = raw as Record<string, unknown>;
  const splitKey = String(value.splitKey || "").trim();
  if (!splitKey) return null;
  const routeKeys = Array.isArray(value.routeKeys)
    ? value.routeKeys.map((key) => String(key || "").trim()).filter(Boolean)
    : [];
  return { splitKey, merged: value.merged === true, routeKeys };
}

type DistanceOf = (points: MapRoutePoint[]) => number;

function timed(value: unknown): value is Array<{ atMs: number }> {
  return Array.isArray(value) && value.every((item) => item && typeof item === "object" && Number.isFinite(Number((item as { atMs?: unknown }).atMs)));
}

// Every per-sample track on a route (points, socTrack, powerTrack, ...) keyed by field.
function timedTracks(route: MapRoute): string[] {
  return Object.keys(route).filter((field) => field !== "parkStops" && timed(route[field]));
}

function routeBounds(route: MapRoute): TripSplitSpan | null {
  const points = Array.isArray(route.points) ? route.points : [];
  const first = points[0];
  const last = points[points.length - 1];
  if (!first || !last) return null;
  return { startMs: Number(first.atMs), endMs: Number(last.atMs) };
}

function parkStopsOf(route: MapRoute): VoltParkStop[] {
  return Array.isArray(route.parkStops) ? route.parkStops : [];
}

function sliceRoute(route: MapRoute, fromMs: number, toMs: number, distanceOf: DistanceOf): MapRoute {
  const next: MapRoute = { ...route };
  for (const field of timedTracks(route)) {
    next[field] = (route[field] as Array<{ atMs: number }>).filter((item) => item.atMs >= fromMs && item.atMs <= toMs);
  }
  next.parkStops = parkStopsOf(route).filter((stop) => Number(stop.startMs) >= fromMs && Number(stop.endMs) <= toMs);
  const points = (next.points || []) as MapRoutePoint[];
  next.distanceMeters = distanceOf(points);
  next.pointCount = points.length;
  delete next._effDone;
  return next;
}

function withSession(route: MapRoute, id: string, bounds: TripSplitSpan): MapRoute {
  route.session = { ...(route.session || {}), id, startedAtMs: bounds.startMs, endedAtMs: bounds.endMs };
  return route;
}

function tripFor(trips: DemoTripRow[], key: string): DemoTripRow | null {
  return trips.find((trip) => String(trip.id) === key) || null;
}

function tripRow(base: DemoTripRow | null, route: MapRoute, bounds: TripSplitSpan, energyKwh: number | null): DemoTripRow {
  const id = String((route.session || {}).id);
  const row: DemoTripRow = {
    ...(base || {}),
    id,
    startedAtMs: bounds.startMs,
    endedAtMs: bounds.endMs,
    durationMs: bounds.endMs - bounds.startMs,
    distanceMeters: Number(route.distanceMeters) || 0,
    pointCount: Array.isArray(route.points) ? route.points.length : 0
  };
  if (energyKwh != null) row.energyKwh = Math.round(energyKwh * 10) / 10;
  return row;
}

function energyOf(trip: DemoTripRow | null): number | null {
  const value = trip ? Number(trip.energyKwh) : Number.NaN;
  return Number.isFinite(value) ? value : null;
}

/**
 * Splits the demo trip [routeKey] at its Park stop [stop]: the first half ends where the stop
 * starts and the second starts just after it ends (the native window rule). Returns null when the
 * route or the stop is not found, or either half would be empty.
 */
export function splitDemoTrip(
  routes: MapRoute[],
  trips: DemoTripRow[],
  routeKey: string,
  stop: TripSplitSpan,
  distanceOf: DistanceOf
): DemoTripSplitResult | null {
  const index = routes.findIndex((route) => String((route.session || {}).id) === routeKey);
  const route = routes[index];
  if (!route) return null;
  const isStop = parkStopsOf(route).some((s) => Number(s.startMs) === stop.startMs && Number(s.endMs) === stop.endMs);
  if (!isStop) return null;
  const sessionId = baseSessionId(routeKey);
  const splitKey = tripSplitKey(sessionId, stop.startMs, stop.endMs);
  const userSplit: VoltUserSplit = { key: splitKey, startMs: stop.startMs, endMs: stop.endMs };
  const first = sliceRoute(route, -Infinity, stop.startMs, distanceOf);
  const second = sliceRoute(route, stop.endMs + 1, Infinity, distanceOf);
  const firstBounds = routeBounds(first);
  const secondBounds = routeBounds(second);
  if (!firstBounds || !secondBounds) return null;
  withSession(first, tripSplitKey(sessionId, firstBounds.startMs, firstBounds.endMs), firstBounds);
  withSession(second, tripSplitKey(sessionId, secondBounds.startMs, secondBounds.endMs), secondBounds);
  // Both halves inherit the original's outer split edges (the spread copied them);
  // the new split becomes the inner edge of each.
  first.userSplitAfter = userSplit;
  second.userSplitBefore = userSplit;

  const original = tripFor(trips, routeKey);
  const energy = energyOf(original);
  const total = (Number(first.distanceMeters) || 0) + (Number(second.distanceMeters) || 0);
  const share = total > 0 ? (Number(first.distanceMeters) || 0) / total : 0.5;
  const firstTrip = tripRow(original, first, firstBounds, energy == null ? null : energy * share);
  const secondTrip = tripRow(original, second, secondBounds, energy == null ? null : energy * (1 - share));
  secondTrip.label = "";
  secondTrip.favorite = false;

  const nextRoutes = routes.slice();
  nextRoutes.splice(index, 1, first, second);
  const nextTrips = original
    ? trips.flatMap((trip) => (trip === original ? [firstTrip, secondTrip] : [trip]))
    : trips.concat([firstTrip, secondTrip]);
  const firstKey = String((first.session || {}).id);
  const secondKey = String((second.session || {}).id);
  return { routes: nextRoutes, trips: nextTrips, change: { splitKey, merged: false, routeKeys: [firstKey, secondKey] } };
}

/**
 * Merges the two demo trips on either side of the user split [splitKey] back into one. The merged
 * trip keeps the first half's label and favorite; the split stop becomes an in-trip Park stop again.
 */
export function mergeDemoTrip(
  routes: MapRoute[],
  trips: DemoTripRow[],
  splitKey: string,
  distanceOf: DistanceOf
): DemoTripSplitResult | null {
  const firstIndex = routes.findIndex((route) => userSplitKey(route, "after") === splitKey);
  const secondIndex = routes.findIndex((route) => userSplitKey(route, "before") === splitKey);
  const first = routes[firstIndex];
  const second = routes[secondIndex];
  if (!first || !second) return null;
  const span = first.userSplitAfter as VoltUserSplit;
  const merged: MapRoute = { ...first };
  for (const field of timedTracks(first)) {
    const tail = timed(second[field]) ? (second[field] as Array<{ atMs: number }>) : [];
    merged[field] = (first[field] as Array<{ atMs: number }>).concat(tail);
  }
  const stop: VoltParkStop = { startMs: span.startMs, endMs: span.endMs, durationMs: span.endMs - span.startMs, doorOpened: false };
  merged.parkStops = parkStopsOf(first).concat([stop], parkStopsOf(second));
  const points = (merged.points || []) as MapRoutePoint[];
  merged.distanceMeters = distanceOf(points);
  merged.pointCount = points.length;
  delete merged._effDone;
  delete merged.userSplitAfter;
  if (second.userSplitAfter) merged.userSplitAfter = second.userSplitAfter;
  const bounds = routeBounds(merged);
  if (!bounds) return null;
  const mergedKey = tripSplitKey(baseSessionId(splitKey), bounds.startMs, bounds.endMs);
  withSession(merged, mergedKey, bounds);

  const firstKey = String((first.session || {}).id);
  const secondKey = String((second.session || {}).id);
  const firstTrip = tripFor(trips, firstKey);
  const secondTrip = tripFor(trips, secondKey);
  const energies = [energyOf(firstTrip), energyOf(secondTrip)].filter((v): v is number => v != null);
  const mergedTrip = tripRow(firstTrip, merged, bounds, energies.length ? energies.reduce((a, b) => a + b, 0) : null);

  const nextRoutes = routes.filter((route) => route !== second).map((route) => (route === first ? merged : route));
  const nextTrips = trips
    .filter((trip) => trip !== secondTrip)
    .map((trip) => (trip === firstTrip ? mergedTrip : trip));
  if (!firstTrip) nextTrips.push(mergedTrip);
  return { routes: nextRoutes, trips: nextTrips, change: { splitKey, merged: true, routeKeys: [mergedKey] } };
}
