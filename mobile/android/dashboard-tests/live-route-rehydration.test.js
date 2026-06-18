import { beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

// When the WebView is torn down and recreated mid-drive, the live route lives only
// in JS memory and is lost. telemetry.ts#hydrateLiveRouteIfActive pulls the
// in-progress track back from the backend (bridge.getCurrentSessionRoute) on the
// first active status, so the map shows the real drive instead of a blank new run.
describe('live-route rehydration', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
  });

  it('seeds the live route from the active session on the first active status', async () => {
    const getCurrentSessionRoute = vi.fn(() => JSON.stringify({
      session: { id: 7, status: 'active' },
      points: [
        { atMs: 1000, lat: 34.05, lng: -118.25, speedMps: 10 },
        { atMs: 2000, lat: 34.06, lng: -118.26, speedMps: 12, soc: 78 },
      ],
    }));
    await loadDashboard({ bridge: createVoltBridgeFixture({ getCurrentSessionRoute }) });
    const VD = window.VoltDashboard;

    VD.setStatus({ state: 'connected', detail: 'Live OBD data.' });

    expect(getCurrentSessionRoute).toHaveBeenCalledTimes(1);
    expect(VD.state.liveRoutePoints).toHaveLength(2);
    expect(VD.state.liveRoutePoints[0]).toMatchObject({ lat: 34.05, lng: -118.25, atMs: 1000 });
    // speedMps is converted to speedKph for the live point shape.
    expect(VD.state.liveRoutePoints[1].speedKph).toBeCloseTo(12 * 3.6, 5);
    expect(VD.state.selectedMapSessionId).toBe('__live_current__');
    expect(VD.state.liveRouteStartedAtMs).toBe(1000);
  });

  it('requests the live route asynchronously when the native bridge supports it', async () => {
    const getCurrentSessionRoute = vi.fn(() => JSON.stringify({ points: [] }));
    const requestCurrentSessionRoute = vi.fn(() => true);
    await loadDashboard({
      bridge: createVoltBridgeFixture({ getCurrentSessionRoute, requestCurrentSessionRoute }),
    });
    const VD = window.VoltDashboard;

    VD.setStatus({ state: 'connected', detail: 'Live OBD data.' });

    expect(requestCurrentSessionRoute).toHaveBeenCalledTimes(1);
    expect(getCurrentSessionRoute).not.toHaveBeenCalled();

    window.VoltTrackerNative.setCurrentSessionRoute(JSON.stringify({
      session: { id: 8, status: 'active' },
      points: [
        { atMs: 3000, lat: 34.07, lng: -118.28, speedMps: 8 },
        { atMs: 4000, lat: 34.08, lng: -118.29, speedMps: 9, soc: 77 },
      ],
    }));

    expect(VD.state.liveRoutePoints).toHaveLength(2);
    expect(VD.state.liveRoutePoints[0]).toMatchObject({ lat: 34.07, lng: -118.28, atMs: 3000 });
    expect(VD.state.selectedMapSessionId).toBe('__live_current__');
  });

  it('does not query the backend when no session is active', async () => {
    const getCurrentSessionRoute = vi.fn(() => '{}');
    await loadDashboard({ bridge: createVoltBridgeFixture({ getCurrentSessionRoute }) });
    const VD = window.VoltDashboard;

    VD.setStatus({ state: 'idle', detail: 'Disconnected.' });

    expect(getCurrentSessionRoute).not.toHaveBeenCalled();
    expect(VD.state.liveRoutePoints).toHaveLength(0);
  });

  it('only hydrates once per activation even across repeated status pushes', async () => {
    const getCurrentSessionRoute = vi.fn(() => JSON.stringify({ points: [] }));
    await loadDashboard({ bridge: createVoltBridgeFixture({ getCurrentSessionRoute }) });
    const VD = window.VoltDashboard;

    VD.setStatus({ state: 'connected' });
    VD.setStatus({ state: 'scanning' });

    expect(getCurrentSessionRoute).toHaveBeenCalledTimes(1);
  });
});
