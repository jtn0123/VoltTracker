/**
 * VoltTracker - Charging Module
 * Charging summary, history, session management, and detail modals
 */

import { state, formatDate, formatDateTime, formatTime } from '@/core';
import { api } from '@/api';
import { loadChartJs, createGradient, getChartDefaults, getEnhancedLegend, getEnhancedTooltip, getEnhancedAxis, getChartColor } from '@/charts';
import type { ChargingSummary, ChargingSession, ChargingCurveResponse } from '@/types/api';

/** Set text content of an element by ID if it exists. */
function setText(id: string, text: string): void {
  const el = document.getElementById(id);
  if (el) el.textContent = text;
}

/** Set innerHTML of an element by ID if it exists. */
function setHtml(id: string, html: string): void {
  const el = document.getElementById(id);
  if (el) el.innerHTML = html;
}

/** Format a charging cost string from summary data. */
function formatCostText(data: ChargingSummary): string {
  if (data.monthly_cost) {
    const prefix = data.has_explicit_costs ? '' : '~';
    return `${prefix}$${data.monthly_cost.toFixed(2)}/month`;
  }
  if (data.total_cost != null) return `$${data.total_cost.toFixed(2)} total`;
  if (data.electricity_rate) return `$${data.electricity_rate}/kWh rate`;
  return 'No cost data';
}

/**
 * Load charging summary for electric efficiency cards
 */
export async function loadChargingSummary(): Promise<void> {
  try {
    const result = await api<ChargingSummary>('/api/charging/summary', { useCache: true, maxAge: 300000 });
    if (result.error || !result.data) return;
    const data = result.data;

    if (data.total_kwh) {
      setHtml('total-kwh', `${data.total_kwh.toFixed(1)}<span class="card-unit">kWh</span>`);
      setText('charging-sessions', `${data.total_sessions} charging sessions`);
    } else {
      setText('total-kwh', '--');
      setText('charging-sessions', 'No charging data');
    }

    setHtml('avg-kwh-session', data.avg_kwh_per_session
      ? `${data.avg_kwh_per_session.toFixed(1)}<span class="card-unit">kWh</span>` : '--');

    setText('charging-cost', formatCostText(data));

    if (data.l1_sessions !== undefined) setText('l1-sessions', String(data.l1_sessions));
    if (data.l2_sessions !== undefined) setText('l2-sessions', String(data.l2_sessions));

    updateCostComparison(data);
  } catch (error) {
    console.error('Failed to load charging summary:', error);
  }
}

/**
 * Update cost comparison display
 */
export function updateCostComparison(data: ChargingSummary): void {
  const section = document.getElementById('cost-comparison-section');
  if (!section) return;

  const hasCostData = data.cost_per_mile_electric || data.cost_per_mile_gas;
  if (!hasCostData) {
    section.style.display = 'none';
    return;
  }

  section.style.display = 'block';

  const electricCost = document.getElementById('cost-per-mile-electric');
  if (electricCost) {
    electricCost.textContent = data.cost_per_mile_electric ? `$${data.cost_per_mile_electric.toFixed(3)}` : '--';
  }

  const gasCost = document.getElementById('cost-per-mile-gas');
  if (gasCost) {
    gasCost.textContent = data.cost_per_mile_gas ? `$${data.cost_per_mile_gas.toFixed(3)}` : '--';
  }

  const savingsEl = document.getElementById('cost-savings');
  if (savingsEl && data.cost_per_mile_electric && data.cost_per_mile_gas) {
    const savings = data.cost_per_mile_gas - data.cost_per_mile_electric;
    const savingsPercent = ((savings / data.cost_per_mile_gas) * 100).toFixed(0);
    if (savings > 0) {
      savingsEl.textContent = `Save $${savings.toFixed(3)}/mi (${savingsPercent}%)`;
      savingsEl.className = 'cost-savings positive';
    } else {
      savingsEl.textContent = `Gas cheaper by $${Math.abs(savings).toFixed(3)}/mi`;
      savingsEl.className = 'cost-savings negative';
    }
  } else if (savingsEl) {
    savingsEl.textContent = 'Compare when data available';
    savingsEl.className = 'cost-savings';
  }
}

/**
 * Format charging duration
 */
export function formatChargingDuration(startTime: string, endTime: string): string {
  const start = new Date(startTime);
  const end = new Date(endTime);
  const minutes = Math.round((end.getTime() - start.getTime()) / 60000);

  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(minutes / 60);
  const mins = minutes % 60;
  return mins > 0 ? `${hours}h ${mins}m` : `${hours}h`;
}

// Whitelist of charge types we accept from the server. Anything outside this
// set falls back to a generic 'unknown' class so a malicious or malformed
// charge_type can never inject CSS-class characters into the rendered HTML.
const KNOWN_CHARGE_TYPES = new Set(['l1', 'l2', 'dcfc', 'dc']);

/** Escape HTML special characters in user-provided strings before interpolation. */
function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (c) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;',
  }[c] as string));
}

/** Format common session display fields. */
function sessionFields(session: ChargingSession) {
  // Whitelist + escape charge_type so neither the CSS class nor the visible
  // text can be used as an XSS or CSS-injection vector.
  const rawType = session.charge_type ?? '';
  const typeKey = rawType.toLowerCase();
  const safeTypeClass = KNOWN_CHARGE_TYPES.has(typeKey) ? typeKey : 'unknown';
  const safeTypeText = escapeHtml(rawType);
  const typeBadge = rawType
    ? `<span class="badge badge-${safeTypeClass}">${safeTypeText}</span>`
    : '--';

  const energy = session.kwh_added != null ? session.kwh_added.toFixed(1) + ' kWh' : '--';
  const soc = session.start_soc !== null && session.end_soc !== null
    ? `${session.start_soc}% → ${session.end_soc}%` : '--';
  const duration = session.end_time ? formatChargingDuration(session.start_time, session.end_time) : '--';
  // location_name is user-controlled (manual entry) — escape before interpolation.
  const location = escapeHtml(session.location_name || '--');
  const dateStr = formatDateTime(new Date(session.start_time));
  return { typeBadge, safeTypeClass, safeTypeText, energy, soc, duration, location, dateStr };
}

/** Render a charging session table row. */
function renderChargingRow(session: ChargingSession): string {
  const f = sessionFields(session);
  return `<tr class="clickable" onclick="openChargingDetailModal(${session.id})">
    <td>${f.dateStr}</td><td>${f.typeBadge}</td><td>${f.energy}</td>
    <td>${f.soc}</td><td>${f.duration}</td><td>${f.location}</td>
    <td><button class="btn-delete" onclick="event.stopPropagation(); deleteChargingSession(${session.id})" title="Delete session">×</button></td>
  </tr>`;
}

/** Render a charging session card. */
function renderChargingCard(session: ChargingSession): string {
  const f = sessionFields(session);
  // Reuse the whitelisted/escaped fields from sessionFields rather than
  // re-interpolating raw session.charge_type, which would re-introduce the
  // XSS surface this function was just hardened against.
  const badge = f.safeTypeText
    ? `<span class="charging-card-badge ${f.safeTypeClass}">${f.safeTypeText}</span>`
    : '';
  return `<div class="charging-card" role="listitem" onclick="openChargingDetailModal(${session.id})">
    <div class="charging-card-header"><span class="charging-card-date">${f.dateStr}</span>${badge}</div>
    <div class="charging-card-stats">
      <div class="charging-card-stat"><span class="charging-card-stat-label">Energy</span><span class="charging-card-stat-value">${f.energy}</span></div>
      <div class="charging-card-stat"><span class="charging-card-stat-label">SOC</span><span class="charging-card-stat-value">${f.soc}</span></div>
      <div class="charging-card-stat"><span class="charging-card-stat-label">Duration</span><span class="charging-card-stat-value">${f.duration}</span></div>
      <div class="charging-card-stat"><span class="charging-card-stat-label">Location</span><span class="charging-card-stat-value">${f.location}</span></div>
    </div>
  </div>`;
}

/**
 * Load charging history
 */
export async function loadChargingHistory(): Promise<void> {
  try {
    const result = await api<{ sessions: ChargingSession[]; pagination?: unknown } | ChargingSession[]>('/api/charging/history?per_page=20');
    if (result.error || !result.data) return;
    const sessions: ChargingSession[] = Array.isArray(result.data) ? result.data : result.data.sessions || [];

    const tableBody = document.getElementById('charging-table-body');
    const cardsContainer = document.getElementById('charging-cards');

    if (!tableBody) return;

    if (!sessions || sessions.length === 0) {
      tableBody.innerHTML = `
        <tr>
          <td colspan="7" class="empty-state">
            <h3>No Charging Sessions</h3>
            <p>Add charging sessions manually or they'll be detected automatically.</p>
          </td>
        </tr>
      `;
      if (cardsContainer) {
        cardsContainer.innerHTML = `
          <div class="empty-state">
            <h3>No Charging Sessions</h3>
            <p>Add charging sessions manually or they'll be detected automatically.</p>
          </div>
        `;
      }
      return;
    }

    tableBody.innerHTML = sessions.map(renderChargingRow).join('');

    if (cardsContainer) {
      cardsContainer.innerHTML = sessions.map(renderChargingCard).join('');
    }
  } catch (error) {
    console.error('Failed to load charging history:', error);
  }
}

/**
 * Open add charging session modal
 */
export function openAddChargingModal(): void {
  const modal = document.getElementById('charging-modal');
  if (!modal) return;
  modal.classList.add('show');
  modal.setAttribute('aria-hidden', 'false');
  document.body.style.overflow = 'hidden';

  const now = new Date();
  const localDateTime = now.toISOString().slice(0, 16);
  const chargeStart = document.getElementById('charge-start') as HTMLInputElement | null;
  if (chargeStart) {
    chargeStart.value = localDateTime;
    setTimeout(() => chargeStart.focus(), 100);
  }
}

/**
 * Close charging modal
 */
export function closeChargingModal(): void {
  const modal = document.getElementById('charging-modal');
  if (!modal?.classList.contains('show')) return;

  modal.classList.remove('show');
  modal.setAttribute('aria-hidden', 'true');
  document.body.style.overflow = '';

  const form = document.getElementById('charging-form') as HTMLFormElement | null;
  if (form) form.reset();
}

/**
 * Submit charging session form
 */
export async function submitChargingSession(event: Event): Promise<void> {
  event.preventDefault();

  const getValue = (id: string): string => (document.getElementById(id) as HTMLInputElement)?.value || '';

  const formData: Record<string, string | number | null> = {
    start_time: getValue('charge-start'),
    end_time: getValue('charge-end') || null,
    start_soc: Number.parseFloat(getValue('charge-start-soc')) || null,
    end_soc: Number.parseFloat(getValue('charge-end-soc')) || null,
    kwh_added: Number.parseFloat(getValue('charge-kwh')) || null,
    charge_type: getValue('charge-type') || null,
    cost: Number.parseFloat(getValue('charge-cost')) || null,
    location_name: getValue('charge-location') || null,
    notes: getValue('charge-notes') || null,
  };

  // Remove null/empty values
  const cleanData: Record<string, string | number> = {};
  for (const [key, val] of Object.entries(formData)) {
    if (val !== null && val !== '') cleanData[key] = val;
  }

  try {
    const result = await api<{ id: number }>('/api/charging/add', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(cleanData),
    });

    if (result.error) {
      showError(`Failed to add charging session: ${result.error}`);
    } else {
      closeChargingModal();
      loadChargingSummary();
      loadChargingHistory();
    }
  } catch (error) {
    console.error('Failed to submit charging session:', error);
    showError('Failed to add charging session. Please try again.');
  }
}

/**
 * Delete a charging session
 */
export async function deleteChargingSession(sessionId: number): Promise<void> {
  if (!confirm('Are you sure you want to delete this charging session?')) return;

  try {
    const result = await api<{ success: boolean }>(`/api/charging/${sessionId}`, { method: 'DELETE' });

    if (result.error) {
      showError(`Failed to delete charging session: ${result.error}`);
    } else {
      loadChargingSummary();
      loadChargingHistory();
    }
  } catch (error) {
    console.error('Failed to delete charging session:', error);
    showError('Failed to delete charging session. Please try again.');
  }
}

/**
 * Open charging session detail modal
 */
export async function openChargingDetailModal(sessionId: number): Promise<void> {
  const modal = document.getElementById('charging-detail-modal');
  if (!modal) return;
  modal.classList.add('show');
  modal.setAttribute('aria-hidden', 'false');
  document.body.style.overflow = 'hidden';

  const closeBtn = modal.querySelector<HTMLElement>('.modal-close');
  if (closeBtn) setTimeout(() => closeBtn.focus(), 100);

  // Show loading state
  const detailSummary = document.getElementById('charging-detail-summary');
  if (detailSummary) detailSummary.innerHTML = '<div class="modal-loading"><div class="spinner"></div><span>Loading charging details…</span></div>';

  try {
    const [sessionResult, curveResult] = await Promise.all([
      api<ChargingSession>(`/api/charging/${sessionId}`),
      api<ChargingCurveResponse>(`/api/charging/${sessionId}/curve`),
    ]);
    if (sessionResult.error || curveResult.error) return;
    const session = sessionResult.data!;
    const curveData = curveResult.data!;

    renderChargingDetailSummary(session);
    renderChargingCurveChart(curveData);
    renderChargingCostBreakdown(session);
  } catch (error) {
    console.error('Failed to load charging session details:', error);
  }
}

/**
 * Close charging detail modal
 */
export function closeChargingDetailModal(): void {
  const modal = document.getElementById('charging-detail-modal');
  if (!modal?.classList.contains('show')) return;

  modal.classList.remove('show');
  modal.setAttribute('aria-hidden', 'true');
  document.body.style.overflow = '';

  if (state.chargingCurveChart) {
    state.chargingCurveChart.destroy();
    state.chargingCurveChart = null;
  }
}

/**
 * Render charging session summary in the detail modal
 */
export function renderChargingDetailSummary(session: ChargingSession): void {
  const summaryEl = document.getElementById('charging-detail-summary');
  if (!summaryEl) return;

  const duration = session.end_time ? formatChargingDuration(session.start_time, session.end_time) : 'In progress';

  summaryEl.innerHTML = `
    <div class="charging-detail-grid">
      <div class="charging-stat">
        <div class="charging-stat-label">Date</div>
        <div class="charging-stat-value">${formatDate(new Date(session.start_time))}</div>
      </div>
      <div class="charging-stat">
        <div class="charging-stat-label">Duration</div>
        <div class="charging-stat-value">${duration}</div>
      </div>
      <div class="charging-stat">
        <div class="charging-stat-label">Type</div>
        <div class="charging-stat-value">
          ${
            session.charge_type
              ? `<span class="badge badge-${session.charge_type.toLowerCase()}">${session.charge_type}</span>`
              : '--'
          }
        </div>
      </div>
      <div class="charging-stat">
        <div class="charging-stat-label">Energy Added</div>
        <div class="charging-stat-value">${session.kwh_added != null ? session.kwh_added.toFixed(1) + ' kWh' : '--'}</div>
      </div>
      <div class="charging-stat">
        <div class="charging-stat-label">SOC Change</div>
        <div class="charging-stat-value">
          ${
            session.start_soc !== null && session.end_soc !== null
              ? `${session.start_soc}% → ${session.end_soc}%`
              : '--'
          }
        </div>
      </div>
      <div class="charging-stat">
        <div class="charging-stat-label">Peak Power</div>
        <div class="charging-stat-value">${session.peak_power_kw != null ? session.peak_power_kw.toFixed(1) + ' kW' : '--'}</div>
      </div>
      <div class="charging-stat">
        <div class="charging-stat-label">Avg Power</div>
        <div class="charging-stat-value">${session.avg_power_kw != null ? session.avg_power_kw.toFixed(1) + ' kW' : '--'}</div>
      </div>
      <div class="charging-stat">
        <div class="charging-stat-label">Location</div>
        <div class="charging-stat-value">${session.location_name || '--'}</div>
      </div>
    </div>
  `;
}

/**
 * Render charging curve chart
 */
export async function renderChargingCurveChart(curveData: ChargingCurveResponse): Promise<void> {
  const ctx = document.getElementById('charging-curve-chart') as HTMLCanvasElement | null;
  if (!ctx) return;

  if (state.chargingCurveChart) {
    state.chargingCurveChart.destroy();
    state.chargingCurveChart = null;
  }

  if (!curveData.curve || curveData.curve.length === 0) {
    const parent = ctx.parentElement;
    if (parent) {
      parent.innerHTML = `
        <div class="empty-state" style="padding: 2rem; text-align: center;">
          <p>No charging curve data available for this session.</p>
          <p style="font-size: 0.875rem; color: var(--text-secondary);">
            Curve data is recorded during active charging sessions.
          </p>
        </div>
      `;
    }
    return;
  }

  if (!(globalThis as Record<string, unknown>).Chart) await loadChartJs();

  const curve = curveData.curve;
  const labels = curve.map((d) => formatTime(new Date(d.timestamp)));
  const powerData = curve.map((d) => d.power_kw);
  const socData = curve.map((d) => d.soc);

  const context = ctx.getContext('2d');
  if (!context) return;

  const chartColor2 = getChartColor(2);
  const chartColor1 = getChartColor(1);
  const powerGradient = createGradient(context, `${chartColor2}66`, `${chartColor2}05`);
  const socGradient = createGradient(context, `${chartColor1}4d`, `${chartColor1}05`);
  const defaults = getChartDefaults();

  state.chargingCurveChart = new Chart(ctx, {
    type: 'line',
    data: {
      labels,
      datasets: [
        {
          label: 'Power (kW)',
          data: powerData,
          borderColor: chartColor2,
          backgroundColor: powerGradient,
          borderWidth: 2.5,
          fill: true,
          tension: 0.4,
          pointRadius: 0,
          pointHoverRadius: 4,
          yAxisID: 'y',
        },
        {
          label: 'SOC (%)',
          data: socData,
          borderColor: chartColor1,
          backgroundColor: socGradient,
          borderWidth: 2.5,
          fill: true,
          tension: 0.4,
          pointRadius: 0,
          pointHoverRadius: 4,
          yAxisID: 'y1',
        },
      ],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      interaction: { mode: 'index', intersect: false },
      plugins: {
        legend: getEnhancedLegend(true),
        tooltip: getEnhancedTooltip({
          callbacks: {
            label: (context: any) => {
              const label = context.dataset.label || '';
              const value = context.parsed.y;
              return label.includes('Power') ? `${label}: ${value.toFixed(1)} kW` : `${label}: ${value.toFixed(0)}%`;
            },
          },
        }),
      },
      scales: {
        x: getEnhancedAxis({ display: true, ticks: { maxTicksLimit: 8 } }),
        y: {
          type: 'linear',
          display: true,
          position: 'left',
          title: { display: true, text: 'Power (kW)', color: chartColor2, font: defaults.titleFont },
          grid: { color: 'rgba(255, 255, 255, 0.08)' },
          ticks: { color: chartColor2, font: { size: defaults.tickFont.size }, padding: 8 },
          min: 0,
        },
        y1: {
          type: 'linear',
          display: true,
          position: 'right',
          title: { display: true, text: 'SOC (%)', color: chartColor1, font: defaults.titleFont },
          grid: { drawOnChartArea: false },
          ticks: { color: chartColor1, font: { size: defaults.tickFont.size }, padding: 8 },
          min: 0,
          max: 100,
        },
      },
    },
  });
}

/**
 * Render charging cost breakdown
 */
export function renderChargingCostBreakdown(session: ChargingSession): void {
  const breakdownEl = document.getElementById('charging-cost-breakdown');
  if (!breakdownEl) return;

  const kwh = session.kwh_added || 0;
  const explicitCost = session.cost;
  const rate = session.cost_per_kwh || session.electricity_rate || 0.12;
  const estimatedCost = kwh * rate;
  const actualCost = explicitCost !== null ? explicitCost : estimatedCost;

  const milesPerKwh = 3.5;
  const equivalentMiles = kwh * milesPerKwh;
  const gasGallons = equivalentMiles / 35;
  const gasCost = gasGallons * 3.5;
  const savings = gasCost - actualCost;

  breakdownEl.innerHTML = `
    <h3>Cost Analysis</h3>
    <div class="cost-breakdown-grid">
      <div class="cost-item">
        <span class="cost-label">Session Cost</span>
        <span class="cost-value ${explicitCost !== null ? '' : 'estimated'}">
          ${explicitCost !== null ? '' : '~'}$${actualCost.toFixed(2)}
        </span>
      </div>
      <div class="cost-item">
        <span class="cost-label">Rate</span>
        <span class="cost-value">$${rate.toFixed(2)}/kWh</span>
      </div>
      <div class="cost-item">
        <span class="cost-label">Equiv. Miles</span>
        <span class="cost-value">${equivalentMiles.toFixed(0)} mi</span>
      </div>
      <div class="cost-item">
        <span class="cost-label">Gas Equiv. Cost</span>
        <span class="cost-value">$${gasCost.toFixed(2)}</span>
      </div>
    </div>
    ${
      savings > 0
        ? `
      <div class="savings-callout">
        <strong>Savings vs Gas:</strong> $${savings.toFixed(2)}
      </div>
    `
        : ''
    }
  `;
}
