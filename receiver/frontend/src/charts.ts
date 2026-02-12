/**
 * VoltTracker - Charts Module
 * Chart.js lazy loading and shared chart configuration helpers
 */

import { DEBUG, state } from '@/core';

/**
 * Lazy load Chart.js library when needed
 */
export async function loadChartJs(): Promise<void> {
  if (state.chartJsLoaded) return;
  if (state.chartJsLoading)
    return new Promise((resolve, reject) => {
      let retries = 0;
      const checkLoaded = setInterval(() => {
        if (state.chartJsLoaded) {
          clearInterval(checkLoaded);
          resolve();
        } else if (++retries >= 100) {
          clearInterval(checkLoaded);
          reject(new Error('Chart.js loading timed out'));
        }
      }, 50);
    });

  state.chartJsLoading = true;
  if (DEBUG) console.log('Loading Chart.js...');

  return new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = 'https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js';
    script.onload = () => {
      state.chartJsLoaded = true;
      state.chartJsLoading = false;
      if (DEBUG) console.log('Chart.js loaded successfully');
      resolve();
    };
    script.onerror = () => {
      state.chartJsLoading = false;
      reject(new Error('Failed to load Chart.js'));
    };
    document.head.appendChild(script);
  });
}

/**
 * Setup Intersection Observer to lazy load Chart.js when charts section becomes visible
 */
export function setupChartLazyLoading(): void {
  const chartSections = document.querySelectorAll('.chart-section, #trip-charts');
  if (chartSections.length === 0) return;

  const observer = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting && !state.chartJsLoaded && !state.chartJsLoading) {
          loadChartJs().catch((error) => {
            console.error('Failed to load Chart.js:', error);
          });
          observer.disconnect();
        }
      });
    },
    {
      rootMargin: '200px',
    },
  );

  chartSections.forEach((section) => observer.observe(section));
}

/**
 * Chart gradient helper
 */
export function createGradient(ctx: CanvasRenderingContext2D, colorStart: string, colorEnd: string): CanvasGradient {
  const gradient = ctx.createLinearGradient(0, 0, 0, ctx.canvas.height);
  gradient.addColorStop(0, colorStart);
  gradient.addColorStop(1, colorEnd);
  return gradient;
}

interface ChartFontDefaults {
  font: { size: number; family: string };
  tickFont: { size: number };
  titleFont: { size: number; weight: string };
}

/**
 * Desktop-responsive chart defaults
 */
export function getChartDefaults(): ChartFontDefaults {
  const isDesktop = window.innerWidth >= 1024;
  return {
    font: {
      size: isDesktop ? 13 : 11,
      family: "'Inter', -apple-system, BlinkMacSystemFont, sans-serif",
    },
    tickFont: {
      size: isDesktop ? 12 : 10,
    },
    titleFont: {
      size: isDesktop ? 14 : 12,
      weight: '600',
    },
  };
}

/**
 * Read a CSS variable value from :root, with a fallback.
 */
export function getCSSVar(name: string, fallback = ''): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback;
}

/**
 * Get chart palette color by index (1-based), reading from CSS --chart-N variables.
 */
export function getChartColor(index: number): string {
  const fallbacks = ['#6366f1', '#34d399', '#f59e0b', '#ec4899', '#3b82f6', '#a78bfa'];
  return getCSSVar(`--chart-${index}`, fallbacks[(index - 1) % fallbacks.length]);
}

// Using Record<string, unknown> for Chart.js config objects since they're complex and loaded dynamically
type ChartConfig = Record<string, unknown>;

/**
 * Enhanced tooltip configuration
 */
/**
 * Get current theme CSS variable value
 */
function getThemeVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

export function getEnhancedTooltip(additionalCallbacks: ChartConfig = {}): ChartConfig {
  const defaults = getChartDefaults();
  const isDark = document.documentElement.dataset.theme !== 'light';
  return {
    backgroundColor: isDark ? 'rgba(22, 25, 34, 0.95)' : 'rgba(255, 255, 255, 0.97)',
    titleColor: isDark ? '#f0f0f3' : '#111827',
    bodyColor: isDark ? '#a1a1aa' : '#4b5563',
    borderColor: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.1)',
    borderWidth: 1,
    cornerRadius: 8,
    padding: 12,
    titleFont: { size: defaults.font.size, weight: '600' },
    bodyFont: { size: defaults.font.size - 1 },
    displayColors: true,
    boxPadding: 4,
    ...additionalCallbacks,
  };
}

/**
 * Enhanced legend configuration
 */
export function getEnhancedLegend(display = true): ChartConfig {
  const defaults = getChartDefaults();
  return {
    display,
    position: 'top',
    labels: {
      color: getThemeVar('--text-secondary') || '#a1a1aa',
      font: { size: defaults.font.size },
      padding: 16,
      usePointStyle: true,
      pointStyle: 'circle',
    },
  };
}

/**
 * Enhanced axis configuration
 */
export function getEnhancedAxis(options: ChartConfig = {}): ChartConfig {
  const defaults = getChartDefaults();
  return {
    grid: {
      color: getThemeVar('--chart-grid') || 'rgba(255, 255, 255, 0.05)',
      ...((options.grid as ChartConfig) ?? {}),
    },
    ticks: {
      color: getThemeVar('--chart-tick') || '#71717a',
      font: { size: defaults.tickFont.size },
      padding: 8,
      ...((options.ticks as ChartConfig) ?? {}),
    },
    title: options.title
      ? {
          display: true,
          font: defaults.titleFont,
          padding: { top: 8, bottom: 8 },
          ...(options.title as ChartConfig),
        }
      : undefined,
    ...options,
  };
}
