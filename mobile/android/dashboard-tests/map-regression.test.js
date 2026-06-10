import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

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
});
