import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

describe('restore progress overlay', () => {
  beforeEach(async () => {
    vi.useRealTimers();
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows a non-dismissible modal while native restore work is busy', () => {
    window.VoltTrackerNative.setRestoreProgress(
      JSON.stringify({
        visible: true,
        busy: true,
        title: 'Reading backup',
        detail: 'Large backup files can take a minute.',
        tone: 'busy',
      }),
    );

    const overlay = document.getElementById('restoreProgress');
    expect(overlay.hidden).toBe(false);
    expect(overlay.dataset.busy).toBe('true');
    expect(overlay.dataset.tone).toBe('busy');
    expect(document.body.dataset.restoreBusy).toBe('true');
    expect(document.getElementById('restoreProgressTitle').textContent).toBe('Reading backup');
    expect(document.getElementById('restoreProgressDetail').textContent).toContain('minute');
    expect(document.getElementById('restoreProgressClose').hidden).toBe(true);
  });

  it('keeps failed restore feedback visible until the user dismisses it', () => {
    window.VoltTrackerNative.setRestoreProgress({
      visible: true,
      busy: false,
      title: 'Restore failed',
      detail: 'Restore failed - that file is not a valid Volt Tracker backup.',
      tone: 'blocked',
    });

    const overlay = document.getElementById('restoreProgress');
    const close = document.getElementById('restoreProgressClose');
    expect(overlay.hidden).toBe(false);
    expect(overlay.dataset.busy).toBe('false');
    expect(overlay.dataset.tone).toBe('blocked');
    expect(close.hidden).toBe(false);

    close.click();

    expect(overlay.hidden).toBe(true);
  });

  it('briefly confirms success, then clears the modal automatically', () => {
    vi.useFakeTimers();
    window.VoltTrackerNative.setRestoreProgress({
      visible: true,
      busy: false,
      title: 'Restore complete',
      detail: 'Backup restored.',
      tone: 'ok',
    });

    const overlay = document.getElementById('restoreProgress');
    expect(overlay.hidden).toBe(false);
    expect(overlay.dataset.tone).toBe('ok');

    vi.advanceTimersByTime(2200);

    expect(overlay.hidden).toBe(true);
  });
});
