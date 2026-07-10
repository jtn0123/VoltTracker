/** Stable signatures used to skip expensive redraws without hiding real content changes. */

function objectValue(value: unknown): Record<string, unknown> {
  return value != null && typeof value === "object" ? (value as Record<string, unknown>) : {};
}

function finiteNumber(value: unknown): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

export function numberSeriesSignature(samples: readonly unknown[], width: number): string {
  return `${width}|${samples.map((value) => finiteNumber(value)).join(",")}`;
}

export function staticRouteDrawSignature(
  fitKey: string,
  layer: string,
  hasRoute: boolean,
  enrichedPointCount: number,
  unitSystem: string,
): string {
  return [fitKey, layer, hasRoute ? 1 : 0, enrichedPointCount, unitSystem].join("|");
}

export function tripGeometrySignature(trips: readonly unknown[]): string {
  return trips
    .map((entry) => {
      const trip = objectValue(entry);
      return [
        String(trip.id ?? ""),
        finiteNumber(trip.pointCount),
        trip.hasRoute === false ? 0 : 1,
        finiteNumber(trip.startedAtMs),
        finiteNumber(trip.endedAtMs),
        finiteNumber(trip.distanceMeters),
        String(trip.label ?? ""),
        trip.favorite === true ? 1 : 0,
        finiteNumber(trip.updatedAtMs ?? trip.routeUpdatedAtMs),
      ].join(":");
    })
    .join("|");
}

export function storageRollupSignature(storageValue: unknown): string {
  const storage = objectValue(storageValue);
  const routes = Array.isArray(storage.recentRoutes) ? storage.recentRoutes : [];
  const routeSignature = routes
    .map((entry) => {
      const route = objectValue(entry);
      const session = objectValue(route.session);
      const points = Array.isArray(route.points) ? route.points.length : 0;
      return [
        String(session.id ?? ""),
        finiteNumber(route.pointCount || points),
        finiteNumber(route.distanceMeters),
        finiteNumber(session.startedAtMs),
        finiteNumber(session.endedAtMs),
        String(session.label ?? route.label ?? ""),
        session.favorite === true || route.favorite === true ? 1 : 0,
      ].join(":");
    })
    .join("|");
  // These counts advance during a live session but do not change completed rollups.
  return [
    finiteNumber(storage.sessionCount),
    finiteNumber(storage.tripSegmentCount),
    finiteNumber(storage.chargeSessionCount),
    finiteNumber(storage.batterySnapshotCount),
    routes.length,
    routeSignature,
  ].join("|");
}
