import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

describe('daily-use quality-of-life controls', () => {
  beforeEach(async () => {
    window.localStorage.clear();
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    window.localStorage.clear();
  });

  it('Drive Focus is reversible and preserves the detailed tile selection', () => {
    const tile = (key) => document.querySelector(`[data-tile-key="${key}"]`);

    document.querySelector('[data-drive-preset="focus"]').click();
    expect(document.documentElement.dataset.drivePreset).toBe('focus');
    expect(tile('rpm').hidden).toBe(false);
    expect(tile('aux12v').hidden).toBe(false);
    expect(tile('gps').hidden).toBe(false);
    expect(tile('coolant').hidden).toBe(true);
    expect(tile('throttle').hidden).toBe(true);
    expect(tile('load').hidden).toBe(true);

    document.querySelector('[data-drive-preset="detailed"]').click();
    expect(document.documentElement.dataset.drivePreset).toBe('detailed');
    expect([...document.querySelectorAll('[data-tile-key]')].every((node) => !node.hidden)).toBe(true);
  });

  it('defaults live telemetry to quiet and lets the user opt into announcements', () => {
    const regions = [...document.querySelectorAll('[data-live-telemetry]')];
    const toggle = document.querySelector('[data-pref-quiet-telemetry]');
    expect(regions.length).toBeGreaterThan(2);
    expect(regions.every((node) => node.getAttribute('aria-live') === 'off')).toBe(true);
    expect(toggle.getAttribute('aria-pressed')).toBe('true');

    toggle.click();
    expect(regions.every((node) => node.getAttribute('aria-live') === 'polite')).toBe(true);
    expect(toggle.getAttribute('aria-pressed')).toBe('false');
  });

  it('keeps expert diagnostics collapsed until Advanced is selected', async () => {
    const VD = window.VoltDashboard;
    const expert = [...document.querySelectorAll('[data-diagnostics-expert]')];
    const ensureSignals = vi.fn(() => Promise.resolve(VD));
    VD.ensureSignalsModule = ensureSignals;

    expect(expert.length).toBeGreaterThan(2);
    expect(expert.every((node) => node.hidden)).toBe(true);
    VD.prefs.set('diagnosticsMode', 'advanced');
    VD.applyDiagnosticsMode();
    await Promise.resolve();

    expect(expert.every((node) => !node.hidden)).toBe(true);
    expect(ensureSignals).toHaveBeenCalledTimes(1);
    expect(document.getElementById('diagnosticsModeHint').textContent).toMatch(/Live signals/i);
  });

  it('Settings section shortcuts scroll to and focus the requested section', () => {
    vi.useFakeTimers();
    window.VoltDashboard.setView('settings');
    const section = document.getElementById('settingsData');
    section.scrollIntoView = vi.fn();

    window.VoltDashboard.scrollToSettingsSection('settingsData');
    expect(section.scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' });
    vi.advanceTimersByTime(220);
    expect(document.activeElement).toBe(section);
  });

  it('Charge starts with one useful empty card instead of empty analytical chrome', () => {
    expect(document.getElementById('chargeEmptyState').hidden).toBe(false);
    expect(document.querySelector('#chargeEmptyState summary').textContent).toMatch(/What will appear/i);
    expect(document.getElementById('chargeSummaryGrid').hidden).toBe(true);
    expect(document.getElementById('cellBalanceCard').hidden).toBe(true);
    expect(document.getElementById('sohTrendCard').hidden).toBe(true);
  });

  it('saves common charge targets with one tap and keeps both inputs synced', () => {
    document.querySelector('#view-preferences [data-charge-target="80"]').click();
    expect(window.VoltDashboard.prefs.get('chargeTargetSoc', 0)).toBe(80);
    expect(document.getElementById('prefChargeTargetInput').value).toBe('80');
    expect(document.getElementById('liveChargeTargetInput').value).toBe('80');
    expect([...document.querySelectorAll('[data-charge-target="80"]')].every((button) => button.getAttribute('aria-pressed') === 'true')).toBe(true);
  });

  it('shows a durable last-backup receipt from the native callback', () => {
    const at = new Date('2026-07-10T15:30:00Z').getTime();
    window.VoltTrackerNative.setBackupReceipt(JSON.stringify({ lastBackupAtMs: at, lastBackupTrips: 42 }));
    const receipt = document.getElementById('lastBackupReceipt');
    expect(receipt.textContent).toMatch(/Backup prepared/);
    expect(receipt.textContent).toMatch(/42 drives/);
    expect(receipt.dataset.state).toBe('saved');
  });

  it('clears authoritative telemetry fields that disappear from a newer sample', () => {
    window.VoltTrackerNative.updateTelemetry(JSON.stringify({
      source: 'obd',
      updatedAt: Date.now(),
      sampleCount: 10,
      speedKph: 112,
      soc: 74,
      powerKw: 18,
    }));
    expect(window.VoltDashboard.state.telemetry.speedKph).toBe(112);

    window.VoltTrackerNative.updateTelemetry(JSON.stringify({
      source: 'obd',
      updatedAt: Date.now() + 1,
      sampleCount: 11,
      soc: 73.9,
    }));

    expect(window.VoltDashboard.state.telemetry.speedKph).toBeNull();
    expect(window.VoltDashboard.state.telemetry.powerKw).toBeNull();
    expect(window.VoltDashboard.state.telemetry.soc).toBe(73.9);
  });

  it('collapses first-run placeholder instruments behind an explanatory disclosure', () => {
    window.VoltDashboard.renderDriveSourceBadge();
    const drive = document.querySelector('[data-view="drive"]');
    expect(drive.classList.contains('is-prelive')).toBe(true);
    expect(document.querySelector('.drive-empty-preview summary').textContent).toMatch(/What will appear/i);
  });

  it('remembers the preferred historical map layer and detail disclosure in backups', async () => {
    await window.VoltDashboard.ensureMapModule();
    window.VoltDashboard.setView('map');
    document.querySelector('[data-map-layer="heat"]').click();
    expect(window.VoltDashboard.prefs.get('mapLayer', '')).toBe('heat');

    document.getElementById('scrubToggle').click();
    expect(window.VoltDashboard.prefs.get('mapDetailsExpanded', false)).toBe(true);
    const backup = JSON.parse(window.VoltDashboard.prefs.exportForBackup());
    expect(backup.preferences.mapLayer).toBe('heat');
    expect(backup.preferences.mapDetailsExpanded).toBe(true);
  });
});
