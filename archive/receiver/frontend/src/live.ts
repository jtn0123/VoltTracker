/**
 * VoltTracker - Live Telemetry Module
 * WebSocket connection, real-time updates, and power flow
 */

import { DEBUG, state, store, formatDateTime, getElapsedTime } from '@/core';
import { api } from '@/api';
import type { LiveTelemetryResponse, TelemetryPoint, WsTelemetryData, WsToastData } from '@/types/api';
import { LiveTelemetryResponseSchema } from '@/types/schemas';
import { ConnectionStatus, PowerFlowState } from '@/types/enums';

// JTN-488: throttle repeated `[WS] Connection error` warnings so a flapping
// Socket.IO handshake cannot flood the console (previously 30+/minute on the
// dashboard, even more on the map page). The cap is intentionally low — one
// warning per minute is enough to surface a real regression while leaving the
// console useful for other errors.
const WS_WARN_INTERVAL_MS = 60_000;

/**
 * Initialize WebSocket connection for real-time updates
 */
export function initWebSocket(): void {
  if (typeof io === 'undefined') {
    if (DEBUG) console.log('Socket.IO not available, falling back to polling');
    state.useWebSocket = false;
    if (!state.liveRefreshInterval) {
      state.liveRefreshInterval = setInterval(loadLiveTelemetry, 10000);
    }
    return;
  }

  try {
    // Read WebSocket auth credential from meta tag injected by the server
    const wsTokenMeta = document.querySelector('meta[name="ws-token"]');
    const wsToken = wsTokenMeta?.getAttribute('content') || '';

    state.socket = io(undefined, {
      auth: { token: wsToken, password: wsToken },
      reconnectionDelay: 2000,
      reconnectionDelayMax: 30000,
      reconnectionAttempts: 10,
    });

    let reconnectAttempt = 0;
    // JTN-488: rate-limit the console warning for connect_error. Only emit one
    // warning per WS_WARN_INTERVAL_MS window, plus a final summary of how many
    // errors were suppressed. This prevents the handshake-loop flood while
    // still surfacing the first failure immediately.
    let lastConnectErrorWarnAt = 0;
    let suppressedErrorCount = 0;

    state.socket.on('connect', () => {
      if (DEBUG) console.log('[WS] Connected (attempts=%d)', reconnectAttempt);
      reconnectAttempt = 0;
      if (suppressedErrorCount > 0) {
        console.info('[WS] Recovered after %d suppressed connection error(s)', suppressedErrorCount);
      }
      lastConnectErrorWarnAt = 0;
      suppressedErrorCount = 0;
      if (state.liveRefreshInterval) {
        clearInterval(state.liveRefreshInterval);
        state.liveRefreshInterval = null;
      }
      state.useWebSocket = true;
      updateConnectionStatus(ConnectionStatus.Connected);
    });

    state.socket.on('disconnect', (reason: string) => {
      if (DEBUG) console.log('[WS] Disconnected: %s', reason);
      updateConnectionStatus(ConnectionStatus.Disconnected);
      if (!state.liveRefreshInterval) {
        state.liveRefreshInterval = setInterval(loadLiveTelemetry, 10000);
      }
    });

    (state.socket as any).io.on('reconnect_attempt', (attempt: number) => {
      reconnectAttempt = attempt;
      if (DEBUG) console.log('[WS] Reconnect attempt #%d (backoff up to %dms)', attempt, Math.min(2000 * Math.pow(2, attempt - 1), 30000));
    });

    (state.socket as any).io.on('reconnect', (attempt: number) => {
      if (DEBUG) console.log('[WS] Reconnected after %d attempt(s)', attempt);
    });

    (state.socket as any).io.on('reconnect_failed', () => {
      console.warn('[WS] Reconnection failed after max attempts — staying on polling');
    });

    state.socket.on('telemetry', (data: WsTelemetryData) => {
      handleRealtimeTelemetry(data);
    });

    state.socket.on('toast', (data: WsToastData) => {
      // Dedup is handled by the ToastManager; just forward
      showToast(data.message, data.type, data.duration, data.actions);
    });

    state.socket.on('connect_error', (err: Error) => {
      state.useWebSocket = false;
      if (!state.liveRefreshInterval) {
        state.liveRefreshInterval = setInterval(loadLiveTelemetry, 10000);
      }
      const now = Date.now();
      if (now - lastConnectErrorWarnAt >= WS_WARN_INTERVAL_MS) {
        const suppressedNote =
          suppressedErrorCount > 0
            ? ` (${suppressedErrorCount} similar error(s) suppressed in the last minute)`
            : '';
        console.warn(
          '[WS] Connection error: %s (attempt #%d), falling back to polling%s',
          err.message,
          reconnectAttempt,
          suppressedNote,
        );
        lastConnectErrorWarnAt = now;
        suppressedErrorCount = 0;
      } else {
        suppressedErrorCount += 1;
      }
    });
  } catch (error) {
    console.error('Failed to initialize WebSocket:', error);
    state.useWebSocket = false;
    if (!state.liveRefreshInterval) {
      state.liveRefreshInterval = setInterval(loadLiveTelemetry, 10000);
    }
  }
}

/**
 * Handle real-time telemetry from WebSocket
 */
export function handleRealtimeTelemetry(data: WsTelemetryData): void {
  const liveSection = document.getElementById('live-trip-section');
  const powerFlowSection = document.getElementById('power-flow-section');

  if (liveSection && !state.liveTripActive) {
    showInfo('New trip detected', 3000);
    state.liveTripActive = true;
  }

  if (liveSection) {
    liveSection.style.display = 'block';
    liveSection.classList.add('is-live');
  }
  if (powerFlowSection && data.hv_power !== null) powerFlowSection.style.display = 'block';

  updateLiveValue('live-speed', data.speed, 0, ' mph');
  updateLiveValue('live-speed-readout', data.speed, 1, '');
  updateLiveValue('live-rpm', data.rpm, 0, ' RPM');
  updateLiveValue('live-soc', data.soc, 1, '%');
  updateLiveValue('live-fuel', data.fuel_percent, 1, '%');
  updateLiveGauge('live-speed-fill', data.speed, 120);

  if (data.hv_power !== null && data.hv_power !== undefined) {
    updateRealtimePowerDisplay(data.hv_power);
    updateLiveValue('live-power', Math.abs(data.hv_power), 0, ' kW');
    updateLiveValue('live-power-readout', Math.abs(data.hv_power), 1, '');
    updateLiveGauge('live-power-fill', Math.abs(data.hv_power), 60);
  }

  const statusPill = document.getElementById('live-status-pill');
  if (statusPill) statusPill.textContent = 'Live';
  const mode = document.getElementById('live-mode');
  if (mode) mode.textContent = data.hv_power && data.hv_power < -1 ? 'Regen' : 'Driving';

  updateConnectionStatus(ConnectionStatus.Live);
  store.emit('telemetry:update', data);
}

/**
 * Update real-time power display elements (kW, mode label, direction arrow)
 */
function updateRealtimePowerDisplay(hvPower: number): void {
  const powerKw = document.getElementById('power-kw');
  const powerMode = document.getElementById('power-mode');
  const powerDirection = document.getElementById('power-direction');

  if (powerKw) powerKw.textContent = Math.abs(hvPower).toFixed(1) + ' kW';
  updateLiveValue('live-power', Math.abs(hvPower), 0, ' kW');
  updateLiveValue('live-power-readout', Math.abs(hvPower), 1, '');
  updateLiveGauge('live-power-fill', Math.abs(hvPower), 60);

  const powerSub = document.getElementById('live-power-sub');
  if (powerMode) {
    if (hvPower > 1) {
      powerMode.textContent = 'Discharging';
      powerMode.className = 'power-mode discharging';
      if (powerSub) powerSub.textContent = 'drawing from battery';
    } else if (hvPower < -1) {
      powerMode.textContent = 'Regen';
      powerMode.className = 'power-mode regen';
      if (powerSub) powerSub.textContent = 'returning to battery';
    } else {
      powerMode.textContent = 'Idle';
      powerMode.className = 'power-mode';
      if (powerSub) powerSub.textContent = 'powertrain idle';
    }
  }
  if (powerDirection) {
    const arrow = powerDirection.querySelector('.arrow-icon');
    if (arrow) arrow.textContent = hvPower < 0 ? '←' : '→';
  }
}

/**
 * Update a live value element
 */
export function updateLiveValue(elementId: string, value: number | null, decimals: number, suffix: string): void {
  const element = document.getElementById(elementId);
  if (element && value !== null && value !== undefined) {
    element.textContent = value.toFixed(decimals) + (suffix || '');
  }
}

function updateLiveText(elementId: string, value: string): void {
  const element = document.getElementById(elementId);
  if (element) element.textContent = value;
}

function updateLiveGauge(elementClass: string, value: number | null, max: number): void {
  const element = document.querySelector<SVGPathElement>(`.${elementClass}`);
  if (!element || value === null || value === undefined) return;
  const arcLength = 220;
  const pct = Math.max(0, Math.min(1, value / max));
  element.style.strokeDasharray = `${(pct * arcLength).toFixed(1)} ${arcLength}`;
}

function isLiveWorkspaceRequested(section: HTMLElement): boolean {
  return globalThis.location.hash === '#live-trip-section' || section.dataset.routeVisible === 'true';
}

/**
 * Update connection status indicator
 */
export function updateConnectionStatus(status: ConnectionStatus): void {
  // Use the reactive store — subscribers in main.ts handle the DOM updates
  store.setState({ connectionStatus: status });
}

/**
 * Build HTML for the live trip stats section
 */
function buildLiveTripHtml(data: LiveTelemetryResponse): string {
  const elapsed = getElapsedTime(data.start_time!);
  const stats = data.trip_stats || {
    in_gas_mode: false,
    gas_mpg: null,
    miles_driven: null,
    kwh_used: null,
    kwh_per_mile: null,
  };

  let modeLabel: string, modeValue: string, modeClass: string;
  if (stats.in_gas_mode) {
    modeLabel = 'Gas MPG';
    modeValue = stats.gas_mpg ? stats.gas_mpg.toFixed(1) : '--';
    modeClass = 'engine-on';
  } else {
    modeLabel = 'Mode';
    modeValue = 'EV';
    modeClass = 'engine-off';
  }

  const point = data.data!;
  const speed = point.speed_mph ?? 0;
  const power = point.hv_battery_power_kw ?? 0;
  const soc = point.state_of_charge ?? point.soc ?? null;
  const fuel = point.fuel_level_percent ?? point.fuel_percent ?? null;
  const rpm = point.engine_rpm ?? 0;
  const batteryTemp = point.battery_temp_f ? `${point.battery_temp_f.toFixed(0)}°F` : '--';

  return `
    <div class="live-gauge-grid">
      <article class="live-gauge-card">
        <div class="live-card-title">Speed</div>
        <div class="live-gauge">
          <svg viewBox="0 0 200 160" aria-hidden="true">
            <path d="M 30 130 A 70 70 0 0 1 170 130" class="live-gauge-track"></path>
            <path d="M 30 130 A 70 70 0 0 1 170 130" class="live-gauge-fill live-speed-fill" style="stroke-dasharray: ${(Math.min(1, speed / 120) * 220).toFixed(1)} 220"></path>
            <text x="100" y="105" class="live-gauge-value" id="live-speed">${speed.toFixed(0)} mph</text>
            <text x="100" y="125" class="live-gauge-unit">MPH</text>
          </svg>
          <div>
            <strong id="live-speed-readout">${speed.toFixed(1)}</strong>
            <span>Trip average ${stats.miles_driven?.toFixed(1) || '--'} mi</span>
            <small>${elapsed} elapsed</small>
          </div>
        </div>
      </article>
      <article class="live-gauge-card">
        <div class="live-card-title">Power</div>
        <div class="live-gauge">
          <svg viewBox="0 0 200 160" aria-hidden="true">
            <path d="M 30 130 A 70 70 0 0 1 170 130" class="live-gauge-track"></path>
            <path d="M 30 130 A 70 70 0 0 1 170 130" class="live-gauge-fill live-power-fill" style="stroke-dasharray: ${(Math.min(1, Math.abs(power) / 60) * 220).toFixed(1)} 220"></path>
            <text x="100" y="105" class="live-gauge-value" id="live-power">${Math.abs(power).toFixed(0)} kW</text>
            <text x="100" y="125" class="live-gauge-unit">KW</text>
          </svg>
          <div>
            <strong id="live-power-readout">${Math.abs(power).toFixed(1)}</strong>
            <span>${power < -1 ? 'Returning to battery' : 'Drawing from battery'}</span>
            <small id="live-power-sub">${power < -1 ? 'regen active' : 'powertrain active'}</small>
          </div>
        </div>
      </article>
    </div>
    <div class="live-detail-grid">
      <article class="live-stream-card">
        <div class="live-card-title">Last 12 seconds</div>
        <div class="live-live-summary">
          <div><span>Miles</span><strong>${stats.miles_driven?.toFixed(1) || '--'}</strong></div>
          <div><span>kWh</span><strong>${stats.kwh_used?.toFixed(2) || '--'}</strong></div>
          <div><span>kWh/mi</span><strong>${stats.kwh_per_mile?.toFixed(2) || '--'}</strong></div>
          <div><span>${modeLabel}</span><strong class="${modeClass}">${modeValue}</strong></div>
        </div>
      </article>
      <article class="live-now-card">
        <div class="live-card-title">Now</div>
        <dl class="live-now-list">
          <div><dt>Mode</dt><dd id="live-mode">${stats.in_gas_mode ? 'Gas assist' : 'EV'}</dd></div>
          <div><dt>SOC</dt><dd id="live-soc">${soc !== null ? soc.toFixed(1) : '--'}%</dd></div>
          <div><dt>Fuel</dt><dd id="live-fuel">${fuel !== null ? fuel.toFixed(1) : '--'}%</dd></div>
          <div><dt>Engine</dt><dd id="live-rpm">${rpm.toFixed(0)} RPM</dd></div>
          <div><dt>Battery temp</dt><dd id="live-battery-temp">${batteryTemp}</dd></div>
          <div><dt>Heading</dt><dd>N · 12°</dd></div>
          <div><dt>Elevation</dt><dd>-- ft</dd></div>
        </dl>
      </article>
    </div>
  `;
}

/**
 * Load live telemetry for active trip display
 */
export async function loadLiveTelemetry(): Promise<void> {
  try {
    const result = await api<LiveTelemetryResponse>('/api/telemetry/latest', {
      schema: LiveTelemetryResponseSchema,
    });
    if (result.error) return;
    const data = result.data!;

    const liveSection = document.getElementById('live-trip-section');
    const liveContent = document.getElementById('live-trip-content');
    const powerFlowSection = document.getElementById('power-flow-section');

    if (!liveSection || !liveContent) return;

    if (data.active && data.data) {
      liveSection.style.display = 'block';
      liveSection.classList.add('is-live');

      liveContent.innerHTML = buildLiveTripHtml(data);
      updateLiveText('live-status-pill', 'Live');

      updatePowerFlow(data.data, powerFlowSection);

      if (!state.liveRefreshInterval) {
        state.liveRefreshInterval = setInterval(loadLiveTelemetry, 5000);
      }
    } else {
      if (state.liveTripActive) {
        showSuccess('Trip completed', 3000);
        state.liveTripActive = false;
      }

      const requested = isLiveWorkspaceRequested(liveSection);
      liveSection.style.display = requested ? 'block' : 'none';
      liveSection.classList.remove('is-live');
      updateLiveText('live-status-pill', requested ? 'Live' : 'Idle');
      if (powerFlowSection) powerFlowSection.style.display = 'none';
      if (state.liveRefreshInterval) {
        clearInterval(state.liveRefreshInterval);
        state.liveRefreshInterval = null;
      }
    }
  } catch (error) {
    console.error('Error loading live telemetry:', error);
  }
}

/**
 * Load system status
 */
export async function loadStatus(): Promise<void> {
  const statusDot = document.getElementById('status-dot');
  const lastSync = document.getElementById('last-sync');

  if (!statusDot || !lastSync) return;

  try {
    const result = await api<{ status: string; last_sync: string | null }>('/api/status', { silent: true });
    if (result.error) throw new Error(result.error);
    const data = result.data!;

    state.statusErrorCount = 0;

    if (data.status === 'online') {
      statusDot.classList.remove('offline');
    } else {
      statusDot.classList.add('offline');
    }

    if (data.last_sync) {
      const syncDate = new Date(data.last_sync);
      lastSync.textContent = `Last sync: ${formatDateTime(syncDate)}`;
    } else {
      lastSync.textContent = 'No data yet';
    }
  } catch (error) {
    state.statusErrorCount++;
    if (DEBUG) console.error(`Failed to load status (attempt ${state.statusErrorCount}):`, error);

    if (state.statusErrorCount >= state.STATUS_ERROR_THRESHOLD) {
      statusDot.classList.add('offline');
      lastSync.textContent = 'Connection error';
    }
  }
}

/**
 * Update Power Flow visualization
 */
export function updatePowerFlow(telemetry: TelemetryPoint, section: HTMLElement | null): void {
  if (!section) return;

  const hasPowerData =
    telemetry.hv_battery_power_kw !== null ||
    telemetry.hv_battery_voltage_v !== null ||
    telemetry.motor_a_rpm !== null ||
    (telemetry.engine_rpm != null && telemetry.engine_rpm > 500);

  if (!hasPowerData) {
    section.style.display = 'none';
    return;
  }

  section.style.display = 'block';

  const powerKw = telemetry.hv_battery_power_kw;
  const voltage = telemetry.hv_battery_voltage_v;
  const current = telemetry.hv_battery_current_a;
  const soc = telemetry.soc || 0;

  let mode: PowerFlowState = PowerFlowState.Idle;
  let modeLabel = 'Idle';
  let powerDisplay = '0.0 kW';

  if (powerKw !== null) {
    if (powerKw > 0.5) {
      mode = PowerFlowState.Discharging;
      modeLabel = 'Driving';
      powerDisplay = `${powerKw.toFixed(1)} kW`;
    } else if (powerKw < -0.5) {
      mode = PowerFlowState.Regenerating;
      modeLabel = 'Regen';
      powerDisplay = `${Math.abs(powerKw).toFixed(1)} kW`;
    }
  }

  const isCharging =
    (telemetry.charger_status != null && telemetry.charger_status > 0) ||
    (powerKw !== null && powerKw < -1 && (telemetry.speed_mph == null || telemetry.speed_mph < 1));
  if (isCharging) {
    mode = PowerFlowState.Charging;
    modeLabel = 'Charging';
  }

  const batteryBar = document.getElementById('battery-bar');
  if (batteryBar) {
    batteryBar.style.width = `${soc}%`;
    batteryBar.className = `power-bar ${mode}`;
  }

  const batteryStats = document.getElementById('battery-stats');
  if (batteryStats) {
    const statsText: string[] = [];
    if (voltage !== null) statsText.push(`${voltage.toFixed(0)}V`);
    if (current !== null) statsText.push(`${current.toFixed(1)}A`);
    batteryStats.textContent = statsText.join(', ') || '-- V, -- A';
  }

  const powerKwEl = document.getElementById('power-kw');
  if (powerKwEl) powerKwEl.textContent = powerDisplay;

  const powerModeEl = document.getElementById('power-mode');
  if (powerModeEl) {
    powerModeEl.textContent = modeLabel;
    powerModeEl.className = `power-mode ${mode}`;
  }

  const powerDirection = document.getElementById('power-direction');
  if (powerDirection) {
    if (mode === PowerFlowState.Regenerating || mode === PowerFlowState.Charging) {
      powerDirection.classList.add('reverse');
      powerDirection.classList.remove('active');
    } else if (mode === PowerFlowState.Discharging) {
      powerDirection.classList.add('active');
      powerDirection.classList.remove('reverse');
    } else {
      powerDirection.classList.remove('active', 'reverse');
    }
  }

  updatePowertrainDisplay(telemetry);
}

/**
 * Update powertrain RPM, engine status, and motor temperature display elements
 */
function updatePowertrainDisplay(telemetry: TelemetryPoint): void {
  updateRpmElement('motor-a-rpm', telemetry.motor_a_rpm);
  updateRpmElement('motor-b-rpm', telemetry.motor_b_rpm);
  updateRpmElement('generator-rpm', telemetry.generator_rpm);

  const engineStatus = document.getElementById('engine-status');
  if (engineStatus) {
    const engineRpm = telemetry.engine_rpm ?? 0;
    const engineOn = engineRpm > 500;
    engineStatus.textContent = engineOn ? `ON (${Math.round(engineRpm)} RPM)` : 'OFF';
    engineStatus.className = `powertrain-value ${engineOn ? 'engine-on' : 'engine-off'}`;
  }

  const motorTempsRow = document.getElementById('motor-temps-row');
  const motorTemps = document.getElementById('motor-temps');
  if (motorTempsRow && motorTemps) {
    const maxTemp = telemetry.motor_temp_max_f;
    if (maxTemp !== null && maxTemp !== undefined) {
      motorTempsRow.style.display = 'flex';
      motorTemps.textContent = `${Math.round(maxTemp)}°F`;
    } else {
      motorTempsRow.style.display = 'none';
    }
  }
}

/**
 * Update an RPM display element by id
 */
function updateRpmElement(elementId: string, rpm: number | null): void {
  const el = document.getElementById(elementId);
  if (el) {
    el.textContent = rpm !== null ? `${Math.round(rpm).toLocaleString()} RPM` : '-- RPM';
  }
}
