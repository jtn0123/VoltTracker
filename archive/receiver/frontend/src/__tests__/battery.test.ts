/**
 * Tests for battery.ts module
 * T22: Frontend test coverage for battery
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

// Mock api
vi.mock('@/api', () => ({
  api: vi.fn(),
}));

// Mock charts
vi.mock('@/charts', () => ({
  loadChartJs: vi.fn().mockResolvedValue({}),
  createGradient: vi.fn(),
  getEnhancedLegend: vi.fn(() => ({})),
  getEnhancedTooltip: vi.fn(() => ({})),
  getEnhancedAxis: vi.fn(() => ({})),
  getChartColor: vi.fn(() => '#000'),
  getCSSVar: vi.fn(() => '#000'),
}));

beforeEach(() => {
  document.body.innerHTML = `
    <section id="battery-health-section" style="display:none">
      <div class="battery-health-content"></div>
      <span id="battery-health-status">--</span>
      <div id="battery-health-bar"></div>
      <span id="battery-health-capacity">--</span>
      <span id="battery-health-original">18.4</span>
      <div id="battery-health-percent">--% capacity</div>
      <div id="battery-health-trend"></div>
    </section>
    <section id="battery-cells-section" style="display:none"></section>
    <div id="soc-transition-chart"></div>
  `;
});

describe('battery module', () => {
  it('shows empty state when no battery data', async () => {
    const { api } = await import('@/api');
    (api as any).mockResolvedValue({ data: { has_data: false }, error: null });

    const { loadBatteryHealth } = await import('../battery');
    await loadBatteryHealth();

    const section = document.getElementById('battery-health-section');
    expect(section?.style.display).toBe('block');
  });

  it('handles API error gracefully', async () => {
    const { api } = await import('@/api');
    (api as any).mockResolvedValue({ data: null, error: 'fail' });

    const { loadBatteryHealth } = await import('../battery');
    await expect(loadBatteryHealth()).resolves.not.toThrow();
  });

  it('displays battery health data when available', async () => {
    const { api } = await import('@/api');
    (api as any).mockResolvedValue({
      data: {
        has_data: true,
        latest: { capacity_kwh: 17.5, normalized_capacity_kwh: 17.5, degradation_percent: 4.89, timestamp: '2025-01-01T00:00:00Z' },
        history: [],
        original_capacity: 18.4,
      },
      error: null,
    });

    const { loadBatteryHealth } = await import('../battery');
    await loadBatteryHealth();

    const section = document.getElementById('battery-health-section');
    expect(section?.style.display).toBe('block');
  });

  it('handles missing DOM elements without crashing', async () => {
    document.body.innerHTML = '';
    const { api } = await import('@/api');
    (api as any).mockResolvedValue({ data: { has_data: true, latest: {}, history: [], original_capacity: 18.4 }, error: null });

    const { loadBatteryHealth } = await import('../battery');
    await expect(loadBatteryHealth()).resolves.not.toThrow();
  });

  it('loadSocAnalysis handles empty data', async () => {
    const { api } = await import('@/api');
    (api as any).mockResolvedValue({ data: { transitions: [], stats: {} }, error: null });

    const { loadSocAnalysis } = await import('../battery');
    await expect(loadSocAnalysis()).resolves.not.toThrow();
  });
});
