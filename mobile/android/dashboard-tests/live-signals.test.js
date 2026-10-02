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

  it('shows an unseen gear code as unknown with its raw number', () => {
    const VD = window.VoltDashboard;
    VD.updateTelemetry({
      source: 'obd',
      connected: true,
      sampleCount: 1,
      updatedAt: Date.now(),
      prndlState: '?',
      prndlRaw: 13,
      gearConfidence: 'unknown',
    });
    VD.state.liveSignalsFilter = 'all';
    VD.updateDiagnostics();
    expect(rowFor('Gear (PRNDL)').querySelector('.live-signal-value').textContent).toBe('? (code 13)');
  });

  it('lists the SW-CAN remote-start state in Body & comfort', () => {
    const VD = window.VoltDashboard;
    VD.updateTelemetry({
      source: 'obd',
      connected: true,
      sampleCount: 1,
      updatedAt: Date.now(),
      remoteStartState: 'off',
      climateStaleMs: 30000,
    });
    VD.updateDiagnostics();
    const row = rowFor('Remote start');
    expect(row.dataset.status).toBe('live');
    expect(row.querySelector('.live-signal-value').textContent).toBe('off');
    expect(row.querySelector('.live-signal-age').textContent).toBe('30s ago');
  });

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
    // Default units are imperial: 42 km/h reads in mph like the Drive speed.
    expect(speed.querySelector('.live-signal-value').textContent).toBe('26 mph');
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

  describe('follows the unit setting', () => {
    const SAMPLE = {
      source: 'obd',
      connected: true,
      sampleCount: 1,
      speedKph: 100,
      coolantC: 90,
      odometerKm: 1000,
      tirePressureFlKpa: 262,
      cabinTempEstC: 21.5,
      cycleEvDistanceKm: 56.3,
      cycleFuelUsedL: 3.8,
      aux12vSocPct: 80,
      windowFlPct: 40,
    };
    const valueOf = (label) => rowFor(label).querySelector('.live-signal-value').textContent;

    function paint(units) {
      const VD = window.VoltDashboard;
      VD.prefs.set('units', units);
      VD.updateTelemetry({ ...SAMPLE, updatedAt: Date.now() });
      VD.updateDiagnostics();
    }

    it('converts to psi, miles, mph, °F and gallons for imperial', () => {
      paint('imperial');
      expect(valueOf('Speed')).toBe('62 mph');
      expect(valueOf('Coolant temp')).toBe('194°F');
      expect(valueOf('Odometer')).toBe('621 mi');
      expect(valueOf('Tire front left')).toBe('38 psi');
      expect(valueOf('Cabin temp (est.)')).toBe('71°F');
      expect(valueOf('EV distance since full charge')).toBe('35 mi');
      expect(valueOf('Fuel used (cycle)')).toBe('1.0 gal');
      expect(valueOf('12V state of charge')).toBe('80%');
      expect(valueOf('Window front left')).toBe('40% open');
    });

    it('keeps kPa, km, km/h, °C and litres for metric', () => {
      paint('metric');
      expect(valueOf('Speed')).toBe('100 km/h');
      expect(valueOf('Coolant temp')).toBe('90°C');
      expect(valueOf('Odometer')).toBe('1000 km');
      expect(valueOf('Tire front left')).toBe('262 kPa');
      expect(valueOf('EV distance since full charge')).toBe('56 km');
      expect(valueOf('Fuel used (cycle)')).toBe('3.8 L');
    });

    it('repaints when only the unit setting changes', () => {
      paint('metric');
      expect(valueOf('Tire front left')).toBe('262 kPa');
      window.VoltDashboard.prefs.set('units', 'imperial');
      window.VoltDashboard.updateDiagnostics();
      expect(valueOf('Tire front left')).toBe('38 psi');
    });
  });

  it('shows a fresh gear on the Drive hero chip and hides a stale one', () => {
    const VD = window.VoltDashboard;
    const chip = document.getElementById('driveGearChip');
    expect(chip.hidden).toBe(true);
    VD.updateTelemetry({ source: 'obd', connected: true, sampleCount: 1, updatedAt: Date.now(), prndlState: 'R', prndlRaw: 7, gearConfidence: 'tentative', prndlStateStaleMs: 2000 });
    VD.updateLiveUi();
    expect(chip.hidden).toBe(false);
    expect(document.getElementById('driveGearValue').textContent).toBe('R');
    expect(chip.dataset.confidence).toBe('tentative');
    expect(chip.getAttribute('aria-label')).toContain('not yet confirmed');

    VD.updateTelemetry({ source: 'obd', connected: true, sampleCount: 2, updatedAt: Date.now(), prndlState: 'P', prndlStateStaleMs: 200_000 });
    VD.updateLiveUi();
    expect(chip.hidden).toBe(true);
  });
});
