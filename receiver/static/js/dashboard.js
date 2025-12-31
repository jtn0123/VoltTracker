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

// Initialize dashboard on load
document.addEventListener('DOMContentLoaded', () => {
    initTheme();
    initDatePicker();
    loadStatus();
    loadSummary();
    loadMpgTrend(currentTimeframe);
    loadTrips();
    loadSocAnalysis();
    loadChargingSummary();
    loadChargingHistory();
    loadLiveTelemetry();

    // Refresh status every 30 seconds
    setInterval(loadStatus, 30000);

    // Check for live trip every 10 seconds
    setInterval(loadLiveTelemetry, 10000);

    // Auto-refresh trips every 60 seconds
    setInterval(loadTrips, 60000);
});

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

        if (!liveSection || !liveContent) return;

        if (data.active && data.data) {
            liveSection.style.display = 'block';

            const elapsed = getElapsedTime(data.start_time);
            const lastUpdate = new Date(data.data.timestamp);
            const secondsAgo = Math.floor((Date.now() - lastUpdate) / 1000);

            // Determine engine status
            const engineStatus = data.data.engine_rpm && data.data.engine_rpm > 100 ? 'ON' : 'OFF';
            const engineClass = engineStatus === 'ON' ? 'engine-on' : 'engine-off';

            liveContent.innerHTML = `
                <div class="live-stats">
                    <div class="stat">
                        <span class="label">SOC</span>
                        <span class="value">${data.data.soc?.toFixed(1) || '--'}%</span>
                    </div>
                    <div class="stat">
                        <span class="label">Fuel</span>
                        <span class="value">${data.data.fuel_percent?.toFixed(1) || '--'}%</span>
                    </div>
                    <div class="stat">
                        <span class="label">Speed</span>
                        <span class="value">${data.data.speed_mph?.toFixed(0) || '0'} mph</span>
                    </div>
                    <div class="stat">
                        <span class="label">Engine</span>
                        <span class="value ${engineClass}">${engineStatus}</span>
                    </div>
                </div>
                <div class="live-meta">
                    Trip started: ${elapsed} ago |
                    Last update: ${secondsAgo}s ago |
                    Points: ${data.point_count}
                </div>
            `;

            // Start faster refresh when active (every 5 seconds)
            if (!liveRefreshInterval) {
                liveRefreshInterval = setInterval(loadLiveTelemetry, 5000);
            }
        } else {
            liveSection.style.display = 'none';
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
 * Render trip route on map
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

    // Create polyline from GPS points
    const latlngs = gpsPoints.map(p => [p.latitude, p.longitude]);
    const polyline = L.polyline(latlngs, { color: '#3282b8', weight: 4 }).addTo(tripMap);

    // Add start and end markers
    L.marker(latlngs[0], {
        icon: L.divIcon({
            className: 'map-marker-start',
            html: '<div style="background:#28a745;width:12px;height:12px;border-radius:50%;border:2px solid white;"></div>'
        })
    }).addTo(tripMap).bindPopup('Start');

    L.marker(latlngs[latlngs.length - 1], {
        icon: L.divIcon({
            className: 'map-marker-end',
            html: '<div style="background:#dc3545;width:12px;height:12px;border-radius:50%;border:2px solid white;"></div>'
        })
    }).addTo(tripMap).bindPopup('End');

    // Fit map to polyline bounds
    tripMap.fitBounds(polyline.getBounds(), { padding: [20, 20] });
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

        // Average kWh per session
        const avgKwh = document.getElementById('avg-kwh-session');
        if (data.avg_kwh_per_session) {
            avgKwh.innerHTML = `${data.avg_kwh_per_session.toFixed(1)}<span class="card-unit">kWh</span>`;
            if (data.total_cost) {
                document.getElementById('charging-cost').textContent =
                    `$${data.total_cost.toFixed(2)} total cost`;
            } else {
                document.getElementById('charging-cost').textContent = 'Cost not tracked';
            }
        } else {
            avgKwh.textContent = '--';
            document.getElementById('charging-cost').textContent = 'No data yet';
        }

        // Electric miles (from trips data)
        const electricMiles = document.getElementById('total-electric-miles');
        if (data.total_electric_miles) {
            electricMiles.innerHTML = `${data.total_electric_miles.toLocaleString()}<span class="card-unit">mi</span>`;
        } else {
            electricMiles.textContent = '--';
        }

        // EV ratio
        const evRatio = document.getElementById('ev-ratio');
        if (data.ev_ratio !== undefined) {
            evRatio.innerHTML = `${data.ev_ratio}<span class="card-unit">%</span>`;
        } else {
            evRatio.textContent = '--';
        }

        // L1/L2 session counts
        if (data.l1_sessions !== undefined) {
            document.getElementById('l1-sessions').textContent = data.l1_sessions;
        }
        if (data.l2_sessions !== undefined) {
            document.getElementById('l2-sessions').textContent = data.l2_sessions;
        }

    } catch (error) {
        console.error('Failed to load charging summary:', error);
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
