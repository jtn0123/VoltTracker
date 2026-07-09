// Regression guard for the Insights recompute storm (storage-status.ts#setStorage).
// setStorage fires from setAppState on every app-state broadcast (~1 Hz on a
// live connection). It used to invalidate the trips/insights rollups and
// re-fetch them — rebuilding every Insights chart via replaceChildren — even
// when the storage summary was byte-identical. The fix only reloads when a
// rollup-relevant field actually changes.
import { describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

describe('insights rollup memo (setStorage recompute storm)', () => {
  it('does not reload trips/insights rollups on an unchanged storage broadcast', async () => {
    const getInsights = vi.fn(() => JSON.stringify({ tripCount: 3, totalDistanceMeters: 5200 }));
    const getTrips = vi.fn(() => JSON.stringify({ trips: [] }));
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard({
      bridge: createVoltBridgeFixture({ getInsights, getTrips }),
      extras: ['insights-panel.js'],
    });
    const VD = window.VoltDashboard;
    VD.setView('insights');
    await VD.pendingLazyLoads();
    await flush();

    // Baseline storage triggers a rollup load.
    VD.setStorage({ sampleCount: 10, tripSegmentCount: 3, sessionCount: 1 });
    await flush();
    const insightsBaseline = getInsights.mock.calls.length;
    const tripsBaseline = getTrips.mock.calls.length;
    expect(insightsBaseline).toBeGreaterThan(0);

    // Identical storage broadcast — the rollups must NOT reload (no chart rebuild).
    VD.setStorage({ sampleCount: 10, tripSegmentCount: 3, sessionCount: 1 });
    await flush();
    expect(getInsights.mock.calls.length).toBe(insightsBaseline);
    expect(getTrips.mock.calls.length).toBe(tripsBaseline);

    // A real change (new trip + samples) reloads exactly once more.
    VD.setStorage({ sampleCount: 25, tripSegmentCount: 4, sessionCount: 1 });
    await flush();
    expect(getInsights.mock.calls.length).toBeGreaterThan(insightsBaseline);
  });
});
