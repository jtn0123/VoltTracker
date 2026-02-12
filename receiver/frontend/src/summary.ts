/**
 * VoltTracker - Summary Module
 * Efficiency summary and MPG trend chart
 */

import { state, formatChartDate } from '@/core';
import { api } from '@/api';
import { loadChartJs, createGradient, getEnhancedLegend, getEnhancedTooltip, getEnhancedAxis, getChartColor } from '@/charts';
import type { EfficiencySummary, MpgTrendPoint } from '@/types/api';
import { EfficiencySummarySchema, MpgTrendPointSchema } from '@/types/schemas';
import { z } from 'zod';

/** Helper to set a card's value and subtitle */
function setCardValue(primaryId: string, value: string | null, unit: string, subtitleId: string | null, subtitle: string, noDataSubtitle: string): void {
  const el = document.getElementById(primaryId);
  if (!el) return;
  if (value) {
    el.innerHTML = `${value}<span class="card-unit">${unit}</span>`;
    if (subtitleId) {
      const sub = document.getElementById(subtitleId);
      if (sub) sub.textContent = subtitle;
    }
  } else {
    el.textContent = '--';
    if (subtitleId) {
      const sub = document.getElementById(subtitleId);
      if (sub) sub.textContent = noDataSubtitle;
    }
  }
}

function updateSummaryCards(data: EfficiencySummary): void {
  document.querySelectorAll('#summary-section .skeleton-card, .electric-cards .skeleton-card').forEach((el) => el.classList.remove('skeleton-card'));

  setCardValue('lifetime-mpg', data.lifetime_gas_mpg ? String(data.lifetime_gas_mpg) : null, 'MPG', 'lifetime-miles', `${data.lifetime_gas_miles} gas miles`, 'No gas data yet');
  setCardValue('tank-mpg', data.current_tank_mpg ? String(data.current_tank_mpg) : null, 'MPG', 'tank-miles', `${data.current_tank_miles} miles this tank`, 'No data since last fill');

  const totalMiles = document.getElementById('total-miles');
  if (totalMiles) {
    totalMiles.innerHTML = data.total_miles_tracked
      ? `${data.total_miles_tracked.toLocaleString()}<span class="card-unit">mi</span>`
      : '--';
  }

  const kwhPerMile = document.getElementById('kwh-per-mile');
  const miPerKwh = document.getElementById('mi-per-kwh');
  if (kwhPerMile) {
    if (data.avg_kwh_per_mile) {
      kwhPerMile.innerHTML = `${data.avg_kwh_per_mile}<span class="card-unit">kWh/mi</span>`;
      if (miPerKwh) miPerKwh.textContent = data.mi_per_kwh ? `${data.mi_per_kwh} mi/kWh` : 'Lifetime average';
    } else {
      kwhPerMile.textContent = '--';
      if (miPerKwh) miPerKwh.textContent = 'No electric data yet';
    }
  }

  setCardValue('total-electric-miles', data.total_electric_miles ? String(data.total_electric_miles.toLocaleString()) : null, 'mi', 'total-kwh-used', data.total_kwh_used ? `${data.total_kwh_used} kWh used` : 'Total EV driving', 'No electric data yet');

  const evRatio = document.getElementById('ev-ratio');
  if (evRatio) {
    evRatio.innerHTML = (data.ev_ratio !== undefined && data.ev_ratio !== null)
      ? `${data.ev_ratio}<span class="card-unit">%</span>`
      : '--';
  }
}

/**
 * Load efficiency summary
 */
export async function loadSummary(): Promise<void> {
  try {
    const result = await api<EfficiencySummary>('/api/efficiency/summary', { useCache: true, maxAge: 300000, schema: EfficiencySummarySchema });
    if (result.error || !result.data) return;
    updateSummaryCards(result.data);
  } catch (error) {
    console.error('Failed to load summary:', error);
  }
}

/**
 * Load MPG trend chart
 */
export async function loadMpgTrend(days: number): Promise<void> {
  try {
    state.currentTimeframe = days;

    document.querySelectorAll('.timeframe-btn').forEach((btn) => {
      const btnEl = btn as HTMLElement;
      btn.classList.toggle('active', Number.parseInt(btnEl.dataset.days || '0') === days);
    });

    let mpgUrl = `/api/mpg/trend?days=${days}`;
    if (state.dateFilter.start) mpgUrl += `&start_date=${state.dateFilter.start}`;
    if (state.dateFilter.end) mpgUrl += `&end_date=${state.dateFilter.end}`;
    const result = await api<MpgTrendPoint[]>(mpgUrl, { schema: z.array(MpgTrendPointSchema) });
    if (result.error || !result.data) return;
    const data = result.data;

    const ctx = document.getElementById('mpg-chart') as HTMLCanvasElement | null;
    if (!ctx) return;

    if (data.length === 0) {
      const parent = ctx.parentElement;
      if (parent) {
        parent.innerHTML = `
          <div class="empty-state">
            <h3>No Gas Trips Yet</h3>
            <p>MPG data will appear after you complete trips using gasoline.</p>
          </div>
        `;
      }
      return;
    }

    if (!window.Chart) {
      await loadChartJs();
    }

    if (state.mpgChart) {
      state.mpgChart.destroy();
    }

    const context = ctx.getContext('2d');
    if (!context) return;

    const mpgColor = getChartColor(1);
    const gradient = createGradient(context, `${mpgColor}66`, `${mpgColor}05`);

    state.mpgChart = new Chart(ctx, {
      type: 'line',
      data: {
        labels: data.map((d) => formatChartDate(new Date(d.date))),
        datasets: [
          {
            label: 'MPG',
            data: data.map((d) => d.mpg),
            borderColor: mpgColor,
            backgroundColor: gradient,
            borderWidth: 2.5,
            fill: true,
            tension: 0.4,
            pointRadius: 5,
            pointHoverRadius: 7,
            pointBackgroundColor: mpgColor,
            pointBorderColor: '#fff',
            pointBorderWidth: 2,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: 'index', intersect: false },
        plugins: {
          legend: getEnhancedLegend(false),
          tooltip: getEnhancedTooltip({
            callbacks: {
              label: (context: any) => {
                const point = data[context.dataIndex];
                return [
                  `MPG: ${point.mpg}`,
                  `Miles: ${point.gas_miles.toFixed(1)} mi`,
                  point.ambient_temp ? `Temp: ${point.ambient_temp}°F` : '',
                ].filter(Boolean);
              },
            },
          }),
        },
        scales: {
          x: getEnhancedAxis(),
          y: getEnhancedAxis({
            suggestedMin: 20,
            suggestedMax: 50,
            title: { text: 'MPG', color: mpgColor },
          }),
        },
      },
    });
  } catch (error) {
    console.error('Failed to load MPG trend:', error);
  }
}
