/**
 * VoltTracker - Main Entry Point
 * Lazy loading, store subscriptions, and app initialization
 */

// ── Global Error Boundary ────────────────────────────────────────────────────
// Catches unhandled errors and promise rejections, reports to backend,
// and shows a fallback UI instead of a blank screen.
function reportErrorToBackend(payload: Record<string, unknown>): void {
  try {
    navigator.sendBeacon(
      '/api/errors/report',
      new Blob([JSON.stringify(payload)], { type: 'application/json' }),
    );
  } catch {
    // sendBeacon not available or failed; swallow silently
  }
}

window.onerror = (message, source, lineno, colno, error) => {
  console.error('[VoltTracker] Uncaught error:', message, source, lineno);
  reportErrorToBackend({
    message: String(message),
    source,
    lineno,
    colno,
    stack: error?.stack ?? '',
    url: window.location.href,
    userAgent: navigator.userAgent,
  });
  showFallbackUI(String(message));
  return false; // Let the default handler run too
};

window.onunhandledrejection = (event: PromiseRejectionEvent) => {
  const reason = event.reason;
  const message = reason instanceof Error ? reason.message : String(reason);
  console.error('[VoltTracker] Unhandled rejection:', message);
  reportErrorToBackend({
    message: `Unhandled Promise: ${message}`,
    stack: reason instanceof Error ? reason.stack ?? '' : '',
    url: window.location.href,
    userAgent: navigator.userAgent,
  });
};

function showFallbackUI(errorMsg: string): void {
  // Only show fallback if the page appears broken (no main content rendered)
  const dashboard = document.getElementById('dashboard-content') || document.querySelector('main');
  if (dashboard && dashboard.children.length > 0) return; // Content exists, don't clobber it

  const fallback = document.getElementById('error-fallback');
  if (fallback) {
    fallback.style.display = 'block';
    const detail = fallback.querySelector('.error-detail');
    if (detail) detail.textContent = errorMsg;
    return;
  }

  // Create an error banner at the top of the page instead of replacing the DOM
  const banner = document.createElement('div');
  banner.id = 'error-fallback';
  banner.setAttribute('role', 'alert');
  banner.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:9999;background:#dc2626;color:#fff;padding:1rem 2rem;font-family:system-ui,sans-serif;display:flex;align-items:center;justify-content:space-between;gap:1rem;box-shadow:0 2px 8px rgba(0,0,0,0.3)';
  banner.innerHTML = `
    <div style="flex:1">
      <strong>Something went wrong</strong>
      <span class="error-detail" style="margin-left:0.5rem;opacity:0.9">${errorMsg.split('<').join('&lt;')}</span>
    </div>
    <button onclick="location.reload()" style="padding:.4rem 1rem;border-radius:6px;border:1px solid rgba(255,255,255,0.3);background:transparent;color:#fff;cursor:pointer;white-space:nowrap">
      Reload
    </button>
    <button onclick="this.parentElement.remove()" style="padding:.4rem .6rem;border:none;background:transparent;color:#fff;cursor:pointer;font-size:1.2rem" aria-label="Dismiss">&times;</button>
  `;
  document.body.prepend(banner);
}

// ── Vendor dependencies (bundled via npm, replaces CDN) ──────────────────────
import './vendor';

// CSS modules — Vite bundles these into the output
import './styles/base.css';
import './styles/layout.css';
import './styles/cards.css';
import './styles/charts.css';
import './styles/trips.css';
import './styles/map.css';
import './styles/charging.css';
import './styles/battery.css';
import './styles/import.css';
import './styles/nav.css';
import './styles/modals.css';
import './styles/forms.css';
import './styles/live.css';
import './styles/theme.css';
import './styles/utilities.css';
import './styles/skeleton.css';

// ── Critical (above-the-fold) imports ────────────────────────────────────────
import { state, store } from '@/store';
import {
  initTheme,
  toggleTheme,
  initDatePicker,
  clearDateFilter,
  toggleExportMenu,
  initBottomNav,
  initHeaderScroll,
  initBackToTop,
  initScrollHandlers,
  initServiceWorker,
} from '@/ui';
import { initWebSocket, loadLiveTelemetry, loadStatus } from '@/live';
import { loadSummary, loadMpgTrend } from '@/summary';
import { setupChartLazyLoading } from '@/charts';
import { loadTrips, openTripModal, closeTripModal, deleteTrip, setTimeframe } from '@/trips';

// ── Lazy-loaded modules (below the fold / on demand) ─────────────────────────
const loadBatteryModule = () => import('@/battery');
const loadChargingModule = () => import('@/charging');
const loadImportModule = () => import('@/import');

// Cache loaded modules to avoid re-importing
let batteryMod: Awaited<ReturnType<typeof loadBatteryModule>> | null = null;
let chargingMod: Awaited<ReturnType<typeof loadChargingModule>> | null = null;
let importMod: Awaited<ReturnType<typeof loadImportModule>> | null = null;

async function getBattery() {
  batteryMod ??= await loadBatteryModule();
  return batteryMod;
}
async function getCharging() {
  chargingMod ??= await loadChargingModule();
  return chargingMod;
}
async function getImport() {
  importMod ??= await loadImportModule();
  return importMod;
}

// ── Store subscriptions (reactive state → UI) ───────────────────────────────

// 1. Connection status → UI indicator
store.subscribe('connectionStatus', (status) => {
  const statusDot = document.getElementById('status-dot');
  const lastSync = document.getElementById('last-sync');
  if (statusDot) {
    statusDot.classList.remove('offline', 'live');
    if (status === 'live' || status === 'connected') statusDot.classList.add('live');
    else if (status === 'disconnected') statusDot.classList.add('offline');
  }
  if (lastSync && status === 'live') lastSync.textContent = 'Live';
});

// 2. Theme changes → update charts
store.subscribe('theme', (newTheme) => {
  document.documentElement.dataset.theme = newTheme;
  localStorage.setItem('theme', newTheme);
  // Re-render charts if they exist (theme colors changed)
  if (state.mpgChart) state.mpgChart.update();
  if (state.socChart) state.socChart.update();
});

// 3. Timeframe changes → reload trend data
store.subscribe('currentTimeframe', (days) => {
  loadMpgTrend(days);
});

// 4. Live telemetry updates via custom event
store.on('telemetry:update', () => {
  // Dashboard live section is updated inline by the handler in live.ts
  // Additional reactions can be wired here
});

// ── Service Worker Recovery ──────────────────────────────────────────────────
(function () {
  const params = new URLSearchParams(window.location.search);
  if (params.get('clear-sw') === '1' && 'serviceWorker' in navigator) {
    navigator.serviceWorker.getRegistrations().then((registrations) => {
      registrations.forEach((reg) => reg.unregister());
      console.log('[SW Recovery] Unregistered all service workers');
      params.delete('clear-sw');
      const newUrl = window.location.pathname + (params.toString() ? '?' + params.toString() : '');
      window.location.replace(newUrl);
    });
  }
})();

// ── Window function exposure (for inline HTML onclick handlers) ──────────────
// Critical modules — available immediately
globalThis.openTripModal = openTripModal;
globalThis.closeTripModal = closeTripModal;
globalThis.deleteTrip = deleteTrip;
globalThis.setTimeframe = setTimeframe;
globalThis.toggleTheme = toggleTheme;
globalThis.clearDateFilter = clearDateFilter;
globalThis.toggleExportMenu = toggleExportMenu;

// Lazy-loaded modules — thunks that import on first call
globalThis.openAddChargingModal = async () => {
  (await getCharging()).openAddChargingModal();
};
globalThis.closeChargingModal = async () => {
  (await getCharging()).closeChargingModal();
};
globalThis.submitChargingSession = async (e: Event) => {
  (await getCharging()).submitChargingSession(e);
};
globalThis.deleteChargingSession = async (id: number) => {
  (await getCharging()).deleteChargingSession(id);
};
globalThis.openChargingDetailModal = async (id: number) => {
  (await getCharging()).openChargingDetailModal(id);
};
globalThis.closeChargingDetailModal = async () => {
  (await getCharging()).closeChargingDetailModal();
};
globalThis.handleImport = async (e: Event) => {
  (await getImport()).handleImport(e);
};
globalThis.closeImportResultModal = async () => {
  (await getImport()).closeImportResultModal();
};
globalThis.copyImportCode = async () => {
  (await getImport()).copyImportCode();
};
globalThis.copyImportReport = async () => {
  (await getImport()).copyImportReport();
};

// ── Cleanup ──────────────────────────────────────────────────────────────────
window.addEventListener('beforeunload', () => {
  if (state.liveRefreshInterval) clearInterval(state.liveRefreshInterval);
  if (state.statusRefreshInterval) clearInterval(state.statusRefreshInterval);
  if (state.tripsRefreshInterval) clearInterval(state.tripsRefreshInterval);
  if (state.socket) state.socket.disconnect();
});

// Close export menu when clicking outside
document.addEventListener('click', (event) => {
  const menu = document.getElementById('export-menu');
  const dropdown = document.querySelector('.export-dropdown');
  const btn = document.getElementById('export-btn');
  if (menu && dropdown && !dropdown.contains(event.target as Node)) {
    menu.classList.remove('show');
    if (btn) btn.setAttribute('aria-expanded', 'false');
  }
});

// Close modals on escape key
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape') {
    closeTripModal();
    // Lazy modules — only call if already loaded
    chargingMod?.closeChargingModal();
    chargingMod?.closeChargingDetailModal();

    const menu = document.getElementById('export-menu');
    const btn = document.getElementById('export-btn');
    if (menu?.classList.contains('show')) {
      menu.classList.remove('show');
      if (btn) {
        btn.setAttribute('aria-expanded', 'false');
        btn.focus();
      }
    }
  }
});

// ── Lazy section loading with IntersectionObserver ───────────────────────────
/**
 * Show a skeleton/spinner overlay inside a section while its module loads.
 */
function showSectionSkeleton(section: HTMLElement): HTMLElement {
  const skeleton = document.createElement('div');
  skeleton.className = 'section-skeleton-overlay';
  skeleton.setAttribute('role', 'status');
  skeleton.setAttribute('aria-label', 'Loading section');
  skeleton.innerHTML = `
    <div class="section-skeleton-content">
      <div class="spinner" aria-hidden="true"></div>
      <span class="section-skeleton-text">Loading...</span>
    </div>
  `;
  section.style.position = 'relative';
  section.appendChild(skeleton);
  return skeleton;
}

function removeSectionSkeleton(skeleton: HTMLElement): void {
  skeleton.classList.add('section-skeleton-fade-out');
  setTimeout(() => skeleton.remove(), 300);
}

function setupSectionObservers(): void {
  const sectionMap: Record<string, () => Promise<void>> = {
    'battery-section': async () => {
      const mod = await getBattery();
      mod.loadBatteryHealth();
      mod.loadBatteryCells();
      mod.loadSocAnalysis();
    },
    'charging-section': async () => {
      const mod = await getCharging();
      mod.loadChargingSummary();
      mod.loadChargingHistory();
    },
    'import-section': async () => {
      await getImport(); // pre-load the module
    },
  };

  const loaded = new Set<string>();
  const observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        const id = entry.target.id;
        if (entry.isIntersecting && !loaded.has(id) && sectionMap[id]) {
          loaded.add(id);
          const skeleton = showSectionSkeleton(entry.target as HTMLElement);
          sectionMap[id]()
            .then(() => removeSectionSkeleton(skeleton))
            .catch((err) => {
              console.error(`Lazy load ${id} failed:`, err);
              removeSectionSkeleton(skeleton);
            });
          observer.unobserve(entry.target);
        }
      }
    },
    { rootMargin: '200px' },
  );

  for (const id of Object.keys(sectionMap)) {
    const el = document.getElementById(id);
    if (el) observer.observe(el);
  }
}

// ── CSV Import Setup ─────────────────────────────────────────────────────────
function setupCsvImport(): void {
  const fileInput = document.getElementById('csv-file') as HTMLInputElement | null;
  const fileNameDisplay = document.getElementById('file-name');
  const importBtn = document.getElementById('import-btn') as HTMLButtonElement | null;
  const importForm = document.getElementById('import-form');

  if (fileInput) {
    fileInput.addEventListener('change', () => {
      const count = fileInput.files?.length || 0;
      if (fileNameDisplay) {
        if (count > 1) {
          fileNameDisplay.textContent = `${count} files selected`;
        } else if (count === 1) {
          fileNameDisplay.textContent = fileInput.files![0].name;
        } else {
          fileNameDisplay.textContent = 'No file selected';
        }
      }
      if (importBtn) importBtn.disabled = count === 0;
    });
  }

  if (importForm) {
    importForm.addEventListener('submit', async (e) => {
      (await getImport()).handleImport(e);
    });
  }

  if (importBtn) {
    importBtn.addEventListener('click', async (e) => {
      if (importBtn.disabled) {
        e.preventDefault();
        const mod = await getImport();
        mod.showImportStatus('Please select CSV files first', 'error');
      }
    });
  }
}

// ── Initialize dashboard ─────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', async () => {
  try {
  initTheme();

  // Attach click handlers programmatically (more reliable than inline onclick in modules)
  document.querySelector('.theme-toggle')?.addEventListener('click', () => toggleTheme());
  document.getElementById('export-btn')?.addEventListener('click', () => toggleExportMenu());

  initDatePicker();
  initWebSocket();
  initServiceWorker();
  setupChartLazyLoading();
  initHeaderScroll();
  initBackToTop();
  initBottomNav();
  initScrollHandlers();
  setupCsvImport();

  // Load critical data in parallel
  const results = await Promise.allSettled([loadStatus(), loadSummary(), loadTrips(), loadLiveTelemetry()]);

  results.forEach((result, index) => {
    if (result.status === 'rejected') {
      const names = ['loadStatus', 'loadSummary', 'loadTrips', 'loadLiveTelemetry'];
      console.error(`${names[index]} failed:`, result.reason);
    }
  });

  // Defer non-critical sections via IntersectionObserver
  setupSectionObservers();

  // If battery/charging sections don't exist as elements, fall back to idle loading
  if (!document.getElementById('battery-section') && !document.getElementById('charging-section')) {
    const loadDeferred = async () => {
      const [bat, chg] = await Promise.all([getBattery(), getCharging()]);
      bat.loadBatteryHealth();
      bat.loadBatteryCells();
      bat.loadSocAnalysis();
      chg.loadChargingSummary();
      chg.loadChargingHistory();
    };
    if (typeof requestIdleCallback !== 'undefined') {
      requestIdleCallback(() => {
        loadDeferred();
      });
    } else {
      setTimeout(loadDeferred, 100);
    }
  }

  // Load MPG trend
  loadMpgTrend(state.currentTimeframe);

  // Refresh status every 30 seconds
  state.statusRefreshInterval = setInterval(loadStatus, 30000);

  // Check for live trip every 10 seconds (fallback if WebSocket fails)
  if (!state.useWebSocket) {
    state.liveRefreshInterval = setInterval(loadLiveTelemetry, 10000);
  }

  // Auto-refresh trips every 60 seconds
  state.tripsRefreshInterval = setInterval(loadTrips, 60000);

  // Pause/resume refresh when tab is hidden/visible
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
      if (state.tripsRefreshInterval) { clearInterval(state.tripsRefreshInterval); state.tripsRefreshInterval = null; }
      if (state.statusRefreshInterval) { clearInterval(state.statusRefreshInterval); state.statusRefreshInterval = null; }
      if (state.liveRefreshInterval) { clearInterval(state.liveRefreshInterval); state.liveRefreshInterval = null; }
    } else {
      if (!state.tripsRefreshInterval) { state.tripsRefreshInterval = setInterval(loadTrips, 60000); }
      if (!state.statusRefreshInterval) { state.statusRefreshInterval = setInterval(loadStatus, 30000); }
      if (!state.liveRefreshInterval) { state.liveRefreshInterval = setInterval(loadLiveTelemetry, 10000); }
      loadTrips();
      loadStatus();
      loadLiveTelemetry();
    }
  });
  } catch (initError) {
    console.error('[VoltTracker] Initialization failed:', initError);
    reportErrorToBackend({
      message: `Init failed: ${initError instanceof Error ? initError.message : String(initError)}`,
      stack: initError instanceof Error ? initError.stack ?? '' : '',
      url: window.location.href,
      userAgent: navigator.userAgent,
    });
    showFallbackUI(initError instanceof Error ? initError.message : String(initError));
  }
});
