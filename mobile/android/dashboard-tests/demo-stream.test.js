// Browser demo stream drive/charge cycle (actions-demo.ts). The stream runs a
// compressed "day with the car": a 60 s trip (EV, a gas stretch, regen
// braking, parked in P), then 30 s on a Level-2
// charger. The charge window is the only way demo mode can feed the Charge
// tab's live time-to-full hero, and the drive window must explicitly zero
// chargerPowerKw — samples merge into state.telemetry, so a stale charger
// reading would pin the hero open forever. Mirrors DemoPollingLoop.kt.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

async function startDemoStream() {
  await import('../app/src/main/dashboard-src/js/actions-demo.ts');
  const run = window.VoltDashboardActionModules.runBrowserDemoStream;
  // The stream bails early unless a demo is active (guards the start/stop race);
  // in real use startDemo sets this before the stream loads.
  window.VoltDashboard.state.demoActive = true;
  run(window.VoltDashboard, window.VoltDashboard.state);
}

describe('browser demo stream drive/charge cycle', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
    vi.useFakeTimers();
  });

  afterEach(() => {
    window.clearInterval(window.__voltDemoTimer ?? undefined);
    delete window.__voltDemoTimer;
    vi.useRealTimers();
  });

  it('drives for the first 60 s with the live charge card hidden', async () => {
    await startDemoStream();
    vi.advanceTimersByTime(5000); // t = 6
    const VD = window.VoltDashboard;
    VD.updateLiveUi();

    const t = VD.state.telemetry;
    expect(t.speedKph).toBeGreaterThan(0);
    // Explicit 0, not undefined — the merge-stale guard the hero relies on.
    expect(t.chargerPowerKw).toBe(0);
    expect(document.getElementById('liveChargeCard').hidden).toBe(true);
  });

  it('parks on a Level-2 charger from t=60 and feeds the charge hero', async () => {
    await startDemoStream();
    vi.advanceTimersByTime(64000); // t = 65, inside the charge window
    const VD = window.VoltDashboard;
    VD.updateLiveUi();

    const t = VD.state.telemetry;
    expect(t.vehicleState).toBe('charging');
    expect(t.speedKph).toBe(0);
    expect(t.chargerPowerKw).toBeGreaterThanOrEqual(3);
    expect(t.chargerPowerKw).toBeLessThanOrEqual(4);

    const card = document.getElementById('liveChargeCard');
    expect(card.hidden).toBe(false);
    expect(document.getElementById('liveChargePower').textContent).toContain('charging');
    expect(document.getElementById('liveChargeSub').textContent).toContain('kW');
    expect(document.getElementById('liveChargeEta').textContent).not.toBe('');
  });

  it('visibly raises the SOC across the charge window', async () => {
    await startDemoStream();
    vi.advanceTimersByTime(61000); // t = 62, charge just started
    const socAtStart = window.VoltDashboard.state.telemetry.soc;
    vi.advanceTimersByTime(27000); // t = 89, charge window ending
    const socAtEnd = window.VoltDashboard.state.telemetry.soc;
    expect(socAtEnd - socAtStart).toBeGreaterThanOrEqual(3);
  });

  it('keeps the SOC sawtooth alive across many cycles (no cap plateau)', async () => {
    // Regression: an accumulated-SOC form net-gained ~3% per cycle and pinned
    // at a 95% cap after ~9 minutes, freezing the drive-phase drain. The
    // periodic form must still drain and recover deep into a long demo run.
    await startDemoStream();
    vi.advanceTimersByTime(539000); // t = 540, start of the 7th cycle
    const lateCycleStart = window.VoltDashboard.state.telemetry.soc;
    vi.advanceTimersByTime(60000); // t = 600, late drive phase just ended
    const lateDriveEnd = window.VoltDashboard.state.telemetry.soc;
    vi.advanceTimersByTime(29000); // t = 629, late charge window ending
    const lateChargeEnd = window.VoltDashboard.state.telemetry.soc;
    expect(lateCycleStart - lateDriveEnd).toBeGreaterThanOrEqual(3);
    expect(lateChargeEnd - lateDriveEnd).toBeGreaterThanOrEqual(3);
    expect(lateChargeEnd).toBeLessThan(80);
  });

  it('unplugs at t=90: charger power zeroes and the hero hides again', async () => {
    await startDemoStream();
    vi.advanceTimersByTime(94000); // t = 95, second drive phase
    const VD = window.VoltDashboard;
    VD.updateLiveUi();

    const t = VD.state.telemetry;
    expect(t.chargerPowerKw).toBe(0);
    expect(t.speedKph).toBeGreaterThan(0);
    expect(document.getElementById('liveChargeCard').hidden).toBe(true);
  });

  it('runs a short gas stretch mid-trip, then EV again', async () => {
    await startDemoStream();
    const VD = window.VoltDashboard;
    vi.advanceTimersByTime(19000); // t = 20, EV leg
    expect(VD.state.telemetry.vehicleState).toBe('driving_ev');
    expect(VD.state.telemetry.rpm).toBe(0);
    expect(VD.state.mode).toBe('ev');
    vi.advanceTimersByTime(20000); // t = 40, gas leg
    expect(VD.state.telemetry.vehicleState).toBe('driving_gas');
    expect(VD.state.telemetry.rpm).toBeGreaterThan(500);
    expect(VD.state.mode).toBe('gas');
    vi.advanceTimersByTime(10000); // t = 50, braking: engine off again
    expect(VD.state.telemetry.rpm).toBe(0);
    expect(VD.state.mode).toBe('ev');
  });

  it('shows regen while driving on electricity', async () => {
    await startDemoStream();
    const powers = [];
    for (let i = 0; i < 35; i += 1) {
      powers.push(window.VoltDashboard.state.telemetry.powerKw);
      vi.advanceTimersByTime(1000);
    }
    expect(powers.some((kw) => kw < -0.5)).toBe(true);
  });

  it('regen-brakes to a stop and parks in P before the charger', async () => {
    await startDemoStream();
    const VD = window.VoltDashboard;
    vi.advanceTimersByTime(47000); // t = 48, braking starts
    const speeds = [];
    for (let i = 0; i < 6; i += 1) {
      expect(VD.state.telemetry.powerKw).toBeLessThan(-0.5);
      speeds.push(VD.state.telemetry.speedKph);
      vi.advanceTimersByTime(1000);
    }
    expect(speeds[0]).toBeGreaterThan(speeds[5]);
    // t = 54..59: parked, not yet charging.
    const t = VD.state.telemetry;
    expect(t.speedKph).toBe(0);
    expect(t.vehicleState).toBe('parked');
    expect(t.prndlState).toBe('P');
    expect(t.chargerPowerKw).toBe(0);
    const parkedAt = [t.latitude, t.longitude];
    vi.advanceTimersByTime(4000); // t = 58, still parked, marker stays put
    expect([VD.state.telemetry.latitude, VD.state.telemetry.longitude]).toEqual(parkedAt);
  });

  it('does not start (or resurrect) the stream if the demo was stopped during the chunk load', async () => {
    // Race: Start kicks off the async demo-chunk load, then Stop sets
    // demoActive=false before it resolves. When the stream finally runs it must
    // bail — otherwise its first sample re-flips demoActive on (telemetry.ts) and
    // the "stopped" demo animates forever.
    await import('../app/src/main/dashboard-src/js/actions-demo.ts');
    const run = window.VoltDashboardActionModules.runBrowserDemoStream;
    const VD = window.VoltDashboard;
    VD.state.demoActive = false; // user already hit Stop

    run(VD, VD.state);
    vi.advanceTimersByTime(3000);

    // No interval created, no demo sample emitted, demo stays stopped.
    expect(window.__voltDemoTimer == null).toBe(true);
    expect(VD.state.telemetry.source).not.toBe('demo');
    expect(VD.state.demoActive).toBe(false);
  });
});
