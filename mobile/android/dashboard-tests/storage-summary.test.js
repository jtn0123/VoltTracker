// storage-status.ts — updateStorageUi counter edge cases.
import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

describe('storage summary raw-telemetry counter', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  function rawCount() {
    return document.getElementById('dbRawTelemetryCount').textContent;
  }

  it('renders a legitimate rawTelemetryCount of 0 instead of falling back to sampleCount', () => {
    window.VoltDashboard.setStorage({ sampleCount: 50, rawTelemetryCount: 0 });
    expect(rawCount()).toBe('0');
  });

  it('falls back to sampleCount only when rawTelemetryCount is missing', () => {
    window.VoltDashboard.setStorage({ sampleCount: 50 });
    expect(rawCount()).toBe('50');

    window.VoltDashboard.setStorage({ sampleCount: 50, rawTelemetryCount: null });
    expect(rawCount()).toBe('50');

    window.VoltDashboard.setStorage({ sampleCount: 50, rawTelemetryCount: 7 });
    expect(rawCount()).toBe('7');
  });
});
