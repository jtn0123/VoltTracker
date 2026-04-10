/**
 * Tests for main.ts — focused on setupSectionObservers.
 *
 * Regression coverage for JTN-483: the IntersectionObserver was wired
 * to element id `battery-section`, but the template only has
 * `battery-health-section` (which is also `display:none` initially).
 * As a result `loadBatteryHealth`, `loadBatteryCells`, and
 * `loadSocAnalysis` never fired from the observer.
 *
 * The fix keys the observer on `soc-section` (always visible, sits
 * next to the battery cards), and the battery module is loaded when
 * the SOC section scrolls into view.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

// The main entry point pulls in vendor (socket.io, flatpickr) and a
// pile of CSS modules we don't care about here. Mock the heavy deps
// so the import is cheap and deterministic.
vi.mock('@/vendor', () => ({ Chart: class {} }));
vi.mock('@/ui', () => ({
  initTheme: vi.fn(),
  toggleTheme: vi.fn(),
  initDatePicker: vi.fn(),
  clearDateFilter: vi.fn(),
  toggleExportMenu: vi.fn(),
  initBottomNav: vi.fn(),
  initHeaderScroll: vi.fn(),
  initBackToTop: vi.fn(),
  initScrollHandlers: vi.fn(),
  initServiceWorker: vi.fn(),
}));
vi.mock('@/live', () => ({
  initWebSocket: vi.fn(),
  loadLiveTelemetry: vi.fn().mockResolvedValue(undefined),
  loadStatus: vi.fn().mockResolvedValue(undefined),
}));
vi.mock('@/summary', () => ({
  loadSummary: vi.fn().mockResolvedValue(undefined),
  loadMpgTrend: vi.fn(),
}));
vi.mock('@/charts', () => ({
  setupChartLazyLoading: vi.fn(),
}));
vi.mock('@/trips', () => ({
  loadTrips: vi.fn().mockResolvedValue(undefined),
  openTripModal: vi.fn(),
  closeTripModal: vi.fn(),
  deleteTrip: vi.fn(),
  setTimeframe: vi.fn(),
}));

const loadBatteryHealth = vi.fn();
const loadBatteryCells = vi.fn();
const loadSocAnalysis = vi.fn();
vi.mock('@/battery', () => ({
  loadBatteryHealth,
  loadBatteryCells,
  loadSocAnalysis,
}));

const loadChargingSummary = vi.fn();
const loadChargingHistory = vi.fn();
vi.mock('@/charging', () => ({
  loadChargingSummary,
  loadChargingHistory,
  closeChargingModal: vi.fn(),
  closeChargingDetailModal: vi.fn(),
}));

vi.mock('@/import', () => ({
  handleImport: vi.fn(),
  closeImportResultModal: vi.fn(),
  copyImportCode: vi.fn(),
  copyImportReport: vi.fn(),
  showImportStatus: vi.fn(),
}));

// Mock store so top-level subscribe calls in main.ts do nothing harmful.
vi.mock('@/store', () => ({
  state: {
    socket: null,
    liveRefreshInterval: null,
    statusRefreshInterval: null,
    tripsRefreshInterval: null,
    useWebSocket: true,
    currentTimeframe: 30,
  },
  store: {
    subscribe: vi.fn(),
    on: vi.fn(),
  },
}));

// Silence CSS imports in main.ts — vitest/jsdom can't parse them and
// they're irrelevant to the observer logic.
vi.mock('./styles/base.css', () => ({}));
vi.mock('./styles/layout.css', () => ({}));
vi.mock('./styles/cards.css', () => ({}));
vi.mock('./styles/charts.css', () => ({}));
vi.mock('./styles/trips.css', () => ({}));
vi.mock('./styles/map.css', () => ({}));
vi.mock('./styles/charging.css', () => ({}));
vi.mock('./styles/battery.css', () => ({}));
vi.mock('./styles/import.css', () => ({}));
vi.mock('./styles/nav.css', () => ({}));
vi.mock('./styles/modals.css', () => ({}));
vi.mock('./styles/forms.css', () => ({}));
vi.mock('./styles/live.css', () => ({}));
vi.mock('./styles/theme.css', () => ({}));
vi.mock('./styles/utilities.css', () => ({}));
vi.mock('./styles/skeleton.css', () => ({}));

type ObserverCallback = IntersectionObserverCallback;

/**
 * Install a fake IntersectionObserver on globalThis and return helpers
 * for inspecting + driving it from tests.
 *
 * The returned `trigger(el)` dispatches a synthetic `isIntersecting: true`
 * entry for the given element through the captured observer callback,
 * simulating the section scrolling into view. `observed` and `unobserved`
 * track the elements that setupSectionObservers started and stopped
 * watching respectively, so tests can make guardrail assertions.
 */
function installMockObserver() {
  let capturedCallback: ObserverCallback | null = null;
  const observed: Element[] = [];
  const unobserved: Element[] = [];

  globalThis.IntersectionObserver = class {
    constructor(cb: ObserverCallback) {
      capturedCallback = cb;
    }
    observe = (el: Element) => {
      observed.push(el);
    };
    unobserve = (el: Element) => {
      unobserved.push(el);
    };
    disconnect = vi.fn();
    root = null;
    rootMargin = '';
    thresholds = [0];
    takeRecords = vi.fn(() => []);
  } as unknown as typeof IntersectionObserver;

  return {
    /** Fire the captured observer callback with an isIntersecting entry for `el`. */
    trigger(el: Element) {
      if (!capturedCallback) throw new Error('observer callback not captured');
      capturedCallback(
        [
          {
            isIntersecting: true,
            target: el,
          } as IntersectionObserverEntry,
        ],
        {} as IntersectionObserver,
      );
    },
    observed,
    unobserved,
  };
}

describe('setupSectionObservers (JTN-483)', () => {
  beforeEach(() => {
    vi.resetModules();
    document.body.innerHTML = '';
    loadBatteryHealth.mockReset();
    loadBatteryCells.mockReset();
    loadSocAnalysis.mockReset();
    loadChargingSummary.mockReset();
    loadChargingHistory.mockReset();
  });

  it('exports the expected lazy section ids', async () => {
    const mod = await import('../main');
    expect(mod.LAZY_SECTION_IDS).toEqual([
      'soc-section',
      'charging-section',
      'import-section',
    ]);
    // Explicit regression: the old (broken) id must not be used.
    expect(mod.LAZY_SECTION_IDS).not.toContain('battery-section');
  });

  it('observes #soc-section when it exists in the DOM', async () => {
    const mock = installMockObserver();
    document.body.innerHTML = `
      <section id="soc-section"></section>
      <section id="charging-section"></section>
    `;

    const { setupSectionObservers } = await import('../main');
    setupSectionObservers();

    const socEl = document.getElementById('soc-section');
    const chgEl = document.getElementById('charging-section');
    expect(socEl).not.toBeNull();
    expect(mock.observed).toContain(socEl!);
    expect(mock.observed).toContain(chgEl!);
  });

  it('triggers battery loaders when #soc-section scrolls into view', async () => {
    const mock = installMockObserver();
    document.body.innerHTML = `
      <section id="soc-section"></section>
    `;

    const { setupSectionObservers } = await import('../main');
    setupSectionObservers();

    const socEl = document.getElementById('soc-section')!;
    mock.trigger(socEl);

    // Use vi.waitFor so the test tolerates any number of microtask flushes
    // required by the dynamic `import('@/battery')` + the `.then()` chain
    // inside the observer callback. Two `setTimeout(0)`s happened to work
    // but are brittle if another async layer is added later.
    await vi.waitFor(() => {
      expect(loadBatteryHealth).toHaveBeenCalledTimes(1);
      expect(loadBatteryCells).toHaveBeenCalledTimes(1);
      expect(loadSocAnalysis).toHaveBeenCalledTimes(1);
    });
    // Guardrail: observer should stop watching after firing once.
    expect(mock.unobserved).toContain(socEl);
  });

  it('does not observe the legacy #battery-section id', async () => {
    const mock = installMockObserver();
    document.body.innerHTML = `
      <section id="battery-section"></section>
      <section id="battery-health-section" style="display:none"></section>
    `;

    const { setupSectionObservers } = await import('../main');
    setupSectionObservers();

    // Neither the legacy id nor the display:none element should be watched;
    // both would silently break lazy loading (JTN-483).
    expect(mock.observed).not.toContain(document.getElementById('battery-section')!);
    expect(mock.observed).not.toContain(document.getElementById('battery-health-section')!);
  });

  // ── JTN-492 regression coverage ─────────────────────────────────────────
  // The template at `receiver/templates/index.html` used to render the
  // import section as `<section class="import-section">` with no `id`,
  // so `document.getElementById('import-section')` returned null and the
  // observer silently skipped the import module preload. These tests pin
  // the fix (real id on the element) and the new guardrail warning.

  it('observes #import-section when it exists in the DOM (JTN-492)', async () => {
    const mock = installMockObserver();
    document.body.innerHTML = `
      <section id="soc-section"></section>
      <section id="charging-section"></section>
      <section id="import-section" class="import-section"></section>
    `;

    const { setupSectionObservers } = await import('../main');
    setupSectionObservers();

    const importEl = document.getElementById('import-section');
    expect(importEl).not.toBeNull();
    expect(mock.observed).toContain(importEl!);
  });

  it('warns when a lazy section id has no matching element (JTN-492)', async () => {
    installMockObserver();
    // Only render soc-section — charging-section and import-section are
    // intentionally missing so the guardrail should warn for each one.
    document.body.innerHTML = `
      <section id="soc-section"></section>
    `;
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    const { setupSectionObservers } = await import('../main');
    setupSectionObservers();

    const warned = warnSpy.mock.calls.map((args) => String(args[0]));
    expect(warned.some((msg) => msg.includes('charging-section'))).toBe(true);
    expect(warned.some((msg) => msg.includes('import-section'))).toBe(true);
    expect(warned.every((msg) => !msg.includes('"soc-section"'))).toBe(true);

    warnSpy.mockRestore();
  });

  it('observes every id in LAZY_SECTION_IDS when all are present in the DOM (JTN-492)', async () => {
    // Render a DOM containing every id declared in LAZY_SECTION_IDS and
    // assert the observer starts watching each one. This is the guardrail
    // that pins the import-section fix and would catch a future drift
    // where a new id is added to the constant but not to the template.
    const mock = installMockObserver();
    const mod = await import('../main');

    document.body.innerHTML = mod.LAZY_SECTION_IDS
      .map((id) => `<section id="${id}"></section>`)
      .join('\n');

    mod.setupSectionObservers();

    for (const id of mod.LAZY_SECTION_IDS) {
      const el = document.getElementById(id);
      expect(el, `fixture missing #${id}`).not.toBeNull();
      expect(mock.observed).toContain(el!);
    }
  });
});
