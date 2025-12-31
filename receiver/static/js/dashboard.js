/**
 * Volt Efficiency Tracker - Dashboard JavaScript
 */

// State
let mpgChart = null;
let socChart = null;
let tripSpeedChart = null;
let tripSocChart = null;
let tripMap = null;
let currentTimeframe = 30;
let dateFilter = { start: null, end: null };
let flatpickrInstance = null;
let liveRefreshInterval = null;
let socket = null;
let useWebSocket = true;  // Will fallback to polling if WebSocket fails

// Initialize dashboard on load
document.addEventListener('DOMContentLoaded', () => {
    initTheme();
    initDatePicker();
    initWebSocket();
    initServiceWorker();
    loadStatus();
    loadSummary();
    loadMpgTrend(currentTimeframe);
    loadTrips();
    loadSocAnalysis();
    loadChargingSummary();
    loadChargingHistory();
    loadLiveTelemetry();
    loadBatteryHealth();

    // Refresh status every 30 seconds
    setInterval(loadStatus, 30000);

    // Check for live trip every 10 seconds (fallback if WebSocket fails)
    if (!useWebSocket) {
        setInterval(loadLiveTelemetry, 10000);
    }

    // Auto-refresh trips every 60 seconds
    setInterval(loadTrips, 60000);
});

/**
 * Initialize WebSocket connection for real-time updates
 */
function initWebSocket() {
    // Check if Socket.IO is available
    if (typeof io === 'undefined') {
        console.log('Socket.IO not available, falling back to polling');
        useWebSocket = false;
        setInterval(loadLiveTelemetry, 10000);
        return;
    }

    try {
        socket = io();

        socket.on('connect', () => {
            console.log('WebSocket connected');
            useWebSocket = true;
            updateConnectionStatus('connected');
        });

        socket.on('disconnect', () => {
            console.log('WebSocket disconnected');
            updateConnectionStatus('disconnected');
            // Start polling as fallback
            if (!liveRefreshInterval) {
                liveRefreshInterval = setInterval(loadLiveTelemetry, 10000);
            }
        });

        socket.on('telemetry', (data) => {
            handleRealtimeTelemetry(data);
        });

        socket.on('connect_error', (error) => {
            console.log('WebSocket connection error, falling back to polling');
            useWebSocket = false;
            if (!liveRefreshInterval) {
                liveRefreshInterval = setInterval(loadLiveTelemetry, 10000);
            }
        });
    } catch (error) {
        console.error('Failed to initialize WebSocket:', error);
        useWebSocket = false;
        setInterval(loadLiveTelemetry, 10000);
    }
}

/**
 * Handle real-time telemetry from WebSocket
 */
function handleRealtimeTelemetry(data) {
    // Show live trip section
    const liveSection = document.getElementById('live-trip-section');
    const powerFlowSection = document.getElementById('power-flow-section');

    if (liveSection) {
        liveSection.style.display = 'block';
    }
    if (powerFlowSection && data.hv_power !== null) {
        powerFlowSection.style.display = 'block';
    }

    // Update live display values
    updateLiveValue('live-speed', data.speed, 0, ' mph');
    updateLiveValue('live-rpm', data.rpm, 0, ' RPM');
    updateLiveValue('live-soc', data.soc, 1, '%');
    updateLiveValue('live-fuel', data.fuel_percent, 1, '%');

    // Update power flow if available
    if (data.hv_power !== null && data.hv_power !== undefined) {
        const powerKw = document.getElementById('power-kw');
        const powerMode = document.getElementById('power-mode');
        const powerDirection = document.getElementById('power-direction');

        if (powerKw) {
            powerKw.textContent = Math.abs(data.hv_power).toFixed(1) + ' kW';
        }
        if (powerMode) {
            if (data.hv_power > 1) {
                powerMode.textContent = 'Discharging';
                powerMode.className = 'power-mode discharging';
            } else if (data.hv_power < -1) {
                powerMode.textContent = 'Regen';
                powerMode.className = 'power-mode regen';
            } else {
                powerMode.textContent = 'Idle';
                powerMode.className = 'power-mode';
            }
        }
        if (powerDirection) {
            powerDirection.querySelector('.arrow-icon').textContent =
                data.hv_power < 0 ? '←' : '→';
        }
    }

    // Update status indicator
    updateConnectionStatus('live');
}

/**
 * Update a live value element
 */
function updateLiveValue(elementId, value, decimals, suffix) {
    const element = document.getElementById(elementId);
    if (element && value !== null && value !== undefined) {
        element.textContent = value.toFixed(decimals) + (suffix || '');
    }
}

/**
 * Update connection status indicator
 */
function updateConnectionStatus(status) {
    const statusDot = document.getElementById('status-dot');
    const lastSync = document.getElementById('last-sync');

    if (statusDot) {
        statusDot.classList.remove('offline', 'live');
        if (status === 'live' || status === 'connected') {
            statusDot.classList.add('live');
        } else if (status === 'disconnected') {
            statusDot.classList.add('offline');
        }
    }

    if (lastSync && status === 'live') {
        lastSync.textContent = 'Live';
    }
}

/**
 * Initialize Service Worker for PWA
 */
function initServiceWorker() {
    if ('serviceWorker' in navigator) {
        navigator.serviceWorker.register('/static/sw.js')
            .then(reg => console.log('Service Worker registered'))
            .catch(err => console.log('Service Worker registration failed:', err));
    }
}

/**
 * Initialize theme from localStorage
 */
function initTheme() {
    const savedTheme = localStorage.getItem('theme') || 'dark';
    document.documentElement.setAttribute('data-theme', savedTheme);
    updateThemeIcon(savedTheme);

    // Set initial aria-pressed for theme toggle button
    const themeBtn = document.querySelector('.theme-toggle');
    if (themeBtn) {
        themeBtn.setAttribute('aria-pressed', savedTheme === 'dark' ? 'true' : 'false');
    }
}

/**
 * Toggle between light and dark theme
 */
function toggleTheme() {
    const currentTheme = document.documentElement.getAttribute('data-theme');
    const newTheme = currentTheme === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', newTheme);
    localStorage.setItem('theme', newTheme);
    updateThemeIcon(newTheme);

    // Update aria-pressed for theme toggle button
    const themeBtn = document.querySelector('.theme-toggle');
    if (themeBtn) {
        themeBtn.setAttribute('aria-pressed', newTheme === 'dark' ? 'true' : 'false');
    }
}

/**
 * Update theme icon
 */
function updateThemeIcon(theme) {
    const icon = document.getElementById('theme-icon');
    if (icon) {
        icon.textContent = theme === 'dark' ? '🌙' : '☀️';
    }
}

/**
 * Initialize date range picker
 */
function initDatePicker() {
    const input = document.getElementById('date-range');
    if (!input) return;

    flatpickrInstance = flatpickr(input, {
        mode: 'range',
        dateFormat: 'M j, Y',
        theme: 'dark',
        onChange: function(selectedDates) {
            if (selectedDates.length === 2) {
                dateFilter.start = selectedDates[0].toISOString().split('T')[0];
                dateFilter.end = selectedDates[1].toISOString().split('T')[0];
                document.getElementById('clear-date-filter').style.display = 'block';
                loadTrips();
            }
        }
    });
}

/**
 * Clear date filter
 */
function clearDateFilter() {
    dateFilter = { start: null, end: null };
    if (flatpickrInstance) {
        flatpickrInstance.clear();
    }
    document.getElementById('clear-date-filter').style.display = 'none';
    loadTrips();
}

/**
 * Load system status
 */
async function loadStatus() {
    try {
        const response = await fetch('/api/status');
        const data = await response.json();

        const statusDot = document.getElementById('status-dot');
        const lastSync = document.getElementById('last-sync');

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
        console.error('Failed to load status:', error);
        document.getElementById('status-dot').classList.add('offline');
    }
}

/**
 * Load live telemetry for active trip display
 */
async function loadLiveTelemetry() {
    try {
        const response = await fetch('/api/telemetry/latest');
        const data = await response.json();

        const liveSection = document.getElementById('live-trip-section');
        const liveContent = document.getElementById('live-trip-content');
        const powerFlowSection = document.getElementById('power-flow-section');

        if (!liveSection || !liveContent) return;

        if (data.active && data.data) {
            liveSection.style.display = 'block';

            const elapsed = getElapsedTime(data.start_time);
            const lastUpdate = new Date(data.data.timestamp);
            const secondsAgo = Math.floor((Date.now() - lastUpdate) / 1000);
            const stats = data.trip_stats || {};

            // Determine mode display
            let modeLabel, modeValue, modeClass;
            if (stats.in_gas_mode) {
                modeLabel = 'Gas MPG';
                modeValue = stats.gas_mpg ? stats.gas_mpg.toFixed(1) : '--';
                modeClass = 'engine-on';
            } else {
                modeLabel = 'Mode';
                modeValue = 'EV';
                modeClass = 'engine-off';
            }

            liveContent.innerHTML = `
                <div class="live-stats">
                    <div class="stat">
                        <span class="label">Miles</span>
                        <span class="value">${stats.miles_driven?.toFixed(1) || '--'}</span>
                    </div>
                    <div class="stat">
                        <span class="label">kWh</span>
                        <span class="value">${stats.kwh_used?.toFixed(2) || '--'}</span>
                    </div>
                    <div class="stat">
                        <span class="label">kWh/mi</span>
                        <span class="value">${stats.kwh_per_mile?.toFixed(2) || '--'}</span>
                    </div>
                    <div class="stat">
                        <span class="label">${modeLabel}</span>
                        <span class="value ${modeClass}">${modeValue}</span>
                    </div>
                </div>
                <div class="live-meta">
                    SOC: ${data.data.soc?.toFixed(0) || '--'}% |
                    Fuel: ${data.data.fuel_percent?.toFixed(0) || '--'}% |
                    ${elapsed} elapsed
                </div>
            `;

            // Update Power Flow section if data available
            updatePowerFlow(data.data, powerFlowSection);

            // Start faster refresh when active (every 5 seconds)
            if (!liveRefreshInterval) {
                liveRefreshInterval = setInterval(loadLiveTelemetry, 5000);
            }
        } else {
            liveSection.style.display = 'none';
            if (powerFlowSection) {
                powerFlowSection.style.display = 'none';
            }
            // Clear faster refresh when no active trip
            if (liveRefreshInterval) {
                clearInterval(liveRefreshInterval);
                liveRefreshInterval = null;
            }
        }
    } catch (error) {
        console.error('Error loading live telemetry:', error);
    }
}

/**
 * Update Power Flow visualization
 */
function updatePowerFlow(telemetry, section) {
    if (!section) return;

    // Check if we have any power flow related data
    const hasPowerData = telemetry.hv_battery_power_kw !== null ||
                         telemetry.hv_battery_voltage_v !== null ||
                         telemetry.motor_a_rpm !== null ||
                         telemetry.engine_rpm > 500;

    if (!hasPowerData) {
        section.style.display = 'none';
        return;
    }

    section.style.display = 'block';

    // Get power values
    const powerKw = telemetry.hv_battery_power_kw;
    const voltage = telemetry.hv_battery_voltage_v;
    const current = telemetry.hv_battery_current_a;
    const soc = telemetry.soc || 0;

    // Determine power mode
    let mode = 'idle';
    let modeLabel = 'Idle';
    let powerDisplay = '0.0 kW';

    if (powerKw !== null) {
        if (powerKw > 0.5) {
            mode = 'discharging';
            modeLabel = 'Driving';
            powerDisplay = `${powerKw.toFixed(1)} kW`;
        } else if (powerKw < -0.5) {
            mode = 'regenerating';
            modeLabel = 'Regen';
            powerDisplay = `${Math.abs(powerKw).toFixed(1)} kW`;
        } else {
            mode = 'idle';
            modeLabel = 'Idle';
            powerDisplay = '0.0 kW';
        }
    }

    // Check if charging (based on charger status or low speed with negative power)
    if (telemetry.charger_status > 0 || (powerKw < -1 && telemetry.speed_mph < 1)) {
        mode = 'charging';
        modeLabel = 'Charging';
    }

    // Update battery bar (based on SOC)
    const batteryBar = document.getElementById('battery-bar');
    if (batteryBar) {
        batteryBar.style.width = `${soc}%`;
        batteryBar.className = `power-bar ${mode}`;
    }

    // Update battery stats
    const batteryStats = document.getElementById('battery-stats');
    if (batteryStats) {
        let statsText = [];
        if (voltage !== null) statsText.push(`${voltage.toFixed(0)}V`);
        if (current !== null) statsText.push(`${current.toFixed(1)}A`);
        batteryStats.textContent = statsText.join(', ') || '-- V, -- A';
    }

    // Update power display
    const powerKwEl = document.getElementById('power-kw');
    if (powerKwEl) {
        powerKwEl.textContent = powerDisplay;
    }

    // Update mode display
    const powerModeEl = document.getElementById('power-mode');
    if (powerModeEl) {
        powerModeEl.textContent = modeLabel;
        powerModeEl.className = `power-mode ${mode}`;
    }

    // Update arrow direction
    const powerDirection = document.getElementById('power-direction');
    if (powerDirection) {
        if (mode === 'regenerating' || mode === 'charging') {
            powerDirection.classList.add('reverse');
            powerDirection.classList.remove('active');
        } else if (mode === 'discharging') {
            powerDirection.classList.add('active');
            powerDirection.classList.remove('reverse');
        } else {
            powerDirection.classList.remove('active', 'reverse');
        }
    }

    // Update motor RPMs
    const motorARpm = document.getElementById('motor-a-rpm');
    if (motorARpm) {
        motorARpm.textContent = telemetry.motor_a_rpm !== null ?
            `${Math.round(telemetry.motor_a_rpm).toLocaleString()} RPM` : '-- RPM';
    }

    const motorBRpm = document.getElementById('motor-b-rpm');
    if (motorBRpm) {
        motorBRpm.textContent = telemetry.motor_b_rpm !== null ?
            `${Math.round(telemetry.motor_b_rpm).toLocaleString()} RPM` : '-- RPM';
    }

    // Update generator RPM
    const generatorRpm = document.getElementById('generator-rpm');
    if (generatorRpm) {
        generatorRpm.textContent = telemetry.generator_rpm !== null ?
            `${Math.round(telemetry.generator_rpm).toLocaleString()} RPM` : '-- RPM';
    }

    // Update engine status
    const engineStatus = document.getElementById('engine-status');
    if (engineStatus) {
        const engineOn = telemetry.engine_rpm > 500;
        engineStatus.textContent = engineOn ? `ON (${Math.round(telemetry.engine_rpm)} RPM)` : 'OFF';
        engineStatus.className = `powertrain-value ${engineOn ? 'engine-on' : 'engine-off'}`;
    }

    // Update motor temps if available
    const motorTempsRow = document.getElementById('motor-temps-row');
    const motorTemps = document.getElementById('motor-temps');
    if (motorTempsRow && motorTemps) {
        const temps = [
            telemetry.motor_temp_1_f,
            telemetry.motor_temp_2_f,
            telemetry.motor_temp_3_f,
            telemetry.motor_temp_4_f
        ].filter(t => t !== null && t !== undefined);

        if (temps.length > 0) {
            motorTempsRow.style.display = 'flex';
            motorTemps.textContent = temps.map(t => `${Math.round(t)}°F`).join(' / ');
        } else {
            motorTempsRow.style.display = 'none';
        }
    }
}

/**
 * Calculate elapsed time from a start timestamp
 */
function getElapsedTime(startTime) {
    const start = new Date(startTime);
    const now = new Date();
    const diffMs = now - start;
    const diffMins = Math.floor(diffMs / 60000);
    const diffHours = Math.floor(diffMins / 60);

    if (diffHours > 0) {
        return `${diffHours}h ${diffMins % 60}m`;
    }
    return `${diffMins}m`;
}

/**
 * Load efficiency summary
 */
async function loadSummary() {
    try {
        const response = await fetch('/api/efficiency/summary');
        const data = await response.json();

        // Lifetime MPG
        const lifetimeMpg = document.getElementById('lifetime-mpg');
        if (data.lifetime_gas_mpg) {
            lifetimeMpg.innerHTML = `${data.lifetime_gas_mpg}<span class="card-unit">MPG</span>`;
            document.getElementById('lifetime-miles').textContent =
                `${data.lifetime_gas_miles} gas miles`;
        } else {
            lifetimeMpg.textContent = '--';
            document.getElementById('lifetime-miles').textContent = 'No gas data yet';
        }

        // Current Tank MPG
        const tankMpg = document.getElementById('tank-mpg');
        if (data.current_tank_mpg) {
            tankMpg.innerHTML = `${data.current_tank_mpg}<span class="card-unit">MPG</span>`;
            document.getElementById('tank-miles').textContent =
                `${data.current_tank_miles} miles this tank`;
        } else {
            tankMpg.textContent = '--';
            document.getElementById('tank-miles').textContent = 'No data since last fill';
        }

        // Total Miles
        const totalMiles = document.getElementById('total-miles');
        if (data.total_miles_tracked) {
            totalMiles.innerHTML = `${data.total_miles_tracked.toLocaleString()}<span class="card-unit">mi</span>`;
        } else {
            totalMiles.textContent = '--';
        }

        // Electric Efficiency (kWh/mile)
        const kwhPerMile = document.getElementById('kwh-per-mile');
        const miPerKwh = document.getElementById('mi-per-kwh');
        if (data.avg_kwh_per_mile) {
            kwhPerMile.innerHTML = `${data.avg_kwh_per_mile}<span class="card-unit">kWh/mi</span>`;
            if (data.mi_per_kwh) {
                miPerKwh.textContent = `${data.mi_per_kwh} mi/kWh`;
            } else {
                miPerKwh.textContent = 'Lifetime average';
            }
        } else {
            kwhPerMile.textContent = '--';
            miPerKwh.textContent = 'No electric data yet';
        }

        // Electric Miles with kWh used
        const electricMiles = document.getElementById('total-electric-miles');
        const totalKwhUsed = document.getElementById('total-kwh-used');
        if (data.total_electric_miles) {
            electricMiles.innerHTML = `${data.total_electric_miles.toLocaleString()}<span class="card-unit">mi</span>`;
            if (data.total_kwh_used) {
                totalKwhUsed.textContent = `${data.total_kwh_used} kWh used`;
            } else {
                totalKwhUsed.textContent = 'Total EV driving';
            }
        } else {
            electricMiles.textContent = '--';
            totalKwhUsed.textContent = 'No electric data yet';
        }

        // EV Ratio
        const evRatio = document.getElementById('ev-ratio');
        if (data.ev_ratio !== undefined && data.ev_ratio !== null) {
            evRatio.innerHTML = `${data.ev_ratio}<span class="card-unit">%</span>`;
        } else {
            evRatio.textContent = '--';
        }

    } catch (error) {
        console.error('Failed to load summary:', error);
    }
}

/**
 * Load MPG trend chart
 */
async function loadMpgTrend(days) {
    try {
        currentTimeframe = days;

        // Update active button
        document.querySelectorAll('.timeframe-btn').forEach(btn => {
            btn.classList.toggle('active', parseInt(btn.dataset.days) === days);
        });

        const response = await fetch(`/api/mpg/trend?days=${days}`);
        const data = await response.json();

        const ctx = document.getElementById('mpg-chart');

        if (data.length === 0) {
            ctx.parentElement.innerHTML = `
                <div class="empty-state">
                    <h3>No Gas Trips Yet</h3>
                    <p>MPG data will appear after you complete trips using gasoline.</p>
                </div>
            `;
            return;
        }

        // Destroy existing chart
        if (mpgChart) {
            mpgChart.destroy();
        }

        mpgChart = new Chart(ctx, {
            type: 'line',
            data: {
                labels: data.map(d => formatDate(new Date(d.date))),
                datasets: [{
                    label: 'MPG',
                    data: data.map(d => d.mpg),
                    borderColor: '#3282b8',
                    backgroundColor: 'rgba(50, 130, 184, 0.1)',
                    borderWidth: 2,
                    fill: true,
                    tension: 0.3,
                    pointRadius: 4,
                    pointBackgroundColor: '#3282b8'
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: {
                        display: false
                    },
                    tooltip: {
                        backgroundColor: '#0f3460',
                        titleColor: '#ffffff',
                        bodyColor: '#b8b8b8',
                        borderColor: '#3282b8',
                        borderWidth: 1,
                        callbacks: {
                            label: (context) => {
                                const point = data[context.dataIndex];
                                return [
                                    `MPG: ${point.mpg}`,
                                    `Miles: ${point.gas_miles}`,
                                    point.ambient_temp ? `Temp: ${point.ambient_temp}°F` : ''
                                ].filter(Boolean);
                            }
                        }
                    }
                },
                scales: {
                    x: {
                        grid: {
                            color: 'rgba(255, 255, 255, 0.1)'
                        },
                        ticks: {
                            color: '#b8b8b8'
                        }
                    },
                    y: {
                        grid: {
                            color: 'rgba(255, 255, 255, 0.1)'
                        },
                        ticks: {
                            color: '#b8b8b8'
                        },
                        suggestedMin: 20,
                        suggestedMax: 50
                    }
                }
            }
        });
    } catch (error) {
        console.error('Failed to load MPG trend:', error);
    }
}

/**
 * Load recent trips
 */
async function loadTrips() {
    try {
        let url = '/api/trips?limit=20';
        if (dateFilter.start) url += `&start_date=${dateFilter.start}`;
        if (dateFilter.end) url += `&end_date=${dateFilter.end}`;

        const response = await fetch(url);
        const trips = await response.json();

        const tableBody = document.getElementById('trips-table-body');
        const tripCards = document.getElementById('trip-cards');

        if (trips.length === 0) {
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

        // Desktop table with clickable rows
        tableBody.innerHTML = trips.map(trip => `
            <tr class="clickable" onclick="openTripModal(${trip.id})">
                <td>${formatDateTime(new Date(trip.start_time))}</td>
                <td>${trip.distance_miles ? trip.distance_miles.toFixed(1) : '--'} mi</td>
                <td>${trip.electric_miles ? trip.electric_miles.toFixed(1) : '--'} mi</td>
                <td>
                    ${trip.gas_mode_entered ?
                        `<span class="badge badge-gas">${trip.gas_miles ? trip.gas_miles.toFixed(1) : '0'} mi</span>` :
                        '<span class="badge badge-electric">Electric</span>'
                    }
                </td>
                <td>${trip.gas_mpg ? trip.gas_mpg + ' MPG' : '--'}</td>
                <td>${trip.soc_at_gas_transition ? trip.soc_at_gas_transition.toFixed(1) + '%' : '--'}</td>
                <td>
                    <button class="btn-delete" onclick="event.stopPropagation(); deleteTrip(${trip.id})" title="Delete trip">×</button>
                </td>
            </tr>
        `).join('');

        // Mobile cards with click handler
        tripCards.innerHTML = trips.map(trip => `
            <div class="trip-card clickable" onclick="openTripModal(${trip.id})">
                <div class="trip-card-header">
                    <span class="trip-card-date">${formatDate(new Date(trip.start_time))}</span>
                    ${trip.gas_mode_entered ?
                        '<span class="badge badge-gas">Gas</span>' :
                        '<span class="badge badge-electric">Electric</span>'
                    }
                </div>
                <div class="trip-card-stats">
                    <div class="trip-card-stat">
                        <span>Total</span>
                        <span>${trip.distance_miles ? trip.distance_miles.toFixed(1) : '--'} mi</span>
                    </div>
                    <div class="trip-card-stat">
                        <span>Electric</span>
                        <span>${trip.electric_miles ? trip.electric_miles.toFixed(1) : '--'} mi</span>
                    </div>
                    <div class="trip-card-stat">
                        <span>Gas</span>
                        <span>${trip.gas_miles ? trip.gas_miles.toFixed(1) : '--'} mi</span>
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
async function openTripModal(tripId) {
    const modal = document.getElementById('trip-modal');
    modal.classList.add('show');
    modal.setAttribute('aria-hidden', 'false');
    document.body.style.overflow = 'hidden';

    // Focus the close button for keyboard users
    const closeBtn = modal.querySelector('.modal-close');
    if (closeBtn) {
        setTimeout(() => closeBtn.focus(), 100);
    }

    try {
        const response = await fetch(`/api/trips/${tripId}`);
        const data = await response.json();
        const trip = data.trip;
        const telemetry = data.telemetry;

        // Render summary stats
        const summaryEl = document.getElementById('trip-detail-summary');
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
                <div class="trip-stat-value">${trip.distance_miles ? trip.distance_miles.toFixed(1) + ' mi' : '--'}</div>
            </div>
            <div class="trip-stat">
                <div class="trip-stat-label">Electric</div>
                <div class="trip-stat-value">${trip.electric_miles ? trip.electric_miles.toFixed(1) + ' mi' : '--'}</div>
            </div>
            <div class="trip-stat">
                <div class="trip-stat-label">Gas</div>
                <div class="trip-stat-value">${trip.gas_miles ? trip.gas_miles.toFixed(1) + ' mi' : '--'}</div>
            </div>
            <div class="trip-stat">
                <div class="trip-stat-label">MPG</div>
                <div class="trip-stat-value">${trip.gas_mpg || '--'}</div>
            </div>
        `;

        // Render map
        renderTripMap(telemetry);

        // Render charts
        renderTripCharts(telemetry);

    } catch (error) {
        console.error('Failed to load trip details:', error);
    }
}

/**
 * Close trip modal
 */
function closeTripModal() {
    const modal = document.getElementById('trip-modal');
    if (!modal.classList.contains('show')) return;

    modal.classList.remove('show');
    modal.setAttribute('aria-hidden', 'true');
    document.body.style.overflow = '';

    // Clean up charts
    if (tripSpeedChart) {
        tripSpeedChart.destroy();
        tripSpeedChart = null;
    }
    if (tripSocChart) {
        tripSocChart.destroy();
        tripSocChart = null;
    }
    if (tripMap) {
        tripMap.remove();
        tripMap = null;
    }
}

/**
 * Render trip route on map with color-coded segments
 * - Green: Electric driving (no engine)
 * - Orange: Gas mode (engine running)
 * - Blue: Regenerating (negative power flow)
 */
function renderTripMap(telemetry) {
    const mapEl = document.getElementById('trip-detail-map');

    // Filter telemetry with valid GPS coordinates
    const gpsPoints = telemetry.filter(t => t.latitude && t.longitude);

    if (gpsPoints.length < 2) {
        mapEl.innerHTML = '<div class="no-gps">No GPS data available for this trip</div>';
        return;
    }

    // Clear previous content
    mapEl.innerHTML = '';

    // Initialize map
    tripMap = L.map(mapEl).setView([gpsPoints[0].latitude, gpsPoints[0].longitude], 13);

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; OpenStreetMap contributors'
    }).addTo(tripMap);

    // Determine if we have power/RPM data for color coding
    const hasPowerData = gpsPoints.some(p =>
        p.engine_rpm !== undefined || p.hv_battery_power_kw !== undefined
    );

    if (hasPowerData) {
        // Check if we should use efficiency coloring (default: true)
        const useEfficiencyView = localStorage.getItem('mapEfficiencyView') !== 'false';

        // Add view toggle control
        addMapViewToggle(tripMap, gpsPoints, useEfficiencyView);

        // Create color-coded polyline segments
        const segments = useEfficiencyView
            ? createEfficiencySegments(gpsPoints)
            : createColorCodedSegments(gpsPoints);

        // Add each colored segment
        segments.forEach(segment => {
            if (segment.points.length >= 2) {
                L.polyline(segment.points, {
                    color: segment.color,
                    weight: 4,
                    opacity: 0.9
                }).addTo(tripMap);
            }
        });

        // Add legend
        addMapLegend(tripMap, useEfficiencyView);

        // Fit map to all points
        const allPoints = gpsPoints.map(p => [p.latitude, p.longitude]);
        const bounds = L.latLngBounds(allPoints);
        tripMap.fitBounds(bounds, { padding: [20, 20] });
    } else {
        // Fallback to simple polyline if no power data
        const latlngs = gpsPoints.map(p => [p.latitude, p.longitude]);
        const polyline = L.polyline(latlngs, { color: '#3282b8', weight: 4 }).addTo(tripMap);
        tripMap.fitBounds(polyline.getBounds(), { padding: [20, 20] });
    }

    // Add start and end markers
    const startPoint = [gpsPoints[0].latitude, gpsPoints[0].longitude];
    const endPoint = [gpsPoints[gpsPoints.length - 1].latitude, gpsPoints[gpsPoints.length - 1].longitude];

    L.marker(startPoint, {
        icon: L.divIcon({
            className: 'map-marker-start',
            html: '<div style="background:#28a745;width:12px;height:12px;border-radius:50%;border:2px solid white;box-shadow:0 2px 4px rgba(0,0,0,0.3);"></div>'
        })
    }).addTo(tripMap).bindPopup('Start');

    L.marker(endPoint, {
        icon: L.divIcon({
            className: 'map-marker-end',
            html: '<div style="background:#dc3545;width:12px;height:12px;border-radius:50%;border:2px solid white;box-shadow:0 2px 4px rgba(0,0,0,0.3);"></div>'
        })
    }).addTo(tripMap).bindPopup('End');
}

/**
 * Create color-coded route segments based on driving mode
 */
function createColorCodedSegments(points) {
    const segments = [];
    let currentSegment = null;

    for (let i = 0; i < points.length; i++) {
        const point = points[i];
        const color = getPointColor(point);
        const latlng = [point.latitude, point.longitude];

        if (!currentSegment || currentSegment.color !== color) {
            // Start new segment
            if (currentSegment && currentSegment.points.length > 0) {
                // Add overlap point for continuity
                currentSegment.points.push(latlng);
            }
            currentSegment = {
                color: color,
                points: currentSegment ? [currentSegment.points[currentSegment.points.length - 1]] : []
            };
            currentSegment.points.push(latlng);
            segments.push(currentSegment);
        } else {
            currentSegment.points.push(latlng);
        }
    }

    return segments;
}

/**
 * Get color for a telemetry point based on driving mode
 */
function getPointColor(point) {
    const engineRpm = point.engine_rpm;
    const powerKw = point.hv_battery_power_kw;

    // Check for regeneration first (negative power = charging battery)
    if (powerKw !== undefined && powerKw !== null && powerKw < -0.5) {
        return '#3498db'; // Blue - Regenerating
    }

    // Check if engine is running (gas mode)
    if (engineRpm !== undefined && engineRpm !== null && engineRpm > 500) {
        return '#e67e22'; // Orange - Gas mode
    }

    // Default to electric
    return '#27ae60'; // Green - Electric
}

/**
 * Get color based on instantaneous efficiency
 * Uses a gradient from red (poor) to yellow (moderate) to green (excellent)
 */
function getEfficiencyColor(efficiency, isGasMode) {
    if (efficiency === null || efficiency === undefined || !isFinite(efficiency)) {
        return '#888888'; // Gray for unknown
    }

    if (isGasMode) {
        // Gas MPG: <30 red, 30-40 yellow, >40 green
        if (efficiency < 25) return '#e74c3c';      // Red - poor
        if (efficiency < 30) return '#e67e22';      // Orange - below average
        if (efficiency < 35) return '#f1c40f';      // Yellow - moderate
        if (efficiency < 40) return '#2ecc71';      // Light green - good
        return '#27ae60';                           // Green - excellent
    } else {
        // Electric miles/kWh: <2.5 red, 2.5-3.5 yellow, >3.5 green
        // (Inverse of kWh/mile: 0.4+ bad, 0.29-0.4 moderate, <0.29 good)
        if (efficiency < 2.0) return '#e74c3c';     // Red - poor
        if (efficiency < 2.5) return '#e67e22';     // Orange - below average
        if (efficiency < 3.0) return '#f1c40f';     // Yellow - moderate
        if (efficiency < 3.5) return '#2ecc71';     // Light green - good
        return '#27ae60';                           // Green - excellent
    }
}

/**
 * Calculate haversine distance between two GPS points (in miles)
 */
function haversineDistance(lat1, lon1, lat2, lon2) {
    const R = 3959; // Earth radius in miles
    const dLat = (lat2 - lat1) * Math.PI / 180;
    const dLon = (lon2 - lon1) * Math.PI / 180;
    const a = Math.sin(dLat / 2) ** 2 +
              Math.cos(lat1 * Math.PI / 180) *
              Math.cos(lat2 * Math.PI / 180) *
              Math.sin(dLon / 2) ** 2;
    return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

/**
 * Create efficiency-colored route segments
 * Colors segments based on instantaneous efficiency (kWh/mile or MPG)
 */
function createEfficiencySegments(points) {
    const segments = [];
    let currentSegment = null;

    for (let i = 1; i < points.length; i++) {
        const prev = points[i - 1];
        const curr = points[i];

        if (!curr.latitude || !curr.longitude || !prev.latitude || !prev.longitude) {
            continue;
        }

        // Calculate distance for this segment
        const distance = haversineDistance(
            prev.latitude, prev.longitude,
            curr.latitude, curr.longitude
        );

        // Skip very short segments (GPS noise)
        if (distance < 0.001) continue;

        // Determine efficiency and mode
        const isGasMode = curr.engine_rpm !== undefined && curr.engine_rpm > 500;
        let efficiency = null;

        if (isGasMode) {
            // Gas mode - calculate MPG for segment
            const prevFuel = prev.fuel_level_percent;
            const currFuel = curr.fuel_level_percent;
            if (prevFuel !== undefined && currFuel !== undefined && prevFuel > currFuel) {
                const fuelUsed = (prevFuel - currFuel) / 100 * 9.31; // gallons
                if (fuelUsed > 0.001) {
                    efficiency = distance / fuelUsed; // MPG
                }
            }
        } else {
            // Electric mode - calculate miles per kWh
            const prevSoc = prev.state_of_charge;
            const currSoc = curr.state_of_charge;
            if (prevSoc !== undefined && currSoc !== undefined && prevSoc > currSoc) {
                const kwUsed = (prevSoc - currSoc) / 100 * 18.4; // kWh
                if (kwUsed > 0.001) {
                    efficiency = distance / kwUsed; // miles per kWh
                }
            }
        }

        // Get color based on efficiency
        const color = getEfficiencyColor(efficiency, isGasMode);
        const latlng = [curr.latitude, curr.longitude];
        const prevLatlng = [prev.latitude, prev.longitude];

        if (!currentSegment || currentSegment.color !== color) {
            // Start new segment
            if (currentSegment && currentSegment.points.length > 0) {
                // Add overlap point for continuity
                currentSegment.points.push(prevLatlng);
            }
            currentSegment = {
                color: color,
                efficiency: efficiency,
                isGasMode: isGasMode,
                points: [prevLatlng]
            };
            segments.push(currentSegment);
        }

        currentSegment.points.push(latlng);
    }

    return segments;
}

/**
 * Add a legend to the map
 */
function addMapLegend(map, isEfficiencyMode = false) {
    const legend = L.control({ position: 'bottomright' });

    legend.onAdd = function() {
        const div = L.DomUtil.create('div', 'map-legend');
        if (isEfficiencyMode) {
            div.innerHTML = `
                <div style="background:rgba(0,0,0,0.8);padding:10px;border-radius:6px;color:white;font-size:11px;">
                    <div style="font-weight:bold;margin-bottom:6px;">Efficiency</div>
                    <div style="margin-bottom:3px;"><span style="display:inline-block;width:12px;height:3px;background:#27ae60;margin-right:6px;"></span>Excellent</div>
                    <div style="margin-bottom:3px;"><span style="display:inline-block;width:12px;height:3px;background:#2ecc71;margin-right:6px;"></span>Good</div>
                    <div style="margin-bottom:3px;"><span style="display:inline-block;width:12px;height:3px;background:#f1c40f;margin-right:6px;"></span>Moderate</div>
                    <div style="margin-bottom:3px;"><span style="display:inline-block;width:12px;height:3px;background:#e67e22;margin-right:6px;"></span>Below Avg</div>
                    <div style="margin-bottom:3px;"><span style="display:inline-block;width:12px;height:3px;background:#e74c3c;margin-right:6px;"></span>Poor</div>
                    <div><span style="display:inline-block;width:12px;height:3px;background:#888888;margin-right:6px;"></span>Unknown</div>
                </div>
            `;
        } else {
            div.innerHTML = `
                <div style="background:rgba(0,0,0,0.7);padding:8px;border-radius:6px;color:white;font-size:11px;">
                    <div style="margin-bottom:4px;"><span style="display:inline-block;width:12px;height:3px;background:#27ae60;margin-right:6px;"></span>Electric</div>
                    <div style="margin-bottom:4px;"><span style="display:inline-block;width:12px;height:3px;background:#e67e22;margin-right:6px;"></span>Gas</div>
                    <div><span style="display:inline-block;width:12px;height:3px;background:#3498db;margin-right:6px;"></span>Regen</div>
                </div>
            `;
        }
        return div;
    };

    legend.addTo(map);
}

/**
 * Add a toggle control to switch between mode and efficiency views
 */
function addMapViewToggle(map, gpsPoints, isEfficiencyMode) {
    const toggle = L.control({ position: 'topleft' });

    toggle.onAdd = function() {
        const div = L.DomUtil.create('div', 'map-view-toggle');
        div.innerHTML = `
            <div style="background:rgba(255,255,255,0.95);padding:6px 10px;border-radius:6px;box-shadow:0 2px 6px rgba(0,0,0,0.2);font-size:12px;">
                <label style="display:flex;align-items:center;cursor:pointer;color:#333;">
                    <input type="checkbox" id="map-efficiency-toggle" ${isEfficiencyMode ? 'checked' : ''} style="margin-right:6px;">
                    Efficiency Heatmap
                </label>
            </div>
        `;

        // Prevent map interactions when clicking the toggle
        L.DomEvent.disableClickPropagation(div);

        // Handle toggle change
        const checkbox = div.querySelector('#map-efficiency-toggle');
        checkbox.addEventListener('change', function() {
            localStorage.setItem('mapEfficiencyView', this.checked);
            // Re-render the map with new view
            renderTripMap(gpsPoints);
        });

        return div;
    };

    toggle.addTo(map);
}

/**
 * Render trip detail charts
 */
function renderTripCharts(telemetry) {
    const speedCtx = document.getElementById('trip-speed-chart');
    const socCtx = document.getElementById('trip-soc-chart');

    if (!speedCtx || !socCtx || telemetry.length === 0) return;

    const labels = telemetry.map(t => formatTime(new Date(t.timestamp)));
    const speeds = telemetry.map(t => t.speed_mph);
    const socs = telemetry.map(t => t.state_of_charge);

    // Speed chart
    if (tripSpeedChart) tripSpeedChart.destroy();
    tripSpeedChart = new Chart(speedCtx, {
        type: 'line',
        data: {
            labels,
            datasets: [{
                label: 'Speed (MPH)',
                data: speeds,
                borderColor: '#3282b8',
                backgroundColor: 'rgba(50, 130, 184, 0.1)',
                borderWidth: 2,
                fill: true,
                tension: 0.3,
                pointRadius: 0
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: { display: true, position: 'top', labels: { color: '#b8b8b8' } }
            },
            scales: {
                x: { display: false },
                y: { grid: { color: 'rgba(255,255,255,0.1)' }, ticks: { color: '#b8b8b8' } }
            }
        }
    });

    // SOC chart
    if (tripSocChart) tripSocChart.destroy();
    tripSocChart = new Chart(socCtx, {
        type: 'line',
        data: {
            labels,
            datasets: [{
                label: 'Battery SOC (%)',
                data: socs,
                borderColor: '#28a745',
                backgroundColor: 'rgba(40, 167, 69, 0.1)',
                borderWidth: 2,
                fill: true,
                tension: 0.3,
                pointRadius: 0
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: { display: true, position: 'top', labels: { color: '#b8b8b8' } }
            },
            scales: {
                x: { display: false },
                y: { grid: { color: 'rgba(255,255,255,0.1)' }, ticks: { color: '#b8b8b8' }, min: 0, max: 100 }
            }
        }
    });
}

/**
 * Load SOC analysis
 */
async function loadSocAnalysis() {
    try {
        const response = await fetch('/api/soc/analysis');
        const data = await response.json();

        // Update SOC floor card
        const socFloor = document.getElementById('soc-floor');
        if (data.average_soc) {
            socFloor.innerHTML = `${data.average_soc}<span class="card-unit">%</span>`;
            document.getElementById('soc-count').textContent =
                `${data.count} transitions recorded`;
        } else {
            socFloor.textContent = '--';
            document.getElementById('soc-count').textContent = 'No gas transitions yet';
        }

        // Update stats
        document.getElementById('soc-min').textContent =
            data.min_soc ? `${data.min_soc}%` : '--';
        document.getElementById('soc-max').textContent =
            data.max_soc ? `${data.max_soc}%` : '--';
        document.getElementById('soc-avg').textContent =
            data.average_soc ? `${data.average_soc}%` : '--';

        // Temperature correlation
        if (data.temperature_correlation) {
            document.getElementById('soc-cold').textContent =
                `${data.temperature_correlation.cold_avg_soc}% (${data.temperature_correlation.cold_count} trips)`;
            document.getElementById('soc-warm').textContent =
                `${data.temperature_correlation.warm_avg_soc}% (${data.temperature_correlation.warm_count} trips)`;
        } else {
            document.getElementById('soc-cold').textContent = '--';
            document.getElementById('soc-warm').textContent = '--';
        }

        // Trend
        if (data.trend) {
            document.getElementById('soc-trend').textContent =
                `${data.trend.direction === 'increasing' ? 'Increasing' : 'Decreasing'} ` +
                `(${data.trend.early_avg}% -> ${data.trend.recent_avg}%)`;
        } else {
            document.getElementById('soc-trend').textContent = 'Not enough data';
        }

        // Histogram chart
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
function renderSocHistogram(histogram) {
    const ctx = document.getElementById('soc-histogram-chart');
    if (!ctx) return;

    // Sort and prepare data
    const labels = Object.keys(histogram).sort((a, b) => parseInt(a) - parseInt(b));
    const values = labels.map(k => histogram[k]);

    if (socChart) {
        socChart.destroy();
    }

    socChart = new Chart(ctx, {
        type: 'bar',
        data: {
            labels: labels.map(l => `${l}%`),
            datasets: [{
                label: 'Transitions',
                data: values,
                backgroundColor: '#3282b8',
                borderRadius: 4
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: {
                    display: false
                },
                tooltip: {
                    backgroundColor: '#0f3460',
                    titleColor: '#ffffff',
                    bodyColor: '#b8b8b8'
                }
            },
            scales: {
                x: {
                    grid: {
                        display: false
                    },
                    ticks: {
                        color: '#b8b8b8'
                    }
                },
                y: {
                    grid: {
                        color: 'rgba(255, 255, 255, 0.1)'
                    },
                    ticks: {
                        color: '#b8b8b8',
                        stepSize: 1
                    },
                    beginAtZero: true
                }
            }
        }
    });
}

/**
 * Format date for display
 */
function formatDate(date) {
    return date.toLocaleDateString('en-US', {
        month: 'short',
        day: 'numeric'
    });
}

/**
 * Format date and time for display
 */
function formatDateTime(date) {
    return date.toLocaleDateString('en-US', {
        month: 'short',
        day: 'numeric',
        hour: 'numeric',
        minute: '2-digit'
    });
}

/**
 * Format time only
 */
function formatTime(date) {
    return date.toLocaleTimeString('en-US', {
        hour: 'numeric',
        minute: '2-digit'
    });
}

/**
 * Format duration between two dates
 */
function formatDuration(start, end) {
    const minutes = Math.round((end - start) / 60000);
    if (minutes < 60) return `${minutes} min`;
    const hours = Math.floor(minutes / 60);
    const mins = minutes % 60;
    return mins > 0 ? `${hours}h ${mins}m` : `${hours}h`;
}

/**
 * Handle timeframe button clicks
 */
function setTimeframe(days) {
    // Update aria-pressed on timeframe buttons
    const buttons = document.querySelectorAll('.timeframe-btn');
    buttons.forEach(btn => {
        const btnDays = parseInt(btn.getAttribute('data-days'));
        const isActive = btnDays === days;
        btn.classList.toggle('active', isActive);
        btn.setAttribute('aria-pressed', isActive ? 'true' : 'false');
    });

    loadMpgTrend(days);
}

/**
 * Toggle export dropdown menu
 */
function toggleExportMenu() {
    const menu = document.getElementById('export-menu');
    const btn = document.getElementById('export-btn');
    const isOpen = menu.classList.toggle('show');

    // Update ARIA state
    if (btn) {
        btn.setAttribute('aria-expanded', isOpen ? 'true' : 'false');
    }
}

// Close export menu when clicking outside
document.addEventListener('click', (event) => {
    const menu = document.getElementById('export-menu');
    const dropdown = document.querySelector('.export-dropdown');
    const btn = document.getElementById('export-btn');
    if (menu && dropdown && !dropdown.contains(event.target)) {
        menu.classList.remove('show');
        if (btn) {
            btn.setAttribute('aria-expanded', 'false');
        }
    }
});

// Close modals on escape key and handle keyboard navigation
document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
        closeTripModal();
        closeChargingModal();

        // Close export menu
        const menu = document.getElementById('export-menu');
        const btn = document.getElementById('export-btn');
        if (menu && menu.classList.contains('show')) {
            menu.classList.remove('show');
            if (btn) {
                btn.setAttribute('aria-expanded', 'false');
                btn.focus();
            }
        }
    }
});

/**
 * Delete a trip
 */
async function deleteTrip(tripId) {
    if (!confirm('Are you sure you want to delete this trip? This will also delete all associated telemetry data.')) {
        return;
    }

    try {
        const response = await fetch(`/api/trips/${tripId}`, {
            method: 'DELETE'
        });

        if (response.ok) {
            // Reload trips and summary
            loadTrips();
            loadSummary();
            loadMpgTrend(currentTimeframe);
            loadSocAnalysis();
        } else {
            const data = await response.json();
            alert(`Failed to delete trip: ${data.error || 'Unknown error'}`);
        }
    } catch (error) {
        console.error('Failed to delete trip:', error);
        alert('Failed to delete trip. Please try again.');
    }
}

/**
 * Load charging summary for electric efficiency cards
 */
async function loadChargingSummary() {
    try {
        const response = await fetch('/api/charging/summary');
        const data = await response.json();

        // Total kWh
        const totalKwh = document.getElementById('total-kwh');
        if (data.total_kwh) {
            totalKwh.innerHTML = `${data.total_kwh.toFixed(1)}<span class="card-unit">kWh</span>`;
            document.getElementById('charging-sessions').textContent =
                `${data.total_sessions} charging sessions`;
        } else {
            totalKwh.textContent = '--';
            document.getElementById('charging-sessions').textContent = 'No charging data';
        }

        // Average kWh per session with cost info
        const avgKwhSession = document.getElementById('avg-kwh-session');
        if (avgKwhSession) {
            if (data.avg_kwh_per_session) {
                avgKwhSession.innerHTML = `${data.avg_kwh_per_session.toFixed(1)}<span class="card-unit">kWh</span>`;
            } else {
                avgKwhSession.textContent = '--';
            }
        }

        // Show monthly/total cost if available
        const chargingCost = document.getElementById('charging-cost');
        if (chargingCost) {
            if (data.monthly_cost) {
                const costLabel = data.has_explicit_costs ? '' : '~';
                chargingCost.textContent = `${costLabel}$${data.monthly_cost.toFixed(2)}/month`;
            } else if (data.total_cost) {
                chargingCost.textContent = `$${data.total_cost.toFixed(2)} total`;
            } else if (data.electricity_rate) {
                chargingCost.textContent = `$${data.electricity_rate}/kWh rate`;
            } else {
                chargingCost.textContent = 'No cost data';
            }
        }

        // L1/L2 session counts
        if (data.l1_sessions !== undefined) {
            document.getElementById('l1-sessions').textContent = data.l1_sessions;
        }
        if (data.l2_sessions !== undefined) {
            document.getElementById('l2-sessions').textContent = data.l2_sessions;
        }

        // Update cost comparison section if it exists
        updateCostComparison(data);

    } catch (error) {
        console.error('Failed to load charging summary:', error);
    }
}

/**
 * Update cost comparison display
 */
function updateCostComparison(data) {
    const section = document.getElementById('cost-comparison-section');
    if (!section) return;

    // Only show if we have cost data
    const hasCostData = data.cost_per_mile_electric || data.cost_per_mile_gas;
    if (!hasCostData) {
        section.style.display = 'none';
        return;
    }

    section.style.display = 'block';

    // Update electric cost per mile
    const electricCost = document.getElementById('cost-per-mile-electric');
    if (electricCost && data.cost_per_mile_electric) {
        electricCost.textContent = `$${data.cost_per_mile_electric.toFixed(3)}`;
    } else if (electricCost) {
        electricCost.textContent = '--';
    }

    // Update gas cost per mile
    const gasCost = document.getElementById('cost-per-mile-gas');
    if (gasCost && data.cost_per_mile_gas) {
        gasCost.textContent = `$${data.cost_per_mile_gas.toFixed(3)}`;
    } else if (gasCost) {
        gasCost.textContent = '--';
    }

    // Update savings
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
 * Load battery health data
 */
async function loadBatteryHealth() {
    try {
        const response = await fetch('/api/battery/health');
        const data = await response.json();

        const section = document.getElementById('battery-health-section');
        if (!section) return;

        // Only show if we have data
        if (!data.has_data) {
            section.style.display = 'none';
            return;
        }

        section.style.display = 'block';

        // Update capacity display
        const capacityEl = document.getElementById('battery-health-capacity');
        if (capacityEl && data.current_capacity_kwh) {
            capacityEl.textContent = data.current_capacity_kwh.toFixed(1);
        }

        // Update original capacity
        const originalEl = document.getElementById('battery-health-original');
        if (originalEl && data.original_capacity_kwh) {
            originalEl.textContent = data.original_capacity_kwh.toFixed(1);
        }

        // Update health percent
        const percentEl = document.getElementById('battery-health-percent');
        if (percentEl && data.health_percent) {
            percentEl.textContent = `${data.health_percent}% capacity`;
        }

        // Update status badge
        const statusEl = document.getElementById('battery-health-status');
        if (statusEl && data.health_status) {
            statusEl.textContent = data.health_status.charAt(0).toUpperCase() + data.health_status.slice(1);
            statusEl.className = `battery-health-status ${data.health_status}`;
        }

        // Update health bar
        const barEl = document.getElementById('battery-health-bar');
        if (barEl && data.health_percent) {
            barEl.style.width = `${data.health_percent}%`;
            // Set bar color based on health
            if (data.health_percent >= 80) {
                barEl.className = 'battery-health-bar good';
            } else if (data.health_percent >= 70) {
                barEl.className = 'battery-health-bar fair';
            } else {
                barEl.className = 'battery-health-bar degraded';
            }
        }

        // Update trend
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
 * Load charging history
 */
async function loadChargingHistory() {
    try {
        const response = await fetch('/api/charging/history?limit=20');
        const sessions = await response.json();

        const tableBody = document.getElementById('charging-table-body');

        if (sessions.length === 0) {
            tableBody.innerHTML = `
                <tr>
                    <td colspan="7" class="empty-state">
                        <h3>No Charging Sessions</h3>
                        <p>Add charging sessions manually or they'll be detected automatically.</p>
                    </td>
                </tr>
            `;
            return;
        }

        tableBody.innerHTML = sessions.map(session => `
            <tr>
                <td>${formatDateTime(new Date(session.start_time))}</td>
                <td>
                    ${session.charge_type ?
                        `<span class="badge badge-${session.charge_type.toLowerCase()}">${session.charge_type}</span>` :
                        '--'
                    }
                </td>
                <td>${session.kwh_added ? session.kwh_added.toFixed(1) + ' kWh' : '--'}</td>
                <td>
                    ${session.start_soc !== null && session.end_soc !== null ?
                        `${session.start_soc}% → ${session.end_soc}%` :
                        '--'
                    }
                </td>
                <td>${session.end_time ? formatChargingDuration(session.start_time, session.end_time) : '--'}</td>
                <td>${session.location_name || '--'}</td>
                <td>
                    <button class="btn-delete" onclick="deleteChargingSession(${session.id})" title="Delete session">×</button>
                </td>
            </tr>
        `).join('');

    } catch (error) {
        console.error('Failed to load charging history:', error);
    }
}

/**
 * Format charging duration
 */
function formatChargingDuration(startTime, endTime) {
    const start = new Date(startTime);
    const end = new Date(endTime);
    const minutes = Math.round((end - start) / 60000);

    if (minutes < 60) return `${minutes} min`;
    const hours = Math.floor(minutes / 60);
    const mins = minutes % 60;
    return mins > 0 ? `${hours}h ${mins}m` : `${hours}h`;
}

/**
 * Open add charging session modal
 */
function openAddChargingModal() {
    const modal = document.getElementById('charging-modal');
    modal.classList.add('show');
    modal.setAttribute('aria-hidden', 'false');
    document.body.style.overflow = 'hidden';

    // Set default start time to now
    const now = new Date();
    const localDateTime = now.toISOString().slice(0, 16);
    document.getElementById('charge-start').value = localDateTime;

    // Focus the first input for keyboard users
    const firstInput = document.getElementById('charge-start');
    if (firstInput) {
        setTimeout(() => firstInput.focus(), 100);
    }
}

/**
 * Close charging modal
 */
function closeChargingModal() {
    const modal = document.getElementById('charging-modal');
    if (!modal.classList.contains('show')) return;

    modal.classList.remove('show');
    modal.setAttribute('aria-hidden', 'true');
    document.body.style.overflow = '';

    // Reset form
    document.getElementById('charging-form').reset();
}

/**
 * Submit charging session form
 */
async function submitChargingSession(event) {
    event.preventDefault();

    const form = document.getElementById('charging-form');
    const formData = {
        start_time: document.getElementById('charge-start').value,
        end_time: document.getElementById('charge-end').value || null,
        start_soc: parseFloat(document.getElementById('charge-start-soc').value) || null,
        end_soc: parseFloat(document.getElementById('charge-end-soc').value) || null,
        kwh_added: parseFloat(document.getElementById('charge-kwh').value) || null,
        charge_type: document.getElementById('charge-type').value || null,
        cost: parseFloat(document.getElementById('charge-cost').value) || null,
        location_name: document.getElementById('charge-location').value || null,
        notes: document.getElementById('charge-notes').value || null
    };

    // Remove null values
    Object.keys(formData).forEach(key => {
        if (formData[key] === null || formData[key] === '') {
            delete formData[key];
        }
    });

    try {
        const response = await fetch('/api/charging/add', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(formData)
        });

        if (response.ok) {
            closeChargingModal();
            loadChargingSummary();
            loadChargingHistory();
        } else {
            const data = await response.json();
            alert(`Failed to add charging session: ${data.error || 'Unknown error'}`);
        }
    } catch (error) {
        console.error('Failed to submit charging session:', error);
        alert('Failed to add charging session. Please try again.');
    }
}

/**
 * Delete a charging session
 */
async function deleteChargingSession(sessionId) {
    if (!confirm('Are you sure you want to delete this charging session?')) {
        return;
    }

    try {
        const response = await fetch(`/api/charging/${sessionId}`, {
            method: 'DELETE'
        });

        if (response.ok) {
            loadChargingSummary();
            loadChargingHistory();
        } else {
            const data = await response.json();
            alert(`Failed to delete charging session: ${data.error || 'Unknown error'}`);
        }
    } catch (error) {
        console.error('Failed to delete charging session:', error);
        alert('Failed to delete charging session. Please try again.');
    }
}

// Close charging modal on escape key
document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
        closeChargingModal();
    }
});

/**
 * Initialize CSV import file input
 */
document.addEventListener('DOMContentLoaded', () => {
    const fileInput = document.getElementById('csv-file');
    const fileNameDisplay = document.getElementById('file-name');
    const importBtn = document.getElementById('import-btn');

    if (fileInput) {
        fileInput.addEventListener('change', () => {
            if (fileInput.files.length > 0) {
                fileNameDisplay.textContent = fileInput.files[0].name;
                importBtn.disabled = false;
            } else {
                fileNameDisplay.textContent = 'No file selected';
                importBtn.disabled = true;
            }
        });
    }
});

/**
 * Handle CSV import form submission
 */
async function handleImport(event) {
    event.preventDefault();

    const fileInput = document.getElementById('csv-file');
    const statusDiv = document.getElementById('import-status');
    const importBtn = document.getElementById('import-btn');

    if (!fileInput.files.length) {
        showImportStatus('Please select a CSV file', 'error');
        return;
    }

    const file = fileInput.files[0];

    // Show loading state
    importBtn.disabled = true;
    showImportStatus('Importing...', 'loading');

    const formData = new FormData();
    formData.append('file', file);

    try {
        const response = await fetch('/api/import/csv', {
            method: 'POST',
            body: formData
        });

        const data = await response.json();

        if (response.ok) {
            const stats = data.stats;
            showImportStatus(
                `Successfully imported ${stats.parsed_rows} records. ` +
                (stats.skipped_rows > 0 ? `Skipped ${stats.skipped_rows} invalid rows.` : ''),
                'success'
            );

            // Reload data
            loadTrips();
            loadSummary();
            loadMpgTrend(currentTimeframe);
            loadSocAnalysis();

            // Reset form
            fileInput.value = '';
            document.getElementById('file-name').textContent = 'No file selected';
        } else {
            showImportStatus(data.error || data.message || 'Import failed', 'error');
        }
    } catch (error) {
        console.error('Import error:', error);
        showImportStatus('Import failed. Please try again.', 'error');
    } finally {
        importBtn.disabled = false;
    }
}

/**
 * Show import status message
 */
function showImportStatus(message, type) {
    const statusDiv = document.getElementById('import-status');
    statusDiv.textContent = message;
    statusDiv.className = `import-status show ${type}`;
}
