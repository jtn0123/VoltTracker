import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

// Regression: the SOC caption and the "More signals" EV-range cell used to show
// evDistanceThisCycleKm (222487, distance driven this cycle) labelled as
// "EV range". They must use the car's own estimate, evRangeKm (2241A6), and
// hide it when missing or stale.
describe('telemetry.ts — EV range caption', () => {
  beforeEach(async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-06-03T12:00:00Z'));
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  async function render(extra) {
    const { initialTelemetryState } = await import(
      '../app/src/main/dashboard-src/js/telemetry-state.ts'
    );
    const VD = window.VoltDashboard;
    VD.state.telemetry = { ...initialTelemetryState(), soc: 63, ...extra };
    VD.updateLiveUi();
    return {
      caption: document.getElementById('driveSocSub').textContent,
      moreCell: document.getElementById('moreEvRange').firstChild?.textContent ?? '',
    };
  }

  it('never labels this-cycle distance as EV range', async () => {
    const { caption, moreCell } = await render({ evDistanceThisCycleKm: 12.5 });
    expect(caption).toBe('state of charge');
    expect(moreCell).toBe('--');
  });

  it("shows the car's own range estimate", async () => {
    const { caption, moreCell } = await render({
      evDistanceThisCycleKm: 12.5,
      evRangeKm: 42,
      evRangeStaleMs: 1_000,
    });
    // 42 km → 26 mi (imperial default).
    expect(caption).toBe('≈ 26 mi EV range');
    expect(moreCell).toBe('26 mi');
  });

  it('hides a stale range estimate', async () => {
    const { caption, moreCell } = await render({ evRangeKm: 42, evRangeStaleMs: 180_000 });
    expect(caption).toBe('state of charge');
    expect(moreCell).toBe('--');
  });

  it('treats missing, negative and non-numeric readings as no range', async () => {
    for (const evRangeKm of [null, '', -3, 'x']) {
      const { caption, moreCell } = await render({ evRangeKm });
      expect(caption).toBe('state of charge');
      expect(moreCell).toBe('--');
    }
    // A depleted pack reports a real 0: the cell shows it, the caption stays plain.
    const depleted = await render({ evRangeKm: 0, evRangeStaleMs: 500 });
    expect(depleted.caption).toBe('state of charge');
    expect(depleted.moreCell).toMatch(/^0(\.0+)? mi$/);
  });
});
