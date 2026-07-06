import { describe, expect, it, vi } from 'vitest';

import {
  LIVE_ROUTE_ID,
  LIVE_ROUTE_MAX_POINTS,
  appendLiveRoutePoint,
  haversineMetersJs,
  isValidRoutePoint,
  liveFollowShouldRecenter,
  liveSampleTimeMs,
  mapEffColor,
  numOrNaN,
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
    // Regen / no-data segments carry eff === null. These must read as grey
    // ("no data"), never fall through Number(null) === 0 into the worst red band.
    expect(mapEffColor(null)).toBe('#6a6a72');
    expect(mapEffColor(undefined)).toBe('#6a6a72');
    expect(mapEffColor('')).toBe('#6a6a72');
  });

  it('numOrNaN maps null/empty to NaN so isFinite guards fire (Number(null) === 0 trap)', () => {
    expect(numOrNaN(null)).toBeNaN();
    expect(numOrNaN(undefined)).toBeNaN();
    expect(numOrNaN('')).toBeNaN();
    expect(numOrNaN(0)).toBe(0);
    expect(numOrNaN('4.2')).toBe(4.2);
    expect(numOrNaN(4.2)).toBe(4.2);
    expect(Number.isFinite(numOrNaN(null))).toBe(false);
  });

  it('validates route point latitude and longitude ranges', () => {
    expect(isValidRoutePoint({ lat: 34.05, lng: -118.25, atMs: 1 })).toBe(true);
    expect(isValidRoutePoint({ lat: 91, lng: -118.25 })).toBe(false);
    expect(isValidRoutePoint({ lat: 34.05, lng: -181 })).toBe(false);
    expect(isValidRoutePoint({ lat: 'nope', lng: -118.25 })).toBe(false);
  });

  it('builds stable fit keys for live and stored routes', () => {
    const points = [
      { lat: 34.050001, lng: -118.250001, atMs: 1 },
      { lat: 34.150001, lng: -118.320001, atMs: 2 },
    ];
    expect(routeFitKey({ id: LIVE_ROUTE_ID }, points)).toBe(`${LIVE_ROUTE_ID}:34.05000:-118.25000`);
    expect(routeFitKey({ id: 42 }, points)).toBe('42:2:34.05000:-118.25000:34.15000:-118.32000');
  });

  describe('liveFollowShouldRecenter', () => {
    // A Los Angeles fixture viewport; with the default 0.18 margin the inner
    // "safe" box is roughly lat 34.070..34.140 / lng -118.427..-118.343.
    const view = { north: 34.16, south: 34.05, east: -118.32, west: -118.45 };

    it('stays put while the newest point sits comfortably inside the viewport', () => {
      expect(liveFollowShouldRecenter(view, { lat: 34.10, lng: -118.38 })).toBe(false);
    });

    it('recenters once the newest point nears the viewport edge', () => {
      // Within the top 18% gutter (> 34.140) -> about to leave view.
      expect(liveFollowShouldRecenter(view, { lat: 34.145, lng: -118.38 })).toBe(true);
      // Past the west edge entirely.
      expect(liveFollowShouldRecenter(view, { lat: 34.10, lng: -118.50 })).toBe(true);
    });

    it('recenters when there is no view yet, or the view is degenerate', () => {
      expect(liveFollowShouldRecenter(null, { lat: 34.10, lng: -118.38 })).toBe(true);
      expect(
        liveFollowShouldRecenter({ north: 1, south: 1, east: 1, west: 1 }, { lat: 1, lng: 1 }),
      ).toBe(true);
    });

    it('does not move for a non-finite point (bad GPS sample)', () => {
      expect(liveFollowShouldRecenter(view, { lat: Number.NaN, lng: -118.38 })).toBe(false);
      expect(liveFollowShouldRecenter(view, null)).toBe(true);
    });
  });

  it('computes approximate haversine distance', () => {
    const meters = haversineMetersJs(34.05, -118.25, 34.06, -118.25);
    expect(meters).toBeGreaterThan(1100);
    expect(meters).toBeLessThan(1120);
  });

  it('appendLiveRoutePoint reports the first point of a new route', () => {
    const points = [];
    expect(appendLiveRoutePoint(points, { lat: 34.05, lng: -118.25, atMs: 1000 })).toBe('first');
    expect(points).toHaveLength(1);
  });

  it('appendLiveRoutePoint dedupes near-stationary samples but keeps moved or aged ones', () => {
    const points = [{ lat: 34.05, lng: -118.25, atMs: 1000 }];
    // Same spot, fresh: skipped.
    expect(appendLiveRoutePoint(points, { lat: 34.05, lng: -118.25, atMs: 1500 })).toBe('skipped');
    expect(points).toHaveLength(1);
    // Same spot but >2s later: kept (stationary heartbeat).
    expect(appendLiveRoutePoint(points, { lat: 34.05, lng: -118.25, atMs: 3500 })).toBe('appended');
    // Moved >1m within 2s: kept.
    expect(appendLiveRoutePoint(points, { lat: 34.051, lng: -118.25, atMs: 3600 })).toBe('appended');
    expect(points).toHaveLength(3);
  });

  it('appendLiveRoutePoint trims the buffer to the live route cap', () => {
    const points = [];
    for (let i = 0; i < LIVE_ROUTE_MAX_POINTS + 5; i += 1) {
      appendLiveRoutePoint(points, { lat: 34.05 + i * 0.001, lng: -118.25, atMs: 1000 + i });
    }
    expect(points).toHaveLength(LIVE_ROUTE_MAX_POINTS);
    // Oldest points fall off the front.
    expect(points[0].atMs).toBe(1005);
  });
});
