// Monthly driving trend (Insights tab). renderDriveTrend (insights-panel.ts)
// buckets the logged trips by calendar month and plots monthly distance as an
// SVG bar chart, with efficiency (integrated HV energy) and estimated-cost
// stats. Hidden until two or more months of drives exist. Mirrors the charging
// trend (charge-cost-trend.test.js); the SVG builder is shared via
// VD.buildMonthlyTrendSvg. XSS-safe (createElement / setSvgAttrs / textContent).
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

function monthMs(year, month, day = 15) {
  return new Date(year, month, day, 12, 0, 0).getTime();
}

// One logged trip: `miles` long, starting in the given month, optionally with
// integrated HV energy and an EV share (as the native trip JSON carries them).
function trip(id, startedAtMs, miles, energyKwh = null) {
  return {
    id: String(id),
    sessionId: id,
    startedAtMs,
    endedAtMs: startedAtMs + 30 * 60 * 1000,
    durationMs: 30 * 60 * 1000,
    distanceMeters: miles * 1609.344,
    maxSpeedKph: 90,
    hasRoute: true,
    energyKwh,
    evShare: null,
  };
}

describe('monthly driving trend (Insights)', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    window.localStorage.clear();
    await loadDashboard({ extras: ['insights-panel.js'] });
  });
  afterEach(() => window.localStorage.clear());

  it('hides the trend card with fewer than two months of drives', () => {
    window.VoltDashboard.setTrips([
      trip(1, monthMs(2026, 2, 4), 10),
      trip(2, monthMs(2026, 2, 20), 5),
    ]);
    expect(document.getElementById('driveTrendCard').hidden).toBe(true);
  });

  it('charts distance per month with an honest efficiency stat', () => {
    window.VoltDashboard.setTrips([
      trip(1, monthMs(2026, 0, 10), 100, 25), // Jan: 100 mi, 25 kWh
      trip(2, monthMs(2026, 0, 24), 50),      // Jan: +50 mi, no power logged
      trip(3, monthMs(2026, 1, 12), 80, 20),  // Feb: 80 mi, 20 kWh
    ]);

    const card = document.getElementById('driveTrendCard');
    expect(card.hidden).toBe(false);
    // Latest month (Feb) = 80 mi (imperial default units).
    expect(document.getElementById('driveTrendLatest').textContent).toBe('80 mi');
    // Avg / month = (150 + 80) / 2 = 115 mi.
    expect(document.getElementById('driveTrendAvg').textContent).toBe('115 mi');
    // Efficiency pairs distance and energy from the SAME trips: (100 + 80) mi
    // over 45 kWh = 4.0 mi/kWh — the 50 mi drive without power data is excluded.
    expect(document.getElementById('driveTrendEff').textContent).toBe('4.0 mi/kWh');
    // No electricity rate set → no cost estimate.
    expect(document.getElementById('driveTrendCost').textContent).toBe('--');
    // One bar per month.
    const bars = document.querySelectorAll('#driveTrendChart rect');
    expect(bars).toHaveLength(2);
  });

  it('estimates cost per mile when an electricity rate is set', () => {
    window.VoltDashboard.prefs.set('pricePerKwh', 0.2);
    window.VoltDashboard.setTrips([
      trip(1, monthMs(2026, 0, 10), 100, 25),
      trip(2, monthMs(2026, 1, 12), 80, 20),
    ]);
    // 45 kWh × $0.20 over 180 mi = $0.05 / mi.
    expect(document.getElementById('driveTrendCostLabel').textContent).toBe('Est cost / mi');
    expect(document.getElementById('driveTrendCost').textContent).toBe('$0.05');
  });

  it('renders distances, efficiency, and the cost label in metric mode', () => {
    window.VoltDashboard.prefs.set('units', 'metric');
    window.VoltDashboard.prefs.set('pricePerKwh', 0.2);
    window.VoltDashboard.setTrips([
      trip(1, monthMs(2026, 0, 10), 100, 25),
      trip(2, monthMs(2026, 1, 12), 80, 20),
    ]);
    // 80 mi ≈ 129 km; avg (100+80)/2 = 90 mi ≈ 145 km.
    expect(document.getElementById('driveTrendLatest').textContent).toBe('129 km');
    expect(document.getElementById('driveTrendAvg').textContent).toBe('145 km');
    // 4.0 mi/kWh ≈ 6.4 km/kWh.
    expect(document.getElementById('driveTrendEff').textContent).toBe('6.4 km/kWh');
    // 45 kWh × $0.20 over 180 mi ≈ 290 km → $0.03 / km.
    expect(document.getElementById('driveTrendCostLabel').textContent).toBe('Est cost / km');
    expect(document.getElementById('driveTrendCost').textContent).toBe('$0.03');
  });

  it('leaves efficiency and cost as placeholders when no trip logged power', () => {
    window.VoltDashboard.prefs.set('pricePerKwh', 0.2);
    window.VoltDashboard.setTrips([
      trip(1, monthMs(2026, 0, 10), 100),
      trip(2, monthMs(2026, 1, 12), 80),
    ]);
    expect(document.getElementById('driveTrendCard').hidden).toBe(false);
    expect(document.getElementById('driveTrendEff').textContent).toBe('--');
    expect(document.getElementById('driveTrendCost').textContent).toBe('--');
  });
});

describe('efficiency vs outside temperature (Insights)', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    window.localStorage.clear();
    await loadDashboard({ extras: ['insights-panel.js'] });
  });
  afterEach(() => window.localStorage.clear());

  // A drive with a window-averaged ambient reading and integrated energy.
  function tempTrip(id, tempC, miles, kwh) {
    const t = trip(id, monthMs(2026, 0, (id % 27) + 1), miles, kwh);
    t.avgOutsideTempC = tempC;
    return t;
  }

  it('stays hidden until two temperature bands hold two drives each', () => {
    window.VoltDashboard.setTrips([
      tempTrip(1, -5, 30, 10),
      tempTrip(2, -6, 30, 10),
      tempTrip(3, 22, 40, 10), // warm band has only one drive
    ]);
    expect(document.getElementById('tempEffCard').hidden).toBe(true);
  });

  it('compares cold-band efficiency against the best band with an honest headline', () => {
    window.VoltDashboard.setTrips([
      // Cold band (<32°F): 60 mi / 20 kWh = 3.0 mi/kWh.
      tempTrip(1, -5, 30, 10),
      tempTrip(2, -6, 30, 10),
      // Mild band (68–86°F): 80 mi / 20 kWh = 4.0 mi/kWh.
      tempTrip(3, 22, 40, 10),
      tempTrip(4, 25, 40, 10),
    ]);

    const card = document.getElementById('tempEffCard');
    expect(card.hidden).toBe(false);
    // 3.0 vs 4.0 → 25% lower in the cold.
    expect(document.getElementById('tempEffHead').textContent).toBe('25% lower efficiency in <32°F weather');
    expect(document.getElementById('tempEffBest').textContent).toBe('4.0 mi/kWh');
    expect(document.getElementById('tempEffBestLabel').textContent).toBe('Best (68–86°F)');
    expect(document.getElementById('tempEffCold').textContent).toBe('3.0 mi/kWh');
    expect(document.getElementById('tempEffTrips').textContent).toBe('4');
    // One bar per populated band.
    expect(document.querySelectorAll('#tempEffChart rect')).toHaveLength(2);
  });

  it('uses Celsius band labels and km/kWh in metric mode', () => {
    window.VoltDashboard.prefs.set('units', 'metric');
    window.VoltDashboard.setTrips([
      tempTrip(1, -5, 30, 10),
      tempTrip(2, -6, 30, 10),
      tempTrip(3, 22, 40, 10),
      tempTrip(4, 25, 40, 10),
    ]);
    expect(document.getElementById('tempEffHead').textContent).toBe('25% lower efficiency in <0°C weather');
    expect(document.getElementById('tempEffBestLabel').textContent).toBe('Best (20–30°C)');
    // 4.0 mi/kWh ≈ 6.4 km/kWh, 3.0 mi/kWh ≈ 4.8 km/kWh.
    expect(document.getElementById('tempEffBest').textContent).toBe('6.4 km/kWh');
    expect(document.getElementById('tempEffCold').textContent).toBe('4.8 km/kWh');
  });

  it('reports a steady range when the bands barely differ', () => {
    window.VoltDashboard.setTrips([
      tempTrip(1, -5, 39, 10),
      tempTrip(2, -6, 39, 10),
      tempTrip(3, 22, 40, 10),
      tempTrip(4, 25, 40, 10),
    ]);
    expect(document.getElementById('tempEffHead').textContent).toBe(
      'Efficiency holds steady across temperatures so far.',
    );
  });

  it('ignores drives without ambient or energy data', () => {
    window.VoltDashboard.setTrips([
      tempTrip(1, -5, 30, 10),
      tempTrip(2, -6, 30, 10),
      trip(3, monthMs(2026, 0, 4), 40, 10), // no temperature
      tempTrip(4, 25, 40, null), // no energy
    ]);
    expect(document.getElementById('tempEffCard').hidden).toBe(true);
  });
});

describe('lifetime electric-driving share (Insights)', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    window.localStorage.clear();
    await loadDashboard({ extras: ['insights-panel.js'] });
  });
  afterEach(() => window.localStorage.clear());

  it('renders the rounded percentage from the insights payload', () => {
    window.VoltDashboard.setInsights({ tripCount: 3, totalDistanceMeters: 5200, electricDrivingPct: 71.6 });
    expect(document.getElementById('insightElectricPct').textContent).toBe('72%');
  });

  it('keeps the placeholder when the share is unknown', () => {
    window.VoltDashboard.setInsights({ tripCount: 3, totalDistanceMeters: 5200, electricDrivingPct: null });
    expect(document.getElementById('insightElectricPct').textContent).toBe('--');
  });
});
