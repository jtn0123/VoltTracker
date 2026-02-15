/**
 * Tests for chart rendering setup and lazy-loading behavior
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

// Mock the core module
vi.mock('@/core', () => ({
  DEBUG: false,
  state: {
    chartJsLoaded: false,
    chartJsLoading: false,
  },
}));

describe('Charts Module', () => {
  beforeEach(() => {
    vi.resetModules();
    document.head.innerHTML = '';
    document.body.innerHTML = '';
  });

  it('loadChartJs creates a script element', async () => {
    const { state } = await import('@/core');
    state.chartJsLoaded = false;
    state.chartJsLoading = false;

    const { loadChartJs } = await import('@/charts');

    const promise = loadChartJs();

    // Find the script element added to head
    const scripts = document.head.querySelectorAll('script');
    expect(scripts.length).toBeGreaterThan(0);

    // Simulate load
    const script = scripts[scripts.length - 1] as HTMLScriptElement;
    script.onload?.(new Event('load'));

    await promise;
    expect(state.chartJsLoaded).toBe(true);
  });

  it('loadChartJs returns immediately if already loaded', async () => {
    const { state } = await import('@/core');
    state.chartJsLoaded = true;
    state.chartJsLoading = false;

    const { loadChartJs } = await import('@/charts');
    await loadChartJs();

    // Should not add another script
    const scripts = document.head.querySelectorAll('script[src*="chart.js"]');
    expect(scripts.length).toBe(0);
  });

  it('loadChartJs rejects on script error', async () => {
    const { state } = await import('@/core');
    state.chartJsLoaded = false;
    state.chartJsLoading = false;

    const { loadChartJs } = await import('@/charts');

    const promise = loadChartJs();

    const scripts = document.head.querySelectorAll('script');
    const script = scripts[scripts.length - 1] as HTMLScriptElement;
    script.onerror?.(new Event('error'));

    await expect(promise).rejects.toThrow('Failed to load Chart.js');
  });

  it('setupChartLazyLoading does nothing without chart sections', async () => {
    const { setupChartLazyLoading } = await import('@/charts');
    // No chart sections in DOM - should not throw
    setupChartLazyLoading();
  });

  it('setupChartLazyLoading creates IntersectionObserver when sections exist', async () => {
    const observerSpy = vi.fn();
    globalThis.IntersectionObserver = class {
      constructor(_cb: IntersectionObserverCallback) { /* store cb */ }
      observe = observerSpy;
      unobserve = vi.fn();
      disconnect = vi.fn();
      root = null;
      rootMargin = '';
      thresholds = [0];
      takeRecords = vi.fn();
    } as any;

    document.body.innerHTML = '<div class="chart-section">Charts</div>';

    const { setupChartLazyLoading } = await import('@/charts');
    setupChartLazyLoading();

    expect(observerSpy).toHaveBeenCalled();
  });
});
