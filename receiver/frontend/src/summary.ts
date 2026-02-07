/**
 * VoltTracker - Summary Module
 * Efficiency summary and MPG trend chart
 */

import { state, formatChartDate } from '@/core';
import { api } from '@/api';
import { loadChartJs, createGradient, getEnhancedLegend, getEnhancedTooltip, getEnhancedAxis } from '@/charts';
import type { EfficiencySummary, MpgTrendPoint } from '@/types/api';
import { EfficiencySummarySchema, MpgTrendPointSchema } from '@/types/schemas';
import { z } from 'zod';

/**
 * Load efficiency summary
 */
export async function loadSummary(): Promise<void> {
  try {
    const result = await api<EfficiencySummary>('/api/efficiency/summary', { useCache: true, maxAge: 300000, schema: EfficiencySummarySchema });
    if (result.error || !result.data) return;
    const data = result.data;

    const lifetimeMpg = document.getElementById('lifetime-mpg');
    if (lifetimeMpg) {
      if (data.lifetime_gas_mpg) {
        lifetimeMpg.innerHTML = `${data.lifetime_gas_mpg}<span class="card-unit">MPG</span>`;
        const lifetimeMiles = document.getElementById('lifetime-miles');
        if (lifetimeMiles) lifetimeMiles.textContent = `${data.lifetime_gas_miles} gas miles`;
      } else {
        lifetimeMpg.textContent = '--';
        const lifetimeMiles = document.getElementById('lifetime-miles');
        if (lifetimeMiles) lifetimeMiles.textContent = 'No gas data yet';
      }
    }

    const tankMpg = document.getElementById('tank-mpg');
    if (tankMpg) {
      if (data.current_tank_mpg) {
        tankMpg.innerHTML = `${data.current_tank_mpg}<span class="card-unit">MPG</span>`;
        const tankMiles = document.getElementById('tank-miles');
        if (tankMiles) tankMiles.textContent = `${data.current_tank_miles} miles this tank`;
      } else {
        tankMpg.textContent = '--';
        const tankMiles = document.getElementById('tank-miles');
        if (tankMiles) tankMiles.textContent = 'No data since last fill';
      }
    }

    const totalMiles = document.getElementById('total-miles');
    if (totalMiles) {
      if (data.total_miles_tracked) {
        totalMiles.innerHTML = `${data.total_miles_tracked.toLocaleString()}<span class="card-unit">mi</span>`;
      } else {
        totalMiles.textContent = '--';
      }
    }

    const kwhPerMile = document.getElementById('kwh-per-mile');
    const miPerKwh = document.getElementById('mi-per-kwh');
    if (kwhPerMile) {
      if (data.avg_kwh_per_mile) {
        kwhPerMile.innerHTML = `${data.avg_kwh_per_mile}<span class="card-unit">kWh/mi</span>`;
        if (miPerKwh) {
          miPerKwh.textContent = data.mi_per_kwh ? `${data.mi_per_kwh} mi/kWh` : 'Lifetime average';
        }
      } else {
        kwhPerMile.textContent = '--';
        if (miPerKwh) miPerKwh.textContent = 'No electric data yet';
      }
    }

    const electricMiles = document.getElementById('total-electric-miles');
    const totalKwhUsed = document.getElementById('total-kwh-used');
    if (electricMiles) {
      if (data.total_electric_miles) {
        electricMiles.innerHTML = `${data.total_electric_miles.toLocaleString()}<span class="card-unit">mi</span>`;
        if (totalKwhUsed) {
          totalKwhUsed.textContent = data.total_kwh_used ? `${data.total_kwh_used} kWh used` : 'Total EV driving';
        }
      } else {
        electricMiles.textContent = '--';
        if (totalKwhUsed) totalKwhUsed.textContent = 'No electric data yet';
      }
    }

    const evRatio = document.getElementById('ev-ratio');
    if (evRatio) {
      if (data.ev_ratio !== undefined && data.ev_ratio !== null) {
        evRatio.innerHTML = `${data.ev_ratio}<span class="card-unit">%</span>`;
      } else {
        evRatio.textContent = '--';
      }
    }
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
      btn.classList.toggle('active', parseInt(btnEl.dataset.days || '0') === days);
    });

    const result = await api<MpgTrendPoint[]>(`/api/mpg/trend?days=${days}`, { schema: z.array(MpgTrendPointSchema) });
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

    const gradient = createGradient(context, 'rgba(50, 130, 184, 0.4)', 'rgba(50, 130, 184, 0.02)');

    state.mpgChart = new Chart(ctx, {
      type: 'line',
      data: {
        labels: data.map((d) => formatChartDate(new Date(d.date))),
        datasets: [
          {
            label: 'MPG',
            data: data.map((d) => d.mpg),
            borderColor: '#3282b8',
            backgroundColor: gradient,
            borderWidth: 2.5,
            fill: true,
            tension: 0.4,
            pointRadius: 5,
            pointHoverRadius: 7,
            pointBackgroundColor: '#3282b8',
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
            title: { text: 'MPG', color: '#3282b8' },
          }),
        },
      },
    });
  } catch (error) {
    console.error('Failed to load MPG trend:', error);
  }
}
