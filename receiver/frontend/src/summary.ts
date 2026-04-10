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
 * Check whether the dashboard is currently showing a global "no trips"
 * empty state (rendered by the trips module). JTN-490: when there are zero
 * trips at all, we want to collapse the gas-specific MPG empty state into
 * a compact caption instead of stacking two full empty-state blocks.
 */
function isDashboardGloballyEmpty(): boolean {
  const tripsBody = document.getElementById('trips-table-body');
  const tripCards = document.getElementById('trip-cards');
  const bodyEmpty = !!tripsBody?.querySelector('.empty-state');
  const cardsEmpty = !!tripCards?.querySelector('.empty-state');
  return bodyEmpty || cardsEmpty;
}

/** Show empty state when no MPG data exists. */
function showMpgEmptyState(ctx: HTMLCanvasElement): void {
  // Hide the canvas instead of removing it via parent.innerHTML — wiping the
  // parent removes the #mpg-chart element entirely, so the next call to
  // loadMpgTrendChart() can't re-find it via document.getElementById and the
  // chart never recovers without a full page reload.
  const parent = ctx.parentElement;
  if (!parent) return;

  ctx.style.display = 'none';

  // JTN-490: When the global "No Trips Recorded" empty state is already on
  // screen, a full "No Gas Trips Yet" heading underneath it reads as a
  // duplicated placeholder. Collapse to a compact caption in that case.
  const compact = isDashboardGloballyEmpty();
  const html = compact
    ? '<p class="empty-state-compact">No gas MPG data yet.</p>'
    : '<h3>No Gas Trips Yet</h3><p>MPG data will appear after you complete trips using gasoline.</p>';

  // Reuse an existing empty-state node if loadMpgTrendChart was called twice
  // for the same empty dataset; never duplicate it.
  let emptyState = parent.querySelector<HTMLElement>('.empty-state');
  if (!emptyState) {
    emptyState = document.createElement('div');
    emptyState.className = 'empty-state';
    parent.appendChild(emptyState);
  }
  // Overwrite innerHTML so toggling between compact/full modes (e.g. the
  // trips list loads after the MPG chart) always reflects the latest state.
  emptyState.innerHTML = html;
  emptyState.classList.toggle('empty-state-compact-wrapper', compact);
}

/** Render the MPG trend chart with Chart.js. */
async function renderMpgChart(ctx: HTMLCanvasElement, data: MpgTrendPoint[]): Promise<void> {
  if (!(globalThis as Record<string, unknown>).Chart) await loadChartJs();
  if (state.mpgChart) state.mpgChart.destroy();

  // Reverse the showMpgEmptyState() side effects in case the page just
  // transitioned from "no gas trips" to "first gas trip arrived".
  ctx.style.display = '';
  const existingEmptyState = ctx.parentElement?.querySelector('.empty-state');
  if (existingEmptyState) existingEmptyState.remove();

  const context = ctx.getContext('2d');
  if (!context) return;

  const mpgColor = getChartColor(1);
  const gradient = createGradient(context, `${mpgColor}66`, `${mpgColor}05`);

  state.mpgChart = new Chart(ctx, {
    type: 'line',
    data: {
      labels: data.map((d) => formatChartDate(new Date(d.date))),
      datasets: [{
        label: 'MPG', data: data.map((d) => d.mpg),
        borderColor: mpgColor, backgroundColor: gradient, borderWidth: 2.5,
        fill: true, tension: 0.4, pointRadius: 5, pointHoverRadius: 7,
        pointBackgroundColor: mpgColor, pointBorderColor: '#fff', pointBorderWidth: 2,
      }],
    },
    options: {
      responsive: true, maintainAspectRatio: false,
      interaction: { mode: 'index', intersect: false },
      plugins: {
        legend: getEnhancedLegend(false),
        tooltip: getEnhancedTooltip({
          callbacks: {
            label: (ctx: any) => {
              const point = data[ctx.dataIndex];
              return [`MPG: ${point.mpg}`, `Miles: ${point.gas_miles.toFixed(1)} mi`,
                point.ambient_temp ? `Temp: ${point.ambient_temp}°F` : ''].filter(Boolean);
            },
          },
        }),
      },
      scales: {
        x: getEnhancedAxis(),
        y: getEnhancedAxis({ suggestedMin: 20, suggestedMax: 50, title: { text: 'MPG', color: mpgColor } }),
      },
    },
  });
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
      showMpgEmptyState(ctx);
      return;
    }

    await renderMpgChart(ctx, data);
  } catch (error) {
    console.error('Failed to load MPG trend:', error);
  }
}
