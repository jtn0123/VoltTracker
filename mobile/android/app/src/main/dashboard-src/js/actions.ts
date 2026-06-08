import { runBrowserDemoStream } from "./actions-demo";
import { bindPageDragScroll } from "./actions-page-scroll";
import { createSignalActions } from "./actions-signals";
import { createStorageActions } from "./actions-storage";
import type { BusyButton } from "./actions-storage";

/*
 * actions.ts — wiring + lifecycle.
 *
 * Listener-discipline pattern
 * ---------------------------
 * Every addEventListener registered from this file is passed
 * `{ signal: controller.signal }` so a single `controller.abort()` tears every
 * one of them down at once — no per-listener bookkeeping, no leaked closures
 * if the dashboard is re-bootstrapped. Use `VoltDashboard.actions.resetListeners()`
 * to abort the current set and re-bind from scratch (the bind step is
 * idempotent and safe to call repeatedly).
 *
 * The window-level `error` and `unhandledrejection` listeners in core.js share
 * the same pattern via `VoltDashboard.errorController`; reset there too if you
 * ever need to tear everything down.
 */
  const VD = window.VoltDashboard;
  const state = VD.state;
  const bridge = VD.bridge;
  const el = VD.el;

  // AbortController for every listener bound below. resetListeners() aborts
  // the current set and rebinds — useful for hot-reloading WebView content or
  // for tests that swap fixtures between runs.
  let controller = new AbortController();

  // Element that opened the clear-DTC alertdialog, so focus can return to it.
  let clearDtcOpener: Element | null = null;

  // Lightweight in-flight guard for bridge-triggering buttons. The Android
  // bridge calls are sync-fire-and-forget so we can't await completion; a short
  // 600ms cooldown is enough to swallow accidental double-taps without making
  // the button feel sticky on real-device latency.
  function withBusy<T>(button: BusyButton | null | undefined, fn: () => T): T | undefined {
    // Programmatic callers (no button, e.g. future keyboard shortcut or test)
    // should still execute the action; we just can't paint a busy state.
    if (!button) return fn();
    if (button.dataset.busy === "1") return;
    button.dataset.busy = "1";
    button.disabled = true;
    button.classList.add("busy");
    const release = () => {
      button.dataset.busy = "0";
      button.disabled = false;
      button.classList.remove("busy");
    };
    try {
      const result = fn();
      // Most bridge calls are sync-fire-and-forget. The next setStatus() callback
      // from the bridge implicitly indicates completion, but we don't have a hook
      // into that here. Release after a short delay to allow rapid re-press but
      // suppress immediate double-tap.
      setTimeout(release, 600);
      return result;
    } catch (err) {
      release();
      throw err;
    }
  }

  const {
    refreshStorage,
    clearStorage,
    shareBackup,
    shareEncryptedBackup,
    restoreBackup,
    restoreEncryptedBackup,
    exportDebugBundle
  } = createStorageActions({ VD, bridge, withBusy });

  const {
    exportSignalLog,
    exportSignalLogs,
    deleteSignalLog
  } = createSignalActions({ VD, bridge });

  function refreshDevices() {
    if (!bridge) {
      VD.setStatus({ state: "ready", detail: "Browser preview ready. Start demo to view sample telemetry." });
      return;
    }
    VD.setDevices(bridge.listDevices());
    if (typeof bridge.getDeviceHistory === "function") VD.setHistory(bridge.getDeviceHistory());
  }

  function showBlockedAdapterFeedback(detail: string) {
    VD.setStatus({ state: "blocked", detail });
    VD.setText("adapterSummary", "Adapter needed");
    VD.setText("appStateSummary", detail);
    VD.setText("statusCopy", detail);
    const reviewWarnings = el("reviewWarnings");
    if (reviewWarnings) {
      const p = document.createElement("p");
      p.className = "status-copy";
      p.textContent = detail;
      reviewWarnings.replaceChildren(p);
    }
  }

  function connectSelected(scan: boolean, button?: BusyButton | null) {
    const selected = VD.getSelectedDevice();
    if (!selected) {
      showBlockedAdapterFeedback("Pick a paired or remembered OBD adapter first.");
      return;
    }
    if (!bridge) return;
    // Guard the bridge call so a quick double-tap doesn't issue two
    // overlapping connect/scan invocations against the adapter.
    withBusy(button, () => {
      if (scan && typeof bridge.scan === "function") bridge.scan(selected.address, selected.name);
      else bridge.connect(selected.address, selected.name);
    });
  }

  function tpmsScanSelected(button?: BusyButton | null) {
    detailProbeSelected(button);
  }

  function detailProbeSelected(button?: BusyButton | null) {
    const selected = VD.getSelectedDevice();
    if (!selected) {
      showBlockedAdapterFeedback("Pick a paired or remembered OBD adapter first.");
      return;
    }
    if (!bridge || typeof bridge.detailProbe !== "function") {
      VD.setStatus({ state: "idle", detail: "Detail Probe is only available inside the Android app." });
      return;
    }
    const stage = String(state.signalProbeStage || "tires");
    withBusy(button, () => bridge.detailProbe(selected.address, selected.name, stage));
  }

  function connectLastAdapter() {
    const last = typeof VD.getLastDevice === "function" ? VD.getLastDevice() : state.lastDevice;
    if (!last || !String(last.address || "").trim()) {
      showBlockedAdapterFeedback("Connect once or pick a paired adapter before using Last.");
      return;
    }
    if (bridge && typeof bridge.connectLast === "function") bridge.connectLast();
  }

  function handleAction(action: string | undefined, button: BusyButton | null = null) {
    if (action === "permissions") bridge && bridge.requestPermissions();
    if (action === "refresh") bridge && bridge.refreshDevices();
    if (action === "refreshStorage") refreshStorage();
    if (action === "clearStorage") clearStorage(button);
    if (action === "exportDebug") exportDebugBundle();
    if (action === "backup") shareBackup(button);
    if (action === "backupEncrypted") shareEncryptedBackup(button);
    if (action === "restore") restoreBackup(button);
    if (action === "restoreEncrypted") restoreEncryptedBackup(button);
    if (action === "last") connectLastAdapter();
    if (action === "scan") connectSelected(true, button);
    if (action === "tpmsScan") tpmsScanSelected(button);
    if (action === "detailProbe") detailProbeSelected(button);
    if (action === "connect") connectSelected(false, button);
    if (action === "demo") startDemo();
    if (action === "stopDemo") stopDemo();
    if (action === "stop") stopAll();
    if (action === "openClearDtc") openClearDtcWarning();
    if (action === "cancelClearDtc") closeClearDtcWarning();
    if (action === "confirmClearDtc") confirmClearDtc(button);
    if (action === "previewDtcCodes") void previewDtcCodes();
    if (action === "clearPreviewDtcCodes") clearPreviewDtcCodes();
  }

  function openClearDtcWarning() {
    const panel = el("dtcClearWarning");
    const ack = el("dtcClearAckBox") as HTMLInputElement | null;
    const confirm = el("dtcClearConfirmBtn") as HTMLButtonElement | null;
    if (!panel) return;
    // Remember the trigger so focus can return to it when the panel closes.
    clearDtcOpener = document.activeElement;
    panel.hidden = false;
    if (ack) ack.checked = false;
    if (confirm) confirm.disabled = true;
    panel.scrollIntoView({ behavior: "smooth", block: "nearest" });
    // Move focus into the alertdialog so keyboard/SR users land on the warning.
    if (typeof panel.focus === "function") panel.focus();
  }

  function closeClearDtcWarning() {
    const panel = el("dtcClearWarning");
    if (panel) panel.hidden = true;
    // Return focus to whatever opened the panel (the "Clear codes" button).
    if (clearDtcOpener instanceof HTMLElement && typeof clearDtcOpener.focus === "function") {
      clearDtcOpener.focus();
    }
    clearDtcOpener = null;
  }

  function confirmClearDtc(button?: BusyButton | null) {
    const ack = el("dtcClearAckBox") as HTMLInputElement | null;
    if (!ack || !ack.checked) {
      VD.setStatus({ state: "blocked", detail: "Tick the acknowledgement first." });
      return;
    }
    if (!bridge || typeof bridge.clearVehicleDtcCodes !== "function") {
      VD.setStatus({ state: "idle", detail: "Clear-codes is only available inside the Android app." });
      return;
    }
    withBusy(button, () => {
      bridge.clearVehicleDtcCodes();
      closeClearDtcWarning();
    });
  }

  function previewDtcCodes(): Promise<void> | undefined {
    if (!Array.isArray(VD.dtcSampleCodes) && typeof VD.ensureDtcData === "function") {
      VD.setStatus({ state: "ready", detail: "Loading DTC examples..." });
      return VD.ensureDtcData()
        .then(previewDtcCodes)
        .catch(() => VD.setStatus({ state: "blocked", detail: "DTC examples could not be loaded." }));
    }
    const samples = Array.isArray(VD.dtcSampleCodes) ? VD.dtcSampleCodes : [];
    const storage = state.storage || (state.storage = {});
    storage.latestDiagnosticCodes = samples.map((s) => ({ ...s }));
    storage.diagnosticCodeCount = samples.length;
    storage.diagnosticCodeStatusCounts = samples.reduce((acc: Record<string, number>, s) => {
      const k = String(s.status || "stored").toLowerCase();
      acc[k] = (acc[k] || 0) + 1;
      return acc;
    }, {} as Record<string, number>);
    if (typeof VD.updateDiagnosticCodeUi === "function") VD.updateDiagnosticCodeUi();
    VD.setStatus({ state: "ready", detail: "DTC example data loaded into the Insights view." });
    return undefined;
  }

  function clearPreviewDtcCodes() {
    const storage = state.storage || (state.storage = {});
    storage.latestDiagnosticCodes = [];
    storage.diagnosticCodeCount = 0;
    storage.diagnosticCodeStatusCounts = {};
    if (typeof VD.updateDiagnosticCodeUi === "function") VD.updateDiagnosticCodeUi();
    VD.setStatus({ state: "ready", detail: "DTC examples cleared." });
  }

  function handleDtcSearch(event: Event) {
    const target = event.target as Element | null;
    if (!target) return;
    const link = target.closest("[data-dtc-search]") as HTMLElement | null;
    if (!link) return;
    event.preventDefault();
    const code = link.dataset.dtcSearch;
    if (!code) return;
    if (bridge && typeof bridge.openExternalSearch === "function") {
      bridge.openExternalSearch(code);
    } else {
      const url = typeof VD.dtcSearchUrl === "function" ? VD.dtcSearchUrl(code) : null;
      if (url) window.open(url, "_blank", "noopener,noreferrer");
    }
  }

  function startDemo() {
    VD.ensureDemoData((error) => {
      if (error) {
        VD.setStatus({ state: "blocked", detail: "Demo data could not be loaded." });
        return;
      }
      seedDemoScenario();
      VD.setDemoActive(true, "Demo preview is running.");
      if (bridge) bridge.demo();
      else runBrowserDemo();
    });
  }

  function currentDemoScenario() {
    const picker = el("demoScenarioPicker");
    const active = picker && picker.querySelector<HTMLElement>("[data-scenario].is-active");
    return String(state.demoScenario || (active && active.dataset.scenario) || "typical");
  }

  function seedDemoScenario() {
    const scenario = currentDemoScenario();
    if (typeof VD.loadDemoScenario === "function") {
      VD.loadDemoScenario(scenario);
    } else if (typeof VD.loadSampleData === "function") {
      VD.loadSampleData();
    }
  }

  function refreshNativeDataAfterDemo() {
    if (!bridge) {
      state.storage = {
        database: "volttracker_obd_poc.db",
        databaseBytes: 4096,
        sessionCount: 0,
        sampleCount: 0,
        rawTelemetryCount: 0,
        recentRoutes: [],
        recentSessions: [],
        chargeSummary: { chargeSessionCount: 0, chargingHintCount: 0, recentSessions: [] },
        batterySummary: {},
        detailedSignalCatalog: [],
        enhancedCapabilities: [],
        latestDiagnosticCodes: [],
        diagnosticCodeCount: 0,
        overview: {}
      };
      state.trips = [];
      state.insights = {};
      state.appState = Object.assign({}, state.appState || {}, { vehicle: null, latestTelemetry: null });
      // Clear the demo-mode shadow copies (cross-module invariant: they gate
      // whether storage/trips/insights renders read real vs preview data).
      VD.setState({
        demoPreviewStorage: null,
        demoPreviewTrips: null,
        demoPreviewInsights: null,
        demoPreviewAppState: null,
        _mapSampleLoaded: false
      });
      VD.updateStorageUi();
      VD.renderRealV2Ui();
      VD.renderMap();
      VD.renderRealTrips();
      VD.renderInsightStats();
      return;
    }
    refreshStorage();
    if (typeof VD.loadTrips === "function") VD.loadTrips();
    if (typeof VD.loadInsights === "function") VD.loadInsights();
    if (typeof VD.renderRealV2Ui === "function") VD.renderRealV2Ui();
    if (typeof VD.renderMap === "function") VD.renderMap();
    if (typeof VD.renderRealTrips === "function") VD.renderRealTrips();
    if (typeof VD.renderInsightStats === "function") VD.renderInsightStats();
    VD.setState({
      demoPreviewStorage: null,
      demoPreviewTrips: null,
      demoPreviewInsights: null,
      demoPreviewAppState: null
    });
  }

  function stopDemo() {
    window.clearInterval(window.__voltDemoTimer ?? undefined);
    if (bridge && state.demoActive) bridge.disconnect();
    VD.clearDemoTelemetry();
    if (typeof VD.clearLivePosition === "function") VD.clearLivePosition();
    VD.setDemoActive(false);
    refreshNativeDataAfterDemo();
    VD.updateLiveUi();
    VD.drawTrace();
    VD.setStatus({ state: "idle", detail: "Demo stopped. Real data and captured history will appear here." });
  }

  function stopAll() {
    const wasDemo = state.demoActive;
    window.clearInterval(window.__voltDemoTimer ?? undefined);
    if (bridge) bridge.disconnect();
    VD.clearDemoTelemetry();
    if (typeof VD.clearLivePosition === "function") VD.clearLivePosition();
    VD.setDemoActive(false);
    // If a demo was running, state.storage still holds the synthetic DB summary and the
    // demoPreview* shadow fields are still set. setDemoActive(false) reloads trips/insights but
    // NOT storage, so without this the Settings DB card keeps showing demo counts next to real
    // trips until the next native push. Mirror stopDemo()'s cleanup.
    if (wasDemo) refreshNativeDataAfterDemo();
    VD.updateLiveUi();
    VD.drawTrace();
    VD.setStatus({ state: "idle", detail: "Stopped." });
  }

  function runBrowserDemo() {
    runBrowserDemoStream(VD, state);
  }

  // Window resize handler is debounced to 100ms — drawTrace recomputes canvas
  // backing-store size, which is genuinely expensive to do on every resize event
  // from a runaway WebView layout pass.
  let resizeTimer = 0;
  function debouncedResize() {
    if (resizeTimer) window.clearTimeout(resizeTimer);
    resizeTimer = window.setTimeout(() => {
      resizeTimer = 0;
      VD.drawTrace();
    }, 100);
  }

  function bindListeners() {
    const opts = { signal: controller.signal };

    document.querySelectorAll("[data-nav]").forEach((node) => {
      const button = node as HTMLElement;
      button.addEventListener("click", () => {
        VD.setView(button.dataset.nav ?? "");
        button.blur();
      }, opts);
    });
    document.querySelectorAll("[data-nav-jump]").forEach((node) => {
      const button = node as HTMLElement;
      button.addEventListener("click", () => {
        VD.setView(button.dataset.navJump ?? "");
        button.blur();
      }, opts);
    });
    document.querySelectorAll("[data-action]").forEach((node) => {
      const button = node as HTMLElement;
      button.addEventListener("click", (event) => handleAction(button.dataset.action, event.currentTarget as BusyButton), opts);
    });
    document.querySelectorAll("[data-scenario]").forEach((node) => {
      const button = node as HTMLElement;
      button.addEventListener("click", () => {
        if (typeof VD.loadDemoScenario === "function") VD.loadDemoScenario(button.dataset.scenario);
        // Tapping a scenario is an explicit preview action; keep demo isolation
        // active so native storage/app-state pushes cannot overwrite the sample.
        if (typeof VD.setDemoActive === "function") VD.setDemoActive(true, "Demo preview is running.");
        const picker = el("demoScenarioPicker");
        if (picker) picker.querySelectorAll("[data-scenario]").forEach((b) => b.classList.toggle("is-active", b === button));
      }, opts);
    });
    document.querySelectorAll("[data-map-layer]").forEach((node) => {
      const button = node as HTMLElement;
      button.addEventListener("click", () => {
        // The [data-map-layer] selector guarantees the attribute is present.
        state.mapLayer = button.dataset.mapLayer as string;
        button.blur();
        VD.renderMap();
        window.setTimeout(VD.renderMap, 80);
      }, opts);
    });
    // Bind through bindListenerGuarded so a renamed partial ID logs a warn + skips
    // rather than throwing and aborting every binding below it.
    const onSessionClick = (event: Event) => {
      const target = event.target as Element | null;
      const button = target && target.closest("[data-map-session]");
      if (!button) return;
      VD.setState({ selectedMapSessionId: (button as HTMLElement).dataset.mapSession as string });
      VD.renderMap();
    };
    VD.bindListenerGuarded("mapSessionList", "click", onSessionClick, opts);
    // The new drive-chip strip uses the same [data-map-session] attribute, so
    // share the handler. Without this, tapping a chip did nothing.
    VD.bindListenerGuarded("mapDriveChips", "click", onSessionClick, opts);
    VD.bindListenerGuarded("mapFullBtn", "click", () => {
      state.mapFull = !state.mapFull;
      VD.renderMap();
    }, opts);
    document.addEventListener("click", handleDtcSearch, opts);
    document.addEventListener("change", (event) => {
      const target = event.target as HTMLInputElement | null;
      if (target && target.id === "dtcClearAckBox") {
        const confirm = el("dtcClearConfirmBtn") as HTMLButtonElement | null;
        if (confirm) confirm.disabled = !target.checked;
      }
    }, opts);
    document.addEventListener("click", (event) => {
      const target = event.target as Element | null;
      const signalExport = target && target.closest("[data-signal-export]");
      if (signalExport) {
        exportSignalLog((signalExport as HTMLElement).dataset.signalExport);
        return;
      }
      const signalDelete = target && target.closest("[data-signal-delete]");
      if (signalDelete) {
        deleteSignalLog((signalDelete as HTMLElement).dataset.signalDelete);
        return;
      }
      const realTripButton = target && target.closest("[data-real-trip-id]");
      if (realTripButton) {
        if (typeof VD.selectRealTrip === "function") {
          VD.selectRealTrip((realTripButton as HTMLElement).dataset.realTripId ?? "");
        }
        return;
      }
      const tripButton = target && target.closest("[data-trip-map]");
      if (!tripButton) return;
      const id = (tripButton as HTMLElement).dataset.tripMap;
      const trip = (state.trips || []).find((t) => String(t.id) === String(id));
      if (trip && trip.hasRoute) {
        const route = typeof VD.ensureRouteForTrip === "function" ? VD.ensureRouteForTrip(trip) : null;
        if (route && route.session) {
          const routeKey = String(route.session.id || "");
          const existingRoutes = (state.storage || {}).recentRoutes;
          const routes: VoltRoute[] = Array.isArray(existingRoutes) ? existingRoutes : [];
          state.storage = state.storage || {};
          state.storage.recentRoutes = [
            route,
            ...routes.filter((existing) =>
              String((existing.session || {}).id || "") !== routeKey
            )
          ];
        }
        VD.setState({ selectedMapSessionId: id ?? null });
        VD.setView("map");
      } else {
        VD.setStatus({ state: "ready", detail: "This trip has no stored GPS route." });
      }
    }, opts);
    VD.bindListenerGuarded("permissionBtn", "click", () => handleAction("permissions"), opts);
    VD.bindListenerGuarded("refreshBtn", "click", () => handleAction("refresh"), opts);
    VD.bindListenerGuarded("lastBtn", "click", () => handleAction("last"), opts);
    VD.bindListenerGuarded("scanBtn", "click", (event) => handleAction("scan", event.currentTarget as BusyButton), opts);
    VD.bindListenerGuarded("tpmsScanBtn", "click", (event) => handleAction("tpmsScan", event.currentTarget as BusyButton), opts);
    VD.bindListenerGuarded("exportSignalLogsBtn", "click", exportSignalLogs, opts);
    VD.bindListenerGuarded("connectBtn", "click", (event) => {
      const btn = el("connectBtn");
      const action = (btn && btn.dataset.primaryAction) || "connect";
      handleAction(action, event.currentTarget as BusyButton);
    }, opts);
    VD.bindListenerGuarded("disconnectBtn", "click", () => handleAction("stop"), opts);
    VD.bindListenerGuarded("demoStopBtn", "click", stopDemo, opts);
    bindPageDragScroll(VD, opts);
    window.addEventListener("resize", debouncedResize, opts);
  }

  // Reset hook. Aborts every listener bound by bindListeners() (and the
  // window-level handlers in core.js if you also reset VD.errorController) and
  // re-arms them with a fresh AbortController.
  function resetListeners() {
    controller.abort();
    controller = new AbortController();
    bindListeners();
  }

  VD.actions = {
    refreshDevices,
    connectSelected,
    tpmsScanSelected,
    detailProbeSelected,
    handleAction,
    startDemo,
    stopDemo,
    stopAll,
    refreshStorage,
    clearStorage,
    shareBackup,
    shareEncryptedBackup,
    restoreBackup,
    restoreEncryptedBackup,
    exportDebugBundle,
    exportSignalLog,
    exportSignalLogs,
    deleteSignalLog,
    runBrowserDemo,
    previewDtcCodes,
    clearPreviewDtcCodes,
    resetListeners
  };
  Object.assign(VD, {
    refreshDevices,
    connectSelected,
    tpmsScanSelected,
    detailProbeSelected,
    handleAction,
    startDemo,
    stopDemo,
    stopAll,
    refreshStorage,
    clearStorage,
    shareBackup,
    shareEncryptedBackup,
    restoreBackup,
    restoreEncryptedBackup,
    exportDebugBundle,
    exportSignalLog,
    exportSignalLogs,
    deleteSignalLog,
    runBrowserDemo,
    previewDtcCodes,
    clearPreviewDtcCodes
  });

  // Android side calls into VoltTrackerNative.* on the WebView — this surface
  // is the ABI and must keep its exact shape.
  window.VoltTrackerNative = {
    setDevices: VD.setDevices,
    setHistory: VD.setHistory,
    setStatus: VD.setStatus,
    setStorage: VD.setStorage,
    setAppState: VD.setAppState,
    updateTelemetry: VD.updateTelemetry
  };

  bindListeners();
  VD.setDemoActive(false);
  VD.renderOperationalState();
  VD.updateLiveUi();
  VD.renderRealV2Ui();
  VD.renderMap();
  VD.loadTrips();
  VD.loadInsights();
  if (typeof VD.updateDiagnosticCodeUi === "function") VD.updateDiagnosticCodeUi();
  VD.drawTrace();
  // Initial paint of the Drive-tab live polish — without this the session chip
  // strip + micro-charts stay empty until the first telemetry sample arrives.
  if (typeof VD.renderDriveLive === "function") VD.renderDriveLive();
  refreshDevices();
  refreshStorage();
  if (bridge && typeof bridge.dashboardReady === "function") bridge.dashboardReady();
  requestAnimationFrame(() => VD.scrollAppToTop());
  setTimeout(() => VD.scrollAppToTop(), 200);

export {};
