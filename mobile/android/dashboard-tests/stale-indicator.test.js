// Stale-indicator contract: when more than STALE_THRESHOLD_MS (3000 ms) elapses without a
// fresh sample, every live telemetry tile gets the `.stale` class so the UI
// can dim them. The check runs on a 1 Hz setInterval AND every render — we
// drive the setInterval path here.
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

function staleTileIds() {
  return Array.from(document.querySelectorAll('[data-live-tile="true"]'))
    .map((node) => node.id)
    .filter(Boolean);
}

describe('stale-tile indicator', () => {
  beforeEach(async () => {
    // Fake timers must be installed before loadDashboard so the
    // setInterval(applyStaleIndicator, 1000) registered at the bottom of
    // telemetry.ts binds to the fake clock.
    vi.useFakeTimers();
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('marks live tiles stale until the first sample arrives', () => {
    // No sample has been delivered yet, so lastSampleAt is 0 and the very
    // first interval tick should paint every tile stale.
    vi.advanceTimersByTime(1100);
    for (const id of staleTileIds()) {
      const node = document.getElementById(id);
      expect(node, `#${id} missing from fixture DOM`).not.toBeNull();
      expect(node.classList.contains('stale'), `#${id} should start stale`).toBe(true);
    }
  });

  it('clears stale immediately after a telemetry update, then re-marks after >3 s', () => {
    // Fresh sample via the documented WebView callback.
    window.VoltTrackerNative.updateTelemetry({
      source: 'demo',
      speedKph: 42,
      updatedAt: Date.now(),
    });
    // updateTelemetry defers most work to requestAnimationFrame, but it
    // also calls applyStaleIndicator synchronously through the setInterval
    // tick path. Force one tick to flush the indicator update.
    vi.advanceTimersByTime(50);
    const speed = document.getElementById('speedValue');
    expect(speed.classList.contains('stale'), 'live tile should clear stale after a fresh sample').toBe(false);

    // Push the clock past the 3 s threshold without delivering a new
    // sample; the 1 Hz interval will then re-mark tiles as stale.
    vi.advanceTimersByTime(4000);
    for (const id of staleTileIds()) {
      const node = document.getElementById(id);
      expect(node.classList.contains('stale'), `#${id} should be stale after 4 s of silence`).toBe(true);
    }
  });

  it('keeps the Drive live-rate chip aligned with sample freshness', () => {
    const chip = document.getElementById('liveRateChip');
    const label = document.getElementById('liveRateLabel');
    expect(chip, 'liveRateChip missing from generated DOM').not.toBeNull();

    // No samples yet → the chip advertises "waiting".
    vi.advanceTimersByTime(1100);
    expect(chip.dataset.state).toBe('waiting');
    expect(label.textContent).toBe('waiting');

    // A fresh sample flips the chip to "live".
    window.VoltTrackerNative.updateTelemetry({
      source: 'demo',
      speedKph: 42,
      updatedAt: Date.now(),
    });
    vi.advanceTimersByTime(1100);
    expect(chip.dataset.state).toBe('live');
    expect(label.textContent).toBe('live');

    // Going quiet past the threshold flips it to "stale" (not back to waiting).
    vi.advanceTimersByTime(4000);
    expect(chip.dataset.state).toBe('stale');
    expect(label.textContent).toBe('stale');
  });

  it('explains stale telemetry and lets keyboard users reconnect', async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    const bridge = createVoltBridgeFixture({ tryReconnectNow: vi.fn() });
    await loadDashboard({ bridge });
    const chip = document.getElementById('liveRateChip');

    window.VoltTrackerNative.updateTelemetry({
      source: 'real',
      speedKph: 42,
      sampleCount: 1,
      updatedAt: Date.now(),
    });
    vi.advanceTimersByTime(4100);

    expect(chip.dataset.state).toBe('stale');
    expect(chip.getAttribute('role')).toBe('button');
    expect(chip.getAttribute('aria-label')).toMatch(/press to reconnect/i);
    chip.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    expect(bridge.tryReconnectNow).toHaveBeenCalledTimes(1);
  });
});
