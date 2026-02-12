/**
 * VoltTracker - Battery Module
 * Battery health, cell voltages, and SOC analysis
 */

import { formatDateTime, state } from '@/core';
import { api } from '@/api';
import { loadChartJs, createGradient, getEnhancedLegend, getEnhancedTooltip, getEnhancedAxis, getChartColor, getCSSVar } from '@/charts';
import type { BatteryHealthResponse, BatteryCellsResponse, CellReading, SocAnalysis } from '@/types/api';

/**
 * Load battery health data
 */
export async function loadBatteryHealth(): Promise<void> {
  try {
    const result = await api<BatteryHealthResponse>('/api/battery/health');
    if (result.error) return;
    const data = result.data!;

    const section = document.getElementById('battery-health-section');
    if (!section) return;

    if (!data.has_data) {
      section.style.display = 'block';
      const content = section.querySelector('.battery-health-content, .section-content');
      if (content) content.innerHTML = '<div class="empty-state"><p>No battery health data available yet.</p></div>';
      return;
    }

    section.style.display = 'block';

    const capacityEl = document.getElementById('battery-health-capacity');
    if (capacityEl && data.current_capacity_kwh) capacityEl.textContent = data.current_capacity_kwh.toFixed(1);

    const originalEl = document.getElementById('battery-health-original');
    if (originalEl && data.original_capacity_kwh) originalEl.textContent = data.original_capacity_kwh.toFixed(1);

    const percentEl = document.getElementById('battery-health-percent');
    if (percentEl && data.health_percent) percentEl.textContent = `${data.health_percent}% capacity`;

    const statusEl = document.getElementById('battery-health-status');
    if (statusEl && data.health_status) {
      statusEl.textContent = data.health_status.charAt(0).toUpperCase() + data.health_status.slice(1);
      statusEl.className = `battery-health-status ${data.health_status}`;
    }

    const barEl = document.getElementById('battery-health-bar');
    if (barEl && data.health_percent) {
      barEl.style.width = `${data.health_percent}%`;
      if (data.health_percent >= 80) barEl.className = 'battery-health-bar good';
      else if (data.health_percent >= 70) barEl.className = 'battery-health-bar fair';
      else barEl.className = 'battery-health-bar degraded';
    }

    const trendEl = document.getElementById('battery-health-trend');
    if (trendEl) {
      if (data.yearly_trend_percent !== null && data.yearly_trend_percent !== undefined) {
        const sign = data.yearly_trend_percent >= 0 ? '+' : '';
        const direction = data.yearly_trend_percent >= 0 ? 'positive' : 'negative';
        trendEl.textContent = `${sign}${data.yearly_trend_percent}% this year`;
        trendEl.className = `battery-health-trend ${direction}`;
      } else {
        trendEl.textContent = 'Trend data not yet available';
        trendEl.className = 'battery-health-trend';
      }
    }
  } catch (error) {
    console.error('Failed to load battery health:', error);
  }
}

/**
 * Load battery cell voltage data
 */
export async function loadBatteryCells(): Promise<void> {
  try {
    const result = await api<BatteryCellsResponse>('/api/battery/cells/latest');
    if (result.error) return;
    const data = result.data!;

    const section = document.getElementById('battery-cells-section');
    if (!section) return;

    if (!data.reading) {
      section.style.display = 'block';
      const content = section.querySelector('.battery-cells-content, .section-content');
      if (content) content.innerHTML = '<div class="empty-state"><p>No cell voltage data available yet.</p></div>';
      return;
    }

    section.style.display = 'block';
    const reading = data.reading;

    const deltaEl = document.getElementById('voltage-delta');
    if (deltaEl && reading.voltage_delta !== null) {
      const delta = reading.voltage_delta;
      deltaEl.textContent = `Δ ${(delta * 1000).toFixed(0)}mV`;

      deltaEl.classList.remove('good', 'warning', 'danger');
      if (delta < 0.03) deltaEl.classList.add('good');
      else if (delta < 0.05) deltaEl.classList.add('warning');
      else deltaEl.classList.add('danger');
    }

    updateModuleBars(reading);
    renderCellHeatmap(reading);
    checkWeakCells(reading);

    const timestampEl = document.getElementById('cells-timestamp');
    if (timestampEl && reading.timestamp) {
      timestampEl.textContent = `Last updated: ${formatDateTime(new Date(reading.timestamp))}`;
    }
  } catch (error) {
    console.error('Failed to load battery cells:', error);
  }
}

/**
 * Update module balance bars
 */
export function updateModuleBars(reading: CellReading): void {
  const modules = [
    { bar: 'module1-bar', voltage: 'module1-voltage', avg: reading.module1_avg },
    { bar: 'module2-bar', voltage: 'module2-voltage', avg: reading.module2_avg },
    { bar: 'module3-bar', voltage: 'module3-voltage', avg: reading.module3_avg },
  ];

  const avgValues = modules.map((m) => m.avg).filter((v): v is number => v !== null);
  if (avgValues.length === 0) return;

  const minAvg = Math.min(...avgValues);
  const maxAvg = Math.max(...avgValues);
  const range = maxAvg - minAvg || 0.01;

  modules.forEach((module) => {
    const barEl = document.getElementById(module.bar);
    const voltageEl = document.getElementById(module.voltage);

    if (barEl && module.avg !== null) {
      const normalizedValue = (module.avg - minAvg) / range;
      const width = 80 + normalizedValue * 20;
      barEl.style.width = `${width}%`;
    }

    if (voltageEl && module.avg !== null) {
      voltageEl.textContent = `${module.avg.toFixed(3)}V`;
    }
  });
}

/**
 * Render cell voltage heatmap
 */
export function renderCellHeatmap(reading: CellReading): void {
  const heatmap = document.getElementById('cell-heatmap');
  if (!heatmap || !reading.cell_voltages) return;

  const voltages = reading.cell_voltages;
  const validVoltages = voltages.filter((v): v is number => v !== null && v !== undefined);
  const minV = reading.min_voltage ?? Math.min(...validVoltages);
  const maxV = reading.max_voltage ?? Math.max(...validVoltages);
  const range = maxV - minV || 0.01;

  heatmap.innerHTML = voltages
    .map((voltage, index) => {
      if (voltage === null || voltage === undefined) {
        return `<div class="cell" style="background-color: var(--bg-secondary);" title="Cell ${index + 1}: N/A"></div>`;
      }

      const normalized = (voltage - minV) / range;
      const color = getHeatmapColor(normalized);

      return `<div class="cell" style="background-color: ${color};" title="Cell ${index + 1}: ${voltage.toFixed(3)}V"></div>`;
    })
    .join('');
}

/**
 * Generate heatmap color based on normalized value (0-1)
 */
export function getHeatmapColor(value: number): string {
  // Use CSS variable-derived colors: danger -> warning -> success -> chart-5 -> chart-6
  const dangerHex = getCSSVar('--danger', '#ef4444');
  const warningHex = getCSSVar('--warning', '#f59e0b');
  const successHex = getCSSVar('--success', '#22c55e');
  const c5Hex = getChartColor(5);
  const c6Hex = getChartColor(6);
  const parseHex = (h: string) => {
    h = h.replace('#', '');
    return { r: Number.parseInt(h.slice(0, 2), 16), g: Number.parseInt(h.slice(2, 4), 16), b: Number.parseInt(h.slice(4, 6), 16) };
  };
  const d = parseHex(dangerHex), w = parseHex(warningHex), s = parseHex(successHex), b5 = parseHex(c5Hex), b6 = parseHex(c6Hex);
  const colors = [
    { pos: 0, ...d },
    { pos: 0.25, ...w },
    { pos: 0.5, ...s },
    { pos: 0.75, ...b5 },
    { pos: 1, ...b6 },
  ];

  let lower = colors[0];
  let upper = colors[colors.length - 1];

  for (let i = 0; i < colors.length - 1; i++) {
    if (value >= colors[i].pos && value <= colors[i + 1].pos) {
      lower = colors[i];
      upper = colors[i + 1];
      break;
    }
  }

  const range = upper.pos - lower.pos;
  const factor = range === 0 ? 0 : (value - lower.pos) / range;

  const r = Math.round(lower.r + (upper.r - lower.r) * factor);
  const g = Math.round(lower.g + (upper.g - lower.g) * factor);
  const b = Math.round(lower.b + (upper.b - lower.b) * factor);

  return `rgb(${r}, ${g}, ${b})`;
}

/**
 * Check for weak cells and show warning
 */
export function checkWeakCells(reading: CellReading): void {
  const warningEl = document.getElementById('weak-cells-warning');
  const textEl = document.getElementById('weak-cells-text');
  if (!warningEl || !textEl) return;

  if (!reading.cell_voltages || !reading.avg_voltage) {
    warningEl.style.display = 'none';
    return;
  }

  const threshold = reading.avg_voltage * 0.02;
  const weakCells: { index: number; voltage: number; deviation: number }[] = [];

  reading.cell_voltages.forEach((voltage, index) => {
    if (voltage !== null && voltage < reading.avg_voltage! - threshold) {
      weakCells.push({
        index: index + 1,
        voltage,
        deviation: (voltage - reading.avg_voltage!) * 1000,
      });
    }
  });

  if (weakCells.length > 0) {
    warningEl.style.display = 'flex';
    const cellNumbers = weakCells
      .slice(0, 3)
      .map((c) => c.index)
      .join(', ');
    const moreText = weakCells.length > 3 ? ` and ${weakCells.length - 3} more` : '';
    textEl.textContent = `Weak cells detected: #${cellNumbers}${moreText}`;

    weakCells.forEach((cell) => {
      const cellEl = document.querySelectorAll('#cell-heatmap .cell')[cell.index - 1];
      if (cellEl) cellEl.classList.add('weak');
    });
  } else {
    warningEl.style.display = 'none';
  }
}

/**
 * Load SOC analysis
 */
export async function loadSocAnalysis(): Promise<void> {
  try {
    const result = await api<SocAnalysis>('/api/soc/analysis');
    if (result.error) return;
    const data = result.data!;

    const socFloor = document.getElementById('soc-floor');
    if (socFloor) {
      if (data.average_soc) {
        socFloor.innerHTML = `${data.average_soc}<span class="card-unit">%</span>`;
        const socCount = document.getElementById('soc-count');
        if (socCount) socCount.textContent = `${data.count} transitions recorded`;
      } else {
        socFloor.textContent = '--';
        const socCount = document.getElementById('soc-count');
        if (socCount) socCount.textContent = 'No gas transition data available';
      }
    }

    const setEl = (id: string, val: string) => {
      const el = document.getElementById(id);
      if (el) el.textContent = val;
    };

    setEl('soc-min', data.min_soc != null ? `${data.min_soc}%` : '--');
    setEl('soc-max', data.max_soc != null ? `${data.max_soc}%` : '--');
    setEl('soc-avg', data.average_soc != null ? `${data.average_soc}%` : '--');

    if (data.temperature_correlation) {
      setEl(
        'soc-cold',
        `${data.temperature_correlation.cold_avg_soc}% (${data.temperature_correlation.cold_count} trips)`,
      );
      setEl(
        'soc-warm',
        `${data.temperature_correlation.warm_avg_soc}% (${data.temperature_correlation.warm_count} trips)`,
      );
    } else {
      setEl('soc-cold', '--');
      setEl('soc-warm', '--');
    }

    if (data.trend) {
      setEl(
        'soc-trend',
        `${data.trend.direction === 'increasing' ? 'Increasing' : 'Decreasing'} ` +
          `(${data.trend.early_avg}% -> ${data.trend.recent_avg}%)`,
      );
    } else {
      setEl('soc-trend', 'Not enough data');
    }

    if (data.histogram && Object.keys(data.histogram).length > 0) {
      renderSocHistogram(data.histogram);
    }
  } catch (error) {
    console.error('Failed to load SOC analysis:', error);
  }
}

/**
 * Render SOC histogram chart
 */
export async function renderSocHistogram(histogram: Record<string, number>): Promise<void> {
  const ctx = document.getElementById('soc-histogram-chart') as HTMLCanvasElement | null;
  if (!ctx) return;

  const labels = Object.keys(histogram).sort((a, b) => Number.parseInt(a) - Number.parseInt(b));
  const values = labels.map((k) => histogram[k]);

  if (!window.Chart) await loadChartJs();

  if (state.socChart) state.socChart.destroy();

  const context = ctx.getContext('2d');
  if (!context) return;

  const barGradient = createGradient(context, 'rgba(99, 102, 241, 0.9)', 'rgba(99, 102, 241, 0.5)');

  state.socChart = new Chart(ctx, {
    type: 'bar',
    data: {
      labels: labels.map((l) => `${l}%`),
      datasets: [
        {
          label: 'Transitions',
          data: values,
          backgroundColor: barGradient,
          borderColor: 'rgba(99, 102, 241, 0.8)',
          borderWidth: 1,
          borderRadius: 6,
          hoverBackgroundColor: getChartColor(5),
        },
      ],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: getEnhancedLegend(false),
        tooltip: getEnhancedTooltip({
          callbacks: {
            title: (items: any[]) => `SOC: ${items[0].label}`,
            label: (context: any) => `${context.parsed.y} transition${context.parsed.y !== 1 ? 's' : ''}`,
          },
        }),
      },
      scales: {
        x: getEnhancedAxis({ grid: { display: false } }),
        y: getEnhancedAxis({
          beginAtZero: true,
          ticks: { stepSize: 1 },
          title: { text: 'Frequency', color: '#b8b8b8' },
        }),
      },
    },
  });
}
