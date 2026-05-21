/**
 * Tests for charging.ts module
 * Covers pure helpers, DOM updates, modal toggles, API-driven flows,
 * and a regression guard for the PR #45 XSS fix in `sessionFields`.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

// Mock api module
vi.mock('@/api', () => ({
  api: vi.fn(),
}));

// Mock charts module helpers used by renderChargingCurveChart
vi.mock('@/charts', () => ({
  loadChartJs: vi.fn().mockResolvedValue({}),
  createGradient: vi.fn(() => 'gradient'),
  getChartDefaults: vi.fn(() => ({
    titleFont: { size: 12 },
    tickFont: { size: 10 },
  })),
  getEnhancedLegend: vi.fn(() => ({})),
  getEnhancedTooltip: vi.fn(() => ({})),
  getEnhancedAxis: vi.fn(() => ({})),
  getChartColor: vi.fn((i: number) => (i === 1 ? '#aabbcc' : '#112233')),
}));

// Provide a stable Chart.js global so renderChargingCurveChart can call `new Chart(...)`
class MockChart {
  static instances: MockChart[] = [];
  destroy = vi.fn();
  constructor(public ctx: unknown, public config: unknown) {
    MockChart.instances.push(this);
  }
}
(globalThis as unknown as { Chart: typeof MockChart }).Chart = MockChart;

// jsdom does not implement HTMLCanvasElement.getContext by default. Stub it so
// renderChargingCurveChart can proceed into its Chart.js path.
(HTMLCanvasElement.prototype as unknown as { getContext: () => unknown }).getContext = () => ({
  canvas: {},
  createLinearGradient: () => ({ addColorStop: () => {} }),
});

beforeEach(() => {
  vi.clearAllMocks();
  MockChart.instances = [];
  // Default DOM with every id the module touches
  document.body.innerHTML = `
    <div id="total-kwh">--</div>
    <div id="charging-sessions">--</div>
    <div id="avg-kwh-session">--</div>
    <div id="charging-cost">--</div>
    <div id="l1-sessions">--</div>
    <div id="l2-sessions">--</div>

    <section id="cost-comparison-section" style="display:none">
      <div id="cost-per-mile-electric">--</div>
      <div id="cost-per-mile-gas">--</div>
      <div id="cost-savings">--</div>
    </section>

    <table><tbody id="charging-table-body"></tbody></table>
    <div id="charging-cards"></div>

    <div id="charging-modal" aria-hidden="true">
      <form id="charging-form">
        <input id="charge-start" />
        <input id="charge-end" />
        <input id="charge-start-soc" />
        <input id="charge-end-soc" />
        <input id="charge-kwh" />
        <input id="charge-type" />
        <input id="charge-cost" />
        <input id="charge-location" />
        <input id="charge-notes" />
      </form>
    </div>

    <div id="charging-detail-modal" aria-hidden="true">
      <button class="modal-close"></button>
      <div id="charging-detail-summary"></div>
      <canvas id="charging-curve-chart"></canvas>
      <div id="charging-cost-breakdown"></div>
    </div>
  `;
});

type SessionOverrides = Partial<{
  id: number;
  start_time: string;
  end_time: string | null;
  start_soc: number | null;
  end_soc: number | null;
  kwh_added: number | null;
  charge_type: string | null;
  cost: number | null;
  cost_per_kwh: number | null;
  electricity_rate: number | null;
  location_name: string | null;
  notes: string | null;
  peak_power_kw: number | null;
  avg_power_kw: number | null;
}>;

function makeSession(overrides: SessionOverrides = {}) {
  return {
    id: 1,
    start_time: '2025-01-01T10:00:00Z',
    end_time: '2025-01-01T11:30:00Z',
    start_soc: 20,
    end_soc: 80,
    kwh_added: 10.5,
    charge_type: 'l2',
    cost: 2.5,
    cost_per_kwh: 0.12,
    electricity_rate: 0.12,
    location_name: 'Home',
    notes: null,
    peak_power_kw: 7.2,
    avg_power_kw: 6.5,
    ...overrides,
  };
}

describe('formatChargingDuration', () => {
  it('formats minutes-only durations under an hour', async () => {
    const { formatChargingDuration } = await import('../charging');
    expect(
      formatChargingDuration('2025-01-01T10:00:00Z', '2025-01-01T10:45:00Z'),
    ).toBe('45 min');
  });

  it('formats hours and minutes together', async () => {
    const { formatChargingDuration } = await import('../charging');
    expect(
      formatChargingDuration('2025-01-01T10:00:00Z', '2025-01-01T12:30:00Z'),
    ).toBe('2h 30m');
  });

  it('omits minutes when exactly on the hour', async () => {
    const { formatChargingDuration } = await import('../charging');
    expect(
      formatChargingDuration('2025-01-01T10:00:00Z', '2025-01-01T13:00:00Z'),
    ).toBe('3h');
  });

  it('handles multi-day durations as large hour counts', async () => {
    const { formatChargingDuration } = await import('../charging');
    // 25 hours => "25h"
    expect(
      formatChargingDuration('2025-01-01T10:00:00Z', '2025-01-02T11:00:00Z'),
    ).toBe('25h');
  });
});

describe('updateCostComparison', () => {
  it('hides the comparison section when no cost data is present', async () => {
    const { updateCostComparison } = await import('../charging');
    updateCostComparison({
      cost_per_mile_electric: null,
      cost_per_mile_gas: null,
    } as unknown as Parameters<typeof updateCostComparison>[0]);

    const section = document.getElementById('cost-comparison-section');
    expect(section?.style.display).toBe('none');
  });

  it('renders electric savings when electric is cheaper than gas', async () => {
    const { updateCostComparison } = await import('../charging');
    updateCostComparison({
      cost_per_mile_electric: 0.04,
      cost_per_mile_gas: 0.1,
    } as unknown as Parameters<typeof updateCostComparison>[0]);

    const section = document.getElementById('cost-comparison-section');
    expect(section?.style.display).toBe('block');
    expect(document.getElementById('cost-per-mile-electric')?.textContent).toBe('$0.040');
    expect(document.getElementById('cost-per-mile-gas')?.textContent).toBe('$0.100');

    const savings = document.getElementById('cost-savings');
    expect(savings?.textContent).toContain('Save $0.060/mi');
    expect(savings?.className).toBe('cost-savings positive');
  });

  it('renders a negative comparison when gas is cheaper', async () => {
    const { updateCostComparison } = await import('../charging');
    updateCostComparison({
      cost_per_mile_electric: 0.2,
      cost_per_mile_gas: 0.15,
    } as unknown as Parameters<typeof updateCostComparison>[0]);

    const savings = document.getElementById('cost-savings');
    expect(savings?.textContent).toContain('Gas cheaper by');
    expect(savings?.className).toBe('cost-savings negative');
  });
});

describe('modal toggles', () => {
  it('openAddChargingModal shows modal and pre-fills charge-start', async () => {
    const { openAddChargingModal } = await import('../charging');
    openAddChargingModal();

    const modal = document.getElementById('charging-modal');
    expect(modal?.classList.contains('show')).toBe(true);
    expect(modal?.getAttribute('aria-hidden')).toBe('false');

    const chargeStart = document.getElementById('charge-start') as HTMLInputElement;
    expect(chargeStart.value).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/);
  });

  it('closeChargingModal clears the show class and resets form', async () => {
    const { openAddChargingModal, closeChargingModal } = await import('../charging');
    openAddChargingModal();

    const input = document.getElementById('charge-kwh') as HTMLInputElement;
    input.value = '12.3';

    closeChargingModal();

    const modal = document.getElementById('charging-modal');
    expect(modal?.classList.contains('show')).toBe(false);
    expect(modal?.getAttribute('aria-hidden')).toBe('true');
    expect(input.value).toBe('');
  });

  it('closeChargingDetailModal hides the detail modal', async () => {
    const detail = document.getElementById('charging-detail-modal')!;
    detail.classList.add('show');
    detail.setAttribute('aria-hidden', 'false');

    const { closeChargingDetailModal } = await import('../charging');
    closeChargingDetailModal();

    expect(detail.classList.contains('show')).toBe(false);
    expect(detail.getAttribute('aria-hidden')).toBe('true');
  });
});

describe('loadChargingHistory', () => {
  it('renders an empty state when the API returns no sessions', async () => {
    const { api } = await import('@/api');
    (api as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: { sessions: [] },
      error: null,
    });

    const { loadChargingHistory } = await import('../charging');
    await loadChargingHistory();

    const body = document.getElementById('charging-table-body');
    expect(body?.innerHTML).toContain('No Charging Sessions');
    const cards = document.getElementById('charging-cards');
    expect(cards?.innerHTML).toContain('No Charging Sessions');
  });

  it('renders table rows and cards when sessions are returned', async () => {
    const { api } = await import('@/api');
    (api as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: { sessions: [makeSession({ id: 7, charge_type: 'l2', kwh_added: 8.2 })] },
      error: null,
    });

    const { loadChargingHistory } = await import('../charging');
    await loadChargingHistory();

    const body = document.getElementById('charging-table-body');
    expect(body?.innerHTML).toContain('8.2 kWh');
    // Whitelisted charge_type renders as a safe class name
    expect(body?.innerHTML).toContain('badge-l2');
    // Click handler wiring references the session id
    expect(body?.innerHTML).toContain('openChargingDetailModal(7)');

    const cards = document.getElementById('charging-cards');
    expect(cards?.innerHTML).toContain('charging-card');
    expect(cards?.innerHTML).toContain('8.2 kWh');
  });

  it('escapes a malicious charge_type so script tags cannot be injected (PR #45 XSS regression)', async () => {
    const { api } = await import('@/api');
    const evilSession = makeSession({
      id: 42,
      charge_type: '<script>alert(1)</script>',
      location_name: '"><img src=x onerror=alert(1)>',
    });
    (api as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: { sessions: [evilSession] },
      error: null,
    });

    const { loadChargingHistory } = await import('../charging');
    await loadChargingHistory();

    const body = document.getElementById('charging-table-body');
    const html = body?.innerHTML ?? '';

    // The raw script tag must NOT appear verbatim in the rendered HTML.
    expect(html).not.toContain('<script>alert(1)</script>');
    // The escaped form must be present.
    expect(html).toContain('&lt;script&gt;');
    // Unknown charge_type falls back to the whitelisted 'unknown' class,
    // so no attacker-controlled CSS class ends up in the DOM.
    expect(html).toContain('badge-unknown');
    expect(html).not.toContain('badge-<script');
    // Even after jsdom parses the innerHTML, no <script> element should exist.
    expect(body?.querySelector('script')).toBeNull();
    // Location name with HTML metacharacters is escaped too. Note that
    // jsdom's innerHTML serializer only re-escapes characters that would be
    // unsafe in text content, so `"` round-trips as a literal quote while
    // `<` and `>` are escaped to `&lt;`/`&gt;`.
    expect(html).toContain('&lt;img src=x onerror=alert(1)&gt;');
    // The injected `<img>` is also not present as a live element.
    expect(body?.querySelector('img')).toBeNull();
  });

  it('returns quietly when the API reports an error', async () => {
    const { api } = await import('@/api');
    (api as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: null,
      error: 'boom',
    });

    const { loadChargingHistory } = await import('../charging');
    await expect(loadChargingHistory()).resolves.toBeUndefined();
    expect(document.getElementById('charging-table-body')?.innerHTML).toBe('');
  });
});

describe('loadChargingSummary', () => {
  it('renders kWh totals and cost text from summary data', async () => {
    const { api } = await import('@/api');
    (api as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: {
        total_kwh: 123.4,
        total_sessions: 9,
        avg_kwh_per_session: 13.7,
        monthly_cost: 42.25,
        has_explicit_costs: true,
        electricity_rate: 0.13,
        l1_sessions: 2,
        l2_sessions: 7,
        cost_per_mile_electric: 0.04,
        cost_per_mile_gas: 0.1,
      },
      error: null,
    });

    const { loadChargingSummary } = await import('../charging');
    await loadChargingSummary();

    expect(document.getElementById('total-kwh')?.innerHTML).toContain('123.4');
    expect(document.getElementById('charging-sessions')?.textContent).toBe('9 charging sessions');
    expect(document.getElementById('avg-kwh-session')?.innerHTML).toContain('13.7');
    expect(document.getElementById('charging-cost')?.textContent).toBe('$42.25/month');
    expect(document.getElementById('l1-sessions')?.textContent).toBe('2');
    expect(document.getElementById('l2-sessions')?.textContent).toBe('7');
  });

  it('shows placeholders when total_kwh is missing', async () => {
    const { api } = await import('@/api');
    (api as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: {
        total_kwh: null,
        total_sessions: 0,
        avg_kwh_per_session: null,
        monthly_cost: null,
        total_cost: null,
        has_explicit_costs: false,
        electricity_rate: null,
        l1_sessions: 0,
        l2_sessions: 0,
        cost_per_mile_electric: null,
        cost_per_mile_gas: null,
      },
      error: null,
    });

    const { loadChargingSummary } = await import('../charging');
    await loadChargingSummary();

    expect(document.getElementById('total-kwh')?.textContent).toBe('--');
    expect(document.getElementById('charging-sessions')?.textContent).toBe('No charging data');
    expect(document.getElementById('charging-cost')?.textContent).toBe('No cost data');
  });
});

describe('submitChargingSession', () => {
  it('calls the add API with cleaned data and closes the modal on success', async () => {
    const { api } = await import('@/api');
    const apiMock = api as unknown as ReturnType<typeof vi.fn>;
    apiMock.mockResolvedValue({ data: { id: 99 }, error: null });

    const { submitChargingSession } = await import('../charging');

    // Pretend the modal is open and then populate inputs manually (we don't
    // call openAddChargingModal() here because it overwrites charge-start
    // with the current timestamp).
    const modal = document.getElementById('charging-modal')!;
    modal.classList.add('show');

    (document.getElementById('charge-start') as HTMLInputElement).value = '2025-01-01T10:00';
    (document.getElementById('charge-end') as HTMLInputElement).value = '2025-01-01T11:00';
    (document.getElementById('charge-kwh') as HTMLInputElement).value = '10.5';
    (document.getElementById('charge-type') as HTMLInputElement).value = 'l2';

    const event = { preventDefault: vi.fn() } as unknown as Event;
    await submitChargingSession(event);

    expect(event.preventDefault).toHaveBeenCalled();
    // First call is POST to /api/charging/add; later calls come from the refreshes.
    const addCall = apiMock.mock.calls.find((c) => c[0] === '/api/charging/add');
    expect(addCall).toBeTruthy();
    expect(addCall?.[1]?.method).toBe('POST');
    const body = JSON.parse(addCall?.[1]?.body as string);
    expect(body.start_time).toBe('2025-01-01T10:00');
    expect(body.kwh_added).toBe(10.5);
    expect(body.charge_type).toBe('l2');
    // Nullable fields that were empty should have been stripped
    expect(body).not.toHaveProperty('start_soc');

    // Modal should have been closed
    expect(modal.classList.contains('show')).toBe(false);
  });

  it('surfaces an error toast when the API returns an error', async () => {
    const { api } = await import('@/api');
    const apiMock = api as unknown as ReturnType<typeof vi.fn>;
    apiMock.mockResolvedValue({ data: null, error: 'validation failed' });

    (document.getElementById('charge-start') as HTMLInputElement).value = '2025-01-01T10:00';

    const { submitChargingSession } = await import('../charging');
    const event = { preventDefault: vi.fn() } as unknown as Event;
    await submitChargingSession(event);

    const showError = (globalThis as unknown as { showError: ReturnType<typeof vi.fn> }).showError;
    expect(showError).toHaveBeenCalledWith(
      expect.stringContaining('validation failed'),
    );
  });
});

describe('deleteChargingSession', () => {
  it('does nothing when the user cancels the confirm dialog', async () => {
    (globalThis as unknown as { confirm: () => boolean }).confirm = vi.fn(() => false);

    const { api } = await import('@/api');
    const apiMock = api as unknown as ReturnType<typeof vi.fn>;
    apiMock.mockClear();

    const { deleteChargingSession } = await import('../charging');
    await deleteChargingSession(5);

    expect(apiMock).not.toHaveBeenCalled();
  });

  it('calls DELETE and refreshes when the user confirms', async () => {
    (globalThis as unknown as { confirm: () => boolean }).confirm = vi.fn(() => true);

    const { api } = await import('@/api');
    const apiMock = api as unknown as ReturnType<typeof vi.fn>;
    apiMock.mockResolvedValue({ data: { success: true }, error: null });

    const { deleteChargingSession } = await import('../charging');
    await deleteChargingSession(5);

    const deleteCall = apiMock.mock.calls.find(
      (c) => typeof c[0] === 'string' && c[0] === '/api/charging/5',
    );
    expect(deleteCall).toBeTruthy();
    expect(deleteCall?.[1]?.method).toBe('DELETE');
  });

  it('reports failure to the user when the API returns an error', async () => {
    (globalThis as unknown as { confirm: () => boolean }).confirm = vi.fn(() => true);

    const { api } = await import('@/api');
    const apiMock = api as unknown as ReturnType<typeof vi.fn>;
    apiMock.mockResolvedValue({ data: null, error: 'not found' });

    const { deleteChargingSession } = await import('../charging');
    await deleteChargingSession(9);

    const showError = (globalThis as unknown as { showError: ReturnType<typeof vi.fn> }).showError;
    expect(showError).toHaveBeenCalledWith(expect.stringContaining('not found'));
  });
});

describe('renderChargingDetailSummary', () => {
  it('renders every stat tile with populated values', async () => {
    const { renderChargingDetailSummary } = await import('../charging');
    renderChargingDetailSummary(makeSession() as unknown as Parameters<typeof renderChargingDetailSummary>[0]);

    const html = document.getElementById('charging-detail-summary')?.innerHTML ?? '';
    expect(html).toContain('Energy Added');
    expect(html).toContain('10.5 kWh');
    expect(html).toContain('20% → 80%');
    expect(html).toContain('Home');
  });

  it('falls back to placeholders when optional fields are null', async () => {
    const { renderChargingDetailSummary } = await import('../charging');
    renderChargingDetailSummary(
      makeSession({
        end_time: null,
        start_soc: null,
        end_soc: null,
        kwh_added: null,
        peak_power_kw: null,
        avg_power_kw: null,
        charge_type: null,
        location_name: null,
      }) as unknown as Parameters<typeof renderChargingDetailSummary>[0],
    );

    const html = document.getElementById('charging-detail-summary')?.innerHTML ?? '';
    expect(html).toContain('In progress');
    // Placeholder `--` should appear multiple times for null fields
    expect(html.match(/--/g)?.length).toBeGreaterThan(2);
  });
});

describe('renderChargingCostBreakdown', () => {
  it('renders explicit cost without the estimated prefix', async () => {
    const { renderChargingCostBreakdown } = await import('../charging');
    renderChargingCostBreakdown(
      makeSession({ kwh_added: 10, cost: 2.5, cost_per_kwh: 0.25 }) as unknown as Parameters<typeof renderChargingCostBreakdown>[0],
    );

    const html = document.getElementById('charging-cost-breakdown')?.innerHTML ?? '';
    expect(html).toContain('Cost Analysis');
    expect(html).toContain('$2.50');
    // Savings callout should appear because gas-equivalent cost exceeds $2.50
    expect(html).toContain('Savings vs Gas');
  });

  it('marks cost as estimated when no explicit cost is provided', async () => {
    const { renderChargingCostBreakdown } = await import('../charging');
    renderChargingCostBreakdown(
      makeSession({ kwh_added: 10, cost: null, cost_per_kwh: null, electricity_rate: null }) as unknown as Parameters<typeof renderChargingCostBreakdown>[0],
    );

    const html = document.getElementById('charging-cost-breakdown')?.innerHTML ?? '';
    expect(html).toContain('estimated');
    expect(html).toContain('~$1.20'); // default rate 0.12 * 10
  });
});

describe('renderChargingCurveChart', () => {
  it('shows an empty state when there is no curve data', async () => {
    const canvas = document.getElementById('charging-curve-chart') as HTMLCanvasElement;
    const parent = canvas.parentElement;

    const { renderChargingCurveChart } = await import('../charging');
    await renderChargingCurveChart({ curve: [] } as unknown as Parameters<typeof renderChargingCurveChart>[0]);

    expect(parent?.innerHTML).toContain('No charging curve data available');
    expect(MockChart.instances.length).toBe(0);
  });

  it('constructs a Chart instance when curve data is present', async () => {
    const { renderChargingCurveChart } = await import('../charging');
    await renderChargingCurveChart({
      curve: [
        { timestamp: '2025-01-01T10:00:00Z', power_kw: 3.2, soc: 30 },
        { timestamp: '2025-01-01T10:15:00Z', power_kw: 7.0, soc: 45 },
      ],
    } as unknown as Parameters<typeof renderChargingCurveChart>[0]);

    expect(MockChart.instances.length).toBe(1);
    const config = MockChart.instances[0]?.config as { data: { datasets: unknown[] } };
    expect(config.data.datasets.length).toBe(2);
  });
});
