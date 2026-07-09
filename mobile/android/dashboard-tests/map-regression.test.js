import { beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

describe('map.ts — route selection regressions', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
    await window.VoltDashboard.ensureMapModule();
  });

  it('repairs a stale selected route id when recentRoutes changes', () => {
    const VD = window.VoltDashboard;
    VD.state.selectedMapSessionId = 'old-route';
    VD.state.storage = {
      recentRoutes: [
        {
          session: { id: 'new-route', startedAtMs: Date.now(), endedAtMs: Date.now() + 60_000 },
          points: [
            { lat: 34.05, lng: -118.25 },
            { lat: 34.16, lng: -118.32 },
          ],
          distanceMeters: 1000,
        },
      ],
    };

    VD.renderMap();

    expect(VD.state.selectedMapSessionId).toBe('new-route');
    expect(document.querySelector('[data-map-session="new-route"]').classList.contains('is-active')).toBe(true);
  });

  it('promotes live GPS samples to a selectable current map session', () => {
    const VD = window.VoltDashboard;
    VD.state.storage = {
      recentRoutes: [
        {
          session: { id: 'history-route', startedAtMs: Date.now() - 86_400_000, endedAtMs: Date.now() - 86_340_000 },
          points: [
            { lat: 34.05, lng: -118.25, atMs: Date.now() - 86_400_000 },
            { lat: 34.16, lng: -118.32, atMs: Date.now() - 86_340_000 },
          ],
          distanceMeters: 1000,
        },
      ],
    };

    VD.updateTelemetry({
      source: 'demo',
      latitude: 34.0501,
      longitude: -118.2501,
      speedKph: 24,
      powerKw: 4,
      soc: 78,
      sampleCount: 1,
      updatedAt: Date.now(),
    });
    VD.updateTelemetry({
      source: 'demo',
      latitude: 34.0512,
      longitude: -118.2514,
      speedKph: 31,
      powerKw: 5,
      soc: 77.9,
      sampleCount: 2,
      updatedAt: Date.now() + 1000,
    });
    VD.renderMap();

    const chips = Array.from(document.querySelectorAll('[data-map-session]'));
    expect(VD.state.selectedMapSessionId).toBe('__live_current__');
    expect(chips[0].dataset.mapSession).toBe('__live_current__');
    expect(chips[0].textContent).toContain('live');

    VD.state.selectedMapSessionId = 'history-route';
    VD.renderMap();
    expect(document.querySelector('[data-map-session="history-route"]').classList.contains('is-active')).toBe(true);

    VD.updateTelemetry({
      source: 'demo',
      latitude: 34.052,
      longitude: -118.252,
      speedKph: 35,
      powerKw: 6,
      soc: 77.8,
      sampleCount: 3,
      updatedAt: Date.now() + 2000,
    });
    expect(VD.state.selectedMapSessionId).toBe('history-route');

    VD.state.selectedMapSessionId = '__live_current__';
    VD.renderMap();
    expect(document.querySelector('[data-map-session="__live_current__"]').classList.contains('is-active')).toBe(true);
  });

  it('shows duration and avg speed for a stored session with both timestamps', () => {
    const VD = window.VoltDashboard;
    const start = Date.now() - 3_600_000;
    VD.state.storage = {
      recentRoutes: [
        {
          session: { id: 'full-route', startedAtMs: start, endedAtMs: start + 600_000 },
          points: [
            { lat: 34.05, lng: -118.25 },
            { lat: 34.16, lng: -118.32 },
          ],
          distanceMeters: 10_000,
        },
      ],
    };

    VD.renderMap();

    expect(document.getElementById('mapDuration').textContent).toBe('10m 00s');
    expect(document.getElementById('mapAvgMph').textContent).not.toBe('--');
  });

  it('renders "--" duration/avg for a crash-ended session missing endedAtMs', () => {
    const VD = window.VoltDashboard;
    // A session whose recording crashed before endedAtMs was written. The old
    // code fell back to Date.now(), showing duration = time-since-the-drive
    // and an average speed of ~0.
    VD.state.storage = {
      recentRoutes: [
        {
          session: { id: 'crashed-route', startedAtMs: Date.now() - 86_400_000 },
          points: [
            { lat: 34.05, lng: -118.25 },
            { lat: 34.16, lng: -118.32 },
          ],
          distanceMeters: 10_000,
        },
      ],
    };

    VD.renderMap();

    expect(document.getElementById('mapDuration').textContent).toBe('--');
    expect(document.getElementById('mapAvgMph').textContent).toBe('--');
    // Distance is still real and still shown.
    expect(document.getElementById('mapDistance').textContent).not.toBe('--');
  });

  it('arms live-follow and exposes the Follow button only for the live drive', () => {
    const VD = window.VoltDashboard;
    VD.state.storage = {
      recentRoutes: [
        {
          session: { id: 'history-route', startedAtMs: Date.now() - 86_400_000, endedAtMs: Date.now() - 86_340_000 },
          points: [
            { lat: 34.05, lng: -118.25, atMs: Date.now() - 86_400_000 },
            { lat: 34.16, lng: -118.32, atMs: Date.now() - 86_340_000 },
          ],
          distanceMeters: 1000,
        },
      ],
    };

    // A fresh live drive selects the live route and arms follow; the button shows.
    VD.updateTelemetry({ source: 'demo', latitude: 34.0501, longitude: -118.2501, sampleCount: 1, updatedAt: Date.now() });
    VD.updateTelemetry({ source: 'demo', latitude: 34.0512, longitude: -118.2514, sampleCount: 2, updatedAt: Date.now() + 1000 });
    VD.renderMap();

    const followBtn = document.getElementById('mapFollowBtn');
    expect(VD.state.selectedMapSessionId).toBe('__live_current__');
    expect(VD.state.mapFollowLive).toBe(true);
    expect(followBtn.hidden).toBe(false);
    expect(followBtn.getAttribute('aria-pressed')).toBe('true');

    // Toggling off (e.g. the user wants to inspect) flips state + button.
    VD.setMapFollowLive(false);
    expect(VD.state.mapFollowLive).toBe(false);
    expect(followBtn.getAttribute('aria-pressed')).toBe('false');

    // Toggling with no argument flips it back on (the "recenter & follow" tap).
    VD.setMapFollowLive();
    expect(VD.state.mapFollowLive).toBe(true);
    expect(followBtn.getAttribute('aria-pressed')).toBe('true');

    // Selecting a historical route hides the live-only Follow button.
    VD.state.selectedMapSessionId = 'history-route';
    VD.renderMap();
    expect(followBtn.hidden).toBe(true);
  });

  it('renders "--" duration for a session missing startedAtMs instead of an epoch-scale span', () => {
    const VD = window.VoltDashboard;
    VD.state.storage = {
      recentRoutes: [
        {
          session: { id: 'no-start-route', endedAtMs: Date.now() },
          points: [
            { lat: 34.05, lng: -118.25 },
            { lat: 34.16, lng: -118.32 },
          ],
          distanceMeters: 10_000,
        },
      ],
    };

    VD.renderMap();

    expect(document.getElementById('mapDuration').textContent).toBe('--');
    expect(document.getElementById('mapAvgMph').textContent).toBe('--');
  });

  it('keeps live-route duration ticking against the current clock', () => {
    const VD = window.VoltDashboard;
    const start = Date.now() - 120_000;
    VD.updateTelemetry({
      source: 'demo',
      latitude: 34.0501,
      longitude: -118.2501,
      speedKph: 24,
      sampleCount: 1,
      updatedAt: start,
    });
    VD.updateTelemetry({
      source: 'demo',
      latitude: 34.0512,
      longitude: -118.2514,
      speedKph: 31,
      sampleCount: 2,
      updatedAt: Date.now(),
    });
    VD.renderMap();

    expect(VD.state.selectedMapSessionId).toBe('__live_current__');
    expect(document.getElementById('mapDuration').textContent).not.toBe('--');
  });

  it('lists older trips beyond recentRoutes as selectable history, deduping overlaps', () => {
    const VD = window.VoltDashboard;
    const now = Date.now();
    const day = 86_400_000;
    // The storage summary only ships detailed geometry for the most recent few
    // drives; the trips rollup reaches back weeks. The map list must include
    // those older trips instead of silently capping history.
    VD.state.storage = {
      recentRoutes: [
        {
          session: {
            id: '12:900:2100',
            sessionId: 12,
            startedAtMs: now - day + 900,
            endedAtMs: now - day + 2100,
          },
          points: [
            { lat: 34.05, lng: -118.25, atMs: now - day + 1000 },
            { lat: 34.16, lng: -118.32, atMs: now - day + 2000 },
          ],
          distanceMeters: 1000,
        },
      ],
    };
    VD.state.trips = [
      // Same drive as the detailed route, but keyed by point-clipped bounds —
      // must dedupe via session + time overlap, not appear twice.
      {
        id: '12:1000:2000',
        sessionId: 12,
        hasRoute: true,
        pointCount: 2,
        startedAtMs: now - day + 1000,
        endedAtMs: now - day + 2000,
        distanceMeters: 1000,
      },
      // A three-week-old drive beyond the recentRoutes window: shows as a stub.
      {
        id: '3:5000:9000',
        sessionId: 3,
        hasRoute: true,
        pointCount: 40,
        startedAtMs: now - 21 * day,
        endedAtMs: now - 21 * day + 600_000,
        distanceMeters: 8000,
        adapterName: 'OBDLink MX+',
      },
      // Route-less trips never reach the map list.
      {
        id: '2:100:200',
        sessionId: 2,
        hasRoute: false,
        pointCount: 0,
        startedAtMs: now - 22 * day,
        endedAtMs: now - 22 * day + 60_000,
      },
    ];

    VD.renderMap();

    const rows = Array.from(document.querySelectorAll('#mapSessionList [data-map-session]'));
    expect(rows.map((row) => row.dataset.mapSession)).toEqual(['12:900:2100', '3:5000:9000']);
  });

  it('fetches full geometry over the bridge when an older trip stub is selected', async () => {
    const now = Date.now();
    const day = 86_400_000;
    const fullRoute = {
      session: {
        id: '3:5000:9000',
        sessionId: 3,
        startedAtMs: now - 21 * day,
        endedAtMs: now - 21 * day + 600_000,
        adapterName: 'OBDLink MX+',
        mode: 'obd',
      },
      points: [
        { lat: 34.05, lng: -118.25, atMs: now - 21 * day },
        { lat: 34.10, lng: -118.32, atMs: now - 21 * day + 300_000 },
        { lat: 34.16, lng: -118.32, atMs: now - 21 * day + 600_000 },
      ],
      pointCount: 3,
      distanceMeters: 8000,
    };
    const getTripRoute = vi.fn(() => JSON.stringify(fullRoute));
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard({ bridge: createVoltBridgeFixture({ getTripRoute }) });
    const VD = window.VoltDashboard;
    await VD.ensureMapModule();
    VD.state.storage = { recentRoutes: [] };
    VD.state.trips = [
      {
        id: '3:5000:9000',
        sessionId: 3,
        hasRoute: true,
        pointCount: 3,
        startedAtMs: now - 21 * day,
        endedAtMs: now - 21 * day + 600_000,
        distanceMeters: 8000,
        adapterName: 'OBDLink MX+',
      },
    ];
    VD.state.selectedMapSessionId = '3:5000:9000';

    VD.renderMap();

    expect(getTripRoute).toHaveBeenCalledWith('3:5000:9000');
    expect(document.getElementById('mapPointBadge').textContent).toBe('3 pts');
    expect(document.getElementById('mapDistance').textContent).not.toBe('--');

    // Re-rendering serves the cached geometry instead of re-reading the bridge.
    VD.renderMap();
    expect(getTripRoute).toHaveBeenCalledTimes(1);
  });

  it('requests full geometry asynchronously when the native bridge supports it', async () => {
    const now = Date.now();
    const day = 86_400_000;
    const routeId = '4:6000:9500';
    const fullRoute = {
      session: {
        id: routeId,
        sessionId: 4,
        startedAtMs: now - 12 * day,
        endedAtMs: now - 12 * day + 700_000,
        adapterName: 'OBDLink MX+',
        mode: 'obd',
      },
      points: [
        { lat: 34.05, lng: -118.25, atMs: now - 12 * day },
        { lat: 34.07, lng: -118.28, atMs: now - 12 * day + 350_000 },
        { lat: 34.10, lng: -118.32, atMs: now - 12 * day + 700_000 },
      ],
      pointCount: 3,
      distanceMeters: 6000,
    };
    const getTripRoute = vi.fn(() => JSON.stringify(fullRoute));
    const requestTripRoute = vi.fn(() => true);
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard({ bridge: createVoltBridgeFixture({ getTripRoute, requestTripRoute }) });
    const VD = window.VoltDashboard;
    await VD.ensureMapModule();
    VD.state.storage = { recentRoutes: [] };
    VD.state.trips = [
      {
        id: routeId,
        sessionId: 4,
        hasRoute: true,
        pointCount: 3,
        startedAtMs: now - 12 * day,
        endedAtMs: now - 12 * day + 700_000,
        distanceMeters: 6000,
        adapterName: 'OBDLink MX+',
      },
    ];
    VD.state.selectedMapSessionId = routeId;

    VD.renderMap();

    expect(requestTripRoute).toHaveBeenCalledWith(routeId);
    expect(getTripRoute).not.toHaveBeenCalled();

    window.VoltTrackerNative.setTripRoute(JSON.stringify({ routeKey: routeId, payload: fullRoute }));

    expect(document.getElementById('mapPointBadge').textContent).toBe('3 pts');
    expect(document.getElementById('mapDistance').textContent).not.toBe('--');
  });

  it('clears pending async route fetches when native returns an error payload', async () => {
    const now = Date.now();
    const routeId = `4:${now}:route-error`;
    const requestTripRoute = vi.fn(() => true);
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard({ bridge: createVoltBridgeFixture({ requestTripRoute }) });
    const VD = window.VoltDashboard;
    await VD.ensureMapModule();
    VD.state.storage = { recentRoutes: [] };
    VD.state.trips = [
      {
        id: routeId,
        sessionId: 4,
        hasRoute: true,
        pointCount: 3,
        startedAtMs: now,
        endedAtMs: now + 700_000,
        distanceMeters: 6000,
        adapterName: 'OBDLink MX+',
      },
    ];
    VD.state.selectedMapSessionId = routeId;

    VD.renderMap();
    expect(requestTripRoute).toHaveBeenCalledTimes(1);

    window.VoltTrackerNative.setTripRoute(
      JSON.stringify({
        routeKey: routeId,
        payload: { ok: false, error: 'native_request_failed', message: 'Could not read local data.' },
      }),
    );
    VD.renderMap();

    expect(requestTripRoute).toHaveBeenCalledTimes(1);
  });

  it('keeps fetched route geometry across a structurally-identical trips reassignment', async () => {
    const now = Date.now();
    const day = 86_400_000;
    const routeId = '7:6000:9500';
    const fullRoute = {
      session: { id: routeId, sessionId: 7, startedAtMs: now - 12 * day, endedAtMs: now - 12 * day + 700_000, mode: 'obd' },
      points: [
        { lat: 34.05, lng: -118.25, atMs: now - 12 * day },
        { lat: 34.07, lng: -118.28, atMs: now - 12 * day + 350_000 },
        { lat: 34.1, lng: -118.32, atMs: now - 12 * day + 700_000 },
      ],
      pointCount: 3,
      distanceMeters: 6000,
    };
    const requestTripRoute = vi.fn(() => true);
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard({ bridge: createVoltBridgeFixture({ requestTripRoute }) });
    const VD = window.VoltDashboard;
    await VD.ensureMapModule();
    VD.state.storage = { recentRoutes: [] };
    const tripStub = {
      id: routeId,
      sessionId: 7,
      hasRoute: true,
      pointCount: 3,
      startedAtMs: now - 12 * day,
      endedAtMs: now - 12 * day + 700_000,
      distanceMeters: 6000,
    };
    VD.state.trips = [tripStub];
    VD.state.selectedMapSessionId = routeId;

    // First render fetches geometry once; resolving it fills the point badge.
    VD.renderMap();
    expect(requestTripRoute).toHaveBeenCalledTimes(1);
    window.VoltTrackerNative.setTripRoute(JSON.stringify({ routeKey: routeId, payload: fullRoute }));
    expect(document.getElementById('mapPointBadge').textContent).toBe('3 pts');

    // A storage broadcast reassigns state.trips to a BRAND-NEW array with the
    // same content (applyTripsPayload does this ~1 Hz). The route cache must NOT
    // be evicted: no re-fetch, and the route stays drawn instead of flashing out
    // to the point-less stub / mapEmpty.
    VD.state.trips = [{ ...tripStub }];
    VD.renderMap();

    expect(requestTripRoute).toHaveBeenCalledTimes(1);
    expect(document.getElementById('mapPointBadge').textContent).toBe('3 pts');
    expect(document.getElementById('mapEmpty').hidden).toBe(true);
  });

  it('surfaces basemap tile failures with a retry affordance', () => {
    const VD = window.VoltDashboard;
    const banner = document.getElementById('mapTileError');
    const copy = document.getElementById('mapTileErrorCopy');

    VD.setMapTileError(true, 'Map tiles are not loading. Routes still work.');

    expect(banner.hidden).toBe(false);
    expect(copy.textContent).toBe('Map tiles are not loading. Routes still work.');

    document.getElementById('mapTileRetryBtn').click();
    expect(banner.hidden).toBe(true);
  });

  it('long-pressing a stored map route marks it as not a trip', async () => {
    vi.useFakeTimers();
    try {
      const markTripNotTrip = vi.fn();
      document.body.innerHTML = '';
      delete window.VoltDashboard;
      delete window.VoltTrackerNative;
      delete window.VoltTrackerAndroid;
      await loadDashboard({ bridge: createVoltBridgeFixture({ markTripNotTrip }) });
      const VD = window.VoltDashboard;
      await VD.ensureMapModule();
      VD.state.storage = {
        recentRoutes: [
          {
            session: { id: '42:1000:2000', startedAtMs: 1000, endedAtMs: 2000 },
            points: [
              { lat: 34.05, lng: -118.25, atMs: 1000 },
              { lat: 34.16, lng: -118.32, atMs: 2000 },
            ],
            distanceMeters: 1000,
          },
        ],
      };
      VD.renderMap();

      document
        .querySelector('[data-map-session="42:1000:2000"]')
        .dispatchEvent(new Event('pointerdown', { bubbles: true }));
      vi.advanceTimersByTime(700);

      expect(markTripNotTrip).toHaveBeenCalledWith('42:1000:2000');
    } finally {
      vi.useRealTimers();
    }
  });

  it('marks a route only once when the WebView contextmenu fires during a long-press', async () => {
    vi.useFakeTimers();
    try {
      const markTripNotTrip = vi.fn();
      document.body.innerHTML = '';
      delete window.VoltDashboard;
      delete window.VoltTrackerNative;
      delete window.VoltTrackerAndroid;
      await loadDashboard({ bridge: createVoltBridgeFixture({ markTripNotTrip }) });
      const VD = window.VoltDashboard;
      await VD.ensureMapModule();
      VD.state.storage = {
        recentRoutes: [
          {
            session: { id: '42:1000:2000', startedAtMs: 1000, endedAtMs: 2000 },
            points: [
              { lat: 34.05, lng: -118.25, atMs: 1000 },
              { lat: 34.16, lng: -118.32, atMs: 2000 },
            ],
            distanceMeters: 1000,
          },
        ],
      };
      VD.renderMap();
      const row = document.querySelector('[data-map-session="42:1000:2000"]');

      // Android WebView fires contextmenu ~500ms into a long-press, before the
      // 650ms fallback timer. The route must be marked exactly once, and the
      // suppressed click must not leak into the next tap.
      row.dispatchEvent(new Event('pointerdown', { bubbles: true }));
      vi.advanceTimersByTime(500);
      row.dispatchEvent(new Event('contextmenu', { bubbles: true, cancelable: true }));
      vi.advanceTimersByTime(400);
      row.dispatchEvent(new Event('pointerup', { bubbles: true }));
      expect(markTripNotTrip).toHaveBeenCalledTimes(1);

      // The next ordinary tap selects the route instead of being swallowed.
      row.dispatchEvent(new Event('pointerdown', { bubbles: true }));
      vi.advanceTimersByTime(50);
      row.dispatchEvent(new Event('pointerup', { bubbles: true }));
      row.dispatchEvent(new Event('click', { bubbles: true, cancelable: true }));
      expect(VD.state.selectedMapSessionId).toBe('42:1000:2000');
      expect(markTripNotTrip).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it('exports a stored route as GPX/CSV via the bridge without selecting the row', async () => {
    const exportTripGpx = vi.fn();
    const exportTripCsv = vi.fn();
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard({ bridge: createVoltBridgeFixture({ exportTripGpx, exportTripCsv }) });
    const VD = window.VoltDashboard;
    await VD.ensureMapModule();
    VD.state.storage = {
      recentRoutes: [
        {
          session: { id: '42:1000:2000', startedAtMs: 1000, endedAtMs: 2000 },
          points: [
            { lat: 34.05, lng: -118.25, atMs: 1000 },
            { lat: 34.16, lng: -118.32, atMs: 2000 },
          ],
          distanceMeters: 1000,
        },
      ],
    };
    VD.renderMap();

    const gpxBtn = document.querySelector('[data-trip-export="gpx"][data-trip-export-key="42:1000:2000"]');
    const csvBtn = document.querySelector('[data-trip-export="csv"][data-trip-export-key="42:1000:2000"]');
    expect(gpxBtn).not.toBeNull();
    expect(csvBtn).not.toBeNull();
    // The export buttons must not themselves be selection targets, so a tap on them can never
    // be read by the row-select handler ([data-map-session] delegation).
    expect(gpxBtn.closest('[data-map-session]')).toBeNull();

    gpxBtn.dispatchEvent(new Event('click', { bubbles: true, cancelable: true }));
    expect(exportTripGpx).toHaveBeenCalledWith('42:1000:2000');
    expect(exportTripCsv).not.toHaveBeenCalled();

    csvBtn.dispatchEvent(new Event('click', { bubbles: true, cancelable: true }));
    expect(exportTripCsv).toHaveBeenCalledWith('42:1000:2000');
  });

  it('clears the basemap warning after tiles recover', async () => {
    const tileHandlers = {};
    const fakeMap = {
      fitBounds: vi.fn(),
      invalidateSize: vi.fn(),
      on: vi.fn(() => fakeMap),
      remove: vi.fn(),
      removeLayer: vi.fn(() => fakeMap),
      setView: vi.fn(() => fakeMap),
    };
    const fakeLayer = {
      addTo: vi.fn(() => fakeLayer),
      bindTooltip: vi.fn(() => fakeLayer),
      on: vi.fn((event, handler) => {
        tileHandlers[event] = handler;
        return fakeLayer;
      }),
    };
    const previousLeaflet = window.L;

    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    window.L = {
      map: vi.fn(() => fakeMap),
      tileLayer: vi.fn(() => fakeLayer),
    };
    try {
      await loadDashboard();

      const VD = window.VoltDashboard;
      await VD.ensureMapModule();
      const banner = document.getElementById('mapTileError');

      VD.ensureMap();
      tileHandlers.tileerror({ tile: { src: 'https://a.basemaps.cartocdn.com/bad.png' } });
      tileHandlers.tileerror({ tile: { src: 'https://b.basemaps.cartocdn.com/bad.png' } });
      expect(banner.hidden).toBe(true);

      tileHandlers.tileerror({ tile: { src: 'https://c.basemaps.cartocdn.com/bad.png' } });
      expect(banner.hidden).toBe(false);

      tileHandlers.tileload({});
      expect(banner.hidden).toBe(true);
    } finally {
      window.L = previousLeaflet;
    }
  });

  it('does not rebuild every Leaflet layer when re-rendering an unchanged static route', async () => {
    const chainable = () => {
      const obj = {
        addTo: vi.fn(() => obj),
        on: vi.fn(() => obj),
        bindTooltip: vi.fn(() => obj),
        setLatLngs: vi.fn(() => obj),
        setLatLng: vi.fn(() => obj),
        addLayer: vi.fn(() => obj),
        removeLayer: vi.fn(() => obj),
        remove: vi.fn(() => obj),
      };
      return obj;
    };
    const fakeMap = {
      setView: vi.fn(() => fakeMap),
      fitBounds: vi.fn(() => fakeMap),
      invalidateSize: vi.fn(() => fakeMap),
      on: vi.fn(() => fakeMap),
      remove: vi.fn(() => fakeMap),
      removeLayer: vi.fn(() => fakeMap),
      addLayer: vi.fn(() => fakeMap),
      hasLayer: vi.fn(() => false),
    };
    const layerGroup = vi.fn(() => chainable());
    const polyline = vi.fn(() => chainable());
    const previousLeaflet = window.L;

    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    window.L = {
      map: vi.fn(() => fakeMap),
      tileLayer: vi.fn(() => chainable()),
      layerGroup,
      polyline,
      circleMarker: vi.fn(() => chainable()),
      marker: vi.fn(() => chainable()),
      divIcon: vi.fn(() => ({})),
      latLngBounds: vi.fn(() => ({})),
    };
    try {
      await loadDashboard();
      const VD = window.VoltDashboard;
      await VD.ensureMapModule();
      // Scrubber marker rides the real Leaflet map; the fake has no marker layer,
      // so isolate the draw path we're measuring.
      VD.renderScrubber = () => {};

      // drawMapRoute bails on a zero-size container; give #mapLeaflet a size so
      // the real Leaflet draw path runs.
      const container = document.getElementById('mapLeaflet');
      Object.defineProperty(container, 'offsetWidth', { value: 320, configurable: true });
      Object.defineProperty(container, 'offsetHeight', { value: 240, configurable: true });

      const routeId = '55:1000:5000';
      const firstRoute = {
        session: { id: routeId, startedAtMs: 1000, endedAtMs: 5000 },
        points: [
          { lat: 34.05, lng: -118.25, atMs: 1000 },
          { lat: 34.08, lng: -118.28, atMs: 3000 },
          { lat: 34.12, lng: -118.31, atMs: 5000 },
        ],
        distanceMeters: 5000,
      };
      VD.state.storage = { recentRoutes: [firstRoute] };
      VD.state.selectedMapSessionId = routeId;

      VD.renderMap();
      const groupsAfterFirst = layerGroup.mock.calls.length;
      const polylinesAfterFirst = polyline.mock.calls.length;
      expect(groupsAfterFirst).toBeGreaterThan(0);

      // A broadcast-driven re-render with identical route data must NOT rebuild
      // the Leaflet layers (rebuilding restarted the animated route-flow overlay
      // and re-ran all the band/stop math ~1 Hz).
      VD.renderMap();
      expect(layerGroup.mock.calls.length).toBe(groupsAfterFirst);
      expect(polyline.mock.calls.length).toBe(polylinesAfterFirst);

      // Selecting a different route (changed id + geometry) must rebuild.
      VD.state.storage = {
        recentRoutes: [
          firstRoute,
          {
            session: { id: '66:1000:5000', startedAtMs: 1000, endedAtMs: 5000 },
            points: [
              { lat: 40.05, lng: -74.25, atMs: 1000 },
              { lat: 40.08, lng: -74.28, atMs: 3000 },
              { lat: 40.12, lng: -74.31, atMs: 5000 },
            ],
            distanceMeters: 5000,
          },
        ],
      };
      VD.state.selectedMapSessionId = '66:1000:5000';
      VD.renderMap();
      expect(layerGroup.mock.calls.length).toBeGreaterThan(groupsAfterFirst);
    } finally {
      window.L = previousLeaflet;
    }
  });
});
