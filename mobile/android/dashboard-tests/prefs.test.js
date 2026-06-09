// prefs.ts — persisted display-layer preference store. Pure side-effecting module
// that attaches VD.prefs onto window.VoltDashboard, backed by localStorage.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// Re-executes prefs.ts fresh (resetModules) so the module-scoped listener map is
// clean per call. Storage is only cleared when asked, so persistence can be tested
// across two loads. The import specifier must be a static literal for vite to
// resolve it relative to this file.
async function loadPrefs({ clear = true } = {}) {
  if (clear) window.localStorage.clear();
  vi.resetModules();
  await import('../app/src/main/dashboard-src/js/prefs.ts');
  return window.VoltDashboard.prefs;
}

describe('prefs store', () => {
  beforeEach(() => window.localStorage.clear());
  afterEach(() => {
    window.localStorage.clear();
    document.body.innerHTML = '';
  });

  it('returns the fallback for an unset key', async () => {
    const prefs = await loadPrefs();
    expect(prefs.get('units', 'imperial')).toBe('imperial');
    expect(prefs.get('missing', 42)).toBe(42);
  });

  it('persists and reads back JSON-typed values across a fresh load', async () => {
    const prefs = await loadPrefs();
    prefs.set('units', 'metric');
    prefs.set('pricePerKwh', 0.16);
    prefs.set('autoStart', true);
    prefs.set('tiles', ['rpm', 'soc']);
    expect(prefs.get('units', 'imperial')).toBe('metric');
    expect(prefs.get('pricePerKwh', 0)).toBe(0.16);
    expect(prefs.get('autoStart', false)).toBe(true);
    expect(prefs.get('tiles', [])).toEqual(['rpm', 'soc']);

    const reloaded = await loadPrefs({ clear: false });
    expect(reloaded.get('units', 'imperial')).toBe('metric'); // true persistence
  });

  it('notifies key + wildcard subscribers on set, and stops after unsubscribe', async () => {
    const prefs = await loadPrefs();
    const seen = [];
    const wild = [];
    const off = prefs.subscribe('units', (v) => seen.push(v));
    prefs.subscribe('*', (v) => wild.push(v));
    prefs.set('units', 'metric');
    expect(seen).toEqual(['metric']);
    expect(wild).toEqual(['metric']);
    off();
    prefs.set('units', 'imperial');
    expect(seen).toEqual(['metric']); // unsubscribed
    expect(wild).toEqual(['metric', 'imperial']);
  });

  it('falls back to the default when stored JSON is corrupt', async () => {
    window.localStorage.setItem('vt.pref.units', '{not json');
    const prefs = await loadPrefs({ clear: false });
    expect(prefs.get('units', 'imperial')).toBe('imperial');
  });

  it('gives each Drive tile visibility toggle a unique accessible name', async () => {
    document.body.innerHTML = '<div id="liveReadout"></div><div id="driveTilesEditor"></div>';
    await loadPrefs();

    const toggles = Array.from(document.querySelectorAll('.tile-toggle'));
    const labels = toggles.map((button) => button.getAttribute('aria-label'));

    expect(toggles.length).toBeGreaterThan(1);
    expect(new Set(labels).size).toBe(labels.length);
    expect(labels).toContain('RPM is shown in Drive live readout');
    expect(labels).toContain('GPS is shown in Drive live readout');
    expect(toggles.map((button) => button.textContent)).toEqual(expect.arrayContaining(['Shown']));
  });
});
