/**
 * VoltTracker - Map Module
 * Leaflet lazy loading and trip map rendering
 */

import { DEBUG, state } from '@/core';
import { getCSSVar } from '@/charts';
import type { TelemetryPoint } from '@/types/api';

/**
 * Lazy load Leaflet.js library when needed
 */
export async function loadLeaflet(): Promise<void> {
  if (state.leafletLoaded) return;
  if (state.leafletLoading)
    return new Promise((resolve) => {
      const checkLoaded = setInterval(() => {
        if (state.leafletLoaded) {
          clearInterval(checkLoaded);
          resolve();
        }
      }, 50);
    });

  state.leafletLoading = true;
  if (DEBUG) console.log('Loading Leaflet...');

  const link = document.createElement('link');
  link.rel = 'stylesheet';
  link.href = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.css';
  document.head.appendChild(link);

  return new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.js';
    script.onload = () => {
      state.leafletLoaded = true;
      state.leafletLoading = false;
      if (DEBUG) console.log('Leaflet loaded successfully');
      resolve();
    };
    script.onerror = () => {
      state.leafletLoading = false;
      reject(new Error('Failed to load Leaflet'));
    };
    document.head.appendChild(script);
  });
}

/**
 * Render trip route on map with color-coded segments
 */
export async function renderTripMap(telemetry: TelemetryPoint[]): Promise<void> {
  const mapEl = document.getElementById('trip-detail-map');
  if (!mapEl) return;

  const gpsPoints = telemetry.filter((t) => t.latitude && t.longitude);

  if (gpsPoints.length < 2) {
    mapEl.innerHTML = '<div class="no-gps">No GPS data available for this trip</div>';
    return;
  }

  if (!globalThis.L) {
    mapEl.innerHTML = '<div class="loading-indicator" style="padding:2rem;text-align:center;">Loading map...</div>';
    await loadLeaflet();
  }

  mapEl.innerHTML = '';

  state.tripMap = L.map(mapEl).setView([gpsPoints[0].latitude!, gpsPoints[0].longitude!], 13);

  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '&copy; OpenStreetMap contributors',
  }).addTo(state.tripMap);

  const hasPowerData = gpsPoints.some((p) => p.engine_rpm !== undefined || p.hv_battery_power_kw !== undefined);

  if (hasPowerData) {
    const useEfficiencyView = localStorage.getItem('mapEfficiencyView') !== 'false';

    addMapViewToggle(state.tripMap, gpsPoints, useEfficiencyView);

    const segments = useEfficiencyView ? createEfficiencySegments(gpsPoints) : createColorCodedSegments(gpsPoints);

    segments.forEach((segment) => {
      if (segment.points.length >= 2) {
        L.polyline(segment.points, {
          color: segment.color,
          weight: 4,
          opacity: 0.9,
        }).addTo(state.tripMap!);
      }
    });

    addMapLegend(state.tripMap, useEfficiencyView);

    const allPoints: [number, number][] = gpsPoints.map((p) => [p.latitude!, p.longitude!]);
    const bounds = L.latLngBounds(allPoints);
    state.tripMap.fitBounds(bounds, { padding: [20, 20] });
  } else {
    const latlngs: [number, number][] = gpsPoints.map((p) => [p.latitude!, p.longitude!]);
    const polyline = L.polyline(latlngs, { color: getCSSVar('--accent', '#6366f1'), weight: 4 }).addTo(state.tripMap);
    state.tripMap.fitBounds(polyline.getBounds(), { padding: [20, 20] });
  }

  const startPoint: [number, number] = [gpsPoints[0].latitude!, gpsPoints[0].longitude!];
  const endPoint: [number, number] = [
    gpsPoints[gpsPoints.length - 1].latitude!,
    gpsPoints[gpsPoints.length - 1].longitude!,
  ];

  L.marker(startPoint, {
    icon: L.divIcon({
      className: 'map-marker-start',
      html: `<div style="background:${getCSSVar('--success', '#22c55e')};width:12px;height:12px;border-radius:50%;border:2px solid white;box-shadow:0 2px 4px rgba(0,0,0,0.3);"></div>`,
    }),
  })
    .addTo(state.tripMap)
    .bindPopup('Start');

  L.marker(endPoint, {
    icon: L.divIcon({
      className: 'map-marker-end',
      html: `<div style="background:${getCSSVar('--danger', '#ef4444')};width:12px;height:12px;border-radius:50%;border:2px solid white;box-shadow:0 2px 4px rgba(0,0,0,0.3);"></div>`,
    }),
  })
    .addTo(state.tripMap)
    .bindPopup('End');
}

interface RouteSegment {
  color: string;
  points: [number, number][];
  efficiency?: number | null;
  isGasMode?: boolean;
}

/**
 * Create color-coded route segments based on driving mode
 */
export function createColorCodedSegments(points: TelemetryPoint[]): RouteSegment[] {
  const segments: RouteSegment[] = [];
  let currentSegment: RouteSegment | null = null;

  for (const point of points) {
    const color = getPointColor(point);
    const latlng: [number, number] = [point.latitude!, point.longitude!];

    if (currentSegment == null || currentSegment.color !== color) {
      if (currentSegment?.points.length) {
        currentSegment.points.push(latlng);
      }
      currentSegment = {
        color,
        points: currentSegment ? [currentSegment.points[currentSegment.points.length - 1]] : [],
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
export function getPointColor(point: TelemetryPoint): string {
  const engineRpm = point.engine_rpm;
  const powerKw = point.hv_battery_power_kw;

  if (powerKw !== undefined && powerKw !== null && powerKw < -0.5) {
    return getCSSVar('--info', '#3b82f6');
  }
  if (engineRpm !== undefined && engineRpm !== null && engineRpm > 500) {
    return getCSSVar('--warning', '#f59e0b');
  }
  return getCSSVar('--electric', '#34d399');
}

/**
 * Get color based on instantaneous efficiency
 */
/** Map a value to a color using threshold breakpoints. */
function thresholdColor(value: number, thresholds: [number, string][], defaultColor: string): string {
  for (const [limit, color] of thresholds) {
    if (value < limit) return color;
  }
  return defaultColor;
}

const GAS_THRESHOLDS: [number, string][] = [[25, '#e74c3c'], [30, '#e67e22'], [35, '#f1c40f'], [40, '#2ecc71']];
const EV_THRESHOLDS: [number, string][] = [[2, '#e74c3c'], [2.5, '#e67e22'], [3, '#f1c40f'], [3.5, '#2ecc71']];

export function getEfficiencyColor(efficiency: number | null, isGasMode: boolean): string {
  if (efficiency === null || efficiency === undefined || !Number.isFinite(efficiency)) return '#888888';
  return thresholdColor(efficiency, isGasMode ? GAS_THRESHOLDS : EV_THRESHOLDS, '#27ae60');
}

/**
 * Calculate haversine distance between two GPS points (in miles)
 */
export function haversineDistance(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const R = 3959;
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLon = ((lon2 - lon1) * Math.PI) / 180;
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((lat1 * Math.PI) / 180) * Math.cos((lat2 * Math.PI) / 180) * Math.sin(dLon / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

/** Check if a value is a valid number (not null/undefined). */
function isNum(v: number | null | undefined): v is number {
  return v !== undefined && v !== null;
}

/** Calculate segment efficiency for gas mode. */
function calcGasEfficiency(prev: TelemetryPoint, _curr: TelemetryPoint, distance: number): number | null {
  const prevFuel = prev.fuel_level_percent;
  const currFuel = _curr.fuel_level_percent;
  if (!isNum(prevFuel) || !isNum(currFuel) || prevFuel <= currFuel) return null;
  const fuelUsed = ((prevFuel - currFuel) / 100) * 9.31;
  return fuelUsed > 0.001 ? distance / fuelUsed : null;
}

/** Calculate segment efficiency for EV mode. */
function calcEvEfficiency(prev: TelemetryPoint, curr: TelemetryPoint, distance: number): number | null {
  const prevSoc = prev.state_of_charge;
  const currSoc = curr.state_of_charge;
  if (!isNum(prevSoc) || !isNum(currSoc) || prevSoc <= currSoc) return null;
  const kwUsed = ((prevSoc - currSoc) / 100) * 18.4;
  return kwUsed > 0.001 ? distance / kwUsed : null;
}

/**
 * Create efficiency-colored route segments
 */
export function createEfficiencySegments(points: TelemetryPoint[]): RouteSegment[] {
  const segments: RouteSegment[] = [];
  let currentSegment: RouteSegment | null = null;

  for (let i = 1; i < points.length; i++) {
    const prev = points[i - 1];
    const curr = points[i];

    if (!curr.latitude || !curr.longitude || !prev.latitude || !prev.longitude) continue;

    const distance = haversineDistance(prev.latitude, prev.longitude, curr.latitude, curr.longitude);
    if (distance < 0.001) continue;

    const isGasMode = isNum(curr.engine_rpm) && curr.engine_rpm > 500;
    const efficiency = isGasMode
      ? calcGasEfficiency(prev, curr, distance)
      : calcEvEfficiency(prev, curr, distance);

    const color = getEfficiencyColor(efficiency, isGasMode);
    const latlng: [number, number] = [curr.latitude, curr.longitude];
    const prevLatlng: [number, number] = [prev.latitude, prev.longitude];

    if (currentSegment == null || currentSegment.color !== color) {
      if (currentSegment?.points.length) currentSegment.points.push(prevLatlng);
      currentSegment = { color, efficiency, isGasMode, points: [prevLatlng] };
      segments.push(currentSegment);
    }

    currentSegment.points.push(latlng);
  }

  return segments;
}

/**
 * Add a legend to the map
 */
export function addMapLegend(map: L.LeafletMap, isEfficiencyMode = false): void {
  const legend = L.control({ position: 'bottomright' });

  legend.onAdd = function () {
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
export function addMapViewToggle(map: L.LeafletMap, gpsPoints: TelemetryPoint[], isEfficiencyMode: boolean): void {
  const toggle = L.control({ position: 'topleft' });

  toggle.onAdd = function () {
    const div = L.DomUtil.create('div', 'map-view-toggle');
    div.innerHTML = `
      <div style="background:rgba(255,255,255,0.95);padding:6px 10px;border-radius:6px;box-shadow:0 2px 6px rgba(0,0,0,0.2);font-size:12px;">
        <label style="display:flex;align-items:center;cursor:pointer;color:#333;">
          <input type="checkbox" id="map-efficiency-toggle" ${isEfficiencyMode ? 'checked' : ''} style="margin-right:6px;">
          Efficiency Heatmap
        </label>
      </div>
    `;

    L.DomEvent.disableClickPropagation(div);

    const checkbox = div.querySelector<HTMLInputElement>('#map-efficiency-toggle');
    if (checkbox) checkbox.addEventListener('change', function() {
      localStorage.setItem('mapEfficiencyView', String(this.checked));
      renderTripMap(gpsPoints);
    });

    return div;
  };

  toggle.addTo(map);
}
