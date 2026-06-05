// panels.ts — real trip row rendering.
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

describe('panels.ts — trip route rows', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  it('keeps chips compact (no in-chip map) and renders the route in the large detail preview', () => {
    const VD = window.VoltDashboard;
    VD.state.storage = {
      recentRoutes: [{
        session: { id: 42 },
        points: [
          { lat: 32.78, lng: -117.16 },
          { lat: 32.80, lng: -117.13 },
          { lat: 32.82, lng: -117.10 },
        ],
      }],
    };
    VD.state.trips = [{
      id: 42,
      startedAtMs: Date.now(),
      durationMs: 900_000,
      distanceMeters: 4200,
      maxSpeedKph: 82,
      sampleCount: 91,
      pointCount: 3,
      hasRoute: true,
    }];

    VD.renderRealTrips();

    const row = document.querySelector('#realTripsList .real-trip-chip');
    expect(row.classList.contains('has-route-preview')).toBe(true);
    // The chip is compact: no embedded mini-map (those rendered empty); the badge is clearly
    // labelled rather than the old cryptic "Nx".
    expect(row.querySelector('[data-real-trip-map-role="mini"]')).toBeNull();
    expect(row.querySelector('.trip-route-state').textContent).toBe('3 pts');
    // The route renders in the large detail preview for the selected trip.
    expect(document.querySelector('#realTripRouteBox [data-real-trip-map-role="detail"]')).not.toBeNull();
    expect(document.getElementById('realTripMapBtn').dataset.tripMap).toBe('42');
    expect(document.getElementById('realTripRouteBox').dataset.tripMap).toBe('42');
  });

  it('shows a routeless trip badge as labelled samples, not a cryptic code', () => {
    const VD = window.VoltDashboard;
    VD.state.storage = { recentRoutes: [] };
    VD.state.trips = [{
      id: 7,
      startedAtMs: Date.now(),
      durationMs: 0,
      distanceMeters: 0,
      sampleCount: 660,
      hasRoute: false,
    }];

    VD.renderRealTrips();

    const badge = document.querySelector('#realTripsList .real-trip-chip .trip-route-state');
    expect(badge.textContent).toBe('660 samples');
  });

  it('loads a route on demand for a drive outside the recent-routes window', async () => {
    // A logged drive whose route isn't in storage.recentRoutes (e.g. an older drive, or one
    // folded in from a merged backup) must fetch its route via the bridge and render the preview.
    const getTripRoute = vi.fn(() => JSON.stringify({
      session: { id: 77 },
      points: [
        { lat: 32.70, lng: -117.16 },
        { lat: 32.74, lng: -117.12 },
        { lat: 32.78, lng: -117.08 },
      ],
      pointCount: 3,
      distanceMeters: 5000,
    }));
    await loadDashboard({ bridge: createVoltBridgeFixture({ getTripRoute }) });
    const VD = window.VoltDashboard;
    VD.state.storage = { recentRoutes: [] }; // route NOT pre-loaded
    VD.state.trips = [{
      id: 77,
      startedAtMs: Date.now(),
      durationMs: 600_000,
      distanceMeters: 5000,
      sampleCount: 400,
      pointCount: 3,
      hasRoute: true,
    }];

    VD.renderRealTrips();

    expect(getTripRoute).toHaveBeenCalledWith('77');
    expect(document.querySelector('#realTripRouteBox [data-real-trip-map-role="detail"]')).not.toBeNull();
    expect(document.getElementById('realTripRouteBox').dataset.tripMap).toBe('77');
    // Fetched once and cached — re-rendering must not hit the bridge again.
    VD.renderRealTrips();
    expect(getTripRoute).toHaveBeenCalledTimes(1);
  });

  it('retries an on-demand route miss instead of caching null forever', async () => {
    const getTripRoute = vi
      .fn()
      .mockReturnValueOnce(JSON.stringify({ session: { id: 78 }, points: [] }))
      .mockReturnValueOnce(JSON.stringify({
        session: { id: 78 },
        points: [
          { lat: 32.70, lng: -117.16 },
          { lat: 32.75, lng: -117.10 },
        ],
        pointCount: 2,
        distanceMeters: 4200,
      }));
    await loadDashboard({ bridge: createVoltBridgeFixture({ getTripRoute }) });
    const VD = window.VoltDashboard;
    VD.state.storage = { recentRoutes: [] };
    VD.state.trips = [{
      id: 78,
      startedAtMs: Date.now(),
      durationMs: 500_000,
      distanceMeters: 4200,
      sampleCount: 200,
      pointCount: 2,
      hasRoute: true,
    }];

    VD.renderRealTrips();
    expect(getTripRoute).toHaveBeenCalledTimes(1);

    VD.renderRealTrips();
    expect(getTripRoute).toHaveBeenCalledTimes(2);
    expect(document.getElementById('realTripRouteBox').dataset.tripMap).toBe('78');
  });

  it('waits for enough power samples before marking route efficiency enriched', () => {
    const VD = window.VoltDashboard;
    const route = {
      points: [
        { atMs: 1, lat: 32.70, lng: -117.16, speedMps: 10 },
        { atMs: 2, lat: 32.71, lng: -117.15, speedMps: 10 },
      ],
      powerTrack: [],
    };

    VD.enrichRouteEff(route);
    expect(route._effDone).toBeUndefined();

    route.powerTrack = [
      { atMs: 1, powerKw: 8 },
      { atMs: 2, powerKw: 9 },
    ];
    VD.enrichRouteEff(route);

    expect(route._effDone).toBe(true);
    expect(route.points.some((point) => Number.isFinite(point.eff))).toBe(true);
  });

  it('uses split trip keys when loading and opening a route slice on the map', async () => {
    const splitId = '29:1780437013000:1780438133000';
    const getTripRoute = vi.fn(() => JSON.stringify({
      session: { id: splitId, sessionId: 29, startedAtMs: 1780437013000, endedAtMs: 1780438133000 },
      points: [
        { atMs: 1780437013000, lat: 32.70, lng: -117.16 },
        { atMs: 1780437600000, lat: 32.71, lng: -117.14 },
        { atMs: 1780438133000, lat: 32.72, lng: -117.12 },
      ],
      pointCount: 3,
      distanceMeters: 3200,
    }));
    await loadDashboard({ bridge: createVoltBridgeFixture({ getTripRoute }) });
    const VD = window.VoltDashboard;
    VD.state.storage = { recentRoutes: [] };
    VD.state.trips = [{
      id: splitId,
      sessionId: 29,
      segmentIndex: 1,
      startedAtMs: 1780437013000,
      durationMs: 1_120_000,
      distanceMeters: 3200,
      sampleCount: 320,
      pointCount: 3,
      hasRoute: true,
    }];

    VD.renderRealTrips();

    expect(getTripRoute).toHaveBeenCalledWith(splitId);
    expect(document.getElementById('realTripRouteBox').dataset.tripMap).toBe(splitId);
    VD.state.storage.recentRoutes = [{ session: { id: splitId }, points: [{ lat: 0, lng: 0 }] }];
    document.getElementById('realTripMapBtn').click();
    expect(VD.state.selectedMapSessionId).toBe(splitId);
    expect(VD.state.storage.recentRoutes[0].session.id).toBe(splitId);
    expect(VD.state.storage.recentRoutes).toHaveLength(1);
  });

  it('explains a GPS-less drive and offers to enable location', async () => {
    const requestPermissions = vi.fn();
    await loadDashboard({ bridge: createVoltBridgeFixture({ requestPermissions }) });
    const VD = window.VoltDashboard;
    VD.state.appState = { permissions: { location: false } }; // location currently denied
    VD.state.storage = { recentRoutes: [] };
    VD.state.trips = [{
      id: 9,
      startedAtMs: Date.now(),
      durationMs: 1_200_000,
      distanceMeters: 0,
      sampleCount: 660,
      hasRoute: false,
    }];

    VD.renderRealTrips();

    const box = document.getElementById('realTripRouteBox');
    expect(box.textContent).toContain('No GPS recorded for this drive');
    expect(box.textContent).toContain('Location was off');
    const cta = box.querySelector('.route-empty-cta');
    expect(cta).not.toBeNull();
    expect(cta.textContent).toBe('Enable location');
    cta.click();
    expect(requestPermissions).toHaveBeenCalledTimes(1);
  });

  it('omits the enable-location CTA once location is granted', async () => {
    await loadDashboard();
    const VD = window.VoltDashboard;
    VD.state.appState = { permissions: { location: true } };
    VD.state.storage = { recentRoutes: [] };
    VD.state.trips = [{
      id: 11,
      startedAtMs: Date.now(),
      durationMs: 1_000_000,
      distanceMeters: 0,
      sampleCount: 120,
      hasRoute: false,
    }];

    VD.renderRealTrips();

    const box = document.getElementById('realTripRouteBox');
    expect(box.textContent).toContain('No GPS recorded for this drive');
    expect(box.querySelector('.route-empty-cta')).toBeNull();
  });

  it('renders no summary bar for a "--" metric and a proportional bar for real values', () => {
    const VD = window.VoltDashboard;
    VD.state.storage = { recentRoutes: [] };
    // Two drives so the bars scale against the longest. The selected drive (id 1) has the max
    // distance (full bar); its duration is half the longest (~half bar). A drive with no distance
    // must render a "--" value with an EMPTY bar, not a misleading full one.
    VD.state.trips = [
      {
        id: 1, startedAtMs: Date.now(), durationMs: 600_000,
        distanceMeters: 8000, sampleCount: 500, hasRoute: false,
      },
      {
        id: 2, startedAtMs: Date.now() + 1, durationMs: 1_200_000,
        distanceMeters: 0, sampleCount: 100, hasRoute: false,
      },
    ];

    VD.selectRealTrip(1);
    const rows = document.getElementById('realTripEnergyRows');
    const distanceRow = rows.children[0];
    const durationRow = rows.children[1];
    // Distance: this drive is the longest → full bar, with a real value.
    expect(distanceRow.querySelector('b').textContent).not.toBe('--');
    expect(distanceRow.querySelector('i').style.width).toBe('100%');
    // Duration: 600k of a 1.2M max → ~50% bar.
    expect(durationRow.querySelector('i').style.width).toBe('50%');

    // Now the distance-less drive: "--" value and an empty (0%) bar — the bug we fixed.
    VD.selectRealTrip(2);
    const distanceRow2 = document.getElementById('realTripEnergyRows').children[0];
    expect(distanceRow2.querySelector('b').textContent).toBe('--');
    expect(distanceRow2.querySelector('i').style.width).toBe('0%');
  });

  it('keeps trip strip nodes mounted when selecting another trip', () => {
    const VD = window.VoltDashboard;
    VD.state.storage = {
      recentRoutes: [
        {
          session: { id: 42 },
          points: [
            { lat: 32.78, lng: -117.16 },
            { lat: 32.80, lng: -117.13 },
          ],
        },
        {
          session: { id: 43 },
          points: [
            { lat: 32.81, lng: -117.20 },
            { lat: 32.83, lng: -117.18 },
          ],
        },
      ],
    };
    VD.state.trips = [
      {
        id: 42,
        startedAtMs: Date.now(),
        durationMs: 900_000,
        distanceMeters: 4200,
        sampleCount: 91,
        pointCount: 2,
        hasRoute: true,
      },
      {
        id: 43,
        startedAtMs: Date.now() + 1,
        durationMs: 800_000,
        distanceMeters: 3100,
        sampleCount: 88,
        pointCount: 2,
        hasRoute: true,
      },
    ];

    VD.renderRealTrips();
    const beforeRows = [...document.querySelectorAll('#realTripsList .real-trip-chip')];
    const beforeMapSlots = [...document.querySelectorAll('#realTripsList [data-real-trip-map-role="mini"]')];

    VD.selectRealTrip(43);

    const afterRows = [...document.querySelectorAll('#realTripsList .real-trip-chip')];
    const afterMapSlots = [...document.querySelectorAll('#realTripsList [data-real-trip-map-role="mini"]')];
    expect(afterRows).toEqual(beforeRows);
    expect(afterMapSlots).toEqual(beforeMapSlots);
    expect(document.querySelector('#realTripsList .real-trip-chip.is-active').dataset.realTripId).toBe('43');
    expect(document.getElementById('realTripRouteBox').dataset.tripMap).toBe('43');
  });
});
