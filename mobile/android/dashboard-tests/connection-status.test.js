// connection-status.js — the "last connected" line under the title.
//
// A demo run is not a real adapter connection. The dashboard must never present it as the
// last-connected adapter (it would double up with the live "Demo preview" chip on Drive). New
// demo sessions are no longer written to the summary store, but the line also filters demo-named
// rows defensively so any legacy entry can't leak through.
import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

describe('connection-status.js — last connected line', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
  });

  async function loadWithSessions(sessions) {
    const bridge = createVoltBridgeFixture({
      getRecentSessions: () => JSON.stringify(sessions),
    });
    await loadDashboard({ bridge, extras: ['connection-status.js'] });
  }

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
});
