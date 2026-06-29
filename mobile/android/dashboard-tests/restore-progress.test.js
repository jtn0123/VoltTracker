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
        phase: 'Reading backup',
        bytesDone: 1024 * 1024,
        bytesTotal: 4 * 1024 * 1024,
        percent: 25,
        etaSeconds: 90,
      }),
    );

    const overlay = document.getElementById('restoreProgress');
    expect(overlay.hidden).toBe(false);
    expect(overlay.dataset.busy).toBe('true');
    expect(overlay.dataset.tone).toBe('busy');
    expect(overlay.dataset.progress).toBe('determinate');
    expect(document.body.dataset.restoreBusy).toBe('true');
    expect(document.getElementById('restoreProgressTitle').textContent).toBe('Reading backup');
    expect(document.getElementById('restoreProgressKicker').textContent).toBe('Restore');
    expect(document.getElementById('restoreProgressPhase').textContent).toBe('Reading backup');
    expect(document.getElementById('restoreProgressPercent').textContent).toBe('25%');
    expect(document.getElementById('restoreProgressEta').textContent).toBe('ETA 2m');
    expect(document.getElementById('restoreProgressStats').textContent).toBe('1.0 MB of 4.0 MB');
    expect(document.getElementById('restoreProgressMeter').getAttribute('aria-valuenow')).toBe('25');
    expect(document.getElementById('restoreProgressMeter').getAttribute('aria-label')).toBe('Restore progress');
    expect(document.getElementById('restoreProgressFill').style.width).toBe('25%');
    expect(document.getElementById('restoreProgressDetail').textContent).toContain('minute');
    expect(document.getElementById('restoreProgressClose').hidden).toBe(true);
  });

  it('uses explicit progress operation before localized title/detail text', () => {
    window.VoltTrackerNative.setRestoreProgress({
      visible: true,
      busy: true,
      operation: 'backup',
      title: 'Reading backup',
      detail: 'Restore copy changed by translation.',
      tone: 'busy',
    });

    expect(document.getElementById('restoreProgressKicker').textContent).toBe('Backup');
    expect(document.getElementById('restoreProgressMeter').getAttribute('aria-label')).toBe('Backup progress');
  });

  it('renders merge row progress without byte totals', () => {
    window.VoltTrackerNative.setRestoreProgress({
      visible: true,
      busy: true,
      title: 'Merging backup',
      detail: 'Adding backup rows and matching existing sessions.',
      tone: 'busy',
      phase: 'Merging map samples',
      rowsDone: 1000,
      rowsTotal: 5000,
      etaSeconds: 12,
    });

    const overlay = document.getElementById('restoreProgress');
    expect(overlay.dataset.progress).toBe('determinate');
    expect(document.getElementById('restoreProgressPhase').textContent).toBe('Merging map samples');
    expect(document.getElementById('restoreProgressPercent').textContent).toBe('20%');
    // Counts are formatted with the runtime locale's grouping separator (no hardcoded
    // en-US), so derive the expectation the same way to stay locale-robust on any host.
    const fmt = (n) => n.toLocaleString(undefined);
    expect(document.getElementById('restoreProgressStats').textContent).toBe(
      `${fmt(1000)} of ${fmt(5000)} rows`,
    );
    expect(document.getElementById('restoreProgressEta').textContent).toBe('ETA 12s');
  });

  it('keeps the meter indeterminate when percent is the unknown sentinel', () => {
    window.VoltTrackerNative.setRestoreProgress({
      visible: true,
      busy: true,
      title: 'Reading backup',
      tone: 'busy',
      percent: -1,
    });

    const overlay = document.getElementById('restoreProgress');
    expect(overlay.dataset.progress).toBe('indeterminate');
    expect(document.getElementById('restoreProgressPercent').textContent).toBe('--');
    expect(document.getElementById('restoreProgressMeter').getAttribute('aria-valuenow')).toBeNull();
    expect(document.getElementById('restoreProgressFill').style.width).toBe('');
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
