import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

// The Live signals diagnostic panel (telemetry.ts#renderLiveSignals) lists every
// metric the polling engine surfaces and classifies each as reporting vs "no data"
// off state.telemetry, so the user can see what the car is and isn't answering.
describe('live-signals diagnostic panel', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  function rowFor(label) {
    const names = Array.from(document.querySelectorAll('#liveSignalsList .live-signal-row'));
    return names.find((row) => row.querySelector('.live-signal-name').textContent.startsWith(label)) || null;
  }

  it('marks present metrics reporting and absent ones as no data', () => {
    const VD = window.VoltDashboard;
    // A sample where speed/soc/motor/gear report but the odometer is absent (the
    // NO-DATA case the user hit on their car).
    VD.updateTelemetry({
      source: 'obd',
      connected: true,
      sampleCount: 1,
      updatedAt: Date.now(),
      speedKph: 42,
      speedKphStaleMs: 800,
      soc: 80,
      motorACurrentA: 12.5,
      motorAStaleMs: 65_000,
      prndlState: 'D',
    });
    // "All" so both reporting and no-data rows are present for this assertion
    // (the default filter is "reporting", which hides the no-data Odometer row).
    VD.state.liveSignalsFilter = 'all';
    VD.updateDiagnostics();

    const speed = rowFor('Speed');
    expect(speed.dataset.status).toBe('live');
    expect(speed.querySelector('.live-signal-value').textContent).toBe('42 km/h');
    expect(speed.querySelector('.live-signal-age').textContent).toBe('now');

    const motor = rowFor('Motor A current');
    expect(motor.dataset.status).toBe('live');
    expect(motor.querySelector('.live-signal-value').textContent).toBe('12.5 A');
    expect(motor.querySelector('.live-signal-age').textContent).toBe('1m ago');

    const gear = rowFor('Gear (PRNDL)');
    expect(gear.dataset.status).toBe('live');
    expect(gear.querySelector('.live-signal-value').textContent).toBe('D');

    // Odometer was not in the sample -> reported as not answering.
    const odo = rowFor('Odometer');
    expect(odo.dataset.status).toBe('missing');
    expect(odo.querySelector('.live-signal-value').textContent).toBe('no data');

    // Volt-specific metrics carry the "Volt" tag; standard OBD ones don't.
    expect(motor.querySelector('.live-signal-tag')).not.toBeNull();
    expect(speed.querySelector('.live-signal-tag')).toBeNull();

    // The badge summarizes reporting/total.
    const badge = document.getElementById('liveSignalsBadge').textContent;
    expect(badge).toMatch(/^4\/\d+$/);
  });

  it('shows the connect prompt with nothing reporting before any sample', () => {
    const VD = window.VoltDashboard;
    VD.updateDiagnostics();
    expect(document.getElementById('liveSignalsTitle').textContent).toBe('Connect to see live metrics');
    expect(document.getElementById('liveSignalsBadge').textContent).toMatch(/^0\/\d+$/);
  });

  it('filters to only the non-reporting metrics when "Not reporting" is selected', () => {
    const VD = window.VoltDashboard;
    VD.updateTelemetry({
      source: 'obd',
      connected: true,
      sampleCount: 1,
      updatedAt: Date.now(),
      speedKph: 42,
      soc: 80,
      // odometer + everything else absent.
    });

    VD.state.liveSignalsFilter = 'missing';
    VD.updateDiagnostics();

    // Reporting rows are hidden under the filter; the non-reporting ones remain.
    expect(rowFor('Speed')).toBeNull();
    expect(rowFor('State of charge')).toBeNull();
    expect(rowFor('Odometer')).not.toBeNull();
    expect(rowFor('Odometer').dataset.status).toBe('missing');

    // The badge still counts reporting/total over the full catalog, not the filtered view.
    expect(document.getElementById('liveSignalsBadge').textContent).toMatch(/^2\/\d+$/);
  });

  it('defaults to showing only the reporting metrics, hiding no-data rows', () => {
    const VD = window.VoltDashboard;
    // Default filter is "reporting" (see core.ts initial state).
    VD.updateTelemetry({
      source: 'obd',
      connected: true,
      sampleCount: 1,
      updatedAt: Date.now(),
      speedKph: 42,
      soc: 80,
      // odometer + everything else absent.
    });
    VD.updateDiagnostics();

    // Reporting rows are shown; the no-data ones are hidden by default.
    expect(rowFor('Speed')).not.toBeNull();
    expect(rowFor('Speed').dataset.status).toBe('live');
    expect(rowFor('State of charge')).not.toBeNull();
    expect(rowFor('Odometer')).toBeNull();
    // The badge still counts reporting/total over the full catalog.
    expect(document.getElementById('liveSignalsBadge').textContent).toMatch(/^2\/\d+$/);
  });
});
