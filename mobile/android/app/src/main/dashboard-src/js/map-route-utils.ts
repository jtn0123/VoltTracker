export const LIVE_ROUTE_ID = "__live_current__";

/** Human label for the in-progress route. Shared by the map header/live-route
 *  builder (map.ts) and the session-list rows (map-session-list.ts) so the
 *  copy can never drift between the two surfaces. */
export const CURRENT_DRIVE_LABEL = "Current drive";

export type MapRoutePoint = VoltRoutePoint & {
  atMs: number;
  speedKph?: number;
  soc?: number;
  powerKw?: number;
};

export type MapRoute = VoltRoute & {
  isLive?: boolean;
};

export type MapRouteSession = {
  id?: string | number;
  adapterName?: string;
  mode?: string;
  status?: string;
  startedAtMs?: number;
  endedAtMs?: number;
  /** User-authored trip label (M4); empty/absent when unset. */
  label?: string;
  /** User favorite flag (M4 favorites half); absent/false when not favorited. */
  favorite?: boolean;
  [key: string]: unknown;
};

export function liveSampleTimeMs(sample: VoltTelemetry) {
  const candidates = [
    sample.atMs,
    sample.updatedAtMs,
    sample.updatedAt,
    sample.timestampMs,
    sample.capturedAtMs
  ];
  for (const candidate of candidates) {
    const value = Number(candidate);
    if (!Number.isFinite(value) || value <= 0) continue;
    if (value > 1_000_000_000_000) return value;
    if (value > 1_000_000_000) return value * 1000;
  }
  return Date.now();
}

// Null-safe numeric coercion. `Number(null)` and `Number("")` are 0 (a finite
// value), which silently defeats `Number.isFinite` guards on nullable fields
// (SOC baselines, per-point efficiency). Map null/empty to NaN so those guards
// fire as intended. Use this instead of bare Number() on any field that can be
// null/absent.
export function numOrNaN(value: unknown): number {
  return value == null || value === "" ? NaN : Number(value);
}

export function mapEffColor(eff: unknown) {
  // enrichRouteEff sets eff = null for regen / no-data segments; those must read
  // as "no data" (grey), not fall through Number(null) === 0 into the worst band.
  const value = numOrNaN(eff);
  if (!Number.isFinite(value)) return "#6a6a72";
  if (value >= 4) return "#b8e63b";
  if (value >= 2.7) return "#ffb84a";
  return "#ff6b5f";
}

export function isValidRoutePoint(point: unknown): point is MapRoutePoint {
  const candidate = point as MapRoutePoint | null;
  const lat = Number(candidate && candidate.lat);
  const lng = Number(candidate && candidate.lng);
  // Reject the exact (0,0) "null island" sentinel some GPS/telemetry layers emit before a
  // fix: a single (0,0) between real fixes would draw a ~13,000 km leg off the African coast
  // and blow out fitBounds / route distance. A genuine drive never passes through it.
  if (lat === 0 && lng === 0) return false;
  return Number.isFinite(lat) && Number.isFinite(lng) && Math.abs(lat) <= 90 && Math.abs(lng) <= 180;
}

// Format a coordinate to 5dp for the fit-key, collapsing non-finite values to "" rather than
// the string "NaN" — two distinct malformed routes would otherwise share a "...:NaN:NaN" key
// and suppress a legitimate refit, leaving the map framed on the wrong drive.
function fitKeyCoord(value: unknown): string {
  const n = Number(value);
  return Number.isFinite(n) ? n.toFixed(5) : "";
}

export function routeFitKey(routeSession: MapRouteSession, points: MapRoutePoint[]) {
  const first = points[0];
  const last = points[points.length - 1];
  if (!first || !last) return [(routeSession || {}).id || "", points.length, "", "", "", ""].join(":");
  if (String((routeSession || {}).id || "") === LIVE_ROUTE_ID) {
    // Deliberately excludes points.length so the key stays stable as the live track grows
    // (follow mode reframes via fitLiveFollow); only a changed first fix forces a one-shot refit.
    return [
      LIVE_ROUTE_ID,
      fitKeyCoord(first.lat),
      fitKeyCoord(first.lng)
    ].join(":");
  }
  return [
    (routeSession || {}).id || "",
    points.length,
    fitKeyCoord(first.lat),
    fitKeyCoord(first.lng),
    fitKeyCoord(last.lat),
    fitKeyCoord(last.lng)
  ].join(":");
}

export function haversineMetersJs(lat1: number, lng1: number, lat2: number, lng2: number) {
  const r = 6371000;
  const dLat = (lat2 - lat1) * Math.PI / 180;
  const dLng = (lng2 - lng1) * Math.PI / 180;
  const a = Math.sin(dLat / 2) ** 2
    + Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180)
    * Math.sin(dLng / 2) ** 2;
  return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

export const LIVE_ROUTE_MAX_POINTS = 600;

/** Plain lat/lng bounds (Leaflet-free) so the follow decision can be unit-tested. */
export type LatLngBoundsLike = {
  north: number;
  south: number;
  east: number;
  west: number;
};

/**
 * Decide whether the map should recenter to keep a live drive framed. Returns true when the newest
 * point has drifted into the outer `margin` fraction of the current viewport (or fully outside it),
 * so a parked/zoomed map only moves once the head is about to leave view — it does not jitter on
 * every GPS tick while the vehicle is still comfortably on screen.
 *
 * Pure (no Leaflet) so map.ts can pass `map.getBounds()` flattened to numbers and the behavior is
 * directly testable. `margin` is the fraction of each axis treated as the "about to leave" gutter.
 */
export function liveFollowShouldRecenter(
  view: LatLngBoundsLike | null | undefined,
  last: { lat: number; lng: number } | null | undefined,
  margin = 0.18
): boolean {
  if (!view || !last) return true;
  const lat = Number(last.lat);
  const lng = Number(last.lng);
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return false;
  const latSpan = view.north - view.south;
  const lngSpan = view.east - view.west;
  if (!(latSpan > 0) || !(lngSpan > 0)) return true;
  const latGutter = latSpan * margin;
  const lngGutter = lngSpan * margin;
  return (
    lat > view.north - latGutter ||
    lat < view.south + latGutter ||
    lng > view.east - lngGutter ||
    lng < view.west + lngGutter
  );
}


/**
 * Append a live GPS sample to a route buffer, deduping near-stationary samples (<1 m and <2 s
 * from the previous point) and trimming the buffer to LIVE_ROUTE_MAX_POINTS. Shared by map.ts
 * (module loaded) and telemetry.ts (queueing before the lazy map module arrives) so the two
 * paths can never drift apart.
 *
 * Returns "skipped" when deduped, "first" when this point starts a new route (callers seed
 * liveRouteStartedAtMs / selection from it), otherwise "appended".
 */
export function appendLiveRoutePoint(
  points: MapRoutePoint[],
  point: MapRoutePoint
): "skipped" | "first" | "appended" {
  const previousPoint = points[points.length - 1];
  if (previousPoint) {
    const meters = haversineMetersJs(previousPoint.lat, previousPoint.lng, point.lat, point.lng);
    const ageMs = Math.abs(Number(point.atMs) - Number(previousPoint.atMs));
    if (meters < 1 && ageMs < 2000) return "skipped";
  }
  const first = points.length === 0;
  points.push(point);
  const overflow = points.length - LIVE_ROUTE_MAX_POINTS;
  if (overflow > 0) points.splice(0, overflow);
  return first ? "first" : "appended";
}
