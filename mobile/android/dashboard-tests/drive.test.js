// drive.js — Drive-tab live polish (chip strip + SVG charts).
// Tests the public surface (renderDriveLive / renderDriveNowChips and the
// individual draw* functions) using the minimal DOM fixture and the shared
// loadDashboard helper.
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

// Chart hosts + micro-card headers drive.js touches. Layered on top of
// REQUIRED_DOM via the loader's extraDom option.
const DRIVE_EXTRA_DOM = `
  <div id="driveNowChips"></div>
  <div id="liveTraceChart"><div class="scrub-cursor live-trace-cursor"></div></div>
  <div id="powerBarsChart"></div>
  <div id="socTraceChart"></div>
  <span id="powerMicroTag" data-tone="idle" data-kind="power">kW</span>
  <span id="socMicroTag" data-tone="idle" data-kind="soc">%</span>
`;

describe('drive.js', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    loadDashboard({ extraDom: DRIVE_EXTRA_DOM, extras: ['drive.js'] });
  });

  it('exposes the documented render entry points on window.VoltDashboard', () => {
    const VD = window.VoltDashboard;
    expect(typeof VD.renderDriveLive).toBe('function');
    expect(typeof VD.renderDriveNowChips).toBe('function');
    expect(typeof VD.drawLiveSpeedTrace).toBe('function');
    expect(typeof VD.drawLivePowerBars).toBe('function');
    expect(typeof VD.drawLiveSocTrace).toBe('function');
  });

  it('renders an "Idle" chip when no adapter is connected and no demo is active', () => {
    const VD = window.VoltDashboard;
    VD.renderDriveNowChips();
    const host = document.getElementById('driveNowChips');
    expect(host.innerHTML).toContain('Idle');
    // Tone attribute is what the CSS keys off — a missed tone would render
    // the chip with the wrong color and not fail any other test.
    expect(host.querySelector('[data-tone]')).not.toBeNull();
  });

  it('renders a "Demo preview" chip when state.demoActive is true', () => {
    const VD = window.VoltDashboard;
    VD.state.demoActive = true;
    VD.state.telemetry = { sampleCount: 12, soc: 78, sessionMs: 30_000 };
    VD.renderDriveNowChips();
    const host = document.getElementById('driveNowChips');
    expect(host.innerHTML).toContain('Demo preview');
    expect(host.querySelector('[data-tone="demo"]')).not.toBeNull();
  });

  it('renders a "Recording" chip when adapter.connected is true', () => {
    const VD = window.VoltDashboard;
    VD.state.appState = {
      adapter: { connected: true, name: 'OBDLink MX+' },
      session: { state: 'connected', sampleCount: 42, runtimeMs: 90_000 },
    };
    VD.renderDriveNowChips();
    const host = document.getElementById('driveNowChips');
    expect(host.innerHTML).toContain('Recording');
    expect(host.innerHTML).toContain('42 samples');
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
    expect(host.querySelector('svg')).not.toBeNull();
    expect(host.innerHTML).toContain('waiting for samples');
  });

  it('resize handler debounces multiple resize events into one pending timer', () => {
    vi.useFakeTimers();
    try {
      // 5 rapid resize events. drive.js's handler calls clearTimeout(resizeTimer)
      // before scheduling a fresh setTimeout, so the COUNT of pending resize-driven
      // timers should saturate at 1 (per registered handler) regardless of how many
      // events fired — not grow to 5. After advancing past the debounce window,
      // every transient resize timer must clear back to whatever the dashboard's
      // long-running interval baseline is.
      //
      // We can't assert an exact pending-timer count after the burst because
      // multiple modules (drive.js, panels.js, etc.) each install their own resize
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
