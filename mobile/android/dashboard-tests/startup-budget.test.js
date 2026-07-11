import { performance } from 'node:perf_hooks';

import { describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

const ROUTE_POINT_COUNT = 2000; // ~33min drive at 1 GPS fix/s — the long-route worst case the map must digest.
const MAP_SESSION_COUNT = 250; // months of daily driving in the session list — well past the native 40-trip page.
const TELEMETRY_BURST_COUNT = 300; // a wedged native queue flushing at once; must coalesce into ONE rAF render.
const TAB_SWITCH_REPEAT_COUNT = 25; // repeats x6 tabs = 150 switches, enough samples to average out jsdom jitter.
// Deterministic per-switch WORK budgets (the real regression gate — see the tab-switch
// spec below). Measured 2026-07-11: 18.3 mutation records/switch (body datasets, view/nav
// class + aria toggles, heading text) and exactly 1 rAF/switch (setView's single
// after-paint mark). The budgets leave ~2x headroom for legitimate UI additions while
// still failing hard on per-switch re-renders (a re-rendered 80-row session list alone
// is hundreds of mutation records).
const TAB_SWITCH_MUTATION_BUDGET_PER_SWITCH = 40;
const TAB_SWITCH_RAF_BUDGET_PER_SWITCH = 2;
// Wall-clock is only a SANITY bound, not the gate: CI runners are noisy/shared, so a
// tight ms budget (this was 4ms/switch) is a timing coin-flip there. 25ms/switch is
// ~60x the measured 0.4ms/switch — loose enough that scheduler jitter can't flake it,
// tight enough that a runaway loop per switch still fails.
const TAB_SWITCH_BUDGET_PER_SWITCH_MS = 25;

function makeRoutePoint(index) {
  const capturedAtMs = 1_720_000_000_000 + index * 1000;
  return {
    lat: 34.11872 + index * 0.00004,
    lng: -118.30064 - Math.sin(index / 80) * 0.0009,
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

    // 5s = cold-WebView worst case (old device, first run, no cached bytecode);
    // generous on purpose so CI noise can't flake it, but a runaway boot loop still fails.
    expect(elapsedMs).toBeLessThan(5000);
    expect(window.VoltDashboard.renderMapLoaded).not.toBe(true);
  });

  it('does not read trips or insights on the first dashboard load', async () => {
    const bridge = createVoltBridgeFixture({
      getTrips: vi.fn(() => '[]'),
      getInsights: vi.fn(() => '{}'),
    });

    await loadDashboard({ bridge });
    await window.VoltDashboard.pendingLazyLoads();

    expect(bridge.getTrips).not.toHaveBeenCalled();
    expect(bridge.getInsights).not.toHaveBeenCalled();
    expect(window.VoltDashboard.loadTrips).toBeUndefined();
    expect(window.VoltDashboard.loadInsights).toBeUndefined();
  });

  it('keeps long-route distance math inside the dashboard budget', async () => {
    await loadDashboard();
    const VD = window.VoltDashboard;
    await VD.ensureMapModule();
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
    await VD.ensureMapModule();
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
    await VD.ensureMapModule();
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

    expect(document.querySelectorAll('[data-map-session]')).toHaveLength(80);
    expect(document.querySelector('[data-map-session-more]').textContent).toBe('Show more drives');
    expect(elapsedMs).toBeLessThan(1000);
  });

  it('keeps focus on map-history pagination after showing more drives', async () => {
    await loadDashboard();
    const VD = window.VoltDashboard;
    await VD.ensureMapModule();
    const routes = Array.from({ length: MAP_SESSION_COUNT }, (_, index) => {
      const route = makeRoute(18);
      route.session.id = `focus-session-${index}`;
      route.session.startedAtMs -= index * 3_600_000;
      route.session.endedAtMs -= index * 3_600_000;
      return route;
    });

    VD.renderMapSessionList(routes);
    const firstMore = document.querySelector('[data-map-session-more]');
    firstMore.focus();
    firstMore.click();

    const nextMore = document.querySelector('[data-map-session-more]');
    expect(document.querySelectorAll('[data-map-session]')).toHaveLength(160);
    expect(document.activeElement).toBe(nextMore);
  });

  it('switches all primary tabs repeatedly inside the dashboard budget', async () => {
    await loadDashboard();
    const VD = window.VoltDashboard;
    await VD.ensureMapModule();
    const tabs = ['drive', 'map', 'charge', 'insights', 'diagnostics', 'settings'];
    const switchCount = TAB_SWITCH_REPEAT_COUNT * tabs.length;

    // Deterministic work proxies. A wall-clock-only budget is a coin flip on a
    // loaded CI runner, so the real regression gate is the amount of WORK each
    // switch performs, which jsdom can observe exactly:
    //   - DOM mutation records: setView touches a fixed set of nodes (body
    //     datasets, view/nav class + aria toggles, heading text). Someone
    //     re-rendering a list or rebuilding a section per switch multiplies this.
    //   - rAF schedules: setView defers exactly one after-paint mark per switch;
    //     render work leaking into the synchronous switch path shows up here.
    const mutationObserver = new MutationObserver(() => {});
    mutationObserver.observe(document.body, {
      subtree: true,
      childList: true,
      attributes: true,
      characterData: true,
    });
    const originalRaf = window.requestAnimationFrame;
    let rafScheduleCount = 0;
    window.requestAnimationFrame = (callback) => {
      rafScheduleCount += 1;
      return originalRaf ? originalRaf.call(window, callback) : 0;
    };

    let elapsedMs = 0;
    let mutationRecordCount = 0;
    try {
      const start = performance.now();
      for (let i = 0; i < TAB_SWITCH_REPEAT_COUNT; i += 1) {
        for (const tab of tabs) {
          VD.setView(tab);
        }
      }
      elapsedMs = performance.now() - start;
      mutationRecordCount = mutationObserver.takeRecords().length;
    } finally {
      mutationObserver.disconnect();
      window.requestAnimationFrame = originalRaf;
    }

    expect(document.body.dataset.activeView).toBe('settings');
    expect(mutationRecordCount / switchCount).toBeLessThan(TAB_SWITCH_MUTATION_BUDGET_PER_SWITCH);
    expect(rafScheduleCount / switchCount).toBeLessThan(TAB_SWITCH_RAF_BUDGET_PER_SWITCH);
    expect(elapsedMs / switchCount).toBeLessThan(TAB_SWITCH_BUDGET_PER_SWITCH_MS);
  });

  it('coalesces a high-rate telemetry burst into one render frame inside budget', async () => {
    await loadDashboard();
    const VD = window.VoltDashboard;
    const originalRaf = window.requestAnimationFrame;
    const callbacks = [];
    window.requestAnimationFrame = (callback) => {
      callbacks.push(callback);
      return callbacks.length;
    };

    try {
      const enqueueStart = performance.now();
      for (let index = 0; index < TELEMETRY_BURST_COUNT; index += 1) {
        VD.updateTelemetry({
          sampleCount: index + 1,
          capturedAtMs: 1_720_000_000_000 + index * 50,
          speedKph: 48 + (index % 12),
          rpm: index % 2 === 0 ? 0 : 1100,
          soc: 72 - index * 0.001,
          packVoltage: 360,
          packCurrentA: -12,
        });
      }
      const enqueueElapsedMs = performance.now() - enqueueStart;

      expect(callbacks.length).toBe(1);
      expect(enqueueElapsedMs).toBeLessThan(100);

      const flushStart = performance.now();
      callbacks[0](performance.now());
      const flushElapsedMs = performance.now() - flushStart;

      expect(VD.state.rafPending).toBe(0);
      expect(document.getElementById('speedValue').textContent).not.toBe('--');
      expect(flushElapsedMs).toBeLessThan(750);
    } finally {
      window.requestAnimationFrame = originalRaf;
    }
  });
});
