// prefs.ts — persisted display-layer preference store. Pure side-effecting module
// that attaches VD.prefs onto window.VoltDashboard, backed by localStorage.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// Re-executes prefs.ts fresh (resetModules) so the module-scoped listener map is
// clean per call. Storage is only cleared when asked, so persistence can be tested
// across two loads. The import specifier must be a static literal for vite to
// resolve it relative to this file.
async function loadPrefs({ clear = true } = {}) {
  if (clear) window.localStorage.clear();
  // prefs.ts boots its UI at most once per document (C1 lazy-chunk guard);
  // this helper re-executes the module against a rebuilt DOM, so reset the
  // once-flag or the fresh elements would never get their listeners.
  delete document.__voltPrefsUiBooted;
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

  it('warns once and surfaces a toast notice when localStorage is unavailable', async () => {
    // Some WebView configurations throw on any localStorage access. The store
    // must fall back to in-memory silently for FUNCTIONALITY, but loudly for
    // VISIBILITY: one console.warn plus a once-per-session #statusToast notice
    // (prefs loads before the telemetry toast plumbing, so it drives the node
    // directly on the next tick).
    const descriptor = Object.getOwnPropertyDescriptor(window, 'localStorage');
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      get() {
        throw new Error('localStorage disabled');
      },
    });
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.useFakeTimers();
    try {
      document.body.innerHTML = '<div id="statusToast" hidden></div>';
      const prefs = await loadPrefs({ clear: false });

      // In-memory fallback still round-trips values.
      prefs.set('units', 'metric');
      expect(prefs.get('units', 'imperial')).toBe('metric');

      // Exactly one fallback warning despite repeated access.
      prefs.set('pricePerKwh', 0.18);
      prefs.get('pricePerKwh', 0);
      const fallbackWarns = warn.mock.calls.filter(([msg]) =>
        String(msg).includes('localStorage is unavailable'));
      expect(fallbackWarns).toHaveLength(1);

      // The deferred toast notice appears, then auto-hides.
      vi.advanceTimersByTime(1);
      const toast = document.getElementById('statusToast');
      expect(toast.hidden).toBe(false);
      expect(toast.textContent).toContain("Preferences can't be saved");
      // A degraded-storage warning interrupts the screen reader assertively.
      expect(toast.getAttribute('aria-live')).toBe('assertive');
      vi.advanceTimersByTime(6500);
      expect(toast.hidden).toBe(true);
    } finally {
      vi.useRealTimers();
      warn.mockRestore();
      if (descriptor) Object.defineProperty(window, 'localStorage', descriptor);
      // jsdom may expose localStorage via the prototype: with no own descriptor
      // to restore, the injected throwing getter must be removed explicitly.
      else delete window.localStorage;
    }
  });

  it('does not auto-dismiss a newer status that reused the toast node', async () => {
    const descriptor = Object.getOwnPropertyDescriptor(window, 'localStorage');
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      get() {
        throw new Error('localStorage disabled');
      },
    });
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.useFakeTimers();
    try {
      document.body.innerHTML = '<div id="statusToast" hidden></div>';
      await loadPrefs({ clear: false });

      vi.advanceTimersByTime(1);
      const toast = document.getElementById('statusToast');
      expect(toast.hidden).toBe(false);
      expect(toast.textContent).toContain("Preferences can't be saved");

      // A newer status reuses the shared #statusToast node before the 6s hide
      // timer fires; the prefs notice timer must leave it alone.
      toast.textContent = 'Connected to OBD adapter.';
      vi.advanceTimersByTime(6500);
      expect(toast.hidden).toBe(false);
      expect(toast.textContent).toBe('Connected to OBD adapter.');
    } finally {
      vi.useRealTimers();
      warn.mockRestore();
      if (descriptor) Object.defineProperty(window, 'localStorage', descriptor);
      // jsdom may expose localStorage via the prototype: with no own descriptor
      // to restore, the injected throwing getter must be removed explicitly.
      else delete window.localStorage;
    }
  });

  it('C8: clamps the price fields to realistic ceilings (2 $/kWh, 10 $/gal) on commit', async () => {
    // Minimal pref-price markup so bootPrefsUi binds the numeric prefs.
    document.body.innerHTML = `
      <label><input id="pricePerKwhInput" type="number" /></label>
      <label><input id="publicPricePerKwhInput" type="number" /></label>
      <label><input id="gasPricePerGalInput" type="number" /></label>
    `;
    const prefs = await loadPrefs();

    // "25" is a classic mis-entry of $0.25/kWh — the old 100 ceiling let it
    // straight through and poisoned every cost estimate by ~100x.
    const kwh = document.getElementById('pricePerKwhInput');
    kwh.value = '25';
    kwh.dispatchEvent(new Event('change', { bubbles: true }));
    expect(kwh.value).toBe('2');
    expect(prefs.get('pricePerKwh', 0)).toBe(2);
    // The silent rewrite is flagged for screen readers + the transient hint.
    expect(kwh.getAttribute('aria-invalid')).toBe('true');

    const gas = document.getElementById('gasPricePerGalInput');
    gas.value = '18';
    gas.dispatchEvent(new Event('change', { bubbles: true }));
    expect(gas.value).toBe('10');
    expect(prefs.get('gasPricePerGal', 0)).toBe(10);

    // Ordinary values still commit untouched.
    kwh.value = '0.25';
    kwh.dispatchEvent(new Event('change', { bubbles: true }));
    expect(prefs.get('pricePerKwh', 0)).toBe(0.25);
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
