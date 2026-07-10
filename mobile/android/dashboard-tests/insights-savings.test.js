// M6 — EV-vs-gas savings on Insights. renderInsightStats (insights-panel.ts)
// estimates lifetime savings vs an equivalent gas car from the logged distance,
// the user's comparison MPG / gas price, and their electricity rate. The row is
// an honest estimate (EV energy is derived from distance using an assumed Volt
// efficiency) and stays hidden until all three prefs and a logged distance exist.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

describe('insights estimated savings vs gas', () => {
  async function loadWithInsights(getInsights) {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard({
      bridge: createVoltBridgeFixture({ getInsights }),
      extras: ['insights-panel.js'],
    });
    window.VoltDashboard.setView('insights');
    await window.VoltDashboard.pendingLazyLoads();
  }

  beforeEach(() => window.localStorage.clear());
  afterEach(() => window.localStorage.clear());

  it('shows a prompt state (not a number) until mpg, gas price, and electricity rate are all set (C3)', async () => {
    // 5000 miles logged, but no comparison prefs yet → row VISIBLE in a prompt
    // state (so the advertised savings is discoverable, not silently hidden)
    // with no fabricated number and a tap-through to Settings.
    const getInsights = vi.fn(() =>
      JSON.stringify({ tripCount: 12, totalDistanceMeters: 5000 * 1609.344 }));
    await loadWithInsights(getInsights);

    const row = document.getElementById('insightSavingsRow');
    expect(row.hidden).toBe(false);
    expect(document.getElementById('insightSavings').textContent).toBe('--');

    const note = document.getElementById('insightSavingsNote');
    expect(note.textContent).toContain('Set your MPG, gas price, and rate in Settings');
    const jump = note.querySelector('[data-nav-jump="settings"]');
    expect(jump).not.toBeNull();
    // Tapping the prompt jumps to the Settings view (delegated nav-jump click).
    jump.click();
    expect(window.VoltDashboard.state.view).toBe('settings');

    // Only two of the three prefs set → still the prompt state, still no number.
    window.VoltDashboard.prefs.set('mpg', 30);
    window.VoltDashboard.prefs.set('gasPricePerGal', 4);
    window.VoltDashboard.renderInsightStats();
    expect(document.getElementById('insightSavingsRow').hidden).toBe(false);
    expect(document.getElementById('insightSavings').textContent).toBe('--');
    expect(document.getElementById('insightSavingsNote').querySelector('[data-nav-jump="settings"]')).not.toBeNull();
  });

  it('replaces the prompt link with a plain assumptions line once all prefs are set', async () => {
    const getInsights = vi.fn(() =>
      JSON.stringify({ tripCount: 12, totalDistanceMeters: 5000 * 1609.344 }));
    await loadWithInsights(getInsights);
    // Prompt state first…
    expect(document.getElementById('insightSavingsNote').querySelector('[data-nav-jump="settings"]')).not.toBeNull();
    // …then a full estimate clears the link so no stale tap-through survives.
    window.VoltDashboard.prefs.set('mpg', 30);
    window.VoltDashboard.prefs.set('gasPricePerGal', 4);
    window.VoltDashboard.prefs.set('pricePerKwh', 0.14);
    window.VoltDashboard.renderInsightStats();
    const note = document.getElementById('insightSavingsNote');
    expect(note.querySelector('[data-nav-jump="settings"]')).toBeNull();
    expect(note.textContent).toContain('Estimated');
  });

  it('computes lifetime savings once all prefs and distance are present', async () => {
    const miles = 5000;
    const getInsights = vi.fn(() =>
      JSON.stringify({ tripCount: 12, totalDistanceMeters: miles * 1609.344 }));
    await loadWithInsights(getInsights);

    window.VoltDashboard.prefs.set('mpg', 30);
    window.VoltDashboard.prefs.set('gasPricePerGal', 4);
    window.VoltDashboard.prefs.set('pricePerKwh', 0.14);
    window.VoltDashboard.renderInsightStats();

    const row = document.getElementById('insightSavingsRow');
    expect(row.hidden).toBe(false);

    // gas cost = (5000 / 30) * 4         = 666.667
    // EV cost  = (5000 / 3.5) * 0.14     = 200.000  (3.5 mi/kWh assumed)
    // savings  =                          ≈ 466.67
    const gasCost = (miles / 30) * 4;
    const evCost = (miles / 3.5) * 0.14;
    const expected = (gasCost - evCost).toFixed(2);
    expect(document.getElementById('insightSavings').textContent).toBe(`$${expected}`);
    // The stat is clearly labelled as an estimate with its assumptions.
    const note = document.getElementById('insightSavingsNote').textContent;
    expect(note).toContain('Estimated');
    expect(note).toContain('30 mpg');
    expect(note).toContain('3.5 mi/kWh');
  });

  it('uses whole-history native energy even when the recent trip list is capped', async () => {
    const miles = 100;
    const getInsights = vi.fn(() => JSON.stringify({
      tripCount: 500,
      totalDistanceMeters: miles * 1609.344,
      loggedEnergyKwh: 20,
      loggedEnergyDistanceMeters: miles * 1609.344,
    }));
    await loadWithInsights(getInsights);
    window.VoltDashboard.state.trips = [{ distanceMeters: 1609.344, energyKwh: 100 }];
    window.VoltDashboard.prefs.set('mpg', 25);
    window.VoltDashboard.prefs.set('gasPricePerGal', 4);
    window.VoltDashboard.prefs.set('pricePerKwh', 0.2);

    window.VoltDashboard.renderInsightStats();

    expect(document.getElementById('insightSavings').textContent).toBe('$12.00');
  });

  it('hides the savings row when there is no logged distance', async () => {
    const getInsights = vi.fn(() => JSON.stringify({ tripCount: 0, totalDistanceMeters: 0 }));
    await loadWithInsights(getInsights);
    window.VoltDashboard.prefs.set('mpg', 30);
    window.VoltDashboard.prefs.set('gasPricePerGal', 4);
    window.VoltDashboard.prefs.set('pricePerKwh', 0.14);
    window.VoltDashboard.renderInsightStats();
    expect(document.getElementById('insightSavingsRow').hidden).toBe(true);
  });
});
