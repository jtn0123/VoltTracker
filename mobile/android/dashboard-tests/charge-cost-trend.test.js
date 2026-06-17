// M5 — charging cost/energy trend over time. renderChargeCostTrend
// (storage-status.ts) buckets logged charge sessions by calendar month and
// plots monthly energy (kWh) — or estimated cost (kWh × electricity rate) when
// the rate is set — as an SVG bar chart. Hidden until two or more months exist.
// XSS-safe (createElement / setSvgAttrs / textContent only).
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

// A charge session in calendar month `monthOffset` months before a fixed
// reference month, carrying `energyKwh`.
function monthMs(year, month, day = 15) {
  return new Date(year, month, day, 12, 0, 0).getTime();
}

function chargeSummary(sessions) {
  return { chargeSummary: { chargeSessionCount: sessions.length, recentSessions: sessions } };
}

describe('charging cost/energy trend (M5)', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    window.localStorage.clear();
    await loadDashboard();
  });
  afterEach(() => window.localStorage.clear());

  it('hides the trend card with fewer than two months of energy', () => {
    window.VoltDashboard.setStorage(chargeSummary([
      { id: 1, startedAtMs: monthMs(2026, 2, 4), energyKwh: 8 },
      { id: 2, startedAtMs: monthMs(2026, 2, 20), energyKwh: 6 },
    ]));
    expect(document.getElementById('chargeCostTrendCard').hidden).toBe(true);
    expect(document.querySelector('#chargeCostTrendChart .charge-cost-trend-svg')).toBeNull();
  });

  it('charts energy per month and summary stats when no rate is set', () => {
    window.VoltDashboard.setStorage(chargeSummary([
      { id: 1, startedAtMs: monthMs(2026, 0, 10), energyKwh: 10 },
      { id: 2, startedAtMs: monthMs(2026, 0, 24), energyKwh: 5 }, // Jan total 15
      { id: 3, startedAtMs: monthMs(2026, 1, 12), energyKwh: 8 }, // Feb total 8
      { id: 4, startedAtMs: monthMs(2026, 2, 3), energyKwh: 12 }, // Mar total 12
    ]));

    const card = document.getElementById('chargeCostTrendCard');
    expect(card.hidden).toBe(false);
    expect(document.getElementById('chargeCostTrendEmpty').hidden).toBe(true);
    expect(document.getElementById('chargeCostTrendTitle').textContent).toBe('Monthly charging energy');
    // Latest month (Mar) = 12 kWh.
    expect(document.getElementById('chargeCostTrendLatest').textContent).toBe('12.0 kWh');
    expect(document.getElementById('chargeCostTrendMonths').textContent).toBe('3');
    // Total 15 + 8 + 12 = 35 kWh.
    expect(document.getElementById('chargeCostTrendTotal').textContent).toBe('35.0 kWh');
    // One bar per month.
    const bars = document.querySelectorAll('#chargeCostTrendChart .charge-cost-trend-svg rect');
    expect(bars).toHaveLength(3);
  });

  it('shows estimated cost per month when an electricity rate is set', () => {
    window.VoltDashboard.prefs.set('pricePerKwh', 0.2);
    window.VoltDashboard.setStorage(chargeSummary([
      { id: 1, startedAtMs: monthMs(2026, 0, 10), energyKwh: 10 }, // Jan $2.00
      { id: 2, startedAtMs: monthMs(2026, 1, 12), energyKwh: 20 }, // Feb $4.00
    ]));

    expect(document.getElementById('chargeCostTrendCard').hidden).toBe(false);
    expect(document.getElementById('chargeCostTrendTitle').textContent).toBe('Monthly charging cost');
    // Latest month (Feb) = 20 kWh × $0.20 = $4.00.
    expect(document.getElementById('chargeCostTrendLatest').textContent).toBe('$4.00');
    // Total cost = (10 + 20) × 0.20 = $6.00.
    expect(document.getElementById('chargeCostTrendTotal').textContent).toBe('$6.00');
    // Avg = $6.00 / 2 = $3.00.
    expect(document.getElementById('chargeCostTrendAvg').textContent).toBe('$3.00');
  });

  it('bills public sessions at the public rate per month when one is set (M3)', () => {
    window.VoltDashboard.prefs.set('pricePerKwh', 0.1);
    window.VoltDashboard.prefs.set('publicPricePerKwh', 0.5);
    window.VoltDashboard.setStorage(chargeSummary([
      // Jan: 10 kWh home L2 → $1.00.
      { id: 1, startedAtMs: monthMs(2026, 0, 10), chargerType: 'level2', energyKwh: 10 },
      // Feb: 10 kWh home L2 ($1.00) + 4 kWh public DC-fast ($2.00) → $3.00.
      { id: 2, startedAtMs: monthMs(2026, 1, 5), chargerType: 'level2', energyKwh: 10 },
      { id: 3, startedAtMs: monthMs(2026, 1, 20), chargerType: 'dc_fast', energyKwh: 4 },
    ]));

    expect(document.getElementById('chargeCostTrendTitle').textContent).toBe('Monthly charging cost');
    // Latest month (Feb) = $1.00 home + $2.00 public = $3.00.
    expect(document.getElementById('chargeCostTrendLatest').textContent).toBe('$3.00');
    // Total = $1.00 (Jan) + $3.00 (Feb) = $4.00.
    expect(document.getElementById('chargeCostTrendTotal').textContent).toBe('$4.00');
    // Avg = $4.00 / 2 = $2.00.
    expect(document.getElementById('chargeCostTrendAvg').textContent).toBe('$2.00');
  });

  it('bills every session at the home rate when no public rate is set (unchanged fallback)', () => {
    window.VoltDashboard.prefs.set('pricePerKwh', 0.1);
    window.VoltDashboard.setStorage(chargeSummary([
      { id: 1, startedAtMs: monthMs(2026, 0, 10), chargerType: 'level2', energyKwh: 10 }, // Jan $1.00
      { id: 2, startedAtMs: monthMs(2026, 1, 20), chargerType: 'dc_fast', energyKwh: 4 }, // Feb $0.40 (home rate)
    ]));
    expect(document.getElementById('chargeCostTrendLatest').textContent).toBe('$0.40');
    expect(document.getElementById('chargeCostTrendTotal').textContent).toBe('$1.40');
  });

  it('ignores sessions with no positive energy when bucketing', () => {
    window.VoltDashboard.setStorage(chargeSummary([
      { id: 1, startedAtMs: monthMs(2026, 0, 10), energyKwh: 10 },
      { id: 2, startedAtMs: monthMs(2026, 1, 12), energyKwh: null }, // dropped
      { id: 3, startedAtMs: monthMs(2026, 2, 3), energyKwh: 12 },
    ]));
    // Feb produced no energy, so only Jan + Mar count → 2 months, 2 bars.
    expect(document.getElementById('chargeCostTrendMonths').textContent).toBe('2');
    expect(document.querySelectorAll('#chargeCostTrendChart .charge-cost-trend-svg rect')).toHaveLength(2);
  });

  it('hides the card and clears the chart when the database is cleared', () => {
    window.VoltDashboard.setStorage(chargeSummary([
      { id: 1, startedAtMs: monthMs(2026, 0, 10), energyKwh: 10 },
      { id: 2, startedAtMs: monthMs(2026, 1, 12), energyKwh: 8 },
    ]));
    expect(document.getElementById('chargeCostTrendCard').hidden).toBe(false);

    window.VoltDashboard.setStorage({ chargeSummary: { chargeSessionCount: 0, recentSessions: [] } });
    expect(document.getElementById('chargeCostTrendCard').hidden).toBe(true);
    expect(document.querySelector('#chargeCostTrendChart .charge-cost-trend-svg')).toBeNull();
  });

  it('builds the chart with SVG primitives only (no markup injection)', () => {
    window.VoltDashboard.setStorage(chargeSummary([
      { id: 1, startedAtMs: monthMs(2026, 0, 10), energyKwh: 10 },
      { id: 2, startedAtMs: monthMs(2026, 1, 12), energyKwh: 8 },
    ]));
    const chart = document.getElementById('chargeCostTrendChart');
    const svg = chart.querySelector('svg');
    expect(svg).not.toBeNull();
    // No raw HTML smuggled in — only SVG element children.
    expect(chart.querySelector('script')).toBeNull();
    expect(svg.getAttribute('role')).toBe('img');
  });
});
