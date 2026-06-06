import { performance } from 'node:perf_hooks';

import { describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

const ROUTE_POINT_COUNT = 2000;
const MAP_SESSION_COUNT = 250;

function makeRoutePoint(index) {
  const capturedAtMs = 1_720_000_000_000 + index * 1000;
  return {
    lat: 32.80131 + index * 0.00004,
    lng: -116.9513 - Math.sin(index / 80) * 0.0009,
    atMs: capturedAtMs,
    capturedAtMs,
    speedMps: 12 + (index % 20) * 0.15,
    altM: 110 + Math.sin(index / 45) * 12,
    eff: 3.1 + Math.sin(index / 60) * 0.4,
  };
}

function makeRoute(pointCount = ROUTE_POINT_COUNT) {
  const points = Array.from({ length: pointCount }, (_, index) => makeRoutePoint(index));
  return {
    session: {
      id: 'budget-route',
      startedAtMs: points[0].capturedAtMs,
      endedAtMs: points[points.length - 1].capturedAtMs,
      adapterName: 'budget fixture',
    },
    points,
    distanceMeters: 0,
    socTrack: points.map((point, index) => ({
      atMs: point.capturedAtMs,
      soc: 86 - index * 0.003,
    })),
  };
}

describe('dashboard startup budget', () => {
  it('boots the dashboard bootstrap path inside a generous local budget', async () => {
    const start = performance.now();
    await loadDashboard();
    const elapsedMs = performance.now() - start;

    expect(elapsedMs).toBeLessThan(5000);
  });

  it('keeps long-route distance math inside the dashboard budget', async () => {
    await loadDashboard();
    const VD = window.VoltDashboard;
    const route = makeRoute();

    const start = performance.now();
    const distanceMeters = VD.routeDistanceMeters(route.points);
    const elapsedMs = performance.now() - start;

    expect(distanceMeters).toBeGreaterThan(0);
    expect(elapsedMs).toBeLessThan(100);
  });

  it('renders a long route scrubber inside the dashboard budget', async () => {
    await loadDashboard();
    const VD = window.VoltDashboard;
    const route = makeRoute();
    route.distanceMeters = VD.routeDistanceMeters(route.points);
    Object.defineProperty(document.getElementById('scrubChart'), 'clientWidth', {
      configurable: true,
      value: 720,
    });

    const start = performance.now();
    VD.renderScrubber(route);
    const elapsedMs = performance.now() - start;

    expect(document.getElementById('scrubber').hidden).toBe(false);
    expect(document.querySelectorAll('#scrubChart svg').length).toBe(1);
    expect(elapsedMs).toBeLessThan(1000);
  });

  it('renders long map history lists inside the dashboard budget', async () => {
    await loadDashboard();
    const VD = window.VoltDashboard;
    const routes = Array.from({ length: MAP_SESSION_COUNT }, (_, index) => {
      const route = makeRoute(18);
      route.session.id = `budget-session-${index}`;
      route.session.startedAtMs -= index * 3_600_000;
      route.session.endedAtMs -= index * 3_600_000;
      route.distanceMeters = 3200 + index * 200;
      return route;
    });

    const start = performance.now();
    VD.renderMapSessionList(routes);
    const elapsedMs = performance.now() - start;

    expect(document.querySelectorAll('[data-map-session]').length).toBe(MAP_SESSION_COUNT);
    expect(elapsedMs).toBeLessThan(1000);
  });
});
