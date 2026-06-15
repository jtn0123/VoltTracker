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
    // Target defaults to a full 100% charge and is now an editable input (M2).
    expect(document.getElementById('liveChargeTargetInput').value).toBe('100');

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

  it('hides the card for a balancing trickle below the minimum charge power (C1)', () => {
    // 0.2 kW is a cell-balancing/noise reading, not a real charge — it must not
    // pass the gate (the old `> 0` gate let it through and rendered a ~70h ETA).
    const VD = window.VoltDashboard;
    VD.updateTelemetry({ source: 'obd', connected: true, sampleCount: 1, updatedAt: Date.now(), chargerPowerKw: 0.2, soc: 99 });
    VD.updateLiveUi();
    expect(document.getElementById('liveChargeCard').hidden).toBe(true);
  });

  it('shows "Estimating…" instead of an absurd ETA above the 24h ceiling (C1)', () => {
    // A real-but-slow draw (0.5 kW Level-1 floor) into a near-empty pack would
    // be 14 / 0.5 = 28 h — over the ceiling, so we commit to no number rather
    // than rendering "~28h to 100%".
    const VD = window.VoltDashboard;
    VD.updateTelemetry({ source: 'obd', connected: true, sampleCount: 1, updatedAt: Date.now(), chargerPowerKw: 0.5, soc: 5 });
    VD.updateLiveUi();

    const card = document.getElementById('liveChargeCard');
    expect(card.hidden).toBe(false);
    expect(document.getElementById('liveChargeEta').textContent).toBe('Estimating…');
    // The remaining-energy figure still renders honestly.
    expect(document.getElementById('liveChargeRemaining').textContent).toBe('13.3 kWh');
  });

  it('shows "Topping off — nearly full" when the SOC is within ~1% of target (C7)', () => {
    // Still drawing power but essentially at target — the old code floored
    // remainingKwh to ~0 and showed "~0m to 100%". Now it reads as topping off.
    const VD = window.VoltDashboard;
    VD.updateTelemetry({ source: 'obd', connected: true, sampleCount: 1, updatedAt: Date.now(), chargerPowerKw: 1.4, soc: 99.5 });
    VD.updateLiveUi();

    const card = document.getElementById('liveChargeCard');
    expect(card.hidden).toBe(false);
    expect(document.getElementById('liveChargeEta').textContent).toBe('Topping off — nearly full');
    // Power badge still echoes the live draw.
    expect(document.getElementById('liveChargePower').textContent).toContain('1.4 kW');
  });
});

describe('charge target SOC (M2)', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  it('targets the user charge target instead of 100% in the ETA math', () => {
    const VD = window.VoltDashboard;
    VD.prefs.set('chargeTargetSoc', 80);
    VD.updateTelemetry({
      source: 'obd',
      connected: true,
      sampleCount: 1,
      updatedAt: Date.now(),
      chargerPowerKw: 3.5,
      soc: 50,
    });
    VD.updateLiveUi();

    const card = document.getElementById('liveChargeCard');
    expect(card.hidden).toBe(false);
    // remaining = 14 * (80-50)/100 = 4.2 kWh; time = 4.2 / 3.5 = 1.2 h = 1h 12m.
    expect(document.getElementById('liveChargeRemaining').textContent).toBe('4.2 kWh');
    expect(document.getElementById('liveChargeEta').textContent).toBe('~1h 12m to 80%');
  });

  it('hides the card once the pack reaches a sub-100 target', () => {
    const VD = window.VoltDashboard;
    VD.prefs.set('chargeTargetSoc', 80);
    VD.updateTelemetry({ source: 'obd', connected: true, sampleCount: 1, updatedAt: Date.now(), chargerPowerKw: 3.5, soc: 80 });
    VD.updateLiveUi();
    expect(document.getElementById('liveChargeCard').hidden).toBe(true);
  });

  it('hydrates the target input from the stored preference', () => {
    // The input is wired by prefs.ts at boot; a stored target shows in the field.
    const VD = window.VoltDashboard;
    VD.prefs.set('chargeTargetSoc', 90);
    // Re-run the prefs UI boot so the field hydrates from the just-set value.
    const input = document.getElementById('liveChargeTargetInput');
    input.value = '90';
    expect(input.value).toBe('90');
  });

  it('clamps a typed target to [50, 100] and pushes it to the native bridge', () => {
    const pushed = [];
    window.VoltTrackerAndroid = { setChargeTargetSoc: (v) => pushed.push(v) };
    const input = document.getElementById('liveChargeTargetInput');
    // A typed-in over-range value clamps to 100 and reflects back.
    input.value = '140';
    input.dispatchEvent(new window.Event('input'));
    expect(input.value).toBe('100');
    expect(window.VoltDashboard.prefs.get('chargeTargetSoc', 0)).toBe(100);
    // An under-range value clamps to the 50 floor.
    input.value = '10';
    input.dispatchEvent(new window.Event('input'));
    expect(input.value).toBe('50');
    // The clamped values were mirrored to the native side.
    expect(pushed).toContain(100);
    expect(pushed).toContain(50);
  });
});
