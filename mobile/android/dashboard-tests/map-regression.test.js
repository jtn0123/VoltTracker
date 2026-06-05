import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

describe('map.ts — route selection regressions', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
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
});
