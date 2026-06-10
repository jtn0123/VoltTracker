import { describe, expect, it, vi } from 'vitest';

import {
  LIVE_ROUTE_ID,
  LIVE_ROUTE_MAX_POINTS,
  appendLiveRoutePoint,
  haversineMetersJs,
  isValidRoutePoint,
  liveSampleTimeMs,
  mapEffColor,
  routeFitKey,
} from '../app/src/main/dashboard-src/js/map-route-utils.ts';

describe('map-route-utils.ts', () => {
  it('normalizes live sample timestamps from seconds and milliseconds', () => {
    expect(liveSampleTimeMs({ updatedAt: 1_700_000_000_123 })).toBe(1_700_000_000_123);
    expect(liveSampleTimeMs({ updatedAt: 1_700_000_000 })).toBe(1_700_000_000_000);

    vi.useFakeTimers();
    vi.setSystemTime(123_456);
    expect(liveSampleTimeMs({ updatedAt: -1 })).toBe(123_456);
    vi.useRealTimers();
  });

  it('buckets efficiency colors and invalid values', () => {
    expect(mapEffColor('not-a-number')).toBe('#6a6a72');
    expect(mapEffColor(4.2)).toBe('#b8e63b');
    expect(mapEffColor(3.1)).toBe('#ffb84a');
    expect(mapEffColor(1.9)).toBe('#ff6b5f');
  });

  it('validates route point latitude and longitude ranges', () => {
    expect(isValidRoutePoint({ lat: 32.7, lng: -117.1, atMs: 1 })).toBe(true);
    expect(isValidRoutePoint({ lat: 91, lng: -117.1 })).toBe(false);
    expect(isValidRoutePoint({ lat: 32.7, lng: -181 })).toBe(false);
    expect(isValidRoutePoint({ lat: 'nope', lng: -117.1 })).toBe(false);
  });

  it('builds stable fit keys for live and stored routes', () => {
    const points = [
      { lat: 32.700001, lng: -117.100001, atMs: 1 },
      { lat: 32.800001, lng: -117.200001, atMs: 2 },
    ];
    expect(routeFitKey({ id: LIVE_ROUTE_ID }, points)).toBe(`${LIVE_ROUTE_ID}:32.70000:-117.10000`);
    expect(routeFitKey({ id: 42 }, points)).toBe('42:2:32.70000:-117.10000:32.80000:-117.20000');
  });

  it('computes approximate haversine distance', () => {
    const meters = haversineMetersJs(32.7, -117.1, 32.71, -117.1);
    expect(meters).toBeGreaterThan(1100);
    expect(meters).toBeLessThan(1120);
  });

  it('appendLiveRoutePoint reports the first point of a new route', () => {
    const points = [];
    expect(appendLiveRoutePoint(points, { lat: 32.7, lng: -117.1, atMs: 1000 })).toBe('first');
    expect(points).toHaveLength(1);
  });

  it('appendLiveRoutePoint dedupes near-stationary samples but keeps moved or aged ones', () => {
    const points = [{ lat: 32.7, lng: -117.1, atMs: 1000 }];
    // Same spot, fresh: skipped.
    expect(appendLiveRoutePoint(points, { lat: 32.7, lng: -117.1, atMs: 1500 })).toBe('skipped');
    expect(points).toHaveLength(1);
    // Same spot but >2s later: kept (stationary heartbeat).
    expect(appendLiveRoutePoint(points, { lat: 32.7, lng: -117.1, atMs: 3500 })).toBe('appended');
    // Moved >1m within 2s: kept.
    expect(appendLiveRoutePoint(points, { lat: 32.701, lng: -117.1, atMs: 3600 })).toBe('appended');
    expect(points).toHaveLength(3);
  });

  it('appendLiveRoutePoint trims the buffer to the live route cap', () => {
    const points = [];
    for (let i = 0; i < LIVE_ROUTE_MAX_POINTS + 5; i += 1) {
      appendLiveRoutePoint(points, { lat: 32.7 + i * 0.001, lng: -117.1, atMs: 1000 + i });
    }
    expect(points).toHaveLength(LIVE_ROUTE_MAX_POINTS);
    // Oldest points fall off the front.
    expect(points[0].atMs).toBe(1005);
  });
});
