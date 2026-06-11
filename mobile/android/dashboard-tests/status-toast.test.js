// Status-toast contract: status.detail lands in #statusCopy, which lives on
// the Settings tab. For taps made on other tabs, telemetry.ts mirrors NEW
// detail strings into the #statusToast aria-live region so the feedback is
// visible where the user actually is. Rules pinned here:
//  - the first (boot-time) status push consumes the baseline silently, even
//    when its detail is empty;
//  - later detail changes toast on non-Settings tabs and auto-hide;
//  - while a toast is visible, an identical detail does not re-toast, but
//    once it has hidden a repeat of the SAME detail toasts again;
//  - the Settings tab never toasts (the inline #statusCopy already shows it).
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

describe('status toast', () => {
  beforeEach(async () => {
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

  it('does not toast the boot-time status push', () => {
    window.VoltTrackerNative.setStatus({
      state: 'ready',
      detail: 'Viewing local data. Connect only when you want live OBD logging.',
    });
    expect(document.getElementById('statusToast').hidden).toBe(true);
  });

  it('toasts a later detail change on a non-Settings tab, then auto-hides', () => {
    window.VoltTrackerNative.setStatus({ state: 'ready', detail: 'Boot baseline.' });
    window.VoltTrackerNative.setStatus({
      state: 'blocked',
      detail: 'Pick a paired or remembered OBD adapter first.',
    });
    const toast = document.getElementById('statusToast');
    expect(toast.hidden).toBe(false);
    expect(toast.textContent).toContain('Pick a paired');
    vi.advanceTimersByTime(3500);
    expect(toast.hidden).toBe(true);
  });

  it('does not repeat a toast for an unchanged detail while it is still visible', () => {
    window.VoltTrackerNative.setStatus({ state: 'ready', detail: 'Boot baseline.' });
    window.VoltTrackerNative.setStatus({ state: 'ready', detail: 'Scan finished.' });
    const toast = document.getElementById('statusToast');
    expect(toast.hidden).toBe(false);
    // Same detail again while showing — the 3.2 s hide timer is NOT restarted.
    vi.advanceTimersByTime(1000);
    window.VoltTrackerNative.setStatus({ state: 'ready', detail: 'Scan finished.' });
    vi.advanceTimersByTime(2400);
    expect(toast.hidden).toBe(true);
  });

  it('re-toasts the same detail after the previous toast has hidden', () => {
    window.VoltTrackerNative.setStatus({ state: 'ready', detail: 'Boot baseline.' });
    window.VoltTrackerNative.setStatus({ state: 'ready', detail: 'Scan finished.' });
    const toast = document.getElementById('statusToast');
    vi.advanceTimersByTime(3500);
    expect(toast.hidden).toBe(true);
    // The user repeats the same action later — identical detail must give
    // feedback again instead of being deduped forever.
    window.VoltTrackerNative.setStatus({ state: 'ready', detail: 'Scan finished.' });
    expect(toast.hidden).toBe(false);
    expect(toast.textContent).toContain('Scan finished.');
  });

  it('consumes the boot baseline on the first push even when its detail is empty', () => {
    // Boot-time push with no detail at all — still the baseline.
    window.VoltTrackerNative.setStatus({ state: 'ready' });
    const toast = document.getElementById('statusToast');
    expect(toast.hidden).toBe(true);
    // The NEXT detail is user-action feedback and must toast.
    window.VoltTrackerNative.setStatus({
      state: 'blocked',
      detail: 'Pick a paired or remembered OBD adapter first.',
    });
    expect(toast.hidden).toBe(false);
    expect(toast.textContent).toContain('Pick a paired');
  });

  it('stays quiet on the Settings tab where #statusCopy is visible inline', () => {
    window.VoltTrackerNative.setStatus({ state: 'ready', detail: 'Boot baseline.' });
    window.VoltDashboard.setView('settings');
    window.VoltTrackerNative.setStatus({ state: 'ready', detail: 'Selected adapter.' });
    expect(document.getElementById('statusToast').hidden).toBe(true);
  });
});
