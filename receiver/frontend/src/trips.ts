/**
 * VoltTracker - Trips Module
 * Trip loading, display, modal, and deletion
 */

import { DEBUG, state, fetchJson, formatDate, formatDateTime, formatTime, formatDuration } from './core';
import { loadChartJs, createGradient, getEnhancedLegend, getEnhancedTooltip, getEnhancedAxis } from './charts';
import { renderTripMap } from './map';
import { loadSummary, loadMpgTrend } from './summary';
import { loadSocAnalysis } from './battery';
import type { TripSummary, TripDetail, TelemetryPoint } from './types/api';

/**
 * Load recent trips
 */
export async function loadTrips(): Promise<void> {
  try {
    let url = '/api/trips?limit=20';
    if (state.dateFilter.start) url += `&start_date=${state.dateFilter.start}`;
    if (state.dateFilter.end) url += `&end_date=${state.dateFilter.end}`;

    const response = await fetchJson<{ trips?: TripSummary[] } | TripSummary[]>(url, { useCache: true, maxAge: 300000 });
    const trips: TripSummary[] = Array.isArray(response) ? response : (response.trips || []);

    const tableBody = document.getElementById('trips-table-body');
    const tripCards = document.getElementById('trip-cards');

    if (!tableBody || !tripCards) return;

    if (!trips || trips.length === 0) {
      tableBody.innerHTML = `
        <tr>
          <td colspan="7" class="empty-state">
            <h3>No Trips Recorded</h3>
            <p>Trips will appear once you start driving with Torque Pro connected.</p>
          </td>
        </tr>
      `;
      tripCards.innerHTML = `
        <div class="empty-state">
          <h3>No Trips Recorded</h3>
          <p>Trips will appear once you start driving.</p>
        </div>
      `;
      return;
    }

    tableBody.innerHTML = trips.map(trip => `
      <tr class="clickable" onclick="openTripModal(${trip.id})">
        <td>${formatDateTime(new Date(trip.start_time))}</td>
        <td>${trip.distance_miles != null ? trip.distance_miles.toFixed(1) : '--'} mi</td>
        <td>${trip.electric_miles !== null && trip.electric_miles !== undefined ? trip.electric_miles.toFixed(1) : '--'} mi</td>
        <td>
          ${!trip.distance_miles ?
            '<span class="badge badge-unknown">No Data</span>' :
            (trip.gas_mode_entered ?
              `<span class="badge badge-gas">${trip.gas_miles != null ? trip.gas_miles.toFixed(1) : '0'} mi</span>` :
              '<span class="badge badge-electric">Electric</span>')
          }
        </td>
        <td>${trip.gas_mpg ? trip.gas_mpg + ' MPG' : '--'}</td>
        <td>${trip.soc_at_gas_transition != null ? trip.soc_at_gas_transition.toFixed(1) + '%' : '--'}</td>
        <td>
          <button class="btn-delete" onclick="event.stopPropagation(); deleteTrip(${trip.id})" title="Delete trip">×</button>
        </td>
      </tr>
    `).join('');

    tripCards.innerHTML = trips.map(trip => `
      <div class="trip-card clickable" onclick="openTripModal(${trip.id})">
        <div class="trip-card-header">
          <span class="trip-card-date">${formatDate(new Date(trip.start_time))}</span>
          ${!trip.distance_miles ?
            '<span class="badge badge-unknown">No Data</span>' :
            (trip.gas_mode_entered ?
              '<span class="badge badge-gas">Gas</span>' :
              '<span class="badge badge-electric">Electric</span>')
          }
        </div>
        <div class="trip-card-stats">
          <div class="trip-card-stat">
            <span>Total</span>
            <span>${trip.distance_miles != null ? trip.distance_miles.toFixed(1) : '--'} mi</span>
          </div>
          <div class="trip-card-stat">
            <span>Electric</span>
            <span>${trip.electric_miles !== null && trip.electric_miles !== undefined ? trip.electric_miles.toFixed(1) : '--'} mi</span>
          </div>
          <div class="trip-card-stat">
            <span>Gas</span>
            <span>${trip.gas_miles != null ? trip.gas_miles.toFixed(1) : '--'} mi</span>
          </div>
          <div class="trip-card-stat">
            <span>MPG</span>
            <span>${trip.gas_mpg || '--'}</span>
          </div>
        </div>
      </div>
    `).join('');
  } catch (error) {
    console.error('Failed to load trips:', error);
  }
}

/**
 * Open trip detail modal
 */
export async function openTripModal(tripId: number): Promise<void> {
  state.modalTriggerElement = document.activeElement as HTMLElement;

  const modal = document.getElementById('trip-modal');
  if (!modal) return;
  modal.classList.add('show');
  modal.setAttribute('aria-hidden', 'false');
  document.body.style.overflow = 'hidden';

  const closeBtn = modal.querySelector('.modal-close') as HTMLElement | null;
  if (closeBtn) setTimeout(() => closeBtn.focus(), 100);

  try {
    const data = await fetchJson<TripDetail>(`/api/trips/${tripId}`);
    const trip = data.trip;
    const telemetry = data.telemetry;

    const summaryEl = document.getElementById('trip-detail-summary');
    if (summaryEl) {
      summaryEl.innerHTML = `
        <div class="trip-stat">
          <div class="trip-stat-label">Date</div>
          <div class="trip-stat-value">${formatDate(new Date(trip.start_time))}</div>
        </div>
        <div class="trip-stat">
          <div class="trip-stat-label">Duration</div>
          <div class="trip-stat-value">${trip.end_time ? formatDuration(new Date(trip.start_time), new Date(trip.end_time)) : '--'}</div>
        </div>
        <div class="trip-stat">
          <div class="trip-stat-label">Distance</div>
          <div class="trip-stat-value">${trip.distance_miles != null ? trip.distance_miles.toFixed(1) + ' mi' : '--'}</div>
        </div>
        <div class="trip-stat">
          <div class="trip-stat-label">Electric</div>
          <div class="trip-stat-value">${trip.electric_miles != null ? trip.electric_miles.toFixed(1) + ' mi' : '--'}</div>
        </div>
        <div class="trip-stat">
          <div class="trip-stat-label">Gas</div>
          <div class="trip-stat-value">${trip.gas_miles != null ? trip.gas_miles.toFixed(1) + ' mi' : '--'}</div>
        </div>
        <div class="trip-stat">
          <div class="trip-stat-label">MPG</div>
          <div class="trip-stat-value">${trip.gas_mpg || '--'}</div>
        </div>
      `;
    }

    renderTripMap(telemetry);
    renderTripCharts(telemetry);

    const gpxLink = document.getElementById('trip-export-gpx') as HTMLAnchorElement | null;
    const kmlLink = document.getElementById('trip-export-kml') as HTMLAnchorElement | null;
    if (gpxLink) gpxLink.href = `/api/trips/${tripId}/gpx`;
    if (kmlLink) kmlLink.href = `/api/trips/${tripId}/kml`;
  } catch (error) {
    console.error('Failed to load trip details:', error);
  }
}

/**
 * Close trip modal
 */
export function closeTripModal(): void {
  const modal = document.getElementById('trip-modal');
  if (!modal || !modal.classList.contains('show')) return;

  modal.classList.remove('show');
  modal.setAttribute('aria-hidden', 'true');
  document.body.style.overflow = '';

  if (state.tripSpeedChart) { state.tripSpeedChart.destroy(); state.tripSpeedChart = null; }
  if (state.tripSocChart) { state.tripSocChart.destroy(); state.tripSocChart = null; }
  if (state.tripMap) { state.tripMap.remove(); state.tripMap = null; }

  if (state.modalTriggerElement && typeof state.modalTriggerElement.focus === 'function') {
    state.modalTriggerElement.focus();
    state.modalTriggerElement = null;
  }
}

/**
 * Delete a trip
 */
export async function deleteTrip(tripId: number): Promise<void> {
  if (!confirm('Are you sure you want to delete this trip? This will also delete all associated telemetry data.')) return;

  try {
    const response = await fetch(`/api/trips/${tripId}`, { method: 'DELETE' });

    if (response.ok) {
      showSuccess('Trip deleted successfully');
      loadTrips();
      loadSummary();
      loadMpgTrend(state.currentTimeframe);
      loadSocAnalysis();
    } else {
      let errorMsg = 'Unknown error';
      try {
        const data = await response.json();
        errorMsg = data.error || errorMsg;
      } catch {
        errorMsg = `HTTP ${response.status}`;
      }
      showError(`Failed to delete trip: ${errorMsg}`);
    }
  } catch (error) {
    if (DEBUG) console.error('Failed to delete trip:', error);
    showError('Failed to delete trip. Please try again.');
  }
}

/**
 * Render trip detail charts
 */
export async function renderTripCharts(telemetry: TelemetryPoint[]): Promise<void> {
  const speedCtx = document.getElementById('trip-speed-chart') as HTMLCanvasElement | null;
  const socCtx = document.getElementById('trip-soc-chart') as HTMLCanvasElement | null;

  if (!speedCtx || !socCtx || telemetry.length === 0) return;

  if (!window.Chart) await loadChartJs();

  const labels = telemetry.map(t => formatTime(new Date(t.timestamp)));
  const speeds = telemetry.map(t => t.speed_mph);
  const socs = telemetry.map(t => t.state_of_charge);

  const speedContext = speedCtx.getContext('2d');
  const socContext = socCtx.getContext('2d');
  if (!speedContext || !socContext) return;

  const speedGradient = createGradient(speedContext, 'rgba(50, 130, 184, 0.4)', 'rgba(50, 130, 184, 0.02)');
  const socGradient = createGradient(socContext, 'rgba(40, 167, 69, 0.4)', 'rgba(40, 167, 69, 0.02)');

  if (state.tripSpeedChart) state.tripSpeedChart.destroy();
  state.tripSpeedChart = new Chart(speedCtx, {
    type: 'line',
    data: {
      labels,
      datasets: [{
        label: 'Speed (MPH)',
        data: speeds,
        borderColor: '#3282b8',
        backgroundColor: speedGradient,
        borderWidth: 2,
        fill: true,
        tension: 0.4,
        pointRadius: 0,
        pointHoverRadius: 4
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      interaction: { mode: 'index', intersect: false },
      plugins: {
        legend: getEnhancedLegend(true),
        tooltip: getEnhancedTooltip()
      },
      scales: {
        x: { display: false },
        y: getEnhancedAxis({ title: { text: 'MPH', color: '#3282b8' } })
      }
    }
  });

  if (state.tripSocChart) state.tripSocChart.destroy();
  state.tripSocChart = new Chart(socCtx, {
    type: 'line',
    data: {
      labels,
      datasets: [{
        label: 'Battery SOC (%)',
        data: socs,
        borderColor: '#28a745',
        backgroundColor: socGradient,
        borderWidth: 2,
        fill: true,
        tension: 0.4,
        pointRadius: 0,
        pointHoverRadius: 4
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      interaction: { mode: 'index', intersect: false },
      plugins: {
        legend: getEnhancedLegend(true),
        tooltip: getEnhancedTooltip()
      },
      scales: {
        x: { display: false },
        y: getEnhancedAxis({ min: 0, max: 100, title: { text: 'SOC %', color: '#28a745' } })
      }
    }
  });
}

/**
 * Handle timeframe button clicks
 */
export function setTimeframe(days: number): void {
  const buttons = document.querySelectorAll('.timeframe-btn');
  buttons.forEach(btn => {
    const btnDays = parseInt(btn.getAttribute('data-days') || '0');
    const isActive = btnDays === days;
    btn.classList.toggle('active', isActive);
    btn.setAttribute('aria-pressed', isActive ? 'true' : 'false');
  });

  loadMpgTrend(days);
}
