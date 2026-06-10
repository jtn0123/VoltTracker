// Status-toast contract: status.detail lands in #statusCopy, which lives on
// the Settings tab. For taps made on other tabs, telemetry.ts mirrors NEW
// detail strings into the #statusToast aria-live region so the feedback is
// visible where the user actually is. Rules pinned here:
//  - the first (boot-time) status push sets the baseline silently;
//  - later detail changes toast on non-Settings tabs and auto-hide;
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

  it('does not repeat a toast for an unchanged detail string', () => {
    window.VoltTrackerNative.setStatus({ state: 'ready', detail: 'Boot baseline.' });
    window.VoltTrackerNative.setStatus({ state: 'ready', detail: 'Scan finished.' });
    const toast = document.getElementById('statusToast');
    vi.advanceTimersByTime(3500);
    expect(toast.hidden).toBe(true);
    // Same detail again — stays hidden instead of re-toasting.
    window.VoltTrackerNative.setStatus({ state: 'ready', detail: 'Scan finished.' });
    expect(toast.hidden).toBe(true);
  });

  it('stays quiet on the Settings tab where #statusCopy is visible inline', () => {
    window.VoltTrackerNative.setStatus({ state: 'ready', detail: 'Boot baseline.' });
    window.VoltDashboard.setView('settings');
    window.VoltTrackerNative.setStatus({ state: 'ready', detail: 'Selected adapter.' });
    expect(document.getElementById('statusToast').hidden).toBe(true);
  });
});
