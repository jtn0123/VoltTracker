// Follow-up to the #322 lazy-chunk seams: a failed chunk load used to be a
// SILENT no-op — every ensure*Module() caller keeps a .catch, the loader nulls
// its cached promise so the next tap retries, but the user was never told the
// first tap failed. core.ts now surfaces one debounced retry toast on chunk
// load failure. These tests pin that feedback:
//
//  1. A failed ensure*Module() shows the (urgent) retry toast.
//  2. A successful load shows nothing.
//  3. The toast is debounced — rapid failures (re-taps, chained loaders like
//     insights → charge-history) produce ONE toast, not a stack.
//  4. Feedback only: the retryable-promise semantics are untouched, so the
//     loader still recovers once the chunk comes back.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

const RETRY_TOAST = "Couldn't load this panel — tap to try again";
const DETAIL_CHUNKS = ['charge-history.js', 'maintenance-panel.js', 'dtc-detail.js'];

describe('lazy chunk load-failure toast', () => {
  let VD;
  let toasts;
  let harnessLoader;

  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    window.localStorage.clear();
    await loadDashboard({ skip: DETAIL_CHUNKS });
    VD = window.VoltDashboard;
    toasts = [];
    // telemetry.ts owns VD.showToast; core calls it late-bound through the
    // registry, so swapping the registry entry captures exactly what the user
    // would see without depending on the toast DOM.
    VD.showToast = (message, urgent) => {
      toasts.push({ message: String(message), urgent: Boolean(urgent) });
    };
    harnessLoader = window.__VoltDashboardLoadScript;
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  // Make the named chunks fail while every other load keeps going through the
  // standard harness loader.
  function failChunks(...files) {
    window.__VoltDashboardLoadScript = async (src) => {
      const file = String(src || '').replace(/^js\//, '');
      if (files.includes(file)) throw new Error(`chunk failed: ${file}`);
      return harnessLoader(src);
    };
  }

  it('a failed chunk load surfaces the urgent retry toast', async () => {
    failChunks('dtc-detail.js');
    await expect(VD.ensureDtcDetailModule()).rejects.toThrow();
    expect(toasts).toEqual([{ message: RETRY_TOAST, urgent: true }]);
  });

  it('a successful chunk load shows no toast', async () => {
    await expect(VD.ensureDtcDetailModule()).resolves.toBeDefined();
    expect(toasts).toEqual([]);
  });

  it('debounces rapid failures to one toast, then allows another after the window', async () => {
    const base = Date.now();
    const nowSpy = vi.spyOn(Date, 'now').mockReturnValue(base);
    failChunks('dtc-detail.js', 'maintenance-panel.js');

    // Two different chunks failing back-to-back (e.g. frustrated re-taps
    // across panels) must not stack identical toasts.
    await expect(VD.ensureDtcDetailModule()).rejects.toThrow();
    await expect(VD.ensureMaintenancePanelModule()).rejects.toThrow();
    expect(toasts).toHaveLength(1);

    // After the debounce window a fresh failure is toast-worthy again.
    nowSpy.mockReturnValue(base + 4001);
    await expect(VD.ensureDtcDetailModule()).rejects.toThrow();
    expect(toasts).toHaveLength(2);
    expect(toasts[1].message).toBe(RETRY_TOAST);
  });

  it('chained loaders (insights -> charge-history) reject together with a single toast', async () => {
    failChunks('charge-history.js');
    // ensureInsightsModule chains ensureChargeHistoryModule first; both catch
    // blocks run for one underlying failure — the user sees one toast.
    await expect(VD.ensureInsightsModule()).rejects.toThrow();
    expect(toasts).toHaveLength(1);
  });

  it('keeps the retryable-promise semantics: the loader recovers once the chunk is back', async () => {
    failChunks('dtc-detail.js');
    await expect(VD.ensureDtcDetailModule()).rejects.toThrow();
    expect(typeof VD.openDtcDetail).not.toBe('function');

    // The chunk becomes loadable again (transient failure cleared).
    window.__VoltDashboardLoadScript = harnessLoader;
    await expect(VD.ensureDtcDetailModule()).resolves.toBeDefined();
    expect(typeof VD.openDtcDetail).toBe('function');
    // Success added no toast beyond the failure's single one.
    expect(toasts).toHaveLength(1);
  });
});
