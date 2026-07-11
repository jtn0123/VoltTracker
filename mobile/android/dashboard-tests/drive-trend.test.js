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

  it('stays hidden until three drives carry ambient and energy data', () => {
    window.VoltDashboard.setTrips([
      tempTrip(1, -5, 30, 10),
      tempTrip(2, 22, 40, 10),
    ]);
    expect(document.getElementById('tempEffCard').hidden).toBe(true);
  });

  it('plots a dot per drive and headlines the peak-range temperature', () => {
    window.VoltDashboard.setTrips([
      // Cold drives: 3.0 mi/kWh → ~42 mi estimated range.
      tempTrip(1, -5, 30, 10),
      tempTrip(2, -6, 30, 10),
      // Warm drives: 4.0 mi/kWh → ~56 mi estimated range. Buckets are 5°C wide,
      // so 22°C→20 and 25°C→25; the tie resolves to the warmer bucket.
      tempTrip(3, 22, 40, 10),
      tempTrip(4, 25, 40, 10),
    ]);

    const card = document.getElementById('tempEffCard');
    expect(card.hidden).toBe(false);
    // Peak bucket = 25°C (77°F) at 4.0 mi/kWh × 14 kWh usable = 56 mi.
    expect(document.getElementById('tempEffHead').textContent).toBe(
      'Range peaks near 77°F — about 56 mi',
    );
    // One dot per qualifying drive.
    expect(document.querySelectorAll('#tempEffChart circle')).toHaveLength(4);
    // C4: the chart is a labelled image whose aria-label restates the current
    // peak, so a re-render after new drives re-summarizes for AT users too.
    const svg = document.querySelector('#tempEffChart svg');
    expect(svg.getAttribute('role')).toBe('img');
    expect(svg.getAttribute('aria-label')).toBe(
      'Estimated EV range for each drive against outside temperature; ' +
        'range peaks near 77°F at about 56 mi',
    );
  });

  it('uses Celsius and km in metric mode', () => {
    window.VoltDashboard.prefs.set('units', 'metric');
    window.VoltDashboard.setTrips([
      tempTrip(1, -5, 30, 10),
      tempTrip(2, -6, 30, 10),
      tempTrip(3, 22, 40, 10),
      tempTrip(4, 25, 40, 10),
    ]);
    // 56 mi ≈ 90 km.
    expect(document.getElementById('tempEffHead').textContent).toBe(
      'Range peaks near 25°C — about 90 km',
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
