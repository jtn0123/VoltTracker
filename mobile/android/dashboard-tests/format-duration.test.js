import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

// Regression guard: durations >= 60 min must roll into hours ("3h 24m"), not
// overflow the minutes field ("204m 00s") as they did on the Charge tab.
describe('VoltDashboard.formatDuration', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  it('formats sub-minute, sub-hour, and multi-hour spans', () => {
    const f = window.VoltDashboard.formatDuration;
    expect(f(30 * 1000)).toBe('30s');
    expect(f(90 * 1000)).toBe('1m 30s');
    expect(f(41 * 60 * 1000 + 14 * 1000)).toBe('41m 14s');
    expect(f(204 * 60 * 1000)).toBe('3h 24m');
    expect(f(180 * 60 * 1000)).toBe('3h 00m');
    expect(f(276 * 60 * 1000)).toBe('4h 36m');
  });
});
