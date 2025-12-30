/**
 * Volt Efficiency Tracker - Dashboard JavaScript
 */

// State
let mpgChart = null;
let socChart = null;
let currentTimeframe = 30;

// Initialize dashboard on load
document.addEventListener('DOMContentLoaded', () => {
    loadStatus();
    loadSummary();
    loadMpgTrend(currentTimeframe);
    loadTrips();
    loadSocAnalysis();

    // Refresh status every 30 seconds
    setInterval(loadStatus, 30000);
});

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
        const response = await fetch('/api/trips?limit=20');
        const trips = await response.json();

        const tableBody = document.getElementById('trips-table-body');
        const tripCards = document.getElementById('trip-cards');

        if (trips.length === 0) {
            tableBody.innerHTML = `
                <tr>
                    <td colspan="6" class="empty-state">
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

        // Desktop table
        tableBody.innerHTML = trips.map(trip => `
            <tr>
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
                    <button class="btn-delete" onclick="deleteTrip(${trip.id})" title="Delete trip">×</button>
                </td>
            </tr>
        `).join('');

        // Mobile cards
        tripCards.innerHTML = trips.map(trip => `
            <div class="trip-card">
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
 * Handle timeframe button clicks
 */
function setTimeframe(days) {
    loadMpgTrend(days);
}

/**
 * Toggle export dropdown menu
 */
function toggleExportMenu() {
    const menu = document.getElementById('export-menu');
    menu.classList.toggle('show');
}

// Close export menu when clicking outside
document.addEventListener('click', (event) => {
    const menu = document.getElementById('export-menu');
    const dropdown = document.querySelector('.export-dropdown');
    if (menu && dropdown && !dropdown.contains(event.target)) {
        menu.classList.remove('show');
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
