import { beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

function trip(index) {
  return {
    id: `trip-${index}`,
    sessionId: index,
    startedAtMs: index * 1000,
    endedAtMs: index * 1000 + 500,
    distanceMeters: 1000,
    pointCount: 2,
    hasRoute: true,
  };
}

describe('All drives progressive history loading', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
  });

  it('loads older pages after the bounded initial list until the final short page', async () => {
    const initial = Array.from({ length: 120 }, (_, i) => trip(300 - i));
    const older = Array.from({ length: 120 }, (_, i) => trip(180 - i));
    const oldest = Array.from({ length: 60 }, (_, i) => trip(60 - i));
    const getTripsPage = vi.fn((offset) => JSON.stringify(offset === 120 ? older : offset === 240 ? oldest : []));
    await loadDashboard({
      bridge: createVoltBridgeFixture({ getTrips: () => JSON.stringify(initial), getTripsPage }),
      extras: ['insights-panel.js'],
    });
    window.VoltDashboard.loadTrips(true);

    window.VoltDashboard.loadAllTrips();
    await vi.waitFor(() => expect(window.VoltDashboard.state.trips).toHaveLength(300));

    expect(getTripsPage.mock.calls.map(([offset]) => offset)).toEqual([120, 240]);
    expect(new Set(window.VoltDashboard.state.trips.map((item) => item.id)).size).toBe(300);
  });
});
