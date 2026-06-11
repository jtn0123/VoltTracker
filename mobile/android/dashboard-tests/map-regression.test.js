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
            { lat: 32.7, lng: -117.1 },
            { lat: 32.8, lng: -117.2 },
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
            { lat: 32.7, lng: -117.1, atMs: Date.now() - 86_400_000 },
            { lat: 32.8, lng: -117.2, atMs: Date.now() - 86_340_000 },
          ],
          distanceMeters: 1000,
        },
      ],
    };

    VD.updateTelemetry({
      source: 'demo',
      latitude: 32.7001,
      longitude: -117.1001,
      speedKph: 24,
      powerKw: 4,
      soc: 78,
      sampleCount: 1,
      updatedAt: Date.now(),
    });
    VD.updateTelemetry({
      source: 'demo',
      latitude: 32.7012,
      longitude: -117.1014,
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
      latitude: 32.702,
      longitude: -117.102,
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
            { lat: 32.7, lng: -117.1 },
            { lat: 32.8, lng: -117.2 },
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
            { lat: 32.7, lng: -117.1 },
            { lat: 32.8, lng: -117.2 },
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

  it('renders "--" duration for a session missing startedAtMs instead of an epoch-scale span', () => {
    const VD = window.VoltDashboard;
    VD.state.storage = {
      recentRoutes: [
        {
          session: { id: 'no-start-route', endedAtMs: Date.now() },
          points: [
            { lat: 32.7, lng: -117.1 },
            { lat: 32.8, lng: -117.2 },
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
      latitude: 32.7001,
      longitude: -117.1001,
      speedKph: 24,
      sampleCount: 1,
      updatedAt: start,
    });
    VD.updateTelemetry({
      source: 'demo',
      latitude: 32.7012,
      longitude: -117.1014,
      speedKph: 31,
      sampleCount: 2,
      updatedAt: Date.now(),
    });
    VD.renderMap();

    expect(VD.state.selectedMapSessionId).toBe('__live_current__');
    expect(document.getElementById('mapDuration').textContent).not.toBe('--');
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
              { lat: 32.7, lng: -117.1, atMs: 1000 },
              { lat: 32.8, lng: -117.2, atMs: 2000 },
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
              { lat: 32.7, lng: -117.1, atMs: 1000 },
              { lat: 32.8, lng: -117.2, atMs: 2000 },
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
});
