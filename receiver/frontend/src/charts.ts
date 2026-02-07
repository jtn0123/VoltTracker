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
    return new Promise((resolve) => {
      const checkLoaded = setInterval(() => {
        if (state.chartJsLoaded) {
          clearInterval(checkLoaded);
          resolve();
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

// Using Record<string, unknown> for Chart.js config objects since they're complex and loaded dynamically
type ChartConfig = Record<string, unknown>;

/**
 * Enhanced tooltip configuration
 */
export function getEnhancedTooltip(additionalCallbacks: ChartConfig = {}): ChartConfig {
  const defaults = getChartDefaults();
  return {
    backgroundColor: 'rgba(15, 52, 96, 0.95)',
    titleColor: '#ffffff',
    bodyColor: '#e0e0e0',
    borderColor: 'rgba(50, 130, 184, 0.5)',
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
      color: '#b8b8b8',
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
      color: 'rgba(255, 255, 255, 0.08)',
      ...((options.grid as ChartConfig) || {}),
    },
    ticks: {
      color: '#b8b8b8',
      font: { size: defaults.tickFont.size },
      padding: 8,
      ...((options.ticks as ChartConfig) || {}),
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
