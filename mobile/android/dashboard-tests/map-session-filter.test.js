// M4 — trip-list search / sort / favorites filter. Two layers:
//   1. filterAndSortRoutes (map-session-list.ts) — the pure client-side filter.
//   2. The rendered controls above #mapSessionList, wired through map.ts +
//      actions.ts, re-filtering the list on input/sort/favorites change.
// Rows come from already-loaded routes; rendering stays XSS-safe.
import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { filterAndSortRoutes } from '../app/src/main/dashboard-src/js/map-session-list.ts';

function storedRoute({ id, label = '', favorite = false, distanceMeters = 1000, startedAtMs = 1_700_000_000_000 }) {
  return {
    session: { id, sessionId: id, mode: 'drive', adapterName: 'Adapter', startedAtMs, endedAtMs: startedAtMs + 60_000, label, favorite },
    points: [{ lat: 34.05, lng: -118.25 }, { lat: 34.16, lng: -118.32 }],
    pointCount: 2,
    distanceMeters,
  };
}

const liveRoute = {
  isLive: true,
  session: { id: '__live_current__', status: 'live', adapterName: 'Current', startedAtMs: Date.now() },
  points: [{ lat: 34.05, lng: -118.25 }, { lat: 34.16, lng: -118.32 }],
  pointCount: 2,
  distanceMeters: 500,
};

describe('filterAndSortRoutes (M4, pure)', () => {
  const routes = [
    storedRoute({ id: 'a', label: 'Commute home', favorite: true, distanceMeters: 1000 }),
    storedRoute({ id: 'b', label: 'Costco run', favorite: false, distanceMeters: 5000 }),
    storedRoute({ id: 'c', label: 'Beach trip', favorite: true, distanceMeters: 200 }),
  ];

  it('returns routes unchanged when no filter is supplied', () => {
    expect(filterAndSortRoutes(routes)).toBe(routes);
  });

  it('filters by label substring (case-insensitive)', () => {
    const out = filterAndSortRoutes(routes, { query: 'co', sort: 'recent', favoritesOnly: false });
    // "Costco run" matches "co"; "Commute home" matches "co" too.
    expect(out.map((r) => r.session.id)).toEqual(['a', 'b']);
  });

  it('filters to favorites only', () => {
    const out = filterAndSortRoutes(routes, { query: '', sort: 'recent', favoritesOnly: true });
    expect(out.map((r) => r.session.id)).toEqual(['a', 'c']);
  });

  it('sorts by distance descending', () => {
    const out = filterAndSortRoutes(routes, { query: '', sort: 'distance', favoritesOnly: false });
    expect(out.map((r) => r.session.id)).toEqual(['b', 'a', 'c']);
  });

  it('combines favorites + distance sort', () => {
    const out = filterAndSortRoutes(routes, { query: '', sort: 'distance', favoritesOnly: true });
    // Only favorites (a=1000, c=200), distance desc.
    expect(out.map((r) => r.session.id)).toEqual(['a', 'c']);
  });

  it('always keeps the live route pinned first, exempt from filters', () => {
    const withLive = [liveRoute, ...routes];
    const out = filterAndSortRoutes(withLive, { query: 'beach', sort: 'recent', favoritesOnly: false });
    expect(out[0].session.id).toBe('__live_current__');
    expect(out.map((r) => r.session.id)).toEqual(['__live_current__', 'c']);
  });

  it('matches the formatted date via the formatWhen callback', () => {
    const fmt = (ms) => (Number(ms) > 0 ? 'May 2026' : '');
    const out = filterAndSortRoutes(routes, { query: 'may', sort: 'recent', favoritesOnly: false }, fmt);
    expect(out.map((r) => r.session.id)).toEqual(['a', 'b', 'c']);
  });
});

describe('M4 trip-list controls (rendered)', () => {
  let VD;
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    window.localStorage.clear();
    await loadDashboard();
    await window.VoltDashboard.ensureMapModule();
    VD = window.VoltDashboard;
  });

  function seed() {
    const base = 1_700_000_000_000;
    const mk = (id, label, favorite, distanceMeters) => ({
      id, sessionId: id, startedAtMs: base, endedAtMs: base + 60_000,
      pointCount: 2, hasRoute: true, distanceMeters, adapterName: 'Adapter', label, favorite,
    });
    VD.state.trips = [
      mk('a', 'Commute home', true, 1000),
      mk('b', 'Costco run', false, 5000),
      mk('c', 'Beach trip', true, 200),
    ];
    VD.state.storage = {
      recentRoutes: [
        { session: { id: 'a', sessionId: 'a', mode: 'drive', adapterName: 'Adapter', startedAtMs: base, endedAtMs: base + 60_000 }, points: [{ lat: 34.05, lng: -118.25 }, { lat: 34.16, lng: -118.32 }], pointCount: 2, distanceMeters: 1000 },
        { session: { id: 'b', sessionId: 'b', mode: 'drive', adapterName: 'Adapter', startedAtMs: base, endedAtMs: base + 60_000 }, points: [{ lat: 34.05, lng: -118.25 }, { lat: 34.16, lng: -118.32 }], pointCount: 2, distanceMeters: 5000 },
        { session: { id: 'c', sessionId: 'c', mode: 'drive', adapterName: 'Adapter', startedAtMs: base, endedAtMs: base + 60_000 }, points: [{ lat: 34.05, lng: -118.25 }, { lat: 34.16, lng: -118.32 }], pointCount: 2, distanceMeters: 200 },
      ],
    };
    VD.renderMap();
  }

  function rowIds() {
    return Array.from(document.querySelectorAll('#mapSessionList [data-map-session]')).map((b) => b.dataset.mapSession);
  }

  it('renders all stored drives by default', () => {
    seed();
    expect(rowIds().sort()).toEqual(['a', 'b', 'c']);
  });

  it('filters the list live as the search input changes', () => {
    seed();
    const search = document.getElementById('mapSessionSearch');
    search.value = 'beach';
    search.dispatchEvent(new window.Event('input', { bubbles: true }));
    expect(rowIds()).toEqual(['c']);
  });

  it('shows a "no match" message when the search filters everything out', () => {
    seed();
    const search = document.getElementById('mapSessionSearch');
    search.value = 'zzzzz';
    search.dispatchEvent(new window.Event('input', { bubbles: true }));
    expect(rowIds()).toEqual([]);
    expect(document.querySelector('#mapSessionList .status-copy').textContent).toContain('No drives match');
  });

  it('reorders by longest distance when the distance sort is chosen', () => {
    seed();
    document.querySelector('[data-map-sort="distance"]').click();
    expect(rowIds()).toEqual(['b', 'a', 'c']);
    expect(document.querySelector('[data-map-sort="distance"]').getAttribute('aria-pressed')).toBe('true');
    expect(document.querySelector('[data-map-sort="recent"]').getAttribute('aria-pressed')).toBe('false');
  });

  it('toggles favorites-only and shows the favorites empty hint when none qualify', () => {
    seed();
    const fav = document.getElementById('mapSessionFavoritesOnly');
    fav.click();
    expect(fav.dataset.on).toBe('true');
    expect(fav.getAttribute('aria-pressed')).toBe('true');
    expect(rowIds().sort()).toEqual(['a', 'c']);

    // Un-toggle restores the full list.
    fav.click();
    expect(fav.dataset.on).toBe('false');
    expect(rowIds().sort()).toEqual(['a', 'b', 'c']);
  });

  it('shows the favorites empty hint when favorites-only matches nothing', () => {
    const base = 1_700_000_000_000;
    VD.state.trips = [{ id: 'b', sessionId: 'b', startedAtMs: base, endedAtMs: base + 60_000, pointCount: 2, hasRoute: true, distanceMeters: 5000, adapterName: 'Adapter', label: 'Costco run', favorite: false }];
    VD.state.storage = {
      recentRoutes: [
        { session: { id: 'b', sessionId: 'b', mode: 'drive', adapterName: 'Adapter', startedAtMs: base, endedAtMs: base + 60_000 }, points: [{ lat: 34.05, lng: -118.25 }, { lat: 34.16, lng: -118.32 }], pointCount: 2, distanceMeters: 5000 },
      ],
    };
    VD.renderMap();
    document.getElementById('mapSessionFavoritesOnly').click();
    expect(rowIds()).toEqual([]);
    expect(document.querySelector('#mapSessionList .status-copy').textContent).toContain('No favorite drives yet');
  });
});
