import { beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

async function freshLoad(bridge) {
  document.body.innerHTML = '';
  window.localStorage.clear();
  delete window.VoltDashboard;
  delete window.VoltTrackerNative;
  delete window.VoltTrackerAndroid;
  await loadDashboard({ bridge, extras: ['insights-panel.js'] });
  return window.VoltDashboard;
}

const summaryError = JSON.stringify({
  ok: false,
  error: 'storage_summary_failed',
  message: 'Could not read local storage summary.',
});

describe('dashboard native read errors', () => {
  let bridge;

  beforeEach(() => {
    bridge = createVoltBridgeFixture({
      logClientError: vi.fn(),
      getTrips: vi.fn(() => JSON.stringify({
        ok: false,
        error: 'trips_read_failed',
        message: 'Could not read logged trips.',
      })),
      getInsights: vi.fn(() => JSON.stringify({
        ok: false,
        error: 'insights_read_failed',
        message: 'Could not read vehicle insights.',
      })),
    });
  });

  it('surfaces storage summary failures instead of treating them as empty data', async () => {
    const VD = await freshLoad(bridge);

    VD.setStorage(summaryError);

    expect(VD.state.status).toMatchObject({
      state: 'blocked',
      detail: 'Could not read local storage summary.',
    });
    expect(VD.state.storage).toMatchObject({ error: 'storage_summary_failed' });
    expect(bridge.logClientError).toHaveBeenCalledWith(
      'storage_summary_failed',
      'Could not read local storage summary.'
    );
  });

  it('surfaces trips and insights read failures while keeping UI state safe', async () => {
    const VD = await freshLoad(bridge);

    VD.loadTrips();
    VD.loadInsights();

    expect(VD.state.trips).toEqual([]);
    expect(VD.state.insights).toEqual({});
    expect(VD.state.status).toMatchObject({
      state: 'blocked',
      detail: 'Could not read vehicle insights.',
    });
    expect(bridge.logClientError).toHaveBeenCalledWith(
      'trips_read_failed',
      'Could not read logged trips.'
    );
    expect(bridge.logClientError).toHaveBeenCalledWith(
      'insights_read_failed',
      'Could not read vehicle insights.'
    );
  });
});
