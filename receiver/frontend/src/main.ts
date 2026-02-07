/**
 * VoltTracker - Main Entry Point
 * Imports all modules, wires up event listeners, and initializes the app
 */

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

import { state } from './core';
import { setupChartLazyLoading } from './charts';
import { loadTrips, openTripModal, closeTripModal, deleteTrip, renderTripCharts, setTimeframe } from './trips';
import { loadSummary, loadMpgTrend } from './summary';
import { initWebSocket, loadLiveTelemetry, loadStatus } from './live';
import { loadChargingSummary, loadChargingHistory, openAddChargingModal, closeChargingModal, submitChargingSession, deleteChargingSession, openChargingDetailModal, closeChargingDetailModal } from './charging';
import { loadBatteryHealth, loadBatteryCells, loadSocAnalysis } from './battery';
import { handleImport, showImportStatus, showImportResultModal, closeImportResultModal, copyImportCode, copyImportReport } from './import';
import { initTheme, toggleTheme, initDatePicker, clearDateFilter, toggleExportMenu, initBottomNav, initHeaderScroll, initBackToTop, initServiceWorker } from './ui';

// Service Worker Recovery - unregister broken SW if requested
(function() {
  const params = new URLSearchParams(window.location.search);
  if (params.get('clear-sw') === '1' && 'serviceWorker' in navigator) {
    navigator.serviceWorker.getRegistrations().then(registrations => {
      registrations.forEach(reg => reg.unregister());
      console.log('[SW Recovery] Unregistered all service workers');
      params.delete('clear-sw');
      const newUrl = window.location.pathname + (params.toString() ? '?' + params.toString() : '');
      window.location.replace(newUrl);
    });
    return;
  }
})();

// Expose functions called from inline HTML handlers to window
window.openTripModal = openTripModal;
window.closeTripModal = closeTripModal;
window.deleteTrip = deleteTrip;
window.setTimeframe = setTimeframe;
window.toggleTheme = toggleTheme;
window.clearDateFilter = clearDateFilter;
window.toggleExportMenu = toggleExportMenu;
window.openAddChargingModal = openAddChargingModal;
window.closeChargingModal = closeChargingModal;
window.submitChargingSession = submitChargingSession;
window.deleteChargingSession = deleteChargingSession;
window.openChargingDetailModal = openChargingDetailModal;
window.closeChargingDetailModal = closeChargingDetailModal;
window.handleImport = handleImport;
window.closeImportResultModal = closeImportResultModal;
window.copyImportCode = copyImportCode;
window.copyImportReport = copyImportReport;

// Cleanup intervals and connections on page unload
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
    closeChargingModal();
    closeChargingDetailModal();

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

// Initialize dashboard on load
document.addEventListener('DOMContentLoaded', async () => {
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
    importForm.addEventListener('submit', handleImport);
  }

  if (importBtn) {
    importBtn.addEventListener('click', (e) => {
      if (importBtn.disabled) {
        e.preventDefault();
        showImportStatus('Please select CSV files first', 'error');
      }
    });
  }

  // Load critical data in parallel
  const results = await Promise.allSettled([
    loadStatus(),
    loadSummary(),
    loadTrips(),
    loadLiveTelemetry()
  ]);

  results.forEach((result, index) => {
    if (result.status === 'rejected') {
      const names = ['loadStatus', 'loadSummary', 'loadTrips', 'loadLiveTelemetry'];
      console.error(`${names[index]} failed:`, result.reason);
    }
  });

  // Defer non-critical data loading
  if (typeof requestIdleCallback !== 'undefined') {
    requestIdleCallback(() => {
      loadMpgTrend(state.currentTimeframe);
      loadSocAnalysis();
      loadChargingSummary();
      loadChargingHistory();
      loadBatteryHealth();
      loadBatteryCells();
    });
  } else {
    setTimeout(() => {
      loadMpgTrend(state.currentTimeframe);
      loadSocAnalysis();
      loadChargingSummary();
      loadChargingHistory();
      loadBatteryHealth();
      loadBatteryCells();
    }, 100);
  }

  // Refresh status every 30 seconds
  state.statusRefreshInterval = setInterval(loadStatus, 30000);

  // Check for live trip every 10 seconds (fallback if WebSocket fails)
  if (!state.useWebSocket) {
    state.liveRefreshInterval = setInterval(loadLiveTelemetry, 10000);
  }

  // Auto-refresh trips every 60 seconds
  state.tripsRefreshInterval = setInterval(loadTrips, 60000);
});
