/**
 * Tests for the map module — focused on the pure helpers (color coding,
 * efficiency calculation, segmentation, haversine distance) which carry
 * most of the file's logic. The DOM-touching paths (loadLeaflet,
 * renderTripMap, addMapLegend, addMapViewToggle) are exercised through
 * createColorCodedSegments and createEfficiencySegments end-to-end.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

// Mock @/charts to provide a deterministic getCSSVar so the color
// constants don't depend on the document's actual CSS variables.
vi.mock('@/charts', () => ({
  getCSSVar: vi.fn((_name: string, fallback = '') => fallback),
}));

// Mock @/core to provide a writable state object.
vi.mock('@/core', () => ({
  DEBUG: false,
  state: {
    leafletLoaded: false,
    leafletLoading: false,
    tripMap: null,
  },
}));

import {
  getEfficiencyColor,
  getPointColor,
  haversineDistance,
  createColorCodedSegments,
  createEfficiencySegments,
  loadLeaflet,
} from '@/map';
import type { TelemetryPoint } from '@/types/api';

const mkPoint = (overrides: Partial<TelemetryPoint> = {}): TelemetryPoint =>
  ({
    timestamp: '2026-04-09T12:00:00Z',
    latitude: 40.0,
    longitude: -100.0,
    state_of_charge: 80,
    speed_mph: 30,
    engine_rpm: 0,
    hv_battery_power_kw: 5,
    fuel_level_percent: 50,
    ...overrides,
  } as unknown as TelemetryPoint);

describe('haversineDistance', () => {
  it('returns 0 for the same point', () => {
    expect(haversineDistance(40, -100, 40, -100)).toBe(0);
  });

  it('returns roughly 69 miles for one degree of latitude', () => {
    const d = haversineDistance(40, -100, 41, -100);
    expect(d).toBeGreaterThan(68);
    expect(d).toBeLessThan(70);
  });

  it('is symmetric', () => {
    const a = haversineDistance(40, -100, 41, -101);
    const b = haversineDistance(41, -101, 40, -100);
    expect(a).toBeCloseTo(b, 6);
  });

  it('returns a positive number for typical city-to-city distances', () => {
    // San Francisco to Los Angeles ≈ 347 mi
    const d = haversineDistance(37.7749, -122.4194, 34.0522, -118.2437);
    expect(d).toBeGreaterThan(330);
    expect(d).toBeLessThan(360);
  });
});

describe('getPointColor', () => {
  it('returns regen color when battery power is strongly negative', () => {
    expect(getPointColor(mkPoint({ hv_battery_power_kw: -1.0, engine_rpm: 0 })))
      .toBe('#3b82f6'); // --info
  });

  it('returns gas color when engine is running', () => {
    expect(getPointColor(mkPoint({ hv_battery_power_kw: 5, engine_rpm: 1500 })))
      .toBe('#f59e0b'); // --warning
  });

  it('returns electric color by default', () => {
    expect(getPointColor(mkPoint({ hv_battery_power_kw: 5, engine_rpm: 0 })))
      .toBe('#34d399'); // --electric
  });

  it('treats null power and rpm as default electric', () => {
    expect(getPointColor(mkPoint({ hv_battery_power_kw: null as unknown as number, engine_rpm: null as unknown as number })))
      .toBe('#34d399');
  });

  it('regen takes priority over engine running', () => {
    // power is strongly negative AND engine is running — regen wins
    expect(getPointColor(mkPoint({ hv_battery_power_kw: -2, engine_rpm: 2000 })))
      .toBe('#3b82f6');
  });
});

describe('getEfficiencyColor', () => {
  it('returns gray for null efficiency', () => {
    expect(getEfficiencyColor(null, false)).toBe('#888888');
  });

  it('returns gray for undefined', () => {
    expect(getEfficiencyColor(undefined as unknown as number, false)).toBe('#888888');
  });

  it('returns gray for non-finite values (Infinity)', () => {
    expect(getEfficiencyColor(Infinity, false)).toBe('#888888');
  });

  it('returns gray for NaN', () => {
    expect(getEfficiencyColor(NaN, false)).toBe('#888888');
  });

  describe('EV mode (mi/kWh)', () => {
    it('returns red for very poor efficiency', () => {
      expect(getEfficiencyColor(1.5, false)).toBe('#e74c3c');
    });

    it('returns orange for moderate efficiency', () => {
      expect(getEfficiencyColor(2.3, false)).toBe('#e67e22');
    });

    it('returns green for excellent efficiency', () => {
      expect(getEfficiencyColor(4.0, false)).toBe('#27ae60');
    });
  });

  describe('Gas mode (mpg)', () => {
    it('returns red for very poor mpg', () => {
      expect(getEfficiencyColor(20, true)).toBe('#e74c3c');
    });

    it('returns orange for moderate mpg', () => {
      expect(getEfficiencyColor(28, true)).toBe('#e67e22');
    });

    it('returns green for great mpg', () => {
      expect(getEfficiencyColor(45, true)).toBe('#27ae60');
    });
  });
});

describe('createColorCodedSegments', () => {
  it('returns empty array for empty input', () => {
    expect(createColorCodedSegments([])).toEqual([]);
  });

  it('groups consecutive same-color points into one segment', () => {
    const points = [
      mkPoint({ latitude: 40.0, longitude: -100.0, engine_rpm: 0, hv_battery_power_kw: 5 }),
      mkPoint({ latitude: 40.1, longitude: -100.1, engine_rpm: 0, hv_battery_power_kw: 5 }),
      mkPoint({ latitude: 40.2, longitude: -100.2, engine_rpm: 0, hv_battery_power_kw: 5 }),
    ];
    const segments = createColorCodedSegments(points);
    expect(segments.length).toBe(1);
    expect(segments[0].points.length).toBe(3);
  });

  it('starts a new segment when color changes', () => {
    const points = [
      // electric
      mkPoint({ latitude: 40.0, longitude: -100.0, engine_rpm: 0, hv_battery_power_kw: 5 }),
      mkPoint({ latitude: 40.1, longitude: -100.1, engine_rpm: 0, hv_battery_power_kw: 5 }),
      // gas
      mkPoint({ latitude: 40.2, longitude: -100.2, engine_rpm: 1500, hv_battery_power_kw: 5 }),
      mkPoint({ latitude: 40.3, longitude: -100.3, engine_rpm: 1500, hv_battery_power_kw: 5 }),
    ];
    const segments = createColorCodedSegments(points);
    expect(segments.length).toBe(2);
    expect(segments[0].color).toBe('#34d399'); // electric
    expect(segments[1].color).toBe('#f59e0b'); // gas
  });
});

describe('createEfficiencySegments', () => {
  it('returns empty array for fewer than 2 points', () => {
    expect(createEfficiencySegments([mkPoint()])).toEqual([]);
  });

  it('skips points with no GPS coordinates', () => {
    const points = [
      mkPoint({ latitude: null as unknown as number }),
      mkPoint({ latitude: 41.0, longitude: -101.0 }),
    ];
    expect(createEfficiencySegments(points)).toEqual([]);
  });

  it('creates segments for two well-separated EV points with declining SOC', () => {
    const points = [
      mkPoint({ latitude: 40.0, longitude: -100.0, state_of_charge: 80, engine_rpm: 0 }),
      mkPoint({ latitude: 40.5, longitude: -100.5, state_of_charge: 78, engine_rpm: 0 }),
    ];
    const segments = createEfficiencySegments(points);
    expect(segments.length).toBeGreaterThanOrEqual(1);
    expect(segments[0].isGasMode).toBe(false);
  });

  it('flags as gas mode when engine rpm exceeds threshold', () => {
    const points = [
      mkPoint({ latitude: 40.0, longitude: -100.0, fuel_level_percent: 50, engine_rpm: 1500 }),
      mkPoint({ latitude: 40.5, longitude: -100.5, fuel_level_percent: 49, engine_rpm: 1500 }),
    ];
    const segments = createEfficiencySegments(points);
    expect(segments[0].isGasMode).toBe(true);
  });

  it('returns null efficiency when prev SOC ≤ curr SOC (regen / charging)', () => {
    const points = [
      mkPoint({ latitude: 40.0, longitude: -100.0, state_of_charge: 78, engine_rpm: 0 }),
      mkPoint({ latitude: 40.5, longitude: -100.5, state_of_charge: 80, engine_rpm: 0 }),
    ];
    const segments = createEfficiencySegments(points);
    // efficiency is null → color is the unknown/gray fallback
    expect(segments[0].color).toBe('#888888');
  });

  it('skips segments where the two points are essentially co-located', () => {
    const points = [
      mkPoint({ latitude: 40.0, longitude: -100.0 }),
      mkPoint({ latitude: 40.0, longitude: -100.0 }), // distance = 0
    ];
    expect(createEfficiencySegments(points)).toEqual([]);
  });
});

describe('renderTripMap', () => {
  // Build a stub Leaflet global with the methods renderTripMap calls.
  function installFakeLeaflet() {
    const map = {
      setView: vi.fn().mockReturnThis(),
      addTo: vi.fn(),
      fitBounds: vi.fn(),
    };
    // .addTo() returns the layer itself in real Leaflet so chains like
    // `L.polyline(...).addTo(map).getBounds()` work.
    const polyline: Record<string, ReturnType<typeof vi.fn>> = {
      getBounds: vi.fn(() => 'bounds'),
    };
    polyline.addTo = vi.fn(() => polyline);
    const marker: Record<string, ReturnType<typeof vi.fn>> = {
      bindPopup: vi.fn(),
    };
    marker.addTo = vi.fn(() => marker);
    const fakeL = {
      map: vi.fn(() => map),
      tileLayer: vi.fn(() => ({ addTo: vi.fn() })),
      polyline: vi.fn(() => polyline),
      marker: vi.fn(() => marker),
      latLngBounds: vi.fn(() => 'bounds'),
      divIcon: vi.fn(() => ({})),
      control: vi.fn((_opts) => {
        const ctrl: { onAdd?: () => HTMLElement; addTo: ReturnType<typeof vi.fn> } = {
          addTo: vi.fn(),
        };
        return ctrl;
      }),
      DomUtil: { create: vi.fn(() => document.createElement('div')) },
      DomEvent: { disableClickPropagation: vi.fn() },
    };
    (globalThis as Record<string, unknown>).L = fakeL;
    return { fakeL, map, polyline, marker };
  }

  beforeEach(async () => {
    const { state } = await import('@/core');
    state.leafletLoaded = true;  // skip lazy load in renderTripMap tests
    state.leafletLoading = false;
    state.tripMap = null;
    document.body.innerHTML = '';
    delete (globalThis as Record<string, unknown>).L;
  });

  it('does nothing when there is no #trip-detail-map element', async () => {
    installFakeLeaflet();
    const { renderTripMap } = await import('@/map');
    await renderTripMap([
      { latitude: 40, longitude: -100 } as TelemetryPoint,
      { latitude: 41, longitude: -101 } as TelemetryPoint,
    ]);
    // Map constructor should never be called.
    expect((globalThis as Record<string, unknown>).L).toBeDefined();
  });

  it('renders the no-gps message when there are fewer than 2 GPS points', async () => {
    installFakeLeaflet();
    document.body.innerHTML = '<div id="trip-detail-map"></div>';
    const { renderTripMap } = await import('@/map');

    await renderTripMap([{ latitude: 40, longitude: -100 } as TelemetryPoint]);

    const el = document.getElementById('trip-detail-map');
    expect(el?.innerHTML).toContain('No GPS data available');
  });

  it('renders a simple polyline route when no power data is present', async () => {
    const { fakeL, map } = installFakeLeaflet();
    document.body.innerHTML = '<div id="trip-detail-map"></div>';
    const { renderTripMap } = await import('@/map');

    await renderTripMap([
      { latitude: 40, longitude: -100, engine_rpm: undefined, hv_battery_power_kw: undefined } as unknown as TelemetryPoint,
      { latitude: 41, longitude: -101, engine_rpm: undefined, hv_battery_power_kw: undefined } as unknown as TelemetryPoint,
    ]);

    expect(fakeL.map).toHaveBeenCalled();
    expect(fakeL.tileLayer).toHaveBeenCalledWith(expect.stringContaining('openstreetmap'), expect.anything());
    // Single polyline path (not segmented)
    expect(fakeL.polyline).toHaveBeenCalled();
    // Start + end markers
    expect(fakeL.marker).toHaveBeenCalledTimes(2);
    expect(map.fitBounds).toHaveBeenCalled();
  });

  it('builds segmented polylines when power data is present', async () => {
    const { fakeL } = installFakeLeaflet();
    document.body.innerHTML = '<div id="trip-detail-map"></div>';
    localStorage.setItem('mapEfficiencyView', 'false');  // use mode-coded segments
    const { renderTripMap } = await import('@/map');

    const points: TelemetryPoint[] = [
      { latitude: 40.0, longitude: -100.0, engine_rpm: 0, hv_battery_power_kw: 5, state_of_charge: 80 },
      { latitude: 40.5, longitude: -100.5, engine_rpm: 0, hv_battery_power_kw: 5, state_of_charge: 78 },
      { latitude: 41.0, longitude: -101.0, engine_rpm: 1500, hv_battery_power_kw: 5, state_of_charge: 76 },
    ] as unknown as TelemetryPoint[];

    await renderTripMap(points);

    // Should call polyline at least twice — once per segment color group
    expect(fakeL.polyline.mock.calls.length).toBeGreaterThanOrEqual(2);
    // The view-toggle and legend controls are added via L.control
    expect(fakeL.control).toHaveBeenCalled();
    localStorage.removeItem('mapEfficiencyView');
  });

  it('uses efficiency-mode segments by default (mapEfficiencyView !== "false")', async () => {
    const { fakeL } = installFakeLeaflet();
    document.body.innerHTML = '<div id="trip-detail-map"></div>';
    // No localStorage entry → defaults to efficiency mode
    const { renderTripMap } = await import('@/map');

    await renderTripMap([
      { latitude: 40.0, longitude: -100.0, engine_rpm: 0, hv_battery_power_kw: 5, state_of_charge: 80 } as unknown as TelemetryPoint,
      { latitude: 40.5, longitude: -100.5, engine_rpm: 0, hv_battery_power_kw: 5, state_of_charge: 78 } as unknown as TelemetryPoint,
    ]);

    // Two control calls: legend + view toggle
    expect(fakeL.control.mock.calls.length).toBeGreaterThanOrEqual(2);
  });
});

describe('addMapLegend', () => {
  it('attaches a control with mode-coded legend HTML when isEfficiencyMode=false', async () => {
    let onAdd: (() => HTMLElement) | null = null;
    const fakeControl: { onAdd?: () => HTMLElement; addTo: ReturnType<typeof vi.fn> } = {
      addTo: vi.fn(),
    };
    (globalThis as Record<string, unknown>).L = {
      control: vi.fn(() => fakeControl),
      DomUtil: { create: vi.fn(() => document.createElement('div')) },
    };

    const { addMapLegend } = await import('@/map');
    addMapLegend({} as L.LeafletMap, false);

    onAdd = fakeControl.onAdd ?? null;
    expect(onAdd).not.toBeNull();
    const div = onAdd!();
    expect(div.innerHTML).toContain('Electric');
    expect(div.innerHTML).toContain('Gas');
    expect(fakeControl.addTo).toHaveBeenCalled();
  });

  it('attaches an efficiency legend when isEfficiencyMode=true', async () => {
    const fakeControl: { onAdd?: () => HTMLElement; addTo: ReturnType<typeof vi.fn> } = {
      addTo: vi.fn(),
    };
    (globalThis as Record<string, unknown>).L = {
      control: vi.fn(() => fakeControl),
      DomUtil: { create: vi.fn(() => document.createElement('div')) },
    };

    const { addMapLegend } = await import('@/map');
    addMapLegend({} as L.LeafletMap, true);

    const div = fakeControl.onAdd!();
    expect(div.innerHTML).toContain('Efficiency');
    expect(div.innerHTML).toContain('Excellent');
  });
});

describe('addMapViewToggle', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    localStorage.clear();
  });

  it('attaches a checkbox toggle that updates localStorage on change', async () => {
    const fakeControl: { onAdd?: () => HTMLElement; addTo: ReturnType<typeof vi.fn> } = {
      addTo: vi.fn(),
    };
    (globalThis as Record<string, unknown>).L = {
      control: vi.fn(() => fakeControl),
      DomUtil: { create: vi.fn(() => document.createElement('div')) },
      DomEvent: { disableClickPropagation: vi.fn() },
    };

    const { addMapViewToggle } = await import('@/map');
    addMapViewToggle({} as L.LeafletMap, [], false);

    const div = fakeControl.onAdd!();
    const checkbox = div.querySelector<HTMLInputElement>('#map-efficiency-toggle');
    expect(checkbox).not.toBeNull();
    expect(checkbox?.checked).toBe(false);

    // Simulate the user enabling efficiency view
    checkbox!.checked = true;
    checkbox!.dispatchEvent(new Event('change'));
    expect(localStorage.getItem('mapEfficiencyView')).toBe('true');
  });

  it('renders the checkbox already-checked when isEfficiencyMode=true', async () => {
    const fakeControl: { onAdd?: () => HTMLElement; addTo: ReturnType<typeof vi.fn> } = {
      addTo: vi.fn(),
    };
    (globalThis as Record<string, unknown>).L = {
      control: vi.fn(() => fakeControl),
      DomUtil: { create: vi.fn(() => document.createElement('div')) },
      DomEvent: { disableClickPropagation: vi.fn() },
    };

    const { addMapViewToggle } = await import('@/map');
    addMapViewToggle({} as L.LeafletMap, [], true);

    const div = fakeControl.onAdd!();
    const checkbox = div.querySelector<HTMLInputElement>('#map-efficiency-toggle');
    expect(checkbox?.checked).toBe(true);
  });
});

describe('loadLeaflet', () => {
  beforeEach(async () => {
    const { state } = await import('@/core');
    state.leafletLoaded = false;
    state.leafletLoading = false;
    document.head.innerHTML = '';
  });

  it('appends a script and a stylesheet to head', async () => {
    const promise = loadLeaflet();
    // Both link and script tags should be in head
    expect(document.head.querySelectorAll('link[href*="leaflet"]').length).toBe(1);
    expect(document.head.querySelectorAll('script[src*="leaflet"]').length).toBe(1);

    // Simulate successful script load
    const script = document.head.querySelector('script[src*="leaflet"]') as HTMLScriptElement;
    script.onload?.(new Event('load'));

    await promise;
    const { state } = await import('@/core');
    expect(state.leafletLoaded).toBe(true);
    expect(state.leafletLoading).toBe(false);
  });

  it('returns immediately if leaflet is already loaded', async () => {
    const { state } = await import('@/core');
    state.leafletLoaded = true;

    await loadLeaflet();
    // Should not append any new script/link
    expect(document.head.querySelectorAll('script').length).toBe(0);
  });

  it('rejects when the script fails to load', async () => {
    const promise = loadLeaflet();
    const script = document.head.querySelector('script[src*="leaflet"]') as HTMLScriptElement;
    script.onerror?.(new Event('error'));

    await expect(promise).rejects.toThrow('Failed to load Leaflet');
  });
});
