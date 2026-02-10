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

  // Create a minimal fallback if the template doesn't have one
  document.body.innerHTML = `
    <div style="display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:80vh;font-family:system-ui,sans-serif;padding:2rem;text-align:center">
      <h1 style="margin:0 0 .5rem">Something went wrong</h1>
      <p style="color:#666;max-width:500px">${errorMsg.replace(/</g, '&lt;')}</p>
      <button onclick="location.reload()" style="margin-top:1rem;padding:.5rem 1.5rem;border-radius:6px;border:none;background:#007bff;color:#fff;cursor:pointer">
        Reload
      </button>
    </div>`;
}

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
  return (batteryMod ??= await loadBatteryModule());
}
async function getCharging() {
  return (chargingMod ??= await loadChargingModule());
}
async function getImport() {
  return (importMod ??= await loadImportModule());
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
  document.documentElement.setAttribute('data-theme', newTheme);
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
    return;
  }
})();

// ── Window function exposure (for inline HTML onclick handlers) ──────────────
// Critical modules — available immediately
window.openTripModal = openTripModal;
window.closeTripModal = closeTripModal;
window.deleteTrip = deleteTrip;
window.setTimeframe = setTimeframe;
window.toggleTheme = toggleTheme;
window.clearDateFilter = clearDateFilter;
window.toggleExportMenu = toggleExportMenu;

// Lazy-loaded modules — thunks that import on first call
window.openAddChargingModal = async () => {
  (await getCharging()).openAddChargingModal();
};
window.closeChargingModal = async () => {
  (await getCharging()).closeChargingModal();
};
window.submitChargingSession = async (e: Event) => {
  (await getCharging()).submitChargingSession(e);
};
window.deleteChargingSession = async (id: number) => {
  (await getCharging()).deleteChargingSession(id);
};
window.openChargingDetailModal = async (id: number) => {
  (await getCharging()).openChargingDetailModal(id);
};
window.closeChargingDetailModal = async () => {
  (await getCharging()).closeChargingDetailModal();
};
window.handleImport = async (e: Event) => {
  (await getImport()).handleImport(e);
};
window.closeImportResultModal = async () => {
  (await getImport()).closeImportResultModal();
};
window.copyImportCode = async () => {
  (await getImport()).copyImportCode();
};
window.copyImportReport = async () => {
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
    if (menu && menu.classList.contains('show')) {
      menu.classList.remove('show');
      if (btn) {
        btn.setAttribute('aria-expanded', 'false');
        btn.focus();
      }
    }
  }
});

// ── Lazy section loading with IntersectionObserver ───────────────────────────
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
          sectionMap[id]().catch((err) => console.error(`Lazy load ${id} failed:`, err));
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

// ── Initialize dashboard ─────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', async () => {
  try {
  initTheme();
  initDatePicker();
  initWebSocket();
  initServiceWorker();
  setupChartLazyLoading();
  initHeaderScroll();
  initBackToTop();
  initBottomNav();

  // Setup CSV import file input
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
