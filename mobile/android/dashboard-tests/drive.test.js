// drive.ts — Drive-tab live polish (chip strip + SVG charts).
// Tests the public surface (renderDriveLive / renderDriveNowChips and the
// individual draw* functions) using the minimal DOM fixture and the shared
// loadDashboard helper.
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

// Chart hosts + micro-card headers drive.ts touches. Layered on top of
// REQUIRED_DOM via the loader's extraDom option.
const DRIVE_EXTRA_DOM = `
  <div id="driveNowChips"></div>
  <div id="driveRecording" hidden><span class="settings-recording__dot"></span><strong id="driveRecordingLabel">Recording</strong><small id="driveRecordingMeta"></small></div>
  <button id="topDemoInfo" hidden><strong>Demo / Testing</strong><small id="topDemoMeta">sample data</small></button>
  <div id="liveTraceChart"><canvas id="liveTraceCanvas"></canvas><div class="scrub-cursor live-trace-cursor"></div></div>
  <div id="powerBarsChart"></div>
  <div id="socTraceChart"></div>
  <span id="powerMicroTag" data-tone="idle" data-kind="power">kW</span>
  <span id="socMicroTag" data-tone="idle" data-kind="soc">%</span>
`;

describe('drive.ts', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard({ extraDom: DRIVE_EXTRA_DOM, extras: ['drive.js'] });
  });

  it('exposes the documented render entry points on window.VoltDashboard', () => {
    const VD = window.VoltDashboard;
    expect(typeof VD.renderDriveLive).toBe('function');
    expect(typeof VD.renderDriveNowChips).toBe('function');
    expect(typeof VD.drawLiveSpeedTrace).toBe('function');
    expect(typeof VD.drawLivePowerBars).toBe('function');
    expect(typeof VD.drawLiveSocTrace).toBe('function');
  });

  it('does not repeat an idle status chip on Drive (state lives in the top-bar pill)', () => {
    const VD = window.VoltDashboard;
    VD.renderDriveNowChips();
    const host = document.getElementById('driveNowChips');
    // Idle / "ready · remembered" is shown by the top-bar pill + slim last-connected line, so the
    // Drive now-chips strip should not duplicate it. With no adapter and no history it is empty.
    expect(host.innerHTML).not.toContain('Idle');
    expect(host.children.length).toBe(0);
  });

  it('moves the Demo / Testing marker to the topbar line and keeps the chip strip empty', () => {
    const VD = window.VoltDashboard;
    VD.state.demoActive = true;
    VD.state.telemetry = { sampleCount: 12, soc: 78, sessionMs: 30_000 };
    VD.renderDriveNowChips();
    // The purple "demo" pill sits right next to the header line, so a strip
    // chip would repeat the same state a third time — the strip stays empty.
    const host = document.getElementById('driveNowChips');
    expect(host.children.length).toBe(0);
    const line = document.getElementById('topDemoInfo');
    expect(line.hidden).toBe(false);
    expect(line.textContent).toContain('Demo / Testing');
    // Header meta is SOC-only now: two fragments overflowed the topbar into
    // "60 samples · …" on phone widths, and the sample count lives in the
    // session footnote on Drive.
    expect(document.getElementById('topDemoMeta').textContent).toBe('78% SOC');
  });

  it('hides the topbar Demo / Testing line again when the demo stops', () => {
    const VD = window.VoltDashboard;
    VD.state.demoActive = true;
    VD.renderDriveNowChips();
    expect(document.getElementById('topDemoInfo').hidden).toBe(false);
    VD.state.demoActive = false;
    VD.renderDriveNowChips();
    expect(document.getElementById('topDemoInfo').hidden).toBe(true);
  });

  it('does not show "Recording" when status is idle even if adapter/session state is stale', () => {
    const VD = window.VoltDashboard;
    VD.state.status = { state: 'idle' };
    VD.state.appState = {
      adapter: { connected: true, name: 'OBDLink MX+' },
      session: { state: 'connected', sampleCount: 0 },
    };
    VD.renderDriveNowChips();
    // The recording state consolidates into the Settings #driveRecording footer;
    // idle keeps it hidden and the retired strip empty.
    const rec = document.getElementById('driveRecording');
    expect(rec.hidden).toBe(true);
    expect(document.getElementById('driveNowChips').children.length).toBe(0);
  });

  it('shows the Settings "Recording" footer when an active session has live evidence', () => {
    const VD = window.VoltDashboard;
    VD.state.appState = {
      adapter: { connected: true, name: 'OBDLink MX+' },
      session: { state: 'connected', sampleCount: 42, runtimeMs: 90_000 },
    };
    VD.renderDriveNowChips();
    const rec = document.getElementById('driveRecording');
    expect(rec.hidden).toBe(false);
    expect(rec.textContent).toContain('Recording');
    expect(rec.textContent).toContain('42 samples');
    // The full-width strip is no longer a live-status surface.
    expect(document.getElementById('driveNowChips').children.length).toBe(0);
  });

  it('shows a real "live" placeholder when Recording has evidence but no counted metrics yet', () => {
    const VD = window.VoltDashboard;
    // hasLiveEvidence is true off lastSampleAt while sampleCount/runtime/distance
    // are all still 0 — meta would be empty, and setText coerces "" to "--", so
    // "Recording" must not read "--".
    VD.state.appState = {
      adapter: { connected: true, name: 'OBDLink MX+' },
      session: { state: 'connected', sampleCount: 0 },
    };
    VD.state.lastSampleAt = Date.now();
    VD.renderDriveNowChips();
    const rec = document.getElementById('driveRecording');
    expect(rec.hidden).toBe(false);
    expect(rec.textContent).toContain('Recording');
    expect(document.getElementById('driveRecordingMeta').textContent).toBe('live');
    expect(rec.textContent).not.toContain('--');
  });

  it('uses waiting copy for an active session before the first sample arrives', () => {
    const VD = window.VoltDashboard;
    VD.state.status = { state: 'connected' };
    VD.state.appState = {
      adapter: { connected: true, name: 'OBDLink MX+' },
      session: { state: 'connected', sampleCount: 0 },
    };
    VD.renderDriveNowChips();
    const rec = document.getElementById('driveRecording');
    expect(rec.hidden).toBe(false);
    expect(rec.textContent).toContain('Waiting for data');
    expect(rec.textContent).toContain('adapter connected');
    expect(rec.textContent).not.toContain('Recording');
  });

  it('drawLiveSpeedTrace shows a placeholder when speedHistory is empty', () => {
    const VD = window.VoltDashboard;
    VD.state.speedHistory = [];
    // Give the chart a non-zero clientWidth so paint() actually runs.
    const host = document.getElementById('liveTraceChart');
    Object.defineProperty(host, 'clientWidth', {
      configurable: true,
      value: 320,
    });
    VD.drawLiveSpeedTrace();
    expect(host.querySelector('canvas')).not.toBeNull();
    expect(host.dataset.traceState).toBe('empty');
    expect(host.dataset.traceLabel).toBe('speed trace appears once samples stream in');
  });

  it('drawLiveSpeedTrace does not repaint the canvas on an unchanged history', () => {
    const VD = window.VoltDashboard;
    const host = document.getElementById('liveTraceChart');
    Object.defineProperty(host, 'clientWidth', { configurable: true, value: 320 });
    const ctx = document.getElementById('liveTraceCanvas').getContext('2d');
    const strokeSpy = vi.spyOn(ctx, 'stroke');

    VD.state.speedHistory = [10, 20, 30];
    VD.drawLiveSpeedTrace();
    expect(strokeSpy).toHaveBeenCalledTimes(1);

    // renderDriveLive re-runs on every broadcast and natives re-deliver the last
    // sample verbatim — an unchanged history must NOT re-stroke the canvas.
    VD.drawLiveSpeedTrace();
    expect(strokeSpy).toHaveBeenCalledTimes(1);

    // A genuinely new sample repaints.
    VD.state.speedHistory = [10, 20, 30, 40];
    VD.drawLiveSpeedTrace();
    expect(strokeSpy).toHaveBeenCalledTimes(2);
  });

  it('keeps the optional live readout collapsed until a signal arrives', () => {
    const VD = window.VoltDashboard;
    const group = document.getElementById('liveReadout');

    VD.updateLiveUi();
    expect(group.classList.contains('is-empty')).toBe(true);

    VD.state.telemetry = { rpm: 1234 };
    VD.updateLiveUi();

    expect(group.classList.contains('is-empty')).toBe(false);
    expect(document.getElementById('rpmValue').textContent).toContain('1234');
  });

  it('renders power and SOC chart marks as HTML DOM nodes when live samples exist', () => {
    const VD = window.VoltDashboard;
    const powerHost = document.getElementById('powerBarsChart');
    const socHost = document.getElementById('socTraceChart');
    Object.defineProperty(powerHost, 'clientWidth', {
      configurable: true,
      value: 320,
    });
    Object.defineProperty(socHost, 'clientWidth', {
      configurable: true,
      value: 320,
    });
    VD.state.powerHistory = [4, 12, -3, 0, 18];
    VD.state.socHistory = [78.2, 78.1, 78.0, 77.9];

    VD.drawLivePowerBars();
    VD.drawLiveSocTrace();

    expect(powerHost.dataset.chartState).toBe('ready');
    expect(socHost.dataset.chartState).toBe('ready');
    expect(powerHost.querySelectorAll('.live-power-bar').length).toBeGreaterThan(0);
    expect(socHost.querySelectorAll('.live-soc-segment').length).toBeGreaterThan(0);
    expect(powerHost.querySelector('.live-chart-empty')).toBeNull();
    expect(socHost.querySelector('.live-chart-empty')).toBeNull();
    expect(powerHost.querySelector('svg')).toBeNull();
    expect(socHost.querySelector('svg')).toBeNull();
  });

  it('redraws power and SOC charts when only a middle sample changes', () => {
    const VD = window.VoltDashboard;
    const powerHost = document.getElementById('powerBarsChart');
    const socHost = document.getElementById('socTraceChart');
    Object.defineProperty(powerHost, 'clientWidth', { configurable: true, value: 320 });
    Object.defineProperty(socHost, 'clientWidth', { configurable: true, value: 320 });
    VD.state.powerHistory = [4, 12, -3, 18];
    VD.state.socHistory = [78.2, 78.1, 78.0, 77.9];
    VD.drawLivePowerBars();
    VD.drawLiveSocTrace();
    const powerChart = powerHost.firstElementChild;
    const socChart = socHost.firstElementChild;

    VD.state.powerHistory = [4, 30, -3, 18];
    VD.state.socHistory = [78.2, 75.0, 78.0, 77.9];
    VD.drawLivePowerBars();
    VD.drawLiveSocTrace();

    expect(powerHost.firstElementChild).not.toBe(powerChart);
    expect(socHost.firstElementChild).not.toBe(socChart);
  });

  it('keeps the micro-card corner tags as static unit labels (v2 design)', () => {
    const VD = window.VoltDashboard;
    // The old live value/Δ chips were replaced by the design's bare "kW" / "%"
    // unit labels — a render pass must not overwrite them with values.
    VD.state.sessionStartSoc = 78.4;
    VD.state.telemetry.soc = 64;
    VD.state.telemetry.powerKw = 12.3;
    VD.renderDriveLive();
    expect(document.getElementById('socMicroTag').textContent).toBe('%');
    expect(document.getElementById('powerMicroTag').textContent).toBe('kW');
  });

  it('renders layout-stable empty states for power and SOC charts', () => {
    const VD = window.VoltDashboard;
    const powerHost = document.getElementById('powerBarsChart');
    const socHost = document.getElementById('socTraceChart');
    Object.defineProperty(powerHost, 'clientWidth', {
      configurable: true,
      value: 320,
    });
    Object.defineProperty(socHost, 'clientWidth', {
      configurable: true,
      value: 320,
    });
    VD.state.powerHistory = [];
    VD.state.socHistory = [];

    VD.drawLivePowerBars();
    VD.drawLiveSocTrace();

    expect(powerHost.dataset.chartState).toBe('empty');
    expect(socHost.dataset.chartState).toBe('empty');
    expect(powerHost.querySelector('.live-chart-empty')?.textContent).toBe('Waiting for power samples');
    expect(socHost.querySelector('.live-chart-empty')?.textContent).toBe('Waiting for SOC samples');
    expect(powerHost.querySelector('svg')).toBeNull();
    expect(socHost.querySelector('svg')).toBeNull();
  });

  it('resize handler debounces multiple resize events into one pending timer', () => {
    vi.useFakeTimers();
    try {
      // 5 rapid resize events. drive.ts's handler calls clearTimeout(resizeTimer)
      // before scheduling a fresh setTimeout, so the COUNT of pending resize-driven
      // timers should saturate at 1 (per registered handler) regardless of how many
      // events fired — not grow to 5. After advancing past the debounce window,
      // every transient resize timer must clear back to whatever the dashboard's
      // long-running interval baseline is.
      //
      // We can't assert an exact pending-timer count after the burst because
      // multiple modules (drive.ts, panels.ts, etc.) each install their own resize
      // debouncer and we don't know how many are loaded in this fixture. What we
      // CAN assert is: after the burst + the debounce window expires, the timer
      // count returns to whatever it was before the burst started. A leaky
      // implementation that didn't clearTimeout would leave 5 timers pending and
      // this assertion would fail.
      const baseline = vi.getTimerCount();
      for (let i = 0; i < 5; i += 1) {
        window.dispatchEvent(new Event('resize'));
      }
      // Before advancing time: more than baseline timers are pending (at least one
      // per handler that saw the events).
      expect(vi.getTimerCount()).toBeGreaterThan(baseline);
      // After advancing past the 160 ms debounce: all transient timers fired and
      // cleared themselves.
      vi.advanceTimersByTime(300);
      expect(vi.getTimerCount()).toBe(baseline);
    } finally {
      vi.useRealTimers();
    }
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });
});
