// Bold layout pass — placeholders collapse instead of printing "--", the Drive
// EV/gas mode chip, the compact SOC trace, and the demo car-controls disclosure.
// These are visual-only: every test also pins that the underlying value text
// contract ("--", "Set rate") is unchanged.
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

function freshDom() {
  document.body.innerHTML = '';
  delete window.VoltDashboard;
  delete window.VoltTrackerNative;
  delete window.VoltTrackerAndroid;
  window.localStorage.clear();
}

const $ = (id) => document.getElementById(id);
const click = (node) => node.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));

describe('Drive EV / gas mode chip', () => {
  let VD;
  beforeEach(async () => {
    freshDom();
    await loadDashboard();
    VD = window.VoltDashboard;
  });

  function sample(fields) {
    // Replace (not merge) the sample so a dropped rpm really disappears.
    VD.state.telemetry = {};
    VD.updateTelemetry({ source: 'obd', updatedAt: Date.now(), ...fields });
    VD.state.telemetry = { ...fields };
    VD.updateLiveUi();
  }

  it('stays hidden until an rpm reading exists', () => {
    sample({ speedKph: 40 });
    expect($('driveModeChip').hidden).toBe(true);
    expect($('driveModeChip').dataset.driveMode).toBe('unknown');
  });

  it('reads Electric with the engine off and Gas engine once it turns', () => {
    sample({ rpm: 0 });
    expect($('driveModeChip').hidden).toBe(false);
    expect($('driveModeChip').dataset.driveMode).toBe('ev');
    expect($('driveModeLabel').textContent).toBe('Electric');

    sample({ rpm: 1450 });
    expect($('driveModeChip').dataset.driveMode).toBe('gas');
    expect($('driveModeLabel').textContent).toBe('Gas engine');
  });

  it('treats a sub-idle rpm blip as electric', () => {
    sample({ rpm: 120 });
    expect($('driveModeChip').dataset.driveMode).toBe('ev');
  });

  it('hides again when the rpm reading disappears', () => {
    sample({ rpm: 900 });
    expect($('driveModeChip').hidden).toBe(false);
    sample({});
    expect($('driveModeChip').hidden).toBe(true);
  });
});

describe('Drive SOC trace compact form', () => {
  let VD;
  let host;
  beforeEach(async () => {
    freshDom();
    await loadDashboard({ extras: ['drive.js'] });
    VD = window.VoltDashboard;
    host = $('socTraceChart');
    Object.defineProperty(host, 'clientWidth', { configurable: true, value: 320 });
  });

  it('shrinks to a compact strip with a plain-language note when SOC barely moved', () => {
    VD.state.socHistory = [64, 64.1, 64];
    VD.drawLiveSocTrace();
    expect(host.dataset.chartState).toBe('ready');
    expect(host.dataset.chartSize).toBe('compact');
    expect(host.querySelector('.live-chart-note')?.textContent).toBe('Steady around 64% this session');
  });

  it('uses the full-height chart once SOC has moved', () => {
    VD.state.socHistory = [78.2, 77.4, 76.1, 75.0];
    VD.drawLiveSocTrace();
    expect(host.dataset.chartSize).toBe('full');
    expect(host.querySelector('.live-chart-note')).toBeNull();
  });
});

describe('This-trip card placeholders', () => {
  let VD;
  beforeEach(async () => {
    freshDom();
    await loadDashboard({ extras: ['insights-panel.js'] });
    VD = window.VoltDashboard;
  });
  afterEach(() => window.localStorage.clear());

  function seed(energyKwh, durationMs) {
    const now = Date.now();
    VD.setTrips([{
      id: '9', sessionId: 9, startedAtMs: now - 3600e3, endedAtMs: now - 3600e3 + durationMs,
      durationMs, distanceMeters: 16093, maxSpeedKph: 0, hasRoute: true, energyKwh, evShare: null,
    }]);
    VD.setStorage({
      recentRoutes: [{
        session: { id: '9', startedAtMs: now - 3600e3, mode: 'drive' },
        distanceMeters: 16093, pointCount: 42, points: [],
      }],
    });
  }

  const cellHidden = (id) => $(id).parentElement.hidden;

  it('hides stat cells whose value is a placeholder, keeping the "--" text', () => {
    seed(null, 0);
    expect($('overviewDistance').textContent).not.toBe('--');
    expect($('tripTimeValue').textContent).toBe('--');
    expect(cellHidden('tripTimeValue')).toBe(true);
    expect(cellHidden('overviewMaxSpeed')).toBe(true);
    expect(cellHidden('tripEffValue')).toBe(true);
    expect($('overviewDistance').closest('.trip-stats').dataset.cols).toBe('1');
  });

  it('shows the cells once the trip has values', () => {
    seed(3.5, 30 * 60 * 1000);
    expect(cellHidden('tripTimeValue')).toBe(false);
    expect(cellHidden('tripEffValue')).toBe(false);
    // No max speed on this trip: three stats, so a narrow phone keeps them on one row.
    expect(cellHidden('overviewMaxSpeed')).toBe(true);
    expect($('overviewDistance').closest('.trip-stats').dataset.cols).toBe('3');
  });
});

describe('Charge cost KPI', () => {
  let VD;
  beforeEach(async () => {
    freshDom();
    await loadDashboard();
    VD = window.VoltDashboard;
  });
  afterEach(() => window.localStorage.clear());

  function seedCharge() {
    VD.setStorage({
      chargeSummary: {
        chargeSessionCount: 1,
        recentSessions: [
          { id: 5, startedAtMs: Date.now() - 3_600_000, endedAtMs: Date.now() - 600_000, chargerType: 'level2', startSoc: 40, endSoc: 90, powerKw: 7.2, energyKwh: 9.6 },
        ],
      },
    });
  }

  it('offers a Set rate link instead of a "--" cost when no rate is set', () => {
    VD.prefs.set('pricePerKwh', 0);
    seedCharge();
    expect($('chargeEnergyCost').textContent).toBe('--');
    expect($('chargeEnergyCost').hidden).toBe(true);
    expect($('chargeRateLink').hidden).toBe(false);
    expect($('chargeRateLink').dataset.settingsFocus).toBe('pricePerKwhInput');
  });

  it('shows the cost and drops the link once a rate is set', () => {
    VD.prefs.set('pricePerKwh', 0.25);
    seedCharge();
    expect($('chargeEnergyCost').hidden).toBe(false);
    expect($('chargeEnergyCost').textContent).toBe('$2.40');
    expect($('chargeRateLink').hidden).toBe(true);
  });
});

describe('Diagnostics recovery facts', () => {
  it('hides a fact cell with no value (Bluetooth during the demo)', async () => {
    freshDom();
    await loadDashboard();
    const VD = window.VoltDashboard;
    VD.setState({ demoActive: true });
    VD.setStatus({ state: 'connected', detail: 'Demo' });
    expect($('diagRecoveryBt').textContent).toBe('--');
    expect($('diagRecoveryBt').parentElement.hidden).toBe(true);
    expect($('diagRecoverySource').parentElement.hidden).toBe(false);
  });
});

describe('Car controls demo disclosure', () => {
  it('collapses behind Show / Hide while the demo runs', async () => {
    freshDom();
    const native = { available: true, enabled: false, unlocked: false, unlockedRemainingMs: 0, pinLockedOut: false };
    const bridge = createVoltBridgeFixture({ getCarControlState: () => JSON.stringify(native) });
    await loadDashboard({ bridge });
    const VD = window.VoltDashboard;
    await VD.ensureCarControlsModule();
    VD.setDemoActive(true);
    VD.renderCarControls();

    const expand = $('carControlsExpand');
    expect($('carControlsCard').hidden).toBe(false);
    expect(expand.hidden).toBe(false);
    expect(expand.getAttribute('aria-expanded')).toBe('false');
    expect($('carControlsBody').hidden).toBe(true);

    click(expand);
    VD.flushRender();
    expect(expand.getAttribute('aria-expanded')).toBe('true');
    expect(expand.textContent).toBe('Hide');
    expect($('carControlsBody').hidden).toBe(false);

    VD.setDemoActive(false);
    VD.renderCarControls();
    // Controls are off on this car, so outside the demo the whole card goes away.
    expect($('carControlsCard').hidden).toBe(true);
  });
});
