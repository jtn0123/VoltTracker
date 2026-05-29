// trips.js/panels.js — real trip row rendering.
import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

describe('panels.js — trip route rows', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  it('renders map-backed route preview slots for route-bearing real trips', () => {
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
      hasRoute: true,
    }];

    VD.renderRealTrips();

    const row = document.querySelector('#realTripsList .real-trip-chip');
    expect(row.classList.contains('has-route-preview')).toBe(true);
    expect(row.querySelector('[data-real-trip-map-role="mini"]')).not.toBeNull();
    expect(row.querySelector('.trip-route-state').textContent).toBe('GPS pts');
    expect(document.querySelector('#realTripRouteBox [data-real-trip-map-role="detail"]')).not.toBeNull();
    expect(document.getElementById('realTripMapBtn').dataset.tripMap).toBe('42');
    expect(document.getElementById('realTripRouteBox').dataset.tripMap).toBe('42');
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
