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

  function seedRouteWithTrips(VD, label, favorite = false) {
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
        favorite,
      },
    ];
    VD.state.storage = {
      recentRoutes: [
        {
          session: { id, sessionId: 7, mode: 'drive', adapterName: 'Adapter X', startedAtMs, endedAtMs },
          points: [
            { lat: 34.05, lng: -118.25 },
            { lat: 34.16, lng: -118.32 },
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

  it('renders an outline star carrying the route key for an unfavorited drive', () => {
    const VD = window.VoltDashboard;
    const id = seedRouteWithTrips(VD, '', false);
    VD.renderMap();

    const fav = document.querySelector('#mapSessionList [data-trip-favorite]');
    expect(fav).toBeTruthy();
    expect(fav.dataset.tripFavorite).toBe(id);
    expect(fav.dataset.tripFavoriteState).toBe('0');
    expect(fav.getAttribute('aria-pressed')).toBe('false');
    expect(fav.textContent).toBe('☆'); // ☆
  });

  it('renders a filled star marked pressed for a favorited drive', () => {
    const VD = window.VoltDashboard;
    seedRouteWithTrips(VD, '', true);
    VD.renderMap();

    const fav = document.querySelector('#mapSessionList [data-trip-favorite]');
    expect(fav.dataset.tripFavoriteState).toBe('1');
    expect(fav.getAttribute('aria-pressed')).toBe('true');
    expect(fav.classList.contains('is-favorite')).toBe(true);
    expect(fav.textContent).toBe('★'); // ★
  });

  it('toggling forwards the FLIPPED state to bridge.setTripFavorite', async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    const setTripFavorite = vi.fn();
    await loadDashboard({ bridge: createVoltBridgeFixture({ setTripFavorite }) });
    await window.VoltDashboard.ensureMapModule();
    const VD = window.VoltDashboard;
    const id = seedRouteWithTrips(VD, '', false);
    VD.renderMap();

    document.querySelector('#mapSessionList [data-trip-favorite]').click();
    expect(setTripFavorite).toHaveBeenCalledWith(id, true);
  });

  it('un-favoriting an already-favorited drive forwards false', async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    const setTripFavorite = vi.fn();
    await loadDashboard({ bridge: createVoltBridgeFixture({ setTripFavorite }) });
    await window.VoltDashboard.ensureMapModule();
    const VD = window.VoltDashboard;
    const id = seedRouteWithTrips(VD, '', true);
    VD.renderMap();

    document.querySelector('#mapSessionList [data-trip-favorite]').click();
    expect(setTripFavorite).toHaveBeenCalledWith(id, false);
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

  // M1/C4: the add flow is now an inline form (no more window.prompt chain). Helper fills the
  // form's inputs and submits it.
  function fillMaintenanceForm({ type, note, odometer, intervalKm, intervalMonths } = {}) {
    document.getElementById('addMaintenanceBtn').click(); // open the form
    const set = (id, value) => {
      const input = document.getElementById(id);
      if (input && value != null) input.value = String(value);
    };
    set('maintTypeInput', type);
    set('maintNoteInput', note);
    set('maintOdometerInput', odometer);
    set('maintIntervalKmInput', intervalKm);
    set('maintIntervalMonthsInput', intervalMonths);
    document.getElementById('maintenanceForm').dispatchEvent(new Event('submit', { cancelable: true }));
  }

  it('the add form is hidden until Add entry is clicked', async () => {
    await loadWithMaintenance(() => '[]');
    window.VoltDashboard.setStorage({});
    const form = document.getElementById('maintenanceForm');
    expect(form.hidden).toBe(true);
    document.getElementById('addMaintenanceBtn').click();
    expect(form.hidden).toBe(false);
    expect(document.getElementById('addMaintenanceBtn').getAttribute('aria-expanded')).toBe('true');
  });

  it('Add entry submits a JSON payload (incl. interval) to bridge.addMaintenanceEntry', async () => {
    const addMaintenanceEntry = vi.fn();
    await loadWithMaintenance(() => '[]', { addMaintenanceEntry });
    window.VoltDashboard.setStorage({});

    fillMaintenanceForm({ type: 'Oil change', note: 'Synthetic 5W-30', odometer: '1000', intervalKm: '5000', intervalMonths: '12' });

    expect(addMaintenanceEntry).toHaveBeenCalledTimes(1);
    const payload = JSON.parse(addMaintenanceEntry.mock.calls[0][0]);
    expect(payload.type).toBe('Oil change');
    expect(payload.note).toBe('Synthetic 5W-30');
    // 1000 mi (imperial default) is converted to km for storage.
    expect(payload.odometerKm).toBeCloseTo(1609.344, 1);
    expect(payload.intervalKm).toBeCloseTo(5000 * 1.609344, 1);
    expect(payload.intervalMonths).toBe(12);
    expect(payload.date).toBeGreaterThan(0);
    // The form collapses after a successful save.
    expect(document.getElementById('maintenanceForm').hidden).toBe(true);
  });

  it('Add entry omits the odometer and interval when blank', async () => {
    const addMaintenanceEntry = vi.fn();
    await loadWithMaintenance(() => '[]', { addMaintenanceEntry });
    window.VoltDashboard.setStorage({});

    fillMaintenanceForm({ type: 'Coolant flush' });
    const payload = JSON.parse(addMaintenanceEntry.mock.calls[0][0]);
    expect(payload).not.toHaveProperty('odometerKm');
    expect(payload).not.toHaveProperty('intervalKm');
    expect(payload).not.toHaveProperty('intervalMonths');
    expect(payload.type).toBe('Coolant flush');
  });

  it('Add entry aborts without a bridge call when type and note are blank', async () => {
    const addMaintenanceEntry = vi.fn();
    await loadWithMaintenance(() => '[]', { addMaintenanceEntry });
    window.VoltDashboard.setStorage({});

    fillMaintenanceForm({ type: '   ' });
    expect(addMaintenanceEntry).not.toHaveBeenCalled();
  });

  it('Cancel collapses the form without a bridge call', async () => {
    const addMaintenanceEntry = vi.fn();
    await loadWithMaintenance(() => '[]', { addMaintenanceEntry });
    window.VoltDashboard.setStorage({});

    document.getElementById('addMaintenanceBtn').click();
    expect(document.getElementById('maintenanceForm').hidden).toBe(false);
    document.querySelector('[data-action="cancelMaintenance"]').click();
    expect(document.getElementById('maintenanceForm').hidden).toBe(true);
    expect(addMaintenanceEntry).not.toHaveBeenCalled();
  });

  it('Remove forwards the entry id to bridge.deleteMaintenanceEntry after confirming', async () => {
    const deleteMaintenanceEntry = vi.fn();
    await loadWithMaintenance(
      () => JSON.stringify([{ id: 9, createdAtMs: 1_700_000_000_000, type: 'Oil change', note: '' }]),
      { deleteMaintenanceEntry },
    );
    window.VoltDashboard.setStorage({});

    // C1: removal is a no-undo data loss, so it now routes through the shared
    // confirm dialog. The bridge is only called after the user confirms.
    document.querySelector('#maintenanceList [data-maint-delete]').click();
    const dialog = document.getElementById('appDialog');
    expect(dialog.hidden).toBe(false);
    expect(deleteMaintenanceEntry).not.toHaveBeenCalled();

    document.getElementById('appDialogConfirm').click();
    // confirmAppDialog settles its promise on the confirm click; let the .then()
    // that fires the bridge call run.
    for (let i = 0; i < 5; i += 1) await Promise.resolve();
    expect(deleteMaintenanceEntry).toHaveBeenCalledWith('9');
  });

  it('Remove does NOT call the bridge when the confirm dialog is cancelled', async () => {
    const deleteMaintenanceEntry = vi.fn();
    await loadWithMaintenance(
      () => JSON.stringify([{ id: 9, createdAtMs: 1_700_000_000_000, type: 'Oil change', note: '' }]),
      { deleteMaintenanceEntry },
    );
    window.VoltDashboard.setStorage({});

    document.querySelector('#maintenanceList [data-maint-delete]').click();
    document.getElementById('appDialogCancel').click();
    for (let i = 0; i < 5; i += 1) await Promise.resolve();
    expect(deleteMaintenanceEntry).not.toHaveBeenCalled();
  });
});

describe('M1/C4 — maintenance next-due / overdue', () => {
  async function loadWithMaintenance(getMaintenanceLog) {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard({ bridge: createVoltBridgeFixture({ getMaintenanceLog }) });
  }

  // Latest odometer is exposed via the battery snapshot the storage summary carries.
  function storageWithOdometer(odometerKm) {
    return { batterySummary: { latestBatterySnapshot: { odometerKm } } };
  }

  const NOW = Date.now();

  it('shows a distance "until due" line for an entry well ahead of the interval', async () => {
    // Logged at 10,000 km with a 8,000 km interval → due at 18,000 km. Current odo 12,000 km →
    // 6,000 km remaining (well over the ~1,609 km "due soon" floor), so it reads as ahead.
    await loadWithMaintenance(() =>
      JSON.stringify([
        { id: 1, createdAtMs: NOW, type: 'Oil change', note: '', odometerKm: 10000, intervalKm: 8000 },
      ]),
    );
    window.VoltDashboard.setStorage(storageWithOdometer(12000));
    const due = document.querySelector('#maintenanceList .maint-due');
    expect(due).toBeTruthy();
    expect(due.dataset.due).toBe('ok');
    expect(due.textContent).toContain('until due');
  });

  it('flags overdue when the odometer has passed the due distance', async () => {
    await loadWithMaintenance(() =>
      JSON.stringify([
        { id: 1, createdAtMs: NOW, type: 'Oil change', note: '', odometerKm: 10000, intervalKm: 8000 },
      ]),
    );
    // Current odometer 20,000 km > due-at 18,000 km → overdue by 2,000 km.
    window.VoltDashboard.setStorage(storageWithOdometer(20000));
    const due = document.querySelector('#maintenanceList .maint-due');
    expect(due.dataset.due).toBe('overdue');
    expect(due.textContent.toLowerCase()).toContain('overdue');
    // Aggregate banner surfaces the overdue count.
    const hint = document.getElementById('maintenanceDueHint');
    expect(hint.hidden).toBe(false);
    expect(hint.dataset.due).toBe('overdue');
    expect(hint.textContent.toLowerCase()).toContain('overdue');
  });

  it('marks an entry due-soon when remaining distance is within the threshold', async () => {
    // Due at 18,000 km; current 17,000 km → 1,000 km remaining (< ~1,609 km floor) → due soon.
    await loadWithMaintenance(() =>
      JSON.stringify([
        { id: 1, createdAtMs: NOW, type: 'Oil change', note: '', odometerKm: 10000, intervalKm: 8000 },
      ]),
    );
    window.VoltDashboard.setStorage(storageWithOdometer(17000));
    const due = document.querySelector('#maintenanceList .maint-due');
    expect(due.dataset.due).toBe('due-soon');
    const hint = document.getElementById('maintenanceDueHint');
    expect(hint.dataset.due).toBe('due-soon');
  });

  it('computes a months-based overdue line independent of odometer', async () => {
    // Logged 8 months ago with a 6-month interval → overdue on time, no odometer needed.
    const eightMonthsAgo = NOW - Math.round(8 * 30.4375 * 86400000);
    await loadWithMaintenance(() =>
      JSON.stringify([
        { id: 1, createdAtMs: eightMonthsAgo, type: 'Brake fluid', note: '', intervalMonths: 6 },
      ]),
    );
    window.VoltDashboard.setStorage({});
    const due = document.querySelector('#maintenanceList .maint-due');
    expect(due.dataset.due).toBe('overdue');
    expect(due.textContent.toLowerCase()).toContain('overdue');
  });

  it('shows no due line and no banner for an entry without an interval', async () => {
    await loadWithMaintenance(() =>
      JSON.stringify([{ id: 1, createdAtMs: NOW, type: 'Tire rotation', note: '', odometerKm: 10000 }]),
    );
    window.VoltDashboard.setStorage(storageWithOdometer(20000));
    expect(document.querySelector('#maintenanceList .maint-due')).toBeNull();
    expect(document.getElementById('maintenanceDueHint').hidden).toBe(true);
  });

  it('falls back to the vehicle odometer (miles) when no battery snapshot is present', async () => {
    await loadWithMaintenance(() =>
      JSON.stringify([
        { id: 1, createdAtMs: NOW, type: 'Oil change', note: '', odometerKm: 10000, intervalKm: 8000 },
      ]),
    );
    // No battery snapshot; vehicle reports 13,000 mi ≈ 20,921 km > due-at 18,000 km → overdue.
    window.VoltDashboard.setStorage({});
    window.VoltDashboard.state.appState = { vehicle: { odometerMiles: 13000 } };
    window.VoltDashboard.renderMaintenanceList();
    const due = document.querySelector('#maintenanceList .maint-due');
    expect(due.dataset.due).toBe('overdue');
  });
});
