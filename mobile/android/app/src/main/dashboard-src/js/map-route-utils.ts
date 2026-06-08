export const LIVE_ROUTE_ID = "__live_current__";

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

export function mapEffColor(eff: unknown) {
  const value = Number(eff);
  if (!Number.isFinite(value)) return "#6a6a72";
  if (value >= 4) return "#b8e63b";
  if (value >= 2.7) return "#ffb84a";
  return "#ff6b5f";
}

export function isValidRoutePoint(point: unknown): point is MapRoutePoint {
  const candidate = point as MapRoutePoint | null;
  const lat = Number(candidate && candidate.lat);
  const lng = Number(candidate && candidate.lng);
  return Number.isFinite(lat) && Number.isFinite(lng) && Math.abs(lat) <= 90 && Math.abs(lng) <= 180;
}

export function routeFitKey(routeSession: MapRouteSession, points: MapRoutePoint[]) {
  const first = points[0];
  const last = points[points.length - 1];
  if (!first || !last) return [(routeSession || {}).id || "", points.length, "", "", "", ""].join(":");
  if (String((routeSession || {}).id || "") === LIVE_ROUTE_ID) {
    return [
      LIVE_ROUTE_ID,
      Number(first.lat).toFixed(5),
      Number(first.lng).toFixed(5)
    ].join(":");
  }
  return [
    (routeSession || {}).id || "",
    points.length,
    Number(first.lat).toFixed(5),
    Number(first.lng).toFixed(5),
    Number(last.lat).toFixed(5),
    Number(last.lng).toFixed(5)
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
