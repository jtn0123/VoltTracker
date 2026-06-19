// connection-status.ts — the "last connected" line under the title.
//
// A demo run is not a real adapter connection. The dashboard must never present it as the
// last-connected adapter (it would double up with the live "Demo preview" chip on Drive). New
// demo sessions are no longer written to the summary store, but the line also filters demo-named
// rows defensively so any legacy entry can't leak through.
import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

beforeEach(() => {
  document.body.innerHTML = '';
  delete window.VoltDashboard;
  delete window.VoltTrackerNative;
  delete window.VoltTrackerAndroid;
});

async function loadWithSessions(sessions = []) {
  const bridge = createVoltBridgeFixture({
    getRecentSessions: () => JSON.stringify(sessions),
  });
  await loadDashboard({ bridge, extras: ['connection-status.js'] });
  return window.VoltDashboard;
}

describe('connection-status.ts — last connected line', () => {
  it('shows the most recent real adapter, skipping demo sessions', async () => {
    await loadWithSessions([
      { adapter: 'Demo stream', endMs: 5_000_000, outcome: 'success' },
      { adapter: 'OBDLink MX+ 54242', endMs: 4_000_000, outcome: 'success' },
    ]);

    const badge = document.getElementById('lastConnectedBadge');
    const label = document.getElementById('lastConnectedLabel');
    expect(badge.hidden).toBe(false);
    expect(label.textContent).toBe('OBDLink MX+ 54242');
  });

  it('hides the line entirely when only demo sessions exist', async () => {
    await loadWithSessions([
      { adapter: 'Demo stream', endMs: 5_000_000, outcome: 'success' },
      { adapter: 'demo', endMs: 4_000_000, outcome: 'success' },
    ]);

    expect(document.getElementById('lastConnectedBadge').hidden).toBe(true);
  });

  it('shows a real adapter normally when there is no demo noise', async () => {
    await loadWithSessions([
      { adapter: 'OBDLink MX+ 54242', endMs: 9_000_000, outcome: 'success' },
    ]);

    expect(document.getElementById('lastConnectedBadge').hidden).toBe(false);
    expect(document.getElementById('lastConnectedLabel').textContent).toBe('OBDLink MX+ 54242');
  });

  it('degrades to a hidden badge when the native getRecentSessions call throws', async () => {
    // A native method can throw synchronously across the JS bridge (e.g. a
    // wedged binder). parseSessions wraps the CALL itself, not just the
    // JSON.parse, so the dashboard must boot cleanly and render the
    // empty-list state instead of crashing mid-bootstrap.
    const bridge = createVoltBridgeFixture({
      getRecentSessions: () => {
        throw new Error('binder transaction failed');
      },
    });
    await expect(
      loadDashboard({ bridge, extras: ['connection-status.js'] })
    ).resolves.toBeTruthy();

    expect(document.getElementById('lastConnectedBadge').hidden).toBe(true);
  });

  it('degrades to a hidden badge when the sessions payload is malformed JSON', async () => {
    const bridge = createVoltBridgeFixture({
      getRecentSessions: () => '{not json',
    });
    await loadDashboard({ bridge, extras: ['connection-status.js'] });

    expect(document.getElementById('lastConnectedBadge').hidden).toBe(true);
  });
});

describe('connection-status.ts — status popover', () => {
  function popover() {
    return document.getElementById('statusPopover');
  }

  function badge() {
    return document.getElementById('stateBadge');
  }

  it('opens from the state badge and mirrors the live connection state', async () => {
    const VD = await loadWithSessions([
      { adapter: 'OBDLink MX+ 54242', startMs: 1_000_000, endMs: 4_600_000, outcome: 'success' },
    ]);
    window.VoltTrackerNative.setStatus({
      state: 'connected',
      detail: 'Streaming OBD data.',
      bluetoothReady: true,
    });
    VD.setAppState({
      adapter: { name: 'OBDLink MX+', address: '...44:55', connected: true, remembered: true },
      permissions: { bluetooth: true, bluetoothPermission: true, bluetoothEnabled: true },
      session: { state: 'connected', sampleCount: 1234, sessionMs: 95_000 },
      gps: { state: 'locked' },
    });
    VD.state.sessionDistanceM = 5000;
    VD.state.sessionStartSoc = 82;
    VD.state.telemetry.soc = 76;

    badge().click();

    expect(popover().hidden).toBe(false);
    expect(badge().getAttribute('aria-expanded')).toBe('true');
    expect(document.getElementById('statusPopoverState').dataset.state).toBe('connected');
    expect(document.getElementById('statusPopoverStateText').textContent).toBe('connected');
    expect(document.getElementById('statusPopoverDetail').textContent).toBe('Streaming OBD data.');
    const connection = document.getElementById('statusPopoverConnection').textContent;
    expect(connection).toContain('OBDLink MX+ (...44:55)');
    expect(connection).toContain('Ready');
    expect(connection).toContain('1,234 samples');
    expect(connection).toContain('locked');
    const trip = document.getElementById('statusPopoverTrip').textContent;
    expect(trip).toMatch(/mi|km/);
    expect(trip).toContain('82% → 76%');
    expect(trip).toContain('1,234');
  });

  it('explains a missing Bluetooth permission and shows the last trip when idle', async () => {
    const VD = await loadWithSessions([
      { adapter: 'OBDLink MX+ 54242', startMs: 1_000_000, endMs: 4_600_000, outcome: 'success' },
    ]);
    window.VoltTrackerNative.setStatus({ state: 'idle', detail: 'Viewing local data.', bluetoothReady: false });
    VD.setAppState({
      permissions: { bluetooth: false, bluetoothPermission: false, bluetoothEnabled: true },
      session: { state: 'idle' },
    });

    badge().click();

    const connection = document.getElementById('statusPopoverConnection').textContent;
    expect(connection).toContain('Permission needed');
    expect(connection).toContain('Not logging');
    const trip = document.getElementById('statusPopoverTrip').textContent;
    expect(trip).toContain('Last trip');
    expect(trip).toContain('1h');
  });

  it('toggles closed from the badge, outside taps, and Escape', async () => {
    await loadWithSessions();

    badge().click();
    expect(popover().hidden).toBe(false);
    badge().click();
    expect(popover().hidden).toBe(true);
    expect(badge().getAttribute('aria-expanded')).toBe('false');

    badge().click();
    document.getElementById('screenTitle').click();
    expect(popover().hidden).toBe(true);

    badge().click();
    document.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    expect(popover().hidden).toBe(true);
  });

  it('Android Back closes an open popover before falling through', async () => {
    const VD = await loadWithSessions();

    badge().click();
    expect(VD.handleAndroidBack()).toBe(true);
    expect(popover().hidden).toBe(true);
    // Nothing left to dismiss on the home tab: Back falls through to the OS.
    expect(VD.handleAndroidBack()).toBe(false);
  });

  it('opens from the last-connected line too', async () => {
    await loadWithSessions([
      { adapter: 'OBDLink MX+ 54242', endMs: 9_000_000, outcome: 'success' },
    ]);

    document.getElementById('lastConnectedBadge').click();

    expect(popover().hidden).toBe(false);
  });

  it('re-renders an open popover when a new status broadcast lands', async () => {
    await loadWithSessions();

    badge().click();
    window.VoltTrackerNative.setStatus({ state: 'connecting', detail: 'Connecting to OBDLink...', bluetoothReady: true });

    expect(document.getElementById('statusPopoverStateText').textContent).toBe('connecting');
    expect(document.getElementById('statusPopoverDetail').textContent).toBe('Connecting to OBDLink...');
  });

  it('shows persistence queue health when writes are dropped or fail', async () => {
    await loadWithSessions();
    window.VoltTrackerNative.setStatus({
      state: 'connected',
      persistenceDroppedTelemetryCount: 12,
      persistenceFailedTelemetryCount: 2,
    });

    badge().click();

    const connection = document.getElementById('statusPopoverConnection').textContent;
    expect(connection).toContain('Persistence');
    expect(connection).toContain('12 queued writes dropped');
    expect(connection).toContain('2 writes failed');
  });
});
