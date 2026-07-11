import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

// E1 map-tile privacy disclosure: the first time the Map renders on an
// install, a dismissible notice states that basemap imagery is fetched from
// OpenStreetMap/CARTO and that those servers see the approximate viewed area
// (tile coordinates). "Got it" persists the dismissal through prefs
// (vt.pref.mapTilePrivacyNoticeDismissed in localStorage) so the notice never
// returns — including across a WebView teardown/reload.
const DISMISSED_KEY = 'vt.pref.mapTilePrivacyNoticeDismissed';

function seedRoute(VD) {
  VD.state.storage = {
    recentRoutes: [
      {
        session: { id: 'route-1', startedAtMs: Date.now() - 600_000, endedAtMs: Date.now() },
        points: [
          { lat: 34.05, lng: -118.25 },
          { lat: 34.16, lng: -118.32 },
        ],
        distanceMeters: 1000,
      },
    ],
  };
}

describe('map.ts — map-tile privacy notice (E1)', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    window.localStorage.clear();
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
    await window.VoltDashboard.ensureMapModule();
  });

  it('shows the disclosure on the first map render', () => {
    const VD = window.VoltDashboard;
    seedRoute(VD);
    const notice = document.getElementById('mapPrivacyNotice');
    expect(notice.hidden).toBe(true);

    VD.renderMap();

    expect(notice.hidden).toBe(false);
    const copy = document.getElementById('mapPrivacyNoticeCopy').textContent;
    expect(copy).toContain('OpenStreetMap/CARTO');
    expect(copy).toContain('approximate area');
    expect(document.getElementById('mapPrivacyGotItBtn').textContent).toBe('Got it');
  });

  it('also shows when the map renders with no routes yet', () => {
    const VD = window.VoltDashboard;
    VD.state.storage = { recentRoutes: [] };

    VD.renderMap();

    expect(document.getElementById('mapPrivacyNotice').hidden).toBe(false);
  });

  it('"Got it" hides the notice, persists the pref, and later renders keep it hidden', () => {
    const VD = window.VoltDashboard;
    seedRoute(VD);
    VD.renderMap();
    const notice = document.getElementById('mapPrivacyNotice');
    expect(notice.hidden).toBe(false);

    document.getElementById('mapPrivacyGotItBtn').click();

    expect(notice.hidden).toBe(true);
    expect(window.localStorage.getItem(DISMISSED_KEY)).toBe('true');

    VD.renderMap();
    expect(notice.hidden).toBe(true);
  });

  it('a dismissal recorded before load keeps the notice hidden in a fresh session', async () => {
    // Simulate an install where the user already tapped "Got it": the pref is
    // in localStorage before the dashboard (and the lazy map chunk) boots.
    document.body.innerHTML = '';
    window.localStorage.clear();
    window.localStorage.setItem(DISMISSED_KEY, 'true');
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
    await window.VoltDashboard.ensureMapModule();

    const VD = window.VoltDashboard;
    seedRoute(VD);
    VD.renderMap();

    expect(document.getElementById('mapPrivacyNotice').hidden).toBe(true);
  });
});
