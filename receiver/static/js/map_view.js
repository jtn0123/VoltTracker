/**
 * GPS Track Map View - VoltTracker
 * Interactive map visualization for trip routes
 */

// State
let map = null;
let markerClusterGroup = null;
let routeLayerGroup = null;
let heatmapLayer = null;
let currentLayer = 'routes';
let allTrips = [];
let selectedTripIds = new Set();
let currentFilters = {
    dateRange: 'last_30_days',
    mode: 'all',
    minEfficiency: null,
    maxEfficiency: null,
    minDistance: null,
    maxDistance: null
};

/**
 * Initialize the map on page load
 */
document.addEventListener('DOMContentLoaded', () => {
    initMap();
    initTheme();
    loadTrips();
});

/**
 * Initialize Leaflet map
 */
function initMap() {
    // Create map centered on US
    map = L.map('map-container', {
        center: [39.8283, -98.5795],
        zoom: 4,
        zoomControl: false
    });

    // Add zoom control to bottom-right
    L.control.zoom({
        position: 'bottomright'
    }).addTo(map);

    // Add tile layer (OpenStreetMap)
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
        maxZoom: 19
    }).addTo(map);

    // Initialize layer groups
    markerClusterGroup = L.markerClusterGroup({
        maxClusterRadius: 50,
        spiderfyOnMaxZoom: true,
        showCoverageOnHover: false,
        zoomToBoundsOnClick: true
    });

    routeLayerGroup = L.layerGroup().addTo(map);

    console.log('[Map] Initialized');
}

/**
 * Load trips from API
 */
async function loadTrips() {
    showLoading(true);

    try {
        // Build query parameters
        const params = new URLSearchParams();
        params.append('date_range', currentFilters.dateRange);

        if (currentFilters.mode !== 'all') {
            params.append(currentFilters.mode, 'true');
        }

        if (currentFilters.minEfficiency) {
            params.append('min_efficiency', currentFilters.minEfficiency);
        }
        if (currentFilters.maxEfficiency) {
            params.append('max_efficiency', currentFilters.maxEfficiency);
        }
        if (currentFilters.minDistance) {
            params.append('min_distance', currentFilters.minDistance);
        }
        if (currentFilters.maxDistance) {
            params.append('max_distance', currentFilters.maxDistance);
        }

        const response = await fetch(`/api/trips/map?${params.toString()}`);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);

        const data = await response.json();
        allTrips = data.trips || [];

        console.log(`[Map] Loaded ${allTrips.length} trips`);

        // Update UI
        renderTrips();
        updateStats();
        updateTripList();

        // Zoom to fit all trips
        if (allTrips.length > 0) {
            zoomToFitAll();
        }

        showSuccess(`Loaded ${allTrips.length} trips`);
    } catch (error) {
        console.error('[Map] Error loading trips:', error);
        showError('Failed to load trips');
    } finally {
        showLoading(false);
    }
}

/**
 * Render trips on map based on current layer
 */
function renderTrips() {
    // Clear existing layers
    markerClusterGroup.clearLayers();
    routeLayerGroup.clearLayers();
    if (heatmapLayer) {
        map.removeLayer(heatmapLayer);
        heatmapLayer = null;
    }

    if (currentLayer === 'routes') {
        renderRoutes();
    } else if (currentLayer.startsWith('heatmap')) {
        renderHeatmap();
    }
}

/**
 * Render route polylines with markers
 */
function renderRoutes() {
    allTrips.forEach(trip => {
        if (!trip.points || trip.points.length < 2) return;

        // Create polyline for route
        const latlngs = trip.points.map(p => [p.lat, p.lon]);

        // Color code by efficiency
        const avgEfficiency = trip.kwh_per_mile;
        let color = '#999999'; // Gray default

        if (avgEfficiency) {
            if (avgEfficiency < 0.25) {
                color = '#10b981'; // Green - efficient
            } else if (avgEfficiency < 0.35) {
                color = '#f59e0b'; // Orange - moderate
            } else {
                color = '#ef4444'; // Red - inefficient
            }
        } else if (trip.gas_mpg && trip.gas_mpg > 0) {
            // Gas mode - use blue
            color = '#3b82f6';
        }

        const polyline = L.polyline(latlngs, {
            color: color,
            weight: 3,
            opacity: 0.7,
            smoothFactor: 1
        });

        polyline.on('click', () => showTripDetail(trip.id));
        polyline.on('mouseover', function() {
            this.setStyle({ weight: 5, opacity: 1 });
        });
        polyline.on('mouseout', function() {
            this.setStyle({ weight: 3, opacity: 0.7 });
        });

        routeLayerGroup.addLayer(polyline);

        // Add start marker
        const startPoint = trip.points[0];
        const startMarker = L.circleMarker([startPoint.lat, startPoint.lon], {
            radius: 6,
            fillColor: color,
            color: '#fff',
            weight: 2,
            opacity: 1,
            fillOpacity: 0.8
        });

        // Popup with trip info
        const popupContent = `
            <div class="map-popup">
                <strong>${new Date(trip.start_time).toLocaleDateString()}</strong><br>
                Distance: ${trip.distance_miles.toFixed(1)} mi<br>
                ${trip.kwh_per_mile ? `Efficiency: ${trip.kwh_per_mile.toFixed(3)} kWh/mi<br>` : ''}
                ${trip.gas_mpg ? `MPG: ${trip.gas_mpg.toFixed(1)}<br>` : ''}
                <button onclick="showTripDetail('${trip.id}')" style="margin-top:0.5rem;padding:0.25rem 0.5rem;background:var(--electric);border:none;border-radius:4px;cursor:pointer;">View Details</button>
            </div>
        `;

        startMarker.bindPopup(popupContent);
        startMarker.on('click', () => showTripDetail(trip.id));

        markerClusterGroup.addLayer(startMarker);
    });

    map.addLayer(markerClusterGroup);
}

/**
 * Render heatmap layer
 */
function renderHeatmap() {
    const heatmapData = [];

    allTrips.forEach(trip => {
        if (!trip.points) return;

        trip.points.forEach(point => {
            let intensity = 1;

            if (currentLayer === 'heatmap-speed' && point.speed) {
                // Speed heatmap: 0-70 mph mapped to 0-1
                intensity = Math.min(point.speed / 70, 1);
            } else if (currentLayer === 'heatmap-efficiency' && point.efficiency) {
                // Efficiency heatmap: inverse (lower is better)
                intensity = Math.max(0, 1 - (point.efficiency / 0.5));
            }

            heatmapData.push([point.lat, point.lon, intensity]);
        });
    });

    if (heatmapData.length > 0) {
        heatmapLayer = L.heatLayer(heatmapData, {
            radius: 15,
            blur: 20,
            maxZoom: 13,
            max: 1.0,
            gradient: {
                0.0: 'blue',
                0.3: 'cyan',
                0.5: 'lime',
                0.7: 'yellow',
                1.0: 'red'
            }
        }).addTo(map);
    }
}

/**
 * Update trip list in sidebar
 */
function updateTripList() {
    const container = document.getElementById('trip-list-container');

    if (allTrips.length === 0) {
        container.innerHTML = '<p style="text-align:center;color:var(--text-secondary);padding:2rem;">No trips found</p>';
        return;
    }

    const html = allTrips.map(trip => {
        const date = new Date(trip.start_time);
        const isSelected = selectedTripIds.has(trip.id);

        return `
            <div class="trip-card ${isSelected ? 'selected' : ''}" data-trip-id="${trip.id}">
                <div class="trip-card-header">
                    <div class="trip-card-date">${date.toLocaleDateString()} ${date.toLocaleTimeString([], {hour: '2-digit', minute: '2-digit'})}</div>
                    <input type="checkbox" class="trip-card-checkbox" ${isSelected ? 'checked' : ''} onclick="toggleTripSelection('${trip.id}', event)">
                </div>
                <div class="trip-card-stats">
                    <div class="trip-stat">
                        Distance: <span class="trip-stat-value">${trip.distance_miles.toFixed(1)} mi</span>
                    </div>
                    ${trip.kwh_per_mile ? `
                        <div class="trip-stat">
                            Efficiency: <span class="trip-stat-value">${trip.kwh_per_mile.toFixed(3)} kWh/mi</span>
                        </div>
                    ` : ''}
                    ${trip.gas_mpg ? `
                        <div class="trip-stat">
                            MPG: <span class="trip-stat-value">${trip.gas_mpg.toFixed(1)}</span>
                        </div>
                    ` : ''}
                    ${trip.electric_miles ? `
                        <div class="trip-stat">
                            Electric: <span class="trip-stat-value">${trip.electric_miles.toFixed(1)} mi</span>
                        </div>
                    ` : ''}
                </div>
            </div>
        `;
    }).join('');

    container.innerHTML = html;

    // Add click handlers
    container.querySelectorAll('.trip-card').forEach(card => {
        card.addEventListener('click', (e) => {
            if (!e.target.classList.contains('trip-card-checkbox')) {
                const tripId = card.dataset.tripId;
                highlightTrip(tripId);
                showTripDetail(tripId);
            }
        });
    });
}

/**
 * Update stats display
 */
function updateStats() {
    const tripsCount = document.getElementById('trips-count');
    const totalDistance = document.getElementById('total-distance');

    tripsCount.textContent = allTrips.length;

    const distance = allTrips.reduce((sum, trip) => sum + (trip.distance_miles || 0), 0);
    totalDistance.textContent = `${distance.toFixed(1)} mi`;
}

/**
 * Toggle trip selection for comparison
 */
function toggleTripSelection(tripId, event) {
    event.stopPropagation();

    if (selectedTripIds.has(tripId)) {
        selectedTripIds.delete(tripId);
    } else {
        selectedTripIds.add(tripId);
    }

    updateTripList();
    updateCompareButton();
}

/**
 * Update compare button state
 */
function updateCompareButton() {
    const compareBtn = document.getElementById('btn-compare');
    const clearBtn = document.getElementById('btn-clear-selection');

    if (selectedTripIds.size >= 2) {
        compareBtn.disabled = false;
        clearBtn.style.display = 'block';
    } else {
        compareBtn.disabled = true;
        clearBtn.style.display = selectedTripIds.size > 0 ? 'block' : 'none';
    }
}

/**
 * Clear trip selection
 */
function clearSelection() {
    selectedTripIds.clear();
    updateTripList();
    updateCompareButton();
}

/**
 * Start trip comparison
 */
function startComparison() {
    if (selectedTripIds.size < 2) {
        showWarning('Select at least 2 trips to compare');
        return;
    }

    const tripIds = Array.from(selectedTripIds);
    showInfo(`Comparing ${tripIds.length} trips...`);

    // Highlight all selected trips
    routeLayerGroup.eachLayer(layer => {
        layer.setStyle({ weight: 3, opacity: 0.3 });
    });

    // Find and highlight selected trips
    const colors = ['#10b981', '#3b82f6', '#f59e0b', '#ef4444', '#8b5cf6'];
    tripIds.forEach((tripId, index) => {
        const trip = allTrips.find(t => t.id === tripId);
        if (!trip || !trip.points) return;

        const latlngs = trip.points.map(p => [p.lat, p.lon]);
        const polyline = L.polyline(latlngs, {
            color: colors[index % colors.length],
            weight: 5,
            opacity: 1
        });

        routeLayerGroup.addLayer(polyline);
    });

    // Zoom to fit selected trips
    const bounds = L.latLngBounds();
    tripIds.forEach(tripId => {
        const trip = allTrips.find(t => t.id === tripId);
        if (trip && trip.bounds) {
            bounds.extend([trip.bounds.north, trip.bounds.east]);
            bounds.extend([trip.bounds.south, trip.bounds.west]);
        }
    });

    if (bounds.isValid()) {
        map.fitBounds(bounds, { padding: [50, 50] });
    }
}

/**
 * Highlight a single trip on map
 */
function highlightTrip(tripId) {
    const trip = allTrips.find(t => t.id === tripId);
    if (!trip || !trip.points) return;

    // Dim all routes
    routeLayerGroup.eachLayer(layer => {
        if (layer instanceof L.Polyline) {
            layer.setStyle({ opacity: 0.2, weight: 2 });
        }
    });

    // Highlight selected route
    const latlngs = trip.points.map(p => [p.lat, p.lon]);
    const polyline = L.polyline(latlngs, {
        color: '#00d4aa',
        weight: 6,
        opacity: 1
    });

    routeLayerGroup.addLayer(polyline);

    // Zoom to trip
    if (trip.bounds) {
        const bounds = L.latLngBounds(
            [trip.bounds.south, trip.bounds.west],
            [trip.bounds.north, trip.bounds.east]
        );
        map.fitBounds(bounds, { padding: [50, 50] });
    }
}

/**
 * Show trip detail modal
 */
async function showTripDetail(tripId) {
    const trip = allTrips.find(t => t.id === tripId);
    if (!trip) return;

    const modal = document.getElementById('trip-detail-modal');
    const content = document.getElementById('trip-detail-content');
    const title = document.getElementById('trip-detail-title');

    title.textContent = `Trip - ${new Date(trip.start_time).toLocaleString()}`;

    // Build detail HTML
    const html = `
        <div class="trip-detail-stats">
            <div class="stat-row">
                <span class="stat-label">Distance:</span>
                <span class="stat-value">${trip.distance_miles.toFixed(2)} miles</span>
            </div>
            ${trip.kwh_per_mile ? `
                <div class="stat-row">
                    <span class="stat-label">Efficiency:</span>
                    <span class="stat-value">${trip.kwh_per_mile.toFixed(3)} kWh/mi</span>
                </div>
            ` : ''}
            ${trip.gas_mpg ? `
                <div class="stat-row">
                    <span class="stat-label">Gas MPG:</span>
                    <span class="stat-value">${trip.gas_mpg.toFixed(1)}</span>
                </div>
            ` : ''}
            ${trip.electric_miles ? `
                <div class="stat-row">
                    <span class="stat-label">Electric Miles:</span>
                    <span class="stat-value">${trip.electric_miles.toFixed(1)} mi</span>
                </div>
            ` : ''}
            ${trip.gas_miles ? `
                <div class="stat-row">
                    <span class="stat-label">Gas Miles:</span>
                    <span class="stat-value">${trip.gas_miles.toFixed(1)} mi</span>
                </div>
            ` : ''}
            ${trip.avg_temp_f ? `
                <div class="stat-row">
                    <span class="stat-label">Avg Temperature:</span>
                    <span class="stat-value">${trip.avg_temp_f.toFixed(0)}°F</span>
                </div>
            ` : ''}
        </div>
    `;

    content.innerHTML = html;

    // Set export links
    document.getElementById('export-gpx-link').href = `/api/trips/${tripId}/gpx`;
    document.getElementById('export-kml-link').href = `/api/trips/${tripId}/kml`;

    modal.classList.add('show');
    modal.setAttribute('aria-hidden', 'false');
}

/**
 * Close trip detail modal
 */
function closeTripDetail() {
    const modal = document.getElementById('trip-detail-modal');
    modal.classList.remove('show');
    modal.setAttribute('aria-hidden', 'true');

    // Restore routes
    renderTrips();
}

/**
 * Find similar trips
 */
async function findSimilarTrips() {
    const modal = document.getElementById('trip-detail-modal');
    const gpxLink = document.getElementById('export-gpx-link');
    const tripId = gpxLink.href.split('/').slice(-2)[0]; // Extract trip ID from GPX link

    showInfo('Finding similar routes...');

    try {
        const response = await fetch(`/api/trips/similar/${tripId}?min_similarity=70`);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);

        const data = await response.json();
        const similarTrips = data.similar_trips || [];

        if (similarTrips.length === 0) {
            showInfo('No similar routes found');
        } else {
            showSuccess(`Found ${similarTrips.length} similar routes`);

            // Highlight similar trips on map
            closeTripDetail();

            similarTrips.forEach((similar, index) => {
                const trip = allTrips.find(t => t.id === similar.trip_id);
                if (trip && trip.points) {
                    const latlngs = trip.points.map(p => [p.lat, p.lon]);
                    const colors = ['#10b981', '#3b82f6', '#f59e0b'];
                    const polyline = L.polyline(latlngs, {
                        color: colors[index % colors.length],
                        weight: 4,
                        opacity: 0.8
                    });

                    polyline.bindPopup(`Similarity: ${similar.similarity_score}%`);
                    routeLayerGroup.addLayer(polyline);
                }
            });
        }
    } catch (error) {
        console.error('[Map] Error finding similar trips:', error);
        showError('Failed to find similar trips');
    }
}

/**
 * Apply filters and reload trips
 */
function applyFilters() {
    currentFilters.dateRange = document.getElementById('date-range-filter').value;
    currentFilters.mode = document.querySelector('input[name="mode"]:checked').value;
    currentFilters.minEfficiency = parseFloat(document.getElementById('min-efficiency').value) || null;
    currentFilters.maxEfficiency = parseFloat(document.getElementById('max-efficiency').value) || null;
    currentFilters.minDistance = parseFloat(document.getElementById('min-distance').value) || null;
    currentFilters.maxDistance = parseFloat(document.getElementById('max-distance').value) || null;

    loadTrips();
}

/**
 * Clear all filters
 */
function clearFilters() {
    document.getElementById('date-range-filter').value = 'last_30_days';
    document.querySelector('input[name="mode"][value="all"]').checked = true;
    document.getElementById('min-efficiency').value = '';
    document.getElementById('max-efficiency').value = '';
    document.getElementById('min-distance').value = '';
    document.getElementById('max-distance').value = '';

    currentFilters = {
        dateRange: 'last_30_days',
        mode: 'all',
        minEfficiency: null,
        maxEfficiency: null,
        minDistance: null,
        maxDistance: null
    };

    loadTrips();
}

/**
 * Change map layer (routes vs heatmap)
 */
function changeMapLayer() {
    currentLayer = document.querySelector('input[name="layer"]:checked').value;
    renderTrips();
}

/**
 * Zoom to fit all trips
 */
function zoomToFitAll() {
    if (allTrips.length === 0) return;

    const bounds = L.latLngBounds();
    allTrips.forEach(trip => {
        if (trip.bounds) {
            bounds.extend([trip.bounds.north, trip.bounds.east]);
            bounds.extend([trip.bounds.south, trip.bounds.west]);
        }
    });

    if (bounds.isValid()) {
        map.fitBounds(bounds, { padding: [50, 50] });
    }
}

/**
 * Go to user's current location
 */
function goToMyLocation() {
    if (!navigator.geolocation) {
        showWarning('Geolocation not supported by your browser');
        return;
    }

    showInfo('Getting your location...');

    navigator.geolocation.getCurrentPosition(
        (position) => {
            const lat = position.coords.latitude;
            const lon = position.coords.longitude;

            map.setView([lat, lon], 13);

            // Add temporary marker
            const marker = L.circleMarker([lat, lon], {
                radius: 8,
                fillColor: '#3b82f6',
                color: '#fff',
                weight: 2,
                fillOpacity: 0.8
            }).addTo(map);

            marker.bindPopup('You are here').openPopup();

            showSuccess('Location found');

            // Remove marker after 5 seconds
            setTimeout(() => {
                map.removeLayer(marker);
            }, 5000);
        },
        (error) => {
            console.error('[Map] Geolocation error:', error);
            showError('Failed to get your location');
        }
    );
}

/**
 * Toggle filter sidebar
 */
function toggleFilters() {
    const sidebar = document.getElementById('filter-sidebar');
    const btn = document.getElementById('btn-filters');

    sidebar.classList.toggle('open');
    btn.classList.toggle('active');
}

/**
 * Toggle trip list sidebar
 */
function toggleTripList() {
    const sidebar = document.getElementById('trip-list-sidebar');
    const btn = document.getElementById('btn-trip-list');

    sidebar.classList.toggle('open');
    btn.classList.toggle('active');
}

/**
 * Initialize theme
 */
function initTheme() {
    const savedTheme = localStorage.getItem('theme') || 'dark';
    document.documentElement.setAttribute('data-theme', savedTheme);
    updateThemeIcon(savedTheme);
}

/**
 * Toggle theme
 */
function toggleTheme() {
    const currentTheme = document.documentElement.getAttribute('data-theme');
    const newTheme = currentTheme === 'dark' ? 'light' : 'dark';

    document.documentElement.setAttribute('data-theme', newTheme);
    localStorage.setItem('theme', newTheme);
    updateThemeIcon(newTheme);
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
 * Show/hide loading overlay
 */
function showLoading(show) {
    const overlay = document.getElementById('loading-overlay');
    overlay.style.display = show ? 'flex' : 'none';
}
