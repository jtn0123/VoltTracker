// trip-labels-maintenance.test.js — M4 (trip labels in the map session list) and
// M5 (the real maintenance log) dashboard rendering, including the DOM XSS-safety
// of the label/note paths.
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

describe('M4 — trip labels in the map session list', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
    await window.VoltDashboard.ensureMapModule();
  });

  function seedRouteWithTrips(VD, label) {
    const startedAtMs = 1_700_000_000_000;
    const endedAtMs = startedAtMs + 60_000;
    const id = `7:${startedAtMs}:${endedAtMs}`;
    VD.state.trips = [
      {
        id,
        sessionId: 7,
        startedAtMs,
        endedAtMs,
        pointCount: 2,
        hasRoute: true,
        distanceMeters: 1000,
        adapterName: 'Adapter X',
        label,
      },
    ];
    VD.state.storage = {
      recentRoutes: [
        {
          session: { id, sessionId: 7, mode: 'drive', adapterName: 'Adapter X', startedAtMs, endedAtMs },
          points: [
            { lat: 32.7, lng: -117.1 },
            { lat: 32.8, lng: -117.2 },
          ],
          pointCount: 2,
          distanceMeters: 1000,
        },
      ],
    };
    return id;
  }

  it('renders the user label as the row title when set', () => {
    const VD = window.VoltDashboard;
    seedRouteWithTrips(VD, 'Commute home');
    VD.renderMap();

    const row = document.querySelector('#mapSessionList [data-map-session]');
    expect(row).toBeTruthy();
    const title = row.querySelector('strong').textContent;
    expect(title).toBe('Commute home');
    // The mode/adapter line drops to a subtitle.
    expect(row.querySelector('.history-sub').textContent).toContain('Adapter X');
  });

  it('falls back to the mode/adapter title when no label is set', () => {
    const VD = window.VoltDashboard;
    seedRouteWithTrips(VD, '');
    VD.renderMap();

    const row = document.querySelector('#mapSessionList [data-map-session]');
    expect(row.querySelector('strong').textContent).toContain('Adapter X');
    expect(row.querySelector('.history-sub')).toBeNull();
  });

  it('offers a Rename affordance carrying the route key + current label', () => {
    const VD = window.VoltDashboard;
    const id = seedRouteWithTrips(VD, 'Road trip');
    VD.renderMap();

    const renameBtn = document.querySelector('#mapSessionList [data-trip-rename]');
    expect(renameBtn).toBeTruthy();
    expect(renameBtn.dataset.tripRename).toBe(id);
    expect(renameBtn.dataset.tripRenameLabel).toBe('Road trip');
    expect(renameBtn.textContent).toBe('Rename');
  });

  it('escapes a hostile label so it cannot inject markup (DOM XSS-safe)', () => {
    const VD = window.VoltDashboard;
    seedRouteWithTrips(VD, '<img src=x onerror=alert(1)>');
    VD.renderMap();

    const list = document.getElementById('mapSessionList');
    // The malicious string lands as text, never as a real <img> element.
    expect(list.querySelector('img')).toBeNull();
    expect(list.querySelector('strong').textContent).toContain('<img src=x onerror=alert(1)>');
  });

  it('forwards a rename to bridge.setTripLabel with the prompted value', async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    const setTripLabel = vi.fn();
    await loadDashboard({ bridge: createVoltBridgeFixture({ setTripLabel }) });
    await window.VoltDashboard.ensureMapModule();
    const VD = window.VoltDashboard;
    const id = seedRouteWithTrips(VD, '');
    VD.renderMap();

    const promptSpy = vi.spyOn(window, 'prompt').mockReturnValue('  Weekend  ');
    try {
      document.querySelector('#mapSessionList [data-trip-rename]').click();
    } finally {
      promptSpy.mockRestore();
    }
    expect(setTripLabel).toHaveBeenCalledWith(id, 'Weekend');
  });

  it('cancelling the rename prompt is a no-op (no bridge call)', async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    const setTripLabel = vi.fn();
    await loadDashboard({ bridge: createVoltBridgeFixture({ setTripLabel }) });
    await window.VoltDashboard.ensureMapModule();
    const VD = window.VoltDashboard;
    seedRouteWithTrips(VD, '');
    VD.renderMap();

    const promptSpy = vi.spyOn(window, 'prompt').mockReturnValue(null);
    try {
      document.querySelector('#mapSessionList [data-trip-rename]').click();
    } finally {
      promptSpy.mockRestore();
    }
    expect(setTripLabel).not.toHaveBeenCalled();
  });
});

describe('M5 — maintenance log', () => {
  async function loadWithMaintenance(getMaintenanceLog, extra = {}) {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard({ bridge: createVoltBridgeFixture({ getMaintenanceLog, ...extra }) });
  }

  it('shows empty-state guidance (not a fake logged row) when the log is empty', async () => {
    await loadWithMaintenance(() => '[]');
    window.VoltDashboard.setStorage({});
    const list = document.getElementById('maintenanceList');
    expect(list.querySelector('.maint-empty')).toBeTruthy();
    expect(list.textContent).toContain('No maintenance logged yet');
    // No delete buttons exist for guidance rows.
    expect(list.querySelector('[data-maint-delete]')).toBeNull();
  });

  it('renders real entries newest-first with date, odometer, and note', async () => {
    await loadWithMaintenance(() =>
      JSON.stringify([
        { id: 2, createdAtMs: 1_700_000_500_000, odometerKm: 1609.344, type: 'Oil change', note: 'Synthetic' },
        { id: 1, createdAtMs: 1_700_000_000_000, odometerKm: null, type: 'Tire rotation', note: '' },
      ]),
    );
    window.VoltDashboard.setStorage({});
    const list = document.getElementById('maintenanceList');
    const rows = list.querySelectorAll('.real-insight-item');
    expect(rows).toHaveLength(2);
    expect(rows[0].querySelector('strong').textContent).toBe('Oil change');
    // Odometer respects the imperial default (1609.344 km == 1000 mi).
    expect(rows[0].querySelector('small').textContent).toContain('1000 mi');
    expect(rows[0].querySelector('small').textContent).toContain('Synthetic');
    expect(rows[1].querySelector('strong').textContent).toBe('Tire rotation');
    // A delete affordance carries the entry id.
    expect(rows[0].querySelector('[data-maint-delete]').dataset.maintDelete).toBe('2');
  });

  it('escapes a hostile note/type so it cannot inject markup (DOM XSS-safe)', async () => {
    await loadWithMaintenance(() =>
      JSON.stringify([
        { id: 1, createdAtMs: 1_700_000_000_000, type: '<b>x</b>', note: '<img src=x onerror=alert(1)>' },
      ]),
    );
    window.VoltDashboard.setStorage({});
    const list = document.getElementById('maintenanceList');
    expect(list.querySelector('img')).toBeNull();
    expect(list.querySelector('button.maint-del')).toBeTruthy(); // the Remove button, not injected markup
    // The hostile type lands as text in the title, never as a real <b> element.
    expect(list.querySelector('.real-insight-item strong').textContent).toBe('<b>x</b>');
    expect(list.textContent).toContain('<img src=x onerror=alert(1)>');
  });

  it('Add entry prompts and forwards a JSON payload to bridge.addMaintenanceEntry', async () => {
    const addMaintenanceEntry = vi.fn();
    await loadWithMaintenance(() => '[]', { addMaintenanceEntry });
    window.VoltDashboard.setStorage({});

    const prompts = ['Oil change', '1000', 'Synthetic 5W-30'];
    const promptSpy = vi.spyOn(window, 'prompt').mockImplementation(() => prompts.shift());
    try {
      document.getElementById('addMaintenanceBtn').click();
    } finally {
      promptSpy.mockRestore();
    }
    expect(addMaintenanceEntry).toHaveBeenCalledTimes(1);
    const payload = JSON.parse(addMaintenanceEntry.mock.calls[0][0]);
    expect(payload.type).toBe('Oil change');
    expect(payload.note).toBe('Synthetic 5W-30');
    // 1000 mi (imperial default) is converted to km for storage.
    expect(payload.odometerKm).toBeCloseTo(1609.344, 1);
    expect(payload.date).toBeGreaterThan(0);
  });

  it('Add entry omits the odometer when the reading is blank', async () => {
    const addMaintenanceEntry = vi.fn();
    await loadWithMaintenance(() => '[]', { addMaintenanceEntry });
    window.VoltDashboard.setStorage({});

    const prompts = ['Coolant flush', '', ''];
    const promptSpy = vi.spyOn(window, 'prompt').mockImplementation(() => prompts.shift());
    try {
      document.getElementById('addMaintenanceBtn').click();
    } finally {
      promptSpy.mockRestore();
    }
    const payload = JSON.parse(addMaintenanceEntry.mock.calls[0][0]);
    expect(payload).not.toHaveProperty('odometerKm');
    expect(payload.type).toBe('Coolant flush');
  });

  it('Add entry aborts without a bridge call when the type is blank', async () => {
    const addMaintenanceEntry = vi.fn();
    await loadWithMaintenance(() => '[]', { addMaintenanceEntry });
    window.VoltDashboard.setStorage({});

    const promptSpy = vi.spyOn(window, 'prompt').mockReturnValue('   ');
    try {
      document.getElementById('addMaintenanceBtn').click();
    } finally {
      promptSpy.mockRestore();
    }
    expect(addMaintenanceEntry).not.toHaveBeenCalled();
  });

  it('Remove forwards the entry id to bridge.deleteMaintenanceEntry', async () => {
    const deleteMaintenanceEntry = vi.fn();
    await loadWithMaintenance(
      () => JSON.stringify([{ id: 9, createdAtMs: 1_700_000_000_000, type: 'Oil change', note: '' }]),
      { deleteMaintenanceEntry },
    );
    window.VoltDashboard.setStorage({});

    document.querySelector('#maintenanceList [data-maint-delete]').click();
    expect(deleteMaintenanceEntry).toHaveBeenCalledWith('9');
  });
});
