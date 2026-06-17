// storage-status.ts — updateStorageUi counter edge cases.
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

describe('storage summary raw-telemetry counter', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  function rawCount() {
    return document.getElementById('dbRawTelemetryCount').textContent;
  }

  it('renders a legitimate rawTelemetryCount of 0 instead of falling back to sampleCount', () => {
    window.VoltDashboard.setStorage({ sampleCount: 50, rawTelemetryCount: 0 });
    expect(rawCount()).toBe('0');
  });

  it('falls back to sampleCount only when rawTelemetryCount is missing', () => {
    window.VoltDashboard.setStorage({ sampleCount: 50 });
    expect(rawCount()).toBe('50');

    window.VoltDashboard.setStorage({ sampleCount: 50, rawTelemetryCount: null });
    expect(rawCount()).toBe('50');

    window.VoltDashboard.setStorage({ sampleCount: 50, rawTelemetryCount: 7 });
    expect(rawCount()).toBe('7');
  });
});

describe('storage summary lazy details', () => {
  async function flushDeferredStorageReads() {
    await new Promise((resolve) => setTimeout(resolve, 0));
    await new Promise((resolve) => setTimeout(resolve, 0));
  }

  beforeEach(async () => {
    vi.restoreAllMocks();
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
  });

  it('defers heavy storage details and patch-merges them into the overview', async () => {
    const bridge = createVoltBridgeFixture({
      getStorageSummary: vi.fn(() => '{"sessionCount":1,"sampleCount":2,"recentSessions":[]}'),
      getStorageDetails: vi.fn(() =>
        JSON.stringify({
          storageDetails: true,
          chargeSummary: { chargeSessionCount: 2, recentSessions: [] },
          batterySummary: { snapshotCount: 1 },
          recentRoutes: [{ id: "drive-1", pointCount: 3 }]
        }),
      ),
    });
    await loadDashboard({ bridge });
    await flushDeferredStorageReads();
    bridge.getStorageDetails.mockClear();

    window.VoltDashboard.setStorage({ sessionCount: 4, sampleCount: 9, recentSessions: [] });
    expect(bridge.getStorageDetails).not.toHaveBeenCalled();
    expect(window.VoltDashboard.state.storage.sessionCount).toBe(4);
    expect(window.VoltDashboard.state.storage.chargeSummary).toBeUndefined();

    await flushDeferredStorageReads();

    expect(bridge.getStorageDetails).toHaveBeenCalledTimes(1);
    expect(window.VoltDashboard.state.storage.sessionCount).toBe(4);
    expect(window.VoltDashboard.state.storage.sampleCount).toBe(9);
    expect(window.VoltDashboard.state.storage.chargeSummary.chargeSessionCount).toBe(2);
    expect(window.VoltDashboard.state.storage.batterySummary.snapshotCount).toBe(1);
    expect(window.VoltDashboard.state.storage.recentRoutes).toHaveLength(1);
    expect(window.VoltDashboard.state.storage.storageDetails).toBeUndefined();
  });

  it('demand-loads trip and insight rollups when their views open', async () => {
    const bridge = createVoltBridgeFixture({
      getStorageSummary: vi.fn(() => '{"sessionCount":1,"sampleCount":2,"recentSessions":[]}'),
      getTrips: vi.fn(() => '[]'),
      getInsights: vi.fn(() => '{}'),
    });
    await loadDashboard({ bridge });
    await flushDeferredStorageReads();

    expect(bridge.getTrips).not.toHaveBeenCalled();
    expect(bridge.getInsights).not.toHaveBeenCalled();

    window.VoltDashboard.setView('map');
    await window.VoltDashboard.pendingLazyLoads();
    expect(bridge.getTrips).toHaveBeenCalledTimes(1);
    expect(bridge.getInsights).not.toHaveBeenCalled();

    window.VoltDashboard.setView('insights');
    await window.VoltDashboard.pendingLazyLoads();
    expect(bridge.getTrips).toHaveBeenCalledTimes(1);
    expect(bridge.getInsights).toHaveBeenCalledTimes(1);
  });
});
