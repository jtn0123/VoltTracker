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
    await loadDashboard({ bridge: createVoltBridgeFixture({ getInsights }) });
  }

  beforeEach(() => window.localStorage.clear());
  afterEach(() => window.localStorage.clear());

  it('hides the savings row until mpg, gas price, and electricity rate are all set', async () => {
    // 5000 miles logged, but no comparison prefs yet → row hidden.
    const getInsights = vi.fn(() =>
      JSON.stringify({ tripCount: 12, totalDistanceMeters: 5000 * 1609.344 }));
    await loadWithInsights(getInsights);

    const row = document.getElementById('insightSavingsRow');
    expect(row.hidden).toBe(true);

    // Only two of the three prefs set → still hidden.
    window.VoltDashboard.prefs.set('mpg', 30);
    window.VoltDashboard.prefs.set('gasPricePerGal', 4);
    window.VoltDashboard.renderInsightStats();
    expect(document.getElementById('insightSavingsRow').hidden).toBe(true);
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
