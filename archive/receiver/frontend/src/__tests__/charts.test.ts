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

  it('loadChartJs returns a waiter promise when another caller is mid-load', async () => {
    vi.useFakeTimers();
    const { state } = await import('@/core');
    state.chartJsLoaded = false;
    state.chartJsLoading = true;  // pretend another caller already started

    const { loadChartJs } = await import('@/charts');
    const promise = loadChartJs();

    // Simulate the in-flight load completing after a couple of poll ticks
    setTimeout(() => { state.chartJsLoaded = true; }, 100);
    await vi.advanceTimersByTimeAsync(150);

    await expect(promise).resolves.toBeUndefined();
    vi.useRealTimers();
  });

  it('lazy loading observer triggers loadChartJs when an entry intersects', async () => {
    const { state } = await import('@/core');
    state.chartJsLoaded = false;
    state.chartJsLoading = false;

    let observerCallback: IntersectionObserverCallback | null = null;
    const observeSpy = vi.fn();
    const disconnectSpy = vi.fn();
    globalThis.IntersectionObserver = class {
      constructor(cb: IntersectionObserverCallback) {
        observerCallback = cb;
      }
      observe = observeSpy;
      unobserve = vi.fn();
      disconnect = disconnectSpy;
      root = null;
      rootMargin = '';
      thresholds = [0];
      takeRecords = vi.fn();
    } as any;

    document.body.innerHTML = '<div class="chart-section">Charts</div>';

    const { setupChartLazyLoading } = await import('@/charts');
    setupChartLazyLoading();

    expect(observerCallback).not.toBeNull();
    // Manually fire the callback as if the section had scrolled into view
    observerCallback!(
      [{ isIntersecting: true } as IntersectionObserverEntry],
      {} as IntersectionObserver,
    );

    // The intersecting branch starts a script load and disconnects the observer
    expect(disconnectSpy).toHaveBeenCalled();
    expect(state.chartJsLoading).toBe(true);
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

describe('Chart configuration helpers', () => {
  beforeEach(() => {
    // Reset documentElement attributes between tests
    document.documentElement.removeAttribute('data-theme');
  });

  describe('createGradient', () => {
    it('builds a linear gradient on the canvas context with two color stops', async () => {
      const { createGradient } = await import('@/charts');

      const addColorStop = vi.fn();
      const fakeGradient = { addColorStop } as unknown as CanvasGradient;
      const fakeCtx = {
        canvas: { height: 200 },
        createLinearGradient: vi.fn(() => fakeGradient),
      } as unknown as CanvasRenderingContext2D;

      const gradient = createGradient(fakeCtx, '#ff0000', '#0000ff');

      expect(fakeCtx.createLinearGradient).toHaveBeenCalledWith(0, 0, 0, 200);
      expect(addColorStop).toHaveBeenNthCalledWith(1, 0, '#ff0000');
      expect(addColorStop).toHaveBeenNthCalledWith(2, 1, '#0000ff');
      expect(gradient).toBe(fakeGradient);
    });
  });

  describe('getChartDefaults', () => {
    it('returns desktop sizes when innerWidth >= 1024', async () => {
      Object.defineProperty(globalThis, 'innerWidth', { value: 1280, configurable: true });
      const { getChartDefaults } = await import('@/charts');
      const d = getChartDefaults();
      expect(d.font.size).toBe(13);
      expect(d.tickFont.size).toBe(12);
      expect(d.titleFont.size).toBe(14);
      expect(d.titleFont.weight).toBe('600');
    });

    it('returns mobile sizes when innerWidth < 1024', async () => {
      Object.defineProperty(globalThis, 'innerWidth', { value: 800, configurable: true });
      const { getChartDefaults } = await import('@/charts');
      const d = getChartDefaults();
      expect(d.font.size).toBe(11);
      expect(d.tickFont.size).toBe(10);
      expect(d.titleFont.size).toBe(12);
    });

    it('always uses the Inter font family', async () => {
      const { getChartDefaults } = await import('@/charts');
      expect(getChartDefaults().font.family).toContain('Inter');
    });
  });

  describe('getCSSVar', () => {
    it('returns the css variable value when set', async () => {
      document.documentElement.style.setProperty('--my-var', '#123456');
      const { getCSSVar } = await import('@/charts');
      expect(getCSSVar('--my-var', '#fallback')).toBe('#123456');
      document.documentElement.style.removeProperty('--my-var');
    });

    it('returns the fallback when the variable is unset', async () => {
      const { getCSSVar } = await import('@/charts');
      expect(getCSSVar('--nonexistent', '#fallback')).toBe('#fallback');
    });

    it('returns empty string when no fallback is provided and var is unset', async () => {
      const { getCSSVar } = await import('@/charts');
      expect(getCSSVar('--also-nonexistent')).toBe('');
    });
  });

  describe('getChartColor', () => {
    it('returns the configured CSS variable when present', async () => {
      document.documentElement.style.setProperty('--chart-1', '#aabbcc');
      const { getChartColor } = await import('@/charts');
      expect(getChartColor(1)).toBe('#aabbcc');
      document.documentElement.style.removeProperty('--chart-1');
    });

    it('falls back to the indigo default for index 1 when no CSS var', async () => {
      const { getChartColor } = await import('@/charts');
      expect(getChartColor(1)).toBe('#6366f1');
    });

    it('cycles through the fallback palette modulo length', async () => {
      const { getChartColor } = await import('@/charts');
      // 6 fallbacks, so index 7 should match index 1
      expect(getChartColor(7)).toBe(getChartColor(1));
    });
  });

  describe('getEnhancedTooltip', () => {
    it('returns dark theme defaults when data-theme is not light', async () => {
      const { getEnhancedTooltip } = await import('@/charts');
      const cfg = getEnhancedTooltip();
      expect(cfg.backgroundColor).toContain('22, 25, 34');
      expect(cfg.titleColor).toBe('#f0f0f3');
      expect(cfg.borderWidth).toBe(1);
      expect(cfg.cornerRadius).toBe(8);
    });

    it('returns light theme defaults when data-theme=light', async () => {
      document.documentElement.dataset.theme = 'light';
      const { getEnhancedTooltip } = await import('@/charts');
      const cfg = getEnhancedTooltip();
      expect(cfg.backgroundColor).toContain('255, 255, 255');
      expect(cfg.titleColor).toBe('#111827');
    });

    it('merges additional callbacks into the result', async () => {
      const { getEnhancedTooltip } = await import('@/charts');
      const cfg = getEnhancedTooltip({ callbacks: { label: 'foo' } });
      expect((cfg.callbacks as Record<string, unknown>).label).toBe('foo');
    });
  });

  describe('getEnhancedLegend', () => {
    it('uses the display=true default', async () => {
      const { getEnhancedLegend } = await import('@/charts');
      const cfg = getEnhancedLegend();
      expect(cfg.display).toBe(true);
      expect(cfg.position).toBe('top');
    });

    it('respects display=false override', async () => {
      const { getEnhancedLegend } = await import('@/charts');
      expect(getEnhancedLegend(false).display).toBe(false);
    });

    it('puts label config under labels', async () => {
      const { getEnhancedLegend } = await import('@/charts');
      const cfg = getEnhancedLegend();
      const labels = cfg.labels as Record<string, unknown>;
      expect(labels.usePointStyle).toBe(true);
      expect(labels.pointStyle).toBe('circle');
    });
  });

  describe('getEnhancedAxis', () => {
    it('returns grid + ticks structure with no extra options', async () => {
      const { getEnhancedAxis } = await import('@/charts');
      const cfg = getEnhancedAxis();
      expect(cfg.grid).toBeDefined();
      expect(cfg.ticks).toBeDefined();
      // No title configured → undefined
      expect(cfg.title).toBeUndefined();
    });

    it('attaches title when one is provided in options', async () => {
      const { getEnhancedAxis } = await import('@/charts');
      const cfg = getEnhancedAxis({ title: { text: 'MPG' } });
      // Note: getEnhancedAxis builds an enriched title object then spreads
      // `...options` at the end of its return — the user's `options.title`
      // wins outright, so the function-supplied display/font/padding get
      // overwritten by whatever the caller passed. The test pins this
      // current behavior; if the spread is removed or reordered later,
      // this assertion is the one to update.
      const title = cfg.title as Record<string, unknown>;
      expect(title.text).toBe('MPG');
    });

    it('merges custom grid + ticks options into defaults', async () => {
      const { getEnhancedAxis } = await import('@/charts');
      const cfg = getEnhancedAxis({
        grid: { lineWidth: 2 },
        ticks: { stepSize: 5 },
      });
      expect((cfg.grid as Record<string, unknown>).lineWidth).toBe(2);
      expect((cfg.ticks as Record<string, unknown>).stepSize).toBe(5);
    });
  });
});
