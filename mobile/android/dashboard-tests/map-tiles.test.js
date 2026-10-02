import { afterEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

// map.ts basemap tiles: the URL templates + attribution come from the native bridge
// (getMapTileConfig -> StadiaTiles.kt). With no key the bridge answers `{}` and the map
// draws NO tile layer (plain background + route) — never a keyless fallback provider.
// Leaflet is faked, so nothing here ever reaches a tile server.

const DARK = 'https://tiles.stadiamaps.com/tiles/alidade_smooth_dark/{z}/{x}/{y}@2x.png?api_key=fake-key';
const LIGHT = 'https://tiles.stadiamaps.com/tiles/alidade_smooth/{z}/{x}/{y}@2x.png?api_key=fake-key';
const ATTRIBUTION = '© Stadia Maps © OpenMapTiles © OpenStreetMap';
const CONFIG = JSON.stringify({ dark: DARK, light: LIGHT, attribution: ATTRIBUTION });

function fakeLeaflet() {
  const layers = [];
  const fakeMap = {
    fitBounds: vi.fn(() => fakeMap),
    invalidateSize: vi.fn(() => fakeMap),
    on: vi.fn(() => fakeMap),
    remove: vi.fn(() => fakeMap),
    removeLayer: vi.fn(() => fakeMap),
    setView: vi.fn(() => fakeMap),
  };
  const tileLayer = vi.fn(() => {
    const handlers = {};
    const layer = {
      handlers,
      addTo: vi.fn(() => layer),
      bindTooltip: vi.fn(() => layer),
      on: vi.fn((event, handler) => {
        handlers[event] = handler;
        return layer;
      }),
    };
    layers.push(layer);
    return layer;
  });
  return { L: { map: vi.fn(() => fakeMap), tileLayer }, fakeMap, layers };
}

/** A matchMedia stub reporting the given scheme and capturing change listeners. */
function stubScheme(light) {
  const listeners = [];
  const matchMedia = vi.fn((query) => ({
    matches: light && /light/.test(query),
    addEventListener: (event, fn) => {
      if (event === 'change') listeners.push(fn);
    },
  }));
  vi.stubGlobal('matchMedia', matchMedia);
  window.matchMedia = matchMedia;
  return listeners;
}

async function bootMap(bridgeOverrides, { light = false } = {}) {
  const leaflet = fakeLeaflet();
  const listeners = stubScheme(light);
  document.body.innerHTML = '';
  delete window.VoltDashboard;
  delete window.VoltTrackerNative;
  delete window.VoltTrackerAndroid;
  window.L = leaflet.L;
  const bridge = createVoltBridgeFixture(bridgeOverrides);
  await loadDashboard({ bridge });
  const VD = window.VoltDashboard;
  await VD.ensureMapModule();
  VD.ensureMap();
  return { ...leaflet, VD, bridge, listeners };
}

describe('map.ts basemap tile config', () => {
  const previousLeaflet = window.L;
  const previousMatchMedia = window.matchMedia;

  afterEach(() => {
    window.L = previousLeaflet;
    window.matchMedia = previousMatchMedia;
    vi.unstubAllGlobals();
  });

  it('draws no tile layer when the build has no key', async () => {
    const { L, fakeMap } = await bootMap({ getMapTileConfig: () => '{}' });
    expect(L.map).toHaveBeenCalledTimes(1);
    expect(fakeMap.setView).toHaveBeenCalled();
    expect(L.tileLayer).not.toHaveBeenCalled();
  });

  it('uses the dark Stadia template and native attribution on a dark scheme', async () => {
    const { L, layers, fakeMap } = await bootMap({ getMapTileConfig: () => CONFIG });
    expect(L.tileLayer).toHaveBeenCalledTimes(1);
    expect(L.tileLayer).toHaveBeenCalledWith(DARK, { maxZoom: 19, attribution: ATTRIBUTION });
    expect(layers[0].addTo).toHaveBeenCalledWith(fakeMap);
  });

  it('uses the light Stadia template on a light scheme', async () => {
    const { L } = await bootMap({ getMapTileConfig: () => CONFIG }, { light: true });
    expect(L.tileLayer).toHaveBeenCalledWith(LIGHT, { maxZoom: 19, attribution: ATTRIBUTION });
  });

  it('accepts an already-parsed config object and tolerates a missing attribution', async () => {
    const { L } = await bootMap({ getMapTileConfig: () => ({ dark: DARK }) });
    expect(L.tileLayer).toHaveBeenCalledWith(DARK, { maxZoom: 19, attribution: '' });
  });

  it.each([
    ['a template on another host', JSON.stringify({ dark: 'https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png' })],
    ['a non-string template', JSON.stringify({ dark: 42 })],
    ['malformed JSON', '{not json'],
  ])('refuses %s and draws no tile layer', async (_label, raw) => {
    const { L } = await bootMap({ getMapTileConfig: () => raw });
    expect(L.tileLayer).not.toHaveBeenCalled();
  });

  it('draws no tile layer on an older native build without the bridge method', async () => {
    const { L } = await bootMap({ getMapTileConfig: undefined });
    expect(L.tileLayer).not.toHaveBeenCalled();
  });

  it('logs tile errors without the API key and warns after a run of failures', async () => {
    const logClientError = vi.fn();
    const { layers } = await bootMap({ getMapTileConfig: () => CONFIG, logClientError });
    const banner = document.getElementById('mapTileError');
    const tile = { src: 'https://tiles.stadiamaps.com/tiles/alidade_smooth_dark/3/1/2@2x.png?api_key=fake-key' };

    layers[0].handlers.tileerror({ tile });
    layers[0].handlers.tileerror({});
    layers[0].handlers.tileerror({ tile });
    const tileLogs = logClientError.mock.calls.filter(([label]) => label === 'map.tileerror');
    expect(tileLogs).toEqual([
      ['map.tileerror', 'Basemap tile failed: https://tiles.stadiamaps.com/tiles/alidade_smooth_dark/3/1/2@2x.png'],
      ['map.tileerror', 'Basemap tile failed: unknown'],
    ]);
    expect(JSON.stringify(logClientError.mock.calls)).not.toContain('fake-key');
    expect(banner.hidden).toBe(false);

    layers[0].handlers.tileload({});
    expect(banner.hidden).toBe(true);
  });

  it('rebuilds the tile layer in the matching style when the system scheme flips', async () => {
    let light = false;
    const { L, layers, fakeMap, listeners } = await bootMap({ getMapTileConfig: () => CONFIG });
    // Re-stub so the next query reports light, then fire the captured change listener.
    const matchMedia = vi.fn(() => ({ matches: light, addEventListener: () => undefined }));
    vi.stubGlobal('matchMedia', matchMedia);
    window.matchMedia = matchMedia;
    light = true;
    expect(listeners.length).toBeGreaterThan(0);
    listeners[listeners.length - 1]();

    expect(fakeMap.removeLayer).toHaveBeenCalledWith(layers[0]);
    expect(L.tileLayer).toHaveBeenLastCalledWith(LIGHT, { maxZoom: 19, attribution: ATTRIBUTION });
  });
});
