// scrubber.ts — Map-tab route scrubber. Tests the public surface
// (renderScrubber / hideScrubber / scrubAtLatLng / scrubberAttachMap) and the
// invariants around empty / single-point routes. We don't exercise the
// Leaflet marker path because it requires a real map; the early-return paths
// for missing scrubMap are what cover the no-map test environment.
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

// Minimal markup: the scrubber container plus the chart hosts the renderer
// touches. The scrubber.ts bootstrap binds to #scrubToggle and #scrubPlay if
// present — include them so the bindings exercise (they're no-ops without
// data). #scrubCursor is the cursor element setScrubCursor() positions via a
// `left: NN%` style — including it lets the clamp tests observe the cursor.
const SCRUBBER_EXTRA_DOM = `
  <div id="scrubber" hidden>
    <button id="scrubToggle" type="button" aria-expanded="false">Details</button>
    <button id="scrubPlay" type="button">Play</button>
    <div id="scrubChart"><div id="scrubCursor" class="scrub-cursor"></div></div>
    <div id="scrubStack" hidden></div>
    <div id="scrubReadout"></div>
  </div>
`;

function withTwoPointRoute() {
  return {
    points: [
      { lat: 34.050, lng: -118.250, atMs: 1_700_000_000_000, speedMps: 0 },
      { lat: 34.052, lng: -118.250, atMs: 1_700_000_030_000, speedMps: 10 },
    ],
  };
}

function withSinglePointRoute() {
  return {
    points: [
      { lat: 34.050, lng: -118.250, atMs: 1_700_000_000_000, speedMps: 0 },
    ],
  };
}

// A longer route with elevation + SOC that both carry GAPS (missing samples).
// scrubber.ts must interpolate across the gaps for SOC (nearest-by-time over
// route.socTrack) and tolerate missing altM for elevation (window-average that
// skips NaN), never letting a gap collapse a reading to a bogus 0.
// A route whose eff field has GAPS (null where power data was missing — e.g.
// regen segments tagged null by insights-panel's enrichRouteEff). Number(null)
// is 0 (finite!), so these nulls must be screened BEFORE coercion or they
// render as a bogus 0.0 reading and a trace diving to the chart floor.
function withNullEffRoute() {
  const base = 1_700_000_000_000;
  const effs = [2.5, null, null, 3.1, 3.4];
  return {
    points: effs.map((eff, i) => ({
      lat: 34.050 + i * 0.001,
      lng: -118.250,
      atMs: base + i * 10_000,
      speedMps: 10,
      eff,
    })),
  };
}

function withGappyRoute() {
  const base = 1_700_000_000_000;
  return {
    points: [
      { lat: 34.050, lng: -118.250, atMs: base + 0, speedMps: 0, altM: 100 },
      { lat: 34.051, lng: -118.250, atMs: base + 10_000, speedMps: 8, altM: NaN },
      { lat: 34.052, lng: -118.250, atMs: base + 20_000, speedMps: 12, altM: 130 },
      { lat: 34.053, lng: -118.250, atMs: base + 30_000, speedMps: 14, altM: NaN },
      { lat: 34.054, lng: -118.250, atMs: base + 40_000, speedMps: 10, altM: 150 },
    ],
    // SOC sampled only at the ends — every interior point must interpolate.
    socTrack: [
      { atMs: base + 0, soc: 80 },
      { atMs: base + 40_000, soc: 76 },
    ],
  };
}

describe('scrubber.ts', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard({ extraDom: SCRUBBER_EXTRA_DOM, extras: ['scrubber.js'] });
  });

  it('exposes the documented public surface on window.VoltDashboard', () => {
    const VD = window.VoltDashboard;
    expect(typeof VD.renderScrubber).toBe('function');
    expect(typeof VD.hideScrubber).toBe('function');
    expect(typeof VD.scrubAtLatLng).toBe('function');
    expect(typeof VD.scrubberAttachMap).toBe('function');
  });

  it('scrubAtLatLng is a no-op when no route is loaded', () => {
    const VD = window.VoltDashboard;
    // No throws, no side effects on the DOM. Just call it.
    expect(() => VD.scrubAtLatLng(34.05, -118.25)).not.toThrow();
    expect(document.getElementById('scrubber').hidden).toBe(true);
  });

  it('renderScrubber hides the panel when the route has fewer than 2 points', () => {
    const VD = window.VoltDashboard;
    const scrubber = document.getElementById('scrubber');
    scrubber.hidden = false; // pretend a previous route had revealed it
    VD.renderScrubber(withSinglePointRoute());
    expect(scrubber.hidden).toBe(true);
  });

  it('renderScrubber reveals the panel for a 2-point route', () => {
    const VD = window.VoltDashboard;
    const scrubber = document.getElementById('scrubber');
    expect(scrubber.hidden).toBe(true);
    VD.renderScrubber(withTwoPointRoute());
    expect(scrubber.hidden).toBe(false);
  });

  it('hideScrubber re-hides the panel and clears internal route state', () => {
    const VD = window.VoltDashboard;
    VD.renderScrubber(withTwoPointRoute());
    expect(document.getElementById('scrubber').hidden).toBe(false);
    VD.hideScrubber();
    expect(document.getElementById('scrubber').hidden).toBe(true);
    // After hide, scrubAtLatLng should be a no-op again — proves scrubData
    // was reset (not just the panel re-hidden).
    expect(() => VD.scrubAtLatLng(34.05, -118.25)).not.toThrow();
  });

  // ----- cursor fraction clamping (0 and 1) ---------------------------------
  // scrubAtLatLng snaps the cursor to the nearest route point's `frac`; the
  // first point is frac 0 and the last is frac 1, so snapping to the route's
  // endpoints exercises both clamp boundaries through the public surface. The
  // cursor's `left` style is the observable output (set as a NN% string).

  function cursorLeft() {
    return document.getElementById('scrubCursor').style.left;
  }

  // renderScrubCharts() bails (and never populates scrubCursors) unless the
  // chart host reports a non-zero width — jsdom gives every element 0. Stub a
  // width so the cursor wiring runs, mirroring drive.test.js's clientWidth hack.
  function giveChartWidth(px = 320) {
    Object.defineProperty(document.getElementById('scrubChart'), 'clientWidth', {
      configurable: true,
      value: px,
    });
  }

  it('snapping to the first route point pins the cursor to fraction 0 (0%)', () => {
    const VD = window.VoltDashboard;
    const route = withGappyRoute();
    giveChartWidth();
    VD.renderScrubber(route);
    const first = route.points[0];
    VD.scrubAtLatLng(first.lat, first.lng);
    expect(cursorLeft()).toBe('0%');
  });

  it('snapping to the last route point pins the cursor to fraction 1 (100%)', () => {
    const VD = window.VoltDashboard;
    const route = withGappyRoute();
    giveChartWidth();
    VD.renderScrubber(route);
    const last = route.points[route.points.length - 1];
    VD.scrubAtLatLng(last.lat, last.lng);
    expect(cursorLeft()).toBe('100%');
  });

  // ----- play / pause toggle state ------------------------------------------
  // The Play button drives an rAF loop; we don't need it to actually advance —
  // we only assert the toggle flips the button label/state on click and back.

  it('the play button toggles between Play and Stop on successive clicks', () => {
    const VD = window.VoltDashboard;
    VD.renderScrubber(withGappyRoute());
    const play = document.getElementById('scrubPlay');
    // Stub rAF so starting playback doesn't schedule real frames in jsdom.
    const rafSpy = vi.spyOn(window, 'requestAnimationFrame').mockReturnValue(1);
    const cancelSpy = vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(() => {});
    try {
      expect(play.textContent).toContain('Play');
      play.click(); // start
      expect(play.textContent).toContain('Pause');
      expect(rafSpy).toHaveBeenCalled();
      play.click(); // stop
      expect(play.textContent).toContain('Play');
      expect(cancelSpy).toHaveBeenCalled();
    } finally {
      rafSpy.mockRestore();
      cancelSpy.mockRestore();
    }
  });

  it('the play button is inert with no route loaded (stays on Play)', () => {
    const VD = window.VoltDashboard;
    VD.hideScrubber(); // ensure no route
    const play = document.getElementById('scrubPlay');
    const rafSpy = vi.spyOn(window, 'requestAnimationFrame').mockReturnValue(1);
    try {
      play.click();
      expect(play.textContent).toContain('Play');
      expect(rafSpy).not.toHaveBeenCalled();
    } finally {
      rafSpy.mockRestore();
    }
  });

  it('reduced-motion users jump to the end without starting playback animation', () => {
    const VD = window.VoltDashboard;
    giveChartWidth();
    VD.renderScrubber(withGappyRoute());
    const play = document.getElementById('scrubPlay');
    const priorMatchMedia = window.matchMedia;
    window.matchMedia = vi.fn(() => ({ matches: true }));
    const rafSpy = vi.spyOn(window, 'requestAnimationFrame').mockReturnValue(1);
    try {
      play.click();
      expect(play.textContent).toContain('Play');
      expect(rafSpy).not.toHaveBeenCalled();
      expect(cursorLeft()).toBe('100%');
    } finally {
      window.matchMedia = priorMatchMedia;
      rafSpy.mockRestore();
    }
  });

  // ----- SOC / elevation interpolation across MISSING samples ----------------

  function readoutValue(label) {
    const chips = document.querySelectorAll('#scrubReadout > div');
    for (const chip of chips) {
      const kicker = chip.querySelector('.kicker');
      if (kicker && kicker.textContent === label) {
        return chip.querySelector('strong')?.textContent ?? null;
      }
    }
    return null;
  }

  it('interpolates SOC at an interior point even though socTrack only has endpoints', () => {
    const VD = window.VoltDashboard;
    const route = withGappyRoute();
    VD.renderScrubber(route);
    // Snap to the geometric midpoint (3rd of 5 points). SOC runs 80 -> 76 over
    // the drive; the time-interpolated value at the midpoint is ~78%, never the
    // "--" a missing-sample reading would show.
    const mid = route.points[2];
    VD.scrubAtLatLng(mid.lat, mid.lng);
    const battery = readoutValue('Battery');
    expect(battery).not.toBe('--');
    expect(battery).toBe('78%');
  });

  it('renders scrubber metrics with readable labels and units in the values', () => {
    const VD = window.VoltDashboard;
    giveChartWidth();
    VD.renderScrubber(withGappyRoute());

    // The distance chip's label carries the unit ("Distance mi") and the value
    // is the bare number — "128.4 mi" clipped to "128.…" in the nowrap cell.
    expect(readoutValue('Distance mi')).toMatch(/^[\d.]+$/);
    // The speed chip's label carries the unit ("Speed mph") and the value is
    // the bare number — "104 mph" overflowed the six-across readout cell.
    expect(readoutValue('Speed mph')).toMatch(/^\d+$/);
    // Elevation likewise: unit in the label ("Elevation ft"), bare number value.
    expect(readoutValue('Elevation ft')).toMatch(/^-?\d+$/);
    // The mi/kWh chip's label already carries the unit, so its value never
    // repeats " mi/kWh" (that redundancy overflowed the compact readout cell).
    expect(readoutValue('mi/kWh')).not.toMatch(/mi\/kWh/);
  });

  it('builds chart SVG nodes in the SVG namespace so traces actually render', () => {
    const VD = window.VoltDashboard;
    giveChartWidth();
    VD.renderScrubber(withGappyRoute());
    const SVG_NS = 'http://www.w3.org/2000/svg';
    // Regression guard: scrubSvg() must declare xmlns. parseSvg() parses the
    // markup as "image/svg+xml"; without the namespace declaration every node
    // lands in the null namespace and renders nothing (fill/stroke/d go inert).
    const svg = document.querySelector('#scrubChart svg');
    expect(svg).not.toBeNull();
    expect(svg.namespaceURI).toBe(SVG_NS);
    const path = svg.querySelector('path');
    expect(path).not.toBeNull();
    expect(path.namespaceURI).toBe(SVG_NS);
  });

  it('renders elevation across altM gaps without collapsing the reading to a bogus value', () => {
    const VD = window.VoltDashboard;
    const route = withGappyRoute();
    VD.renderScrubber(route);
    // Points 1 and 3 have NaN altM; the windowed average must borrow finite
    // neighbours so the elevation readout stays a real number, not "--" and not
    // a spurious 0.
    const gapPoint = route.points[1];
    VD.scrubAtLatLng(gapPoint.lat, gapPoint.lng);
    const elev = readoutValue('Elevation ft');
    expect(elev).not.toBe('--');
    expect(Number(elev)).toBeGreaterThan(0);
  });

  // ----- null-eff samples must stay missing, never coerce to 0 ---------------

  it('shows the "soon" readout at a null-eff point instead of a fake 0.0', () => {
    const VD = window.VoltDashboard;
    const route = withNullEffRoute();
    giveChartWidth();
    VD.renderScrubber(route);
    // Snap to the 2nd point (eff: null). The readout must take the missing-
    // sample path, not print Number(null) === 0 as "0.0".
    const nullPoint = route.points[1];
    VD.scrubAtLatLng(nullPoint.lat, nullPoint.lng);
    expect(readoutValue('mi/kWh')).toBe('soon');

    // A point that does carry eff still renders its real value.
    const realPoint = route.points[3];
    VD.scrubAtLatLng(realPoint.lat, realPoint.lng);
    expect(readoutValue('mi/kWh')).toBe('3.1');
  });

  it('the eff polyline skips null points (restarts with M, no point at the chart floor)', () => {
    const VD = window.VoltDashboard;
    giveChartWidth();
    VD.renderScrubber(withNullEffRoute());
    // The eff trace is the lime path in the combo chart.
    const effPath = document.querySelector('#scrubChart svg path[stroke="#b8e63b"]');
    expect(effPath).not.toBeNull();
    const d = effPath.getAttribute('d');
    // Points 1-2 are null: the line breaks and restarts (two M commands),
    // drawing only the 3 real points (one L continuation).
    expect((d.match(/M/g) || [])).toHaveLength(2);
    expect((d.match(/L/g) || [])).toHaveLength(1);
    // eff scale is 0..7 over h=116 padT=12 padB=9, so a coerced null→0 would
    // land at y=107.0 (the chart floor). It must not.
    expect(d).not.toContain('107.0');
  });

  it('a route whose eff is entirely null never lights up the eff trace', () => {
    const VD = window.VoltDashboard;
    const route = withNullEffRoute();
    route.points.forEach((p) => { p.eff = null; });
    giveChartWidth();
    VD.renderScrubber(route);
    // Number(null) === 0 used to flip scrubHasEff on for all-null routes.
    expect(document.querySelector('#scrubChart svg path[stroke="#b8e63b"]')).toBeNull();
  });

  // ----- resize must re-bind the rebuilt detail tracks ------------------------

  it('re-binds the expanded detail tracks after a window resize re-render', () => {
    const VD = window.VoltDashboard;
    giveChartWidth();
    VD.renderScrubber(withGappyRoute());
    // Expand details — tracks render and get their pointer bindings.
    document.getElementById('scrubToggle').click();
    const before = Array.from(document.querySelectorAll('#scrubStack .scrub-track'));
    expect(before.length).toBeGreaterThan(0);
    before.forEach((track) => expect(track.dataset.scrubBound).toBe('1'));

    // Resize rebuilds the .scrub-track nodes from scratch (debounced 160 ms).
    vi.useFakeTimers();
    try {
      window.dispatchEvent(new window.Event('resize'));
      vi.advanceTimersByTime(200);
    } finally {
      vi.useRealTimers();
    }

    const after = Array.from(document.querySelectorAll('#scrubStack .scrub-track'));
    expect(after.length).toBe(before.length);
    // The rebuilt nodes are NEW elements; each must have been re-bound or
    // dragging on an expanded track dies after rotate/resize.
    after.forEach((track) => expect(track.dataset.scrubBound).toBe('1'));
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });
});
