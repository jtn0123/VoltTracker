/**
 * Tests for trips.ts module
 * T22: Frontend test coverage for trips
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

// Mock DOM
beforeEach(() => {
  document.body.innerHTML = `
    <tbody id="trips-table-body"></tbody>
    <div id="trip-cards"></div>
    <div id="trip-modal" style="display:none"></div>
    <div id="modal-overlay" style="display:none"></div>
    <div id="trip-detail-content"></div>
  `;
});

// Mock api module
vi.mock('@/api', () => ({
  api: vi.fn(),
}));

// Mock charts module
vi.mock('@/charts', () => ({
  loadChartJs: vi.fn().mockResolvedValue({}),
  createGradient: vi.fn(),
  getEnhancedLegend: vi.fn(() => ({})),
  getEnhancedTooltip: vi.fn(() => ({})),
  getEnhancedAxis: vi.fn(() => ({})),
  getChartColor: vi.fn(() => '#000'),
  getCSSVar: vi.fn(() => '#000'),
}));

// Mock map module
vi.mock('@/map', () => ({
  renderTripMap: vi.fn(),
}));

// Mock summary module
vi.mock('@/summary', () => ({
  loadSummary: vi.fn(),
  loadMpgTrend: vi.fn(),
}));

// Mock battery module
vi.mock('@/battery', () => ({
  loadSocAnalysis: vi.fn(),
}));

describe('trips module', () => {
  it('renders empty state when no trips returned', async () => {
    const { api } = await import('@/api');
    (api as any).mockResolvedValue({ data: { trips: [] }, error: null });

    const { loadTrips } = await import('../trips');
    await loadTrips();

    const body = document.getElementById('trips-table-body');
    expect(body?.innerHTML).toContain('No Trips Recorded');
  });

  it('renders trip rows from API response', async () => {
    const { api } = await import('@/api');
    (api as any).mockResolvedValue({
      data: {
        trips: [
          {
            id: 1,
            start_time: '2025-01-01T10:00:00Z',
            distance_miles: 15.2,
            electric_miles: 12.0,
            gas_miles: 3.2,
            gas_mpg: 38.5,
            soc_at_gas_transition: 0.5,
            gas_mode_entered: true,
          },
        ],
      },
      error: null,
    });

    const { loadTrips } = await import('../trips');
    await loadTrips();

    const body = document.getElementById('trips-table-body');
    expect(body?.innerHTML).toContain('15.2');
  });

  it('handles API error gracefully', async () => {
    const { api } = await import('@/api');
    (api as any).mockResolvedValue({ data: null, error: 'Network error' });

    const { loadTrips } = await import('../trips');
    // Should not throw
    await expect(loadTrips()).resolves.not.toThrow();
  });

  it('handles array response format', async () => {
    const { api } = await import('@/api');
    (api as any).mockResolvedValue({
      data: [
        {
          id: 2,
          start_time: '2025-02-01T08:00:00Z',
          distance_miles: 5.0,
          electric_miles: 5.0,
          gas_miles: null,
          gas_mpg: null,
          soc_at_gas_transition: null,
          gas_mode_entered: false,
        },
      ],
      error: null,
    });

    const { loadTrips } = await import('../trips');
    await loadTrips();

    const body = document.getElementById('trips-table-body');
    expect(body?.innerHTML).toContain('5.0');
  });

  it('handles missing DOM elements without crashing', async () => {
    document.body.innerHTML = '';
    const { api } = await import('@/api');
    (api as any).mockResolvedValue({ data: { trips: [] }, error: null });

    const { loadTrips } = await import('../trips');
    await expect(loadTrips()).resolves.not.toThrow();
  });
});
