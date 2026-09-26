// Cross-surface data consistency (polish round 2 follow-up):
//   1. The Drive trip card describes ONE drive — the live one while samples stream, else the
//      most recent stored trip — and says which. No lifetime totals mixed in.
//   2. A fresh live SOC wins on every battery surface; otherwise the stored snapshot shows,
//      labelled with its age.
//   3. The Charge tab's count tile and the Recent-charges headline count the same thing.
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

const text = (id) => document.getElementById(id).textContent;
const HOUR = 3600e3;

let VD;
beforeEach(async () => {
  document.body.innerHTML = '';
  delete window.VoltDashboard;
  delete window.VoltTrackerNative;
  delete window.VoltTrackerAndroid;
  window.localStorage.clear();
  await loadDashboard({ extras: ['insights-panel.js', 'charge-history.js'] });
  VD = window.VoltDashboard;
});
afterEach(() => window.localStorage.clear());

function tripRow(id, startedAtMs, miles, { energyKwh = null, maxSpeedKph = 90, minutes = 30 } = {}) {
  return {
    id: String(id),
    startedAtMs,
    endedAtMs: startedAtMs + minutes * 60e3,
    durationMs: minutes * 60e3,
    distanceMeters: miles * 1609.344,
    maxSpeedKph,
    energyKwh,
  };
}

// Stream `count` live samples one second apart, ending now.
function streamSamples(count, fields) {
  VD.setStatus({ state: 'connected', detail: 'Live OBD data.' });
  const start = Date.now() - (count - 1) * 1000;
  for (let i = 0; i < count; i += 1) {
    VD.updateTelemetry({
      source: 'obd',
      connected: true,
      sampleCount: i + 1,
      sessionMs: (i + 1) * 1000,
      updatedAt: start + i * 1000,
      ...fields(i),
    });
  }
  VD.flushRender();
}

describe('Drive trip card: one drive, labelled', () => {
  it('shows only the most recent stored trip, never lifetime totals', () => {
    const now = Date.now();
    VD.setTrips([
      tripRow(1, now - 50 * HOUR, 40, { energyKwh: 10, maxSpeedKph: 130 }),
      tripRow(2, now - 3 * HOUR, 10, { energyKwh: 2.5, maxSpeedKph: 80, minutes: 25 }),
    ]);
    // The overview carries the lifetime distance + all-time max speed: neither may leak in.
    VD.setStorage({ overview: { distanceMeters: 80467, maxSpeedKph: 130 } });

    expect(text('thisTripKicker')).toMatch(/^Last trip · \dh ago$/);
    expect(text('overviewDistance')).toBe(VD.formatDistance(10 * 1609.344));
    expect(text('tripTimeValue')).toBe(VD.formatDuration(25 * 60e3));
    expect(text('overviewMaxSpeed')).toBe('50 mph');
    expect(text('tripEffValue')).toBe(VD.units.efficiencyText(4));
    expect(text('tripEnergyValue')).toBe('2.5 kWh');
  });

  it('falls back to the newest route when trip rollups are not loaded', () => {
    const now = Date.now();
    VD.setStorage({
      overview: { distanceMeters: 80467, maxSpeedKph: 130 },
      recentRoutes: [
        {
          session: { id: '9', startedAtMs: now - 2 * HOUR, endedAtMs: now - 2 * HOUR + 20 * 60e3 },
          distanceMeters: 8046.72,
          points: [{ lat: 1, lng: 1, speedMps: 20 }, { lat: 1.01, lng: 1, speedMps: 26.8224 }],
        },
      ],
    });
    expect(text('thisTripKicker')).toMatch(/^Last trip · /);
    expect(text('overviewDistance')).toBe(VD.formatDistance(8046.72));
    expect(text('tripTimeValue')).toBe(VD.formatDuration(20 * 60e3));
    expect(text('overviewMaxSpeed')).toBe('60 mph');
    // No trip rollup means no logged energy: efficiency stays honest.
    expect(text('tripEffValue')).toBe('--');
  });

  it('switches to the live drive while samples stream and computes time + efficiency from it', () => {
    const now = Date.now();
    VD.setTrips([tripRow(2, now - 3 * HOUR, 10, { energyKwh: 2.5, maxSpeedKph: 80 })]);
    VD.setStorage({ overview: { distanceMeters: 80467, maxSpeedKph: 130 } });
    // 120 s at a steady 18 kW with a 64 kph peak, moving north ~20 m per sample.
    streamSamples(121, (i) => ({
      speedKph: i === 60 ? 64 : 48,
      powerKw: 18,
      latitude: 34 + i * 0.00018,
      longitude: -118,
    }));

    expect(text('thisTripKicker')).toBe('Current drive');
    const meters = VD.state.sessionDistanceM;
    expect(meters).toBeGreaterThan(2000);
    expect(text('overviewDistance')).toBe(VD.formatDistance(meters));
    expect(text('tripTimeValue')).toBe(VD.formatDuration(121e3));
    expect(text('overviewMaxSpeed')).toBe('40 mph');
    // 18 kW for 120 s = 0.6 kWh net; efficiency is this drive's miles / kWh.
    expect(VD.state.sessionEnergyKwh).toBeCloseTo(0.6, 5);
    expect(text('tripEnergyValue')).toBe('0.6 kWh');
    expect(text('tripEffValue')).toBe(VD.units.efficiencyText(meters / 1609.344 / 0.6));
  });

  it('does not book energy across a sample gap or while plugged in', () => {
    VD.setStatus({ state: 'connected' });
    const t0 = Date.now() - 60e3;
    VD.updateTelemetry({ source: 'obd', updatedAt: t0, powerKw: 10 });
    VD.updateTelemetry({ source: 'obd', updatedAt: t0 + 30e3, powerKw: 10 });
    VD.updateTelemetry({ source: 'obd', updatedAt: t0 + 31e3, powerKw: -6, chargerPowerKw: 6.6 });
    expect(VD.state.sessionEnergyKwh).toBe(0);
    VD.updateTelemetry({ source: 'obd', updatedAt: t0 + 32e3, powerKw: 36 });
    expect(VD.state.sessionEnergyKwh).toBeCloseTo(0.01, 6);
  });
});

describe('Battery SOC agrees across surfaces', () => {
  const snapshotStorage = (capturedAtMs) => ({
    batterySummary: { latestBatterySnapshot: { soc: 77, capturedAtMs, packPowerKw: -2.1, vehicleState: 'idle' } },
  });

  it('labels a stored snapshot with its age when nothing is live', () => {
    VD.setStorage(snapshotStorage(Date.now() - 6 * HOUR));
    expect(text('realPackValue')).toBe('77%');
    expect(text('realPackTitle')).toBe('Pack at 77%');
    expect(text('realPackCopy')).toMatch(/^Last logged 6h ago · idle/);
  });

  it('uses the fresh live SOC on Insights, matching the Drive tile', () => {
    VD.setStorage(snapshotStorage(Date.now() - 6 * HOUR));
    streamSamples(2, () => ({ soc: 65.2, powerKw: 12, vehicleState: 'driving' }));

    expect(text('driveSocValue')).toBe('65%');
    expect(text('realPackValue')).toBe('65%');
    expect(text('realPackTitle')).toBe('Pack at 65%');
    expect(text('realPackCopy')).toBe('Live · driving · Drawing 12.0 kW');
  });

  it('falls back to the snapshot once the live sample goes stale', () => {
    VD.setStorage(snapshotStorage(Date.now() - 6 * HOUR));
    streamSamples(1, () => ({ soc: 65 }));
    expect(text('realPackValue')).toBe('65%');
    VD.setState({ lastSampleAt: Date.now() - 31e3 });
    VD.flushRender();
    expect(text('realPackValue')).toBe('77%');
    expect(text('realPackCopy')).toMatch(/^Last logged 6h ago/);
  });
});

describe('Charge tab counts agree', () => {
  it('counts logged charge sessions in both the tile and the headline, not charging hints', () => {
    const now = Date.now();
    const sessions = [3.0, 11.8, 9.6, 5.2].map((energyKwh, i) => ({
      id: i + 1,
      startedAtMs: now - (i + 1) * 24 * HOUR,
      endedAtMs: now - (i + 1) * 24 * HOUR + 3 * HOUR,
      chargerType: 'level2',
      energyKwh,
    }));
    VD.setStorage({
      chargeSummary: { chargeSessionCount: 4, chargingHintCount: 6, maxPowerKw: 7.2, recentSessions: sessions },
    });
    expect(text('realChargeCount')).toBe('4');
    expect(text('chargeSessionsTitle')).toBe('29.6 kWh across 4 charges');
  });
});
