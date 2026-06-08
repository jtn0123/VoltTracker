// Regression pin for C3: panels.ts#loadTrips / #loadInsights must surface a
// native read failure through VD.setStatus({state:"blocked"}) (+ logClientError)
// rather than silently rendering the empty state as if the read had succeeded.
//
// storage-error.test.js already asserts the *resulting* VD.state.status after a
// failure; this test pins the exact mechanism C3 names — a direct spy on
// VD.setStatus — and also locks in the converse: a genuinely-empty successful
// read must NOT raise a blocked status. If a future refactor drops the
// reportNativeReadError call, these assertions fail.
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

async function freshLoad(bridge) {
  document.body.innerHTML = '';
  window.localStorage.clear();
  delete window.VoltDashboard;
  delete window.VoltTrackerNative;
  delete window.VoltTrackerAndroid;
  await loadDashboard({ bridge });
  return window.VoltDashboard;
}

const tripsError = JSON.stringify({
  ok: false,
  error: 'trips_read_failed',
  message: 'Could not read logged trips.',
});

const insightsError = JSON.stringify({
  ok: false,
  error: 'insights_read_failed',
  message: 'Could not read vehicle insights.',
});

describe('panels.ts trips/insights error surfacing (C3)', () => {
  it('routes a native getTrips error through setStatus(blocked) + logClientError', async () => {
    const bridge = createVoltBridgeFixture({
      logClientError: vi.fn(),
      getTrips: vi.fn(() => tripsError),
    });
    const VD = await freshLoad(bridge);

    const setStatus = vi.spyOn(VD, 'setStatus');
    const callsBefore = bridge.getTrips.mock.calls.length;
    VD.loadTrips();

    expect(bridge.getTrips).toHaveBeenCalled();
    expect(setStatus).toHaveBeenCalledWith(
      expect.objectContaining({
        state: 'blocked',
        detail: 'Could not read logged trips.',
      })
    );
    expect(bridge.logClientError).toHaveBeenCalledWith(
      'trips_read_failed',
      'Could not read logged trips.'
    );
    // The failed read must not be mistaken for a successful empty result.
    expect(VD.state.trips).toEqual([]);
    expect(VD.state.tripsReadError).toBe('Could not read logged trips.');
    expect(document.getElementById('tripsEmptyState').textContent).toContain('Trips could not load.');
    document.querySelector('[data-retry-trips]').click();
    expect(bridge.getTrips).toHaveBeenCalledTimes(callsBefore + 2);
  });

  it('routes a native getInsights error through setStatus(blocked) + logClientError', async () => {
    const bridge = createVoltBridgeFixture({
      logClientError: vi.fn(),
      getInsights: vi.fn(() => insightsError),
    });
    const VD = await freshLoad(bridge);

    const setStatus = vi.spyOn(VD, 'setStatus');
    const callsBefore = bridge.getInsights.mock.calls.length;
    VD.loadInsights();

    expect(bridge.getInsights).toHaveBeenCalled();
    expect(setStatus).toHaveBeenCalledWith(
      expect.objectContaining({
        state: 'blocked',
        detail: 'Could not read vehicle insights.',
      })
    );
    expect(bridge.logClientError).toHaveBeenCalledWith(
      'insights_read_failed',
      'Could not read vehicle insights.'
    );
    expect(VD.state.insightsReadError).toBe('Could not read vehicle insights.');
    expect(document.getElementById('insightsEmptyState').textContent).toContain('Insights could not load.');
    document.querySelector('[data-retry-insights]').click();
    expect(bridge.getInsights).toHaveBeenCalledTimes(callsBefore + 2);
  });

  it('does NOT raise a blocked status for a genuinely-empty successful read', async () => {
    const bridge = createVoltBridgeFixture({
      logClientError: vi.fn(),
      getTrips: vi.fn(() => '[]'),
      getInsights: vi.fn(() => '{}'),
    });
    const VD = await freshLoad(bridge);

    // Spy after load so we only observe what the loaders raise (the bootstrap
    // path emits its own benign missing-tile warnings through logClientError).
    const setStatus = vi.spyOn(VD, 'setStatus');
    const errorsBefore = bridge.logClientError.mock.calls.length;
    VD.loadTrips();
    VD.loadInsights();

    const blockedCalls = setStatus.mock.calls.filter(
      ([payload]) => payload && payload.state === 'blocked'
    );
    expect(blockedCalls).toEqual([]);
    // No NATIVE-READ error should be logged for a successful empty read.
    const newErrorLabels = bridge.logClientError.mock.calls
      .slice(errorsBefore)
      .map(([label]) => label);
    expect(newErrorLabels).not.toContain('trips_read_failed');
    expect(newErrorLabels).not.toContain('insights_read_failed');
  });
});
