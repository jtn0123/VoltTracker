/**
 * Tests for the summary module — covers loadSummary and loadMpgTrend,
 * plus the empty-state DOM handling that PR #45 fixed (the
 * showMpgEmptyState fix where wiping parent.innerHTML used to remove
 * the canvas itself).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('@/api', () => ({
  api: vi.fn(),
}));

vi.mock('@/charts', () => ({
  loadChartJs: vi.fn(async () => undefined),
  createGradient: vi.fn(() => ({} as CanvasGradient)),
  getEnhancedLegend: vi.fn((display = true) => ({ display })),
  getEnhancedTooltip: vi.fn((opts = {}) => opts),
  getEnhancedAxis: vi.fn((opts = {}) => opts),
  getChartColor: vi.fn(() => '#3b82f6'),
}));

vi.mock('@/core', () => ({
  state: {
    currentTimeframe: 30,
    dateFilter: { start: null, end: null },
    mpgChart: null,
  },
  formatChartDate: vi.fn((d: Date) => d.toISOString().slice(0, 10)),
}));

import { api } from '@/api';
import { loadSummary, loadMpgTrend, loadCardSubtitles } from '@/summary';

describe('loadSummary', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.clearAllMocks();
  });

  it('does nothing when api returns an error', async () => {
    (api as ReturnType<typeof vi.fn>).mockResolvedValue({ error: 'boom', data: null });
    document.body.innerHTML = '<div id="lifetime-mpg">old</div>';
    await loadSummary();
    // The DOM should be untouched on error.
    expect(document.getElementById('lifetime-mpg')?.textContent).toBe('old');
  });

  it('does nothing when api returns no data', async () => {
    (api as ReturnType<typeof vi.fn>).mockResolvedValue({ error: null, data: null });
    document.body.innerHTML = '<div id="lifetime-mpg">old</div>';
    await loadSummary();
    expect(document.getElementById('lifetime-mpg')?.textContent).toBe('old');
  });

  it('populates the summary cards from a fully populated API response', async () => {
    document.body.innerHTML = `
      <section id="summary-section">
        <div class="skeleton-card" id="lifetime-mpg"></div>
        <div id="lifetime-miles"></div>
        <div id="tank-mpg"></div>
        <div id="tank-miles"></div>
        <div id="total-miles"></div>
        <div id="kwh-per-mile"></div>
        <div id="mi-per-kwh"></div>
        <div id="total-electric-miles"></div>
        <div id="total-kwh-used"></div>
        <div id="ev-ratio"></div>
      </section>
    `;
    (api as ReturnType<typeof vi.fn>).mockResolvedValue({
      error: null,
      data: {
        lifetime_gas_mpg: 38.5,
        lifetime_gas_miles: 12000,
        current_tank_mpg: 41.2,
        current_tank_miles: 220,
        total_miles_tracked: 25000,
        avg_kwh_per_mile: 0.32,
        mi_per_kwh: 3.1,
        total_electric_miles: 13000,
        total_kwh_used: 4160,
        ev_ratio: 52,
      },
    });

    await loadSummary();

    expect(document.getElementById('lifetime-mpg')?.innerHTML).toContain('38.5');
    expect(document.getElementById('tank-mpg')?.innerHTML).toContain('41.2');
    expect(document.getElementById('total-miles')?.innerHTML).toContain('25,000');
    expect(document.getElementById('kwh-per-mile')?.innerHTML).toContain('0.32');
    expect(document.getElementById('mi-per-kwh')?.textContent).toContain('3.1');
    expect(document.getElementById('ev-ratio')?.innerHTML).toContain('52');
    // Skeleton class is removed once data populates.
    expect(document.getElementById('lifetime-mpg')?.classList.contains('skeleton-card')).toBe(false);
  });

  it('renders no-data placeholders when API returns empty/zero values', async () => {
    document.body.innerHTML = `
      <div id="lifetime-mpg"></div>
      <div id="lifetime-miles"></div>
      <div id="tank-mpg"></div>
      <div id="tank-miles"></div>
      <div id="total-miles"></div>
      <div id="kwh-per-mile"></div>
      <div id="mi-per-kwh"></div>
      <div id="total-electric-miles"></div>
      <div id="total-kwh-used"></div>
      <div id="ev-ratio"></div>
    `;
    (api as ReturnType<typeof vi.fn>).mockResolvedValue({
      error: null,
      data: {
        lifetime_gas_mpg: null,
        lifetime_gas_miles: 0,
        current_tank_mpg: null,
        current_tank_miles: 0,
        total_miles_tracked: 0,
        avg_kwh_per_mile: null,
        mi_per_kwh: null,
        total_electric_miles: null,
        total_kwh_used: null,
        ev_ratio: null,
      },
    });

    await loadSummary();

    expect(document.getElementById('lifetime-mpg')?.textContent).toBe('--');
    expect(document.getElementById('lifetime-miles')?.textContent).toBe('No gas data yet');
    expect(document.getElementById('kwh-per-mile')?.textContent).toBe('--');
    expect(document.getElementById('mi-per-kwh')?.textContent).toBe('No electric data yet');
    expect(document.getElementById('ev-ratio')?.innerHTML).toBe('--');
  });

  it('swallows thrown errors from api gracefully', async () => {
    (api as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('network down'));
    // Should not throw
    await expect(loadSummary()).resolves.toBeUndefined();
  });
});

// JTN-487: these tests guard the regression where the top-of-page summary
// card subtitles (#soc-count, #charging-sessions) stayed stuck on the
// literal placeholder "Loading..." until the user scrolled far enough for
// the battery / charging IntersectionObserver sections to enter the
// viewport. loadCardSubtitles is now called eagerly in the critical-path
// Promise.allSettled block in main.ts and must populate (or clear) both
// subtitles regardless of scroll position.
describe('loadCardSubtitles (JTN-487)', () => {
  beforeEach(() => {
    document.body.innerHTML = `
      <div class="card-subtitle" id="soc-count">Loading...</div>
      <div class="card-subtitle" id="charging-sessions">Loading...</div>
    `;
    vi.clearAllMocks();
  });

  it('replaces "Loading..." with real subtitles when both APIs return data', async () => {
    (api as ReturnType<typeof vi.fn>).mockImplementation(async (url: string) => {
      if (url === '/api/soc/analysis') {
        return { error: null, data: { average_soc: 42, count: 17, min_soc: 10, max_soc: 90 } };
      }
      if (url === '/api/charging/summary') {
        return { error: null, data: { total_kwh: 123.4, total_sessions: 9 } };
      }
      return { error: 'unexpected url', data: null };
    });

    await loadCardSubtitles();

    // Neither subtitle should still read the placeholder text.
    expect(document.getElementById('soc-count')?.textContent).toBe('17 transitions recorded');
    expect(document.getElementById('soc-count')?.textContent).not.toBe('Loading...');
    expect(document.getElementById('charging-sessions')?.textContent).toBe('9 charging sessions');
    expect(document.getElementById('charging-sessions')?.textContent).not.toBe('Loading...');
  });

  it('shows the empty-data subtitles when both APIs return null/zero data', async () => {
    (api as ReturnType<typeof vi.fn>).mockImplementation(async (url: string) => {
      if (url === '/api/soc/analysis') {
        return { error: null, data: { average_soc: null, count: 0 } };
      }
      if (url === '/api/charging/summary') {
        return { error: null, data: { total_kwh: null, total_sessions: 0 } };
      }
      return { error: 'unexpected url', data: null };
    });

    await loadCardSubtitles();

    expect(document.getElementById('soc-count')?.textContent).toBe('No gas transition data available');
    expect(document.getElementById('charging-sessions')?.textContent).toBe('No charging data');
    // And crucially, neither is still the literal "Loading..." placeholder.
    expect(document.getElementById('soc-count')?.textContent).not.toBe('Loading...');
    expect(document.getElementById('charging-sessions')?.textContent).not.toBe('Loading...');
  });

  it('clears both subtitles even when one API errors and the other succeeds', async () => {
    (api as ReturnType<typeof vi.fn>).mockImplementation(async (url: string) => {
      if (url === '/api/soc/analysis') {
        return { error: 'HTTP 500: server down' };
      }
      if (url === '/api/charging/summary') {
        return { error: null, data: { total_kwh: 55.5, total_sessions: 4 } };
      }
      return { error: 'unexpected url', data: null };
    });

    await loadCardSubtitles();

    // SOC API errored — subtitle falls back to the empty-state text, not "Loading...".
    expect(document.getElementById('soc-count')?.textContent).toBe('No gas transition data available');
    expect(document.getElementById('soc-count')?.textContent).not.toBe('Loading...');
    // Charging succeeded — subtitle reflects the real count.
    expect(document.getElementById('charging-sessions')?.textContent).toBe('4 charging sessions');
  });

  it('clears the subtitles even when both API calls reject', async () => {
    (api as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('network down'));

    // loadCardSubtitles uses Promise.allSettled internally so it must not throw
    // even when both branches reject.
    await expect(loadCardSubtitles()).resolves.toBeUndefined();

    expect(document.getElementById('soc-count')?.textContent).toBe('No gas transition data available');
    expect(document.getElementById('charging-sessions')?.textContent).toBe('No charging data');
  });

  it('is a no-op (and does not throw) when the subtitle elements are missing from the DOM', async () => {
    document.body.innerHTML = '';
    (api as ReturnType<typeof vi.fn>).mockResolvedValue({
      error: null,
      data: { average_soc: 42, count: 17 },
    });
    await expect(loadCardSubtitles()).resolves.toBeUndefined();
  });
});

describe('loadMpgTrend', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.clearAllMocks();
    // Stub Chart.js global so renderMpgChart's `new Chart(...)` doesn't blow up.
    (globalThis as Record<string, unknown>).Chart = vi.fn(function (this: object) {
      Object.assign(this, { destroy: vi.fn() });
    });
  });

  it('does nothing when there is no canvas element on the page', async () => {
    (api as ReturnType<typeof vi.fn>).mockResolvedValue({
      error: null,
      data: [{ date: '2026-04-01', mpg: 38, gas_miles: 100, ambient_temp: 70 }],
    });
    // No #mpg-chart element — function should bail without throwing.
    await loadMpgTrend(30);
    expect(api).toHaveBeenCalledOnce();
  });

  it('shows the empty-state without removing the canvas when data is empty', async () => {
    document.body.innerHTML = `
      <div class="chart-container">
        <canvas id="mpg-chart"></canvas>
      </div>
    `;
    (api as ReturnType<typeof vi.fn>).mockResolvedValue({ error: null, data: [] });

    await loadMpgTrend(30);

    // Regression guard for the PR #45 fix: canvas must STILL be present in
    // the DOM after the empty state is shown, just hidden via display:none.
    const canvas = document.getElementById('mpg-chart') as HTMLCanvasElement | null;
    expect(canvas).not.toBeNull();
    expect(canvas?.style.display).toBe('none');
    expect(document.querySelector('.empty-state')).not.toBeNull();
    expect(document.querySelector('.empty-state')?.innerHTML).toContain('No Gas Trips');
  });

  it('does not duplicate the empty-state when called twice with empty data', async () => {
    document.body.innerHTML = `
      <div class="chart-container">
        <canvas id="mpg-chart"></canvas>
      </div>
    `;
    (api as ReturnType<typeof vi.fn>).mockResolvedValue({ error: null, data: [] });

    await loadMpgTrend(30);
    await loadMpgTrend(30);

    expect(document.querySelectorAll('.chart-container .empty-state').length).toBe(1);
  });

  it('JTN-490: collapses to a compact caption when the global trips empty state is already showing', async () => {
    // Simulate the dashboard state after loadTrips() has painted its empty
    // state but before loadMpgTrend() runs — on a brand-new empty database
    // both fire on page load and previously rendered two stacked "no trips"
    // blocks.
    document.body.innerHTML = `
      <table><tbody id="trips-table-body">
        <tr><td colspan="7" class="empty-state"><h3>No Trips Recorded</h3></td></tr>
      </tbody></table>
      <div id="trip-cards"><div class="empty-state"><h3>No Trips Recorded</h3></div></div>
      <div class="chart-container">
        <canvas id="mpg-chart"></canvas>
      </div>
    `;
    (api as ReturnType<typeof vi.fn>).mockResolvedValue({ error: null, data: [] });

    await loadMpgTrend(30);

    // Only ONE full "No Trips Recorded" heading should still be visible in
    // the trip list, and the MPG section should NOT have rendered a second
    // "No Gas Trips Yet" heading on top of it.
    const chartEmpty = document.querySelector('.chart-container .empty-state');
    expect(chartEmpty).not.toBeNull();
    expect(chartEmpty?.innerHTML).not.toContain('No Gas Trips Yet');
    expect(chartEmpty?.innerHTML).toContain('No gas MPG data yet');
    // And the canvas is still hidden, not removed.
    const canvas = document.getElementById('mpg-chart') as HTMLCanvasElement | null;
    expect(canvas?.style.display).toBe('none');
  });

  it('JTN-490: keeps the full empty-state when the trips list has data', async () => {
    document.body.innerHTML = `
      <table><tbody id="trips-table-body">
        <tr><td>15.2</td></tr>
      </tbody></table>
      <div id="trip-cards"><div class="trip-card">populated</div></div>
      <div class="chart-container">
        <canvas id="mpg-chart"></canvas>
      </div>
    `;
    (api as ReturnType<typeof vi.fn>).mockResolvedValue({ error: null, data: [] });

    await loadMpgTrend(30);

    const chartEmpty = document.querySelector('.chart-container .empty-state');
    expect(chartEmpty?.innerHTML).toContain('No Gas Trips Yet');
  });

  it('updates the active timeframe button class', async () => {
    document.body.innerHTML = `
      <div class="chart-container"><canvas id="mpg-chart"></canvas></div>
      <button class="timeframe-btn" data-days="7"></button>
      <button class="timeframe-btn active" data-days="30"></button>
      <button class="timeframe-btn" data-days="90"></button>
    `;
    (api as ReturnType<typeof vi.fn>).mockResolvedValue({ error: null, data: [] });

    await loadMpgTrend(7);

    const buttons = Array.from(document.querySelectorAll<HTMLElement>('.timeframe-btn'));
    expect(buttons[0].classList.contains('active')).toBe(true); // 7d active
    expect(buttons[1].classList.contains('active')).toBe(false); // 30d no longer active
    expect(buttons[2].classList.contains('active')).toBe(false);
  });

  it('appends date filter query params to the API URL when set', async () => {
    document.body.innerHTML = '<div class="chart-container"><canvas id="mpg-chart"></canvas></div>';
    const { state } = await import('@/core');
    state.dateFilter.start = '2026-01-01';
    state.dateFilter.end = '2026-03-01';

    (api as ReturnType<typeof vi.fn>).mockResolvedValue({ error: null, data: [] });

    await loadMpgTrend(30);

    expect(api).toHaveBeenCalledWith(
      expect.stringContaining('start_date=2026-01-01'),
      expect.anything(),
    );
    expect(api).toHaveBeenCalledWith(
      expect.stringContaining('end_date=2026-03-01'),
      expect.anything(),
    );

    // Reset for other tests
    state.dateFilter.start = null;
    state.dateFilter.end = null;
  });

  it('renders a chart when data is present and removes any stale empty-state', async () => {
    document.body.innerHTML = `
      <div class="chart-container">
        <canvas id="mpg-chart" style="display:none;"></canvas>
        <div class="empty-state">stale</div>
      </div>
    `;
    // jsdom doesn't implement HTMLCanvasElement.getContext — without this
    // stub, renderMpgChart bails at `if (!context) return;` before the
    // Chart constructor runs. The cast to `unknown` is needed because
    // getContext has overloaded signatures TS can't reconcile with a
    // single mock factory.
    const canvas = document.getElementById('mpg-chart') as HTMLCanvasElement;
    (canvas as unknown as { getContext: () => object }).getContext =
      vi.fn(() => ({}));

    (api as ReturnType<typeof vi.fn>).mockResolvedValue({
      error: null,
      data: [
        { date: '2026-04-01', mpg: 38, gas_miles: 100, ambient_temp: 70 },
        { date: '2026-04-02', mpg: 40, gas_miles: 120, ambient_temp: 72 },
      ],
    });

    await loadMpgTrend(30);

    // Canvas should be un-hidden, empty-state cleared, Chart constructor called.
    expect(canvas.style.display).toBe('');
    expect(document.querySelector('.empty-state')).toBeNull();
    expect((globalThis as Record<string, unknown>).Chart).toHaveBeenCalled();
  });

  it('swallows thrown errors gracefully', async () => {
    (api as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('boom'));
    await expect(loadMpgTrend(30)).resolves.toBeUndefined();
  });
});
