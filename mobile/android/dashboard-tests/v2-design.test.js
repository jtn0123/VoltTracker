// VoltTracker v2 design additions (Claude Design handoff):
//   • DTC detail bottom sheet — tapping a scanned-code row opens the sheet with
//     severity, likely causes, optional freeze frame, and a Copy report action.
//   • Scan-progress narration — cosmetic Mode 03/07/02 phases while a native
//     scan runs, completing only on the real scan-complete status.
//   • Drive tab trip-energy strip — latest drive's net kWh + estimated cost.
//   • Charge history rows — proportional kWh background bars.
//   • Insights "This week" card — per-day bars with a Distance/Efficiency mode.
//   • Map trip list — "Long trips" filter and per-row estimated cost.
// All rendering stays XSS-safe (createElement / textContent only).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

function freshDom() {
  document.body.innerHTML = '';
  delete window.VoltDashboard;
  delete window.VoltTrackerNative;
  delete window.VoltTrackerAndroid;
  window.localStorage.clear();
}

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

describe('DTC detail bottom sheet', () => {
  let VD;
  beforeEach(async () => {
    freshDom();
    await loadDashboard();
    await window.VoltDashboard.ensureDtcData();
    VD = window.VoltDashboard;
  });

  function seedOneCode(extra = {}) {
    VD.setStorage({
      diagnosticCodeCount: 1,
      latestDiagnosticCodes: [{
        dtc: 'P0AA6', status: 'stored', statusLabel: 'stored',
        firstSeenMs: Date.now() - 3600e3, lastSeenMs: Date.now(), seenCount: 3,
        ...extra,
      }],
    });
  }

  it('opens from a scanned-code row with severity, causes, and meta', () => {
    seedOneCode();
    const row = document.querySelector('#dtcList .dtc-item:not([data-example])');
    expect(row).not.toBeNull();
    expect(row.getAttribute('role')).toBe('button');
    row.click();

    const sheet = document.getElementById('dtcDetailSheet');
    expect(sheet.hidden).toBe(false);
    expect(document.getElementById('dtcDetailBackdrop').hidden).toBe(false);
    expect(document.getElementById('dtcDetailCode').textContent).toBe('P0AA6');
    expect(document.getElementById('dtcDetailSev').dataset.severity).toBe('critical');
    expect(document.getElementById('dtcDetailMeta').textContent).toContain('stored');
    // P0AA6 is in the causes DB, so the list renders.
    expect(document.getElementById('dtcDetailCausesWrap').hidden).toBe(false);
    expect(document.querySelectorAll('#dtcDetailCauses li').length).toBeGreaterThan(0);
    // No freeze-frame payload on the row -> the grid stays out.
    expect(document.getElementById('dtcDetailFrameWrap').hidden).toBe(true);

    document.getElementById('dtcDetailClose').click();
    expect(sheet.hidden).toBe(true);
    expect(document.getElementById('dtcDetailBackdrop').hidden).toBe(true);
  });

  it('renders the freeze-frame conditions grid when the row carries one', () => {
    seedOneCode({ freezeFrame: { Speed: '43 mph', SOC: '12%' } });
    document.querySelector('#dtcList .dtc-item:not([data-example])').click();
    const wrap = document.getElementById('dtcDetailFrameWrap');
    expect(wrap.hidden).toBe(false);
    const cells = document.querySelectorAll('#dtcDetailFrame span');
    expect(cells).toHaveLength(2);
    expect(cells[0].textContent).toContain('Speed');
    expect(cells[0].textContent).toContain('43 mph');
  });

  it('Copy report writes a plain-text report to the clipboard', async () => {
    const writeText = vi.fn(() => Promise.resolve());
    Object.defineProperty(window.navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    });
    seedOneCode();
    document.querySelector('#dtcList .dtc-item:not([data-example])').click();
    document.getElementById('dtcDetailCopy').click();
    expect(writeText).toHaveBeenCalledTimes(1);
    const report = writeText.mock.calls[0][0];
    expect(report).toContain('P0AA6');
    expect(report).toContain('Severity: Critical');
    expect(report).toContain('Likely causes:');
  });

  it('a lookup hit links through to the same detail sheet', () => {
    const input = document.getElementById('dtcSearchInput');
    input.value = 'P0420';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    const detailBtn = document.querySelector('#dtcSearchResult .dtc-lookup-detail');
    expect(detailBtn).not.toBeNull();
    expect(detailBtn.textContent).toContain('tap for detail');
    detailBtn.click();
    expect(document.getElementById('dtcDetailSheet').hidden).toBe(false);
    expect(document.getElementById('dtcDetailCode').textContent).toBe('P0420');
  });

  it('a malformed lookup miss explains the code format', () => {
    const input = document.getElementById('dtcSearchInput');
    input.value = 'ZZZ';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    expect(document.querySelector('#dtcSearchResult .dtc-lookup-desc').textContent)
      .toContain('letter + 4 digits');
  });
});

describe('DTC scan-progress narration', () => {
  let VD;
  beforeEach(async () => {
    freshDom();
    vi.useFakeTimers();
    await loadDashboard();
    VD = window.VoltDashboard;
  });
  afterEach(() => vi.useRealTimers());

  it('narrates phases while scanning and parks below 100% until scan-complete', () => {
    VD.state.status = { state: 'scanning' };
    VD.startDtcScanProgress(false);
    const block = document.getElementById('dtcScanProgress');
    expect(block.hidden).toBe(false);
    expect(document.getElementById('dtcScanPhase').textContent).toBe('Waking modules…');

    // Long scanning window: the bar must cap at 94%, never claim completion.
    vi.advanceTimersByTime(10_000);
    expect(block.hidden).toBe(false);
    expect(document.getElementById('dtcScanPct').textContent).toBe('94%');
    expect(document.getElementById('dtcScanPhase').textContent).toBe('Pulling freeze frames (Mode 02)…');

    // The REAL completion status lets it run to 100% and then hide.
    VD.state.status = { state: 'scan-complete' };
    vi.advanceTimersByTime(400);
    expect(document.getElementById('dtcScanPct').textContent).toBe('100%');
    vi.advanceTimersByTime(1200);
    expect(block.hidden).toBe(true);
  });

  it('aborts silently when the scan dies (error status)', () => {
    VD.state.status = { state: 'scanning' };
    VD.startDtcScanProgress(true);
    vi.advanceTimersByTime(300);
    expect(document.getElementById('dtcScanProgress').hidden).toBe(false);
    VD.state.status = { state: 'error' };
    vi.advanceTimersByTime(200);
    expect(document.getElementById('dtcScanProgress').hidden).toBe(true);
  });
});

describe('Drive trip-energy strip', () => {
  let VD;
  beforeEach(async () => {
    freshDom();
    await loadDashboard({ extras: ['insights-panel.js'] });
    VD = window.VoltDashboard;
  });
  afterEach(() => window.localStorage.clear());

  function seedRouteWithTrip(energyKwh) {
    const now = Date.now();
    VD.setTrips([trip(77, now - 3600e3, 10, energyKwh)]);
    VD.setStorage({
      recentRoutes: [{
        session: { id: '77', startedAtMs: now - 3600e3, mode: 'drive' },
        distanceMeters: 16093,
        pointCount: 42,
        points: [],
      }],
    });
  }

  it('shows the latest drive energy and a cost estimate when the rate is set', () => {
    VD.prefs.set('pricePerKwh', 0.2);
    seedRouteWithTrip(3.5);
    expect(document.getElementById('tripEnergyValue').textContent).toBe('3.5 kWh');
    expect(document.getElementById('tripCostValue').textContent).toBe('≈ $0.70');
  });

  it('prompts for the rate instead of inventing a cost', () => {
    seedRouteWithTrip(3.5);
    expect(document.getElementById('tripEnergyValue').textContent).toBe('3.5 kWh');
    expect(document.getElementById('tripCostValue').textContent).toBe('set rate for cost');
  });

  it('never invents energy for a drive that logged no pack power', () => {
    VD.prefs.set('pricePerKwh', 0.2);
    seedRouteWithTrip(null);
    expect(document.getElementById('tripEnergyValue').textContent).toBe('--');
    expect(document.getElementById('tripCostValue').textContent).toBe('no energy logged');
  });
});

describe('Charge history proportional kWh bars', () => {
  let VD;
  beforeEach(async () => {
    freshDom();
    await loadDashboard();
    VD = window.VoltDashboard;
  });

  it('scales each row bar to the biggest charge in the list', () => {
    const now = Date.now();
    VD.setStorage({
      chargeSummary: {
        chargeSessionCount: 2,
        recentSessions: [
          { id: 2, startedAtMs: now - 3600e3, endedAtMs: now, chargerType: 'level2', startSoc: 40, endSoc: 80, powerKw: 7, energyKwh: 10 },
          { id: 1, startedAtMs: now - 24 * 3600e3, endedAtMs: now - 23 * 3600e3, chargerType: 'level1', startSoc: 50, endSoc: 60, powerKw: 1.3, energyKwh: 2.5 },
        ],
      },
    });
    const bars = document.querySelectorAll('#chargeSessionsList .charge-kwh-bar');
    expect(bars).toHaveLength(2);
    expect(bars[0].style.width).toBe('100%');
    expect(bars[1].style.width).toBe('25%');
  });
});

describe('Insights "This week" card', () => {
  let VD;
  beforeEach(async () => {
    freshDom();
    await loadDashboard({ extras: ['insights-panel.js'] });
    VD = window.VoltDashboard;
  });
  afterEach(() => window.localStorage.clear());

  it('stays hidden with no drives this week', () => {
    VD.setTrips([trip(1, Date.now() - 30 * 24 * 3600e3, 12, 3)]);
    expect(document.getElementById('thisWeekCard').hidden).toBe(true);
  });

  it('charts this week and switches between distance and efficiency modes', () => {
    const todayNoon = new Date();
    todayNoon.setHours(12, 0, 0, 0);
    VD.setTrips([
      trip(1, todayNoon.getTime(), 10, 2.5),
      trip(2, todayNoon.getTime() - 3600e3, 5, 2.5),
      trip(3, Date.now() - 30 * 24 * 3600e3, 40, 9), // old drive, excluded
    ]);
    const card = document.getElementById('thisWeekCard');
    expect(card.hidden).toBe(false);
    expect(document.getElementById('thisWeekHead').textContent)
      .toBe('2 drives · 15 mi this week');
    expect(document.querySelectorAll('#thisWeekChart svg rect').length).toBeGreaterThan(0);

    // Efficiency mode: both drives landed today -> 15 mi / 5 kWh = 3 mi/kWh.
    document.querySelector('[data-week-mode="eff"]').click();
    expect(document.getElementById('thisWeekHead').textContent)
      .toContain('Best day 3.0 mi/kWh');
    // The mode persists like the other chart-view prefs.
    expect(VD.prefs.get('weekChartMode', 'dist')).toBe('eff');
  });
});

describe('map trip-list v2 filters and cost meta', () => {
  let VD;
  beforeEach(async () => {
    freshDom();
    await loadDashboard({ extras: ['insights-panel.js', 'map.js'] });
    VD = window.VoltDashboard;
  });
  afterEach(() => window.localStorage.clear());

  function seedRoutes() {
    const now = Date.now();
    VD.setTrips([trip('long1', now - 3600e3, 15, 4), trip('short1', now - 7200e3, 2, 0.5)]);
    VD.setStorage({
      recentRoutes: [
        {
          session: { id: 'long1', startedAtMs: now - 3600e3, mode: 'drive', status: 'stored' },
          distanceMeters: 15 * 1609.344, pointCount: 90, hasRoute: true,
          points: [
            { lat: 34.1, lng: -118.3, atMs: now - 3600e3 },
            { lat: 34.2, lng: -118.4, atMs: now - 3500e3 },
          ],
        },
        {
          session: { id: 'short1', startedAtMs: now - 7200e3, mode: 'drive', status: 'stored' },
          distanceMeters: 2 * 1609.344, pointCount: 30, hasRoute: true,
          points: [
            { lat: 34.1, lng: -118.3, atMs: now - 7200e3 },
            { lat: 34.11, lng: -118.31, atMs: now - 7100e3 },
          ],
        },
      ],
    });
    VD.renderMapIfLoaded();
  }

  it('the Long trips chip keeps only drives of 10 mi or more', () => {
    seedRoutes();
    const rows = () => document.querySelectorAll('#mapSessionList [data-map-session]');
    expect(rows().length).toBe(2);
    const longToggle = document.getElementById('mapSessionLongOnly');
    longToggle.click();
    expect(longToggle.dataset.on).toBe('true');
    expect(rows().length).toBe(1);
    expect(rows()[0].dataset.mapSession).toBe('long1');
    longToggle.click();
    expect(rows().length).toBe(2);
  });

  it('adds the estimated cost to a row meta when the rate is set', () => {
    VD.prefs.set('pricePerKwh', 0.25);
    seedRoutes();
    const row = Array.from(document.querySelectorAll('#mapSessionList [data-map-session]'))
      .find((node) => node.dataset.mapSession === 'long1');
    // 4 kWh × $0.25 = $1.00, appended to the date · distance · pts meta line.
    expect(row.textContent).toContain('$1.00');
  });
});
