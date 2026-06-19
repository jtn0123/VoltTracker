import { beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

describe('insights HV-pack detail stats', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  it('hides the pack stat row when there is no battery snapshot', () => {
    window.VoltDashboard.setStorage({ batterySummary: {} });
    expect(document.getElementById('realPackStats').hidden).toBe(true);
  });

  it('surfaces voltage, temp, health and power from the latest battery snapshot', () => {
    window.VoltDashboard.setStorage({
      batterySummary: {
        latestBatterySnapshot: {
          soc: 64,
          packVoltage: 364.2,
          batteryTempC: 23,
          sohPct: 92,
          packPowerKw: -7.4,
        },
      },
    });

    const row = document.getElementById('realPackStats');
    expect(row.hidden).toBe(false);
    const text = row.textContent;
    expect(text).toContain('Pack');
    expect(text).toContain('364 V');
    // Units default to imperial, so the seeded 23°C renders as °F (23·9/5+32 = 73).
    expect(text).toContain('73°F');
    expect(text).toContain('92%');
    expect(text).toContain('-7.4 kW');
  });

  it('omits only the missing fields and keeps the present ones', () => {
    window.VoltDashboard.setStorage({
      batterySummary: {
        latestBatterySnapshot: { soc: 50, packVoltage: 360, sohPct: null, packPowerKw: null },
      },
    });
    const row = document.getElementById('realPackStats');
    expect(row.hidden).toBe(false);
    expect(row.textContent).toContain('360 V');
    expect(row.textContent).not.toContain('Health');
    expect(row.querySelectorAll('div')).toHaveLength(1);
  });
});

describe('insights empty-state gating (fresh vs stale state.insights)', () => {
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

  it('hides the empty state once loadInsights refreshes the data a setStorage pass started with', async () => {
    // Bootstrap sees no insights, so the first-run guide is visible.
    const getInsights = vi.fn(() => '{}');
    await loadWithInsights(getInsights);
    expect(document.getElementById('insightsEmptyState').hidden).toBe(false);

    // The native layer now has a logged trip. setStorage runs renderRealV2Ui
    // (which reads the STALE state.insights) before VD.loadInsights() refreshes
    // it — the insights render path must re-toggle the empty state afterwards.
    getInsights.mockReturnValue(JSON.stringify({ tripCount: 3, totalDistanceMeters: 5200 }));
    window.VoltDashboard.setStorage({ sampleCount: 10 });
    await Promise.resolve();
    expect(document.getElementById('insightsEmptyState').hidden).toBe(true);
  });

  it('re-shows the empty state when the insights data is cleared', async () => {
    const getInsights = vi.fn(() => JSON.stringify({ tripCount: 3, totalDistanceMeters: 5200 }));
    await loadWithInsights(getInsights);
    expect(document.getElementById('insightsEmptyState').hidden).toBe(true);

    // Clearing data: renderRealV2Ui still sees the stale trips, loadInsights
    // then refreshes to {} — the guide must come back.
    getInsights.mockReturnValue('{}');
    window.VoltDashboard.setStorage({});
    await Promise.resolve();
    expect(document.getElementById('insightsEmptyState').hidden).toBe(false);
  });
});

describe('efficiency-vs-speed scatter axis + units', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard({ extras: ['insights-panel.js'] });
  });

  // A pre-enriched route (skips enrichRouteEff) with 8 highway points at
  // 85-95 mph — above the old hardcoded 75 mph axis cap.
  function fastRouteStorage() {
    const base = 1_700_000_000_000;
    const mphs = [85, 87, 89, 90, 91, 92, 94, 95];
    return {
      recentRoutes: [{
        _effDone: true,
        points: mphs.map((mph, i) => ({
          lat: 34.05 + i * 0.002,
          lng: -118.25,
          atMs: base + i * 10_000,
          speedMps: mph / 2.2369363,
          eff: 2.5 + (i % 4) * 0.4,
        })),
      }],
    };
  }

  it('keeps 85-95 mph samples inside the plot by growing the x-axis with the data', () => {
    window.VoltDashboard.setStorage(fastRouteStorage());
    window.VoltDashboard.renderInsightScatter();
    const card = document.getElementById('effScatterCard');
    expect(card.hidden).toBe(false);
    const svg = document.querySelector('#effScatter svg');
    const w = Number(svg.getAttribute('width'));
    const padL = 38;
    const padR = 12;
    const circles = Array.from(svg.querySelectorAll('circle'));
    expect(circles).toHaveLength(8);
    circles.forEach((c) => {
      const cx = Number(c.getAttribute('cx'));
      expect(cx).toBeGreaterThanOrEqual(padL);
      // Pre-fix, a 95 mph sample mapped to padL + (95/75)·plotWidth — past the
      // right edge of the viewBox.
      expect(cx).toBeLessThanOrEqual(w - padR);
    });
    // The axis grew: a 90 gridline label exists (axis max ≥ 95).
    const labels = Array.from(svg.querySelectorAll('text')).map((t) => t.textContent);
    expect(labels).toContain('90');
  });

  it('scatter stats follow the units preference instead of hardcoding mi/kWh', () => {
    window.VoltDashboard.setStorage(fastRouteStorage());
    window.VoltDashboard.renderInsightScatter();
    const stats = () => document.getElementById('effScatterStats').textContent;
    expect(stats()).toContain('mi/kWh');

    try {
      window.VoltDashboard.prefs.set('units', 'metric');
      window.VoltDashboard.renderInsightScatter();
      expect(stats()).toContain('km/kWh');
      expect(stats()).not.toContain('mi/kWh');
    } finally {
      window.VoltDashboard.prefs.set('units', 'imperial');
    }
  });
});

describe('efficiency-vs-speed chart view switcher + grade-normalization', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    // Clear any view choice a prior test persisted so each starts from default.
    try {
      window.localStorage.removeItem('vt.pref.effChartView');
    } catch (_err) {
      /* localStorage always present in jsdom */
    }
    await loadDashboard({ extras: ['insights-panel.js'] });
  });

  // A route whose samples cluster at one speed, so 10-mph bucketing yields enough
  // (>=3) samples per bucket to draw bars/curve. `altStep` sets a constant grade:
  // 0 = flat, negative = downhill.
  function clusteredRoute(altStep) {
    const base = 1_700_000_000_000;
    const n = 8;
    const points = [];
    for (let i = 0; i < n; i += 1) {
      points.push({
        lat: 34.05 + i * 0.001,
        lng: -118.25,
        atMs: base + i * 5000,
        speedMps: 80 / 2.2369363, // ~80 mph, highway bucket
        altM: 100 + i * altStep,
        eff: 3.0,
      });
    }
    return { recentRoutes: [{ _effDone: true, points }] };
  }

  function setView(view) {
    window.VoltDashboard.prefs.set('effChartView', view);
  }

  it('renders bucket bars (rects) instead of per-sample dots in the bars view', () => {
    setView('bars');
    window.VoltDashboard.setStorage(clusteredRoute(0));
    window.VoltDashboard.renderInsightScatter();
    const svg = document.querySelector('#effScatter svg');
    const rects = svg.querySelectorAll('rect');
    const circles = svg.querySelectorAll('circle');
    // At least one bucket bar; the only circle is the peak marker (not 8 dots).
    expect(rects.length).toBeGreaterThanOrEqual(1);
    expect(circles.length).toBe(1);
  });

  it('renders a smoothed curve path with no per-sample dots in the curve view', () => {
    setView('curve');
    // Two speed clusters so the curve has >=2 buckets to connect.
    const slow = clusteredRoute(0).recentRoutes[0];
    const fast = clusteredRoute(0).recentRoutes[0];
    slow.points.forEach((p) => {
      p.speedMps = 20 / 2.2369363;
    });
    window.VoltDashboard.setStorage({ recentRoutes: [slow, fast] });
    window.VoltDashboard.renderInsightScatter();
    const svg = document.querySelector('#effScatter svg');
    const paths = svg.querySelectorAll('path');
    const circles = svg.querySelectorAll('circle');
    // Confidence band + median curve = at least 2 paths; only the peak marker is a circle.
    expect(paths.length).toBeGreaterThanOrEqual(2);
    expect(circles.length).toBeLessThanOrEqual(1);
  });

  it('keeps per-sample dots in the explicit scatter view', () => {
    setView('scatter');
    window.VoltDashboard.setStorage(clusteredRoute(0));
    window.VoltDashboard.renderInsightScatter();
    const circles = document.querySelectorAll('#effScatter svg circle');
    expect(circles.length).toBe(8);
  });

  it('falls back to the scatter view when no view preference is stored', () => {
    // beforeEach cleared effChartView, so this exercises the default path with no
    // setView() call — scatter must render its per-sample dots.
    window.VoltDashboard.setStorage(clusteredRoute(0));
    window.VoltDashboard.renderInsightScatter();
    const circles = document.querySelectorAll('#effScatter svg circle');
    expect(circles.length).toBe(8);
  });

  it('grade-normalizes efficiency so a downhill run plots lower than the same eff on the flat', () => {
    setView('scatter');
    const maxCy = () => {
      const circles = Array.from(document.querySelectorAll('#effScatter svg circle'));
      return Math.max(...circles.map((c) => Number(c.getAttribute('cy'))));
    };
    // Flat run: grade ~0, so the flat-equivalent efficiency == the raw 3.0.
    window.VoltDashboard.setStorage(clusteredRoute(0));
    window.VoltDashboard.renderInsightScatter();
    const flatLowest = maxCy();

    // Downhill run: same speed + raw eff, but the descent is normalized out, which
    // LOWERS the flat-equivalent efficiency -> the dot sits lower (larger cy).
    window.VoltDashboard.setStorage(clusteredRoute(-7));
    window.VoltDashboard.renderInsightScatter();
    const downhillLowest = maxCy();

    expect(downhillLowest).toBeGreaterThan(flatLowest + 20);
  });
});
