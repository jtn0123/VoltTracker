// The WebView's live drive figures all describe the same span. Native samples carry the
// service's per-drive totals (LiveDriveTotals.kt), which restart at each new drive, so in an
// adapter session with two drives every live readout — the Drive trip card, the Drive chip, the
// map's "Current drive" header and the status popover — must cover the second drive only, not
// mix its distance with the session's clock or its start SOC.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

const MIN = 60e3;
// A fixed, synthetic clock so every surface formats the same "now".
const NOW = Date.UTC(2026, 0, 15, 18, 0, 0);

let VD;
beforeEach(async () => {
  document.body.innerHTML = '';
  delete window.VoltDashboard;
  delete window.VoltTrackerNative;
  delete window.VoltTrackerAndroid;
  window.localStorage.clear();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  await loadDashboard();
  VD = window.VoltDashboard;
  await VD.ensureMapModule();
});
afterEach(() => {
  vi.useRealTimers();
  window.localStorage.clear();
});

const text = (id) => document.getElementById(id).textContent;

function sample(atMs, fields) {
  return {
    source: 'obd',
    connected: true,
    updatedAt: atMs,
    sessionMs: atMs - (NOW - 60 * MIN),
    ...fields,
  };
}

describe('live drive span: one session, two drives', () => {
  it('shows the second drive on the trip card, Drive chip, map header and popover alike', () => {
    VD.setStatus({ state: 'connected', detail: 'Live OBD data.' });
    VD.setAppState({ session: { state: 'connected', sampleCount: 12, sessionMs: 60 * MIN } });
    const driveA = NOW - 60 * MIN;
    const driveB = NOW - 10 * MIN;

    // Drive A: 5 km, from 80 %, then parked for twenty minutes.
    for (let i = 0; i < 5; i += 1) {
      VD.updateTelemetry(sample(driveA + i * 4 * MIN, {
        speedKph: 60,
        soc: 80 - i,
        latitude: 34 + i * 0.01,
        longitude: -118.25,
        driveDistanceM: i * 1250,
        driveEnergyKwh: i * 0.2,
        driveMaxSpeedKph: 60,
        driveStartedAtMs: driveA,
        driveStartSoc: 80,
      }));
    }
    VD.updateTelemetry(sample(NOW - 30 * MIN, {
      speedKph: 0,
      soc: 76,
      latitude: 34.04,
      longitude: -118.25,
      driveDistanceM: 5000,
      driveEnergyKwh: 0.8,
      driveMaxSpeedKph: 60,
      driveStartedAtMs: driveA,
      driveStartSoc: 80,
    }));
    // Drive B: 1 km so far, from 70 %, ending now. Its GPS points span less than the service
    // distance, so the map takes the service figure.
    for (let i = 0; i < 5; i += 1) {
      VD.updateTelemetry(sample(driveB + i * 2.5 * MIN, {
        speedKph: 40,
        soc: 70 - i * 0.5,
        latitude: 34.1 + i * 0.0005,
        longitude: -118.25,
        driveDistanceM: i * 250,
        driveEnergyKwh: i * 0.05,
        driveMaxSpeedKph: 40,
        driveStartedAtMs: driveB,
        driveStartSoc: 70,
      }));
    }
    VD.flushRender();

    const distance = VD.formatDistance(1000);
    const duration = VD.formatDuration(10 * MIN);

    // Drive trip card.
    expect(text('thisTripKicker')).toBe('Current drive');
    expect(text('overviewDistance')).toBe(distance);
    expect(text('tripTimeValue')).toBe(duration);

    // Drive "Recording" chip: the drive's clock beside the drive's distance, not the session's hour.
    const chip = text('driveRecordingMeta');
    expect(chip).toContain(duration);
    expect(chip).toContain(VD.units.distanceText(1));
    expect(chip).not.toContain(VD.formatDuration(60 * MIN));

    // Map "Current drive" header: the same distance over the same span, though the live route
    // buffer still holds drive A's points.
    VD.renderMap();
    expect(VD.state.selectedMapSessionId).toBe('__live_current__');
    expect(text('mapDistance')).toBe(distance);
    expect(text('mapDuration')).toBe(duration);

    // Status popover trip rows, with the battery delta since drive B began.
    document.getElementById('stateBadge').click();
    const trip = text('statusPopoverTrip');
    expect(trip).toContain(VD.units.distanceText(1));
    expect(trip).toContain(duration);
    expect(trip).toContain('70% → 68%');
    expect(trip).not.toContain('80%');
  });
});
