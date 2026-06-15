// M8 — live charge time-to-full + remaining. renderLiveCharge (telemetry.ts)
// surfaces an estimated time-to-full and energy-remaining card on the Charge tab
// while the car is actively charging (live charger power > 0 and a known SOC
// below the target). It hides whenever the car isn't charging so a parked car
// never shows a stale ETA.
import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

describe('live charge time-to-full', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  it('estimates time-to-full and energy remaining while charging', () => {
    const VD = window.VoltDashboard;
    VD.updateTelemetry({
      source: 'obd',
      connected: true,
      sampleCount: 1,
      updatedAt: Date.now(),
      chargerPowerKw: 3.5,
      soc: 50,
      // No SOH reported → falls back to the Volt nominal 14 kWh usable pack.
    });
    VD.updateLiveUi();

    const card = document.getElementById('liveChargeCard');
    expect(card.hidden).toBe(false);

    // usable = 14 kWh; remaining = 14 * (100-50)/100 = 7.0 kWh
    expect(document.getElementById('liveChargeRemaining').textContent).toBe('7.0 kWh');
    expect(document.getElementById('liveChargeSoc').textContent).toBe('50%');
    expect(document.getElementById('liveChargeTarget').textContent).toBe('100%');

    // time = 7.0 kWh / 3.5 kW = 2.0 h = 2h 00m
    expect(document.getElementById('liveChargeEta').textContent).toBe('~2h 00m to 100%');

    // Charger power is echoed in the badge.
    expect(document.getElementById('liveChargePower').textContent).toContain('3.5 kW');
  });

  it('scales the pack capacity by reported state-of-health', () => {
    const VD = window.VoltDashboard;
    VD.updateTelemetry({
      source: 'obd',
      connected: true,
      sampleCount: 1,
      updatedAt: Date.now(),
      chargerPowerKw: 7,
      soc: 0,
      sohPct: 50, // a 50%-SOH pack holds half the nominal usable energy.
    });
    VD.updateLiveUi();

    expect(document.getElementById('liveChargeCard').hidden).toBe(false);
    // usable = 14 * 0.5 = 7.0 kWh; remaining (0 → 100) = 7.0 kWh.
    expect(document.getElementById('liveChargeRemaining').textContent).toBe('7.0 kWh');
    // time = 7.0 / 7 = 1.0 h.
    expect(document.getElementById('liveChargeEta').textContent).toBe('~1h 00m to 100%');
  });

  it('hides the card when the car is not charging (no charger power)', () => {
    const VD = window.VoltDashboard;
    VD.updateTelemetry({ source: 'obd', connected: true, sampleCount: 1, updatedAt: Date.now(), soc: 60, speedKph: 30 });
    VD.updateLiveUi();
    expect(document.getElementById('liveChargeCard').hidden).toBe(true);
  });

  it('hides the card once the pack reaches the target SOC', () => {
    const VD = window.VoltDashboard;
    VD.updateTelemetry({ source: 'obd', connected: true, sampleCount: 1, updatedAt: Date.now(), chargerPowerKw: 3.5, soc: 100 });
    VD.updateLiveUi();
    expect(document.getElementById('liveChargeCard').hidden).toBe(true);
  });
});
