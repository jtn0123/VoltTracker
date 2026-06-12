import { runBrowserDemoStream } from "./actions-demo";
import { bindPageDragScroll } from "./actions-page-scroll";
import { createSignalActions } from "./actions-signals";
import { createStorageActions } from "./actions-storage";
import type { BusyButton } from "./actions-storage";
import { setDataState } from "./dataset-state";
import type { DataStateValue } from "./dataset-state";

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
  let dtcInertedNodes: HTMLElement[] = [];

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
    // callBridge tolerates a native build that predates a method (warns once,
    // returns undefined) instead of throwing mid-refresh.
    const devices = VD.callBridge("listDevices");
    if (devices !== undefined) VD.setDevices(devices);
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

  // A Connect/Scan tap that had to pause for the Android Bluetooth permission
  // prompt. When a later native status broadcast reports bluetoothReady, the
  // connection resumes automatically (the device-list refresh that precedes
  // that status auto-selects the likely OBD adapter).
  let pendingPermissionConnect: { scan: boolean; requestedAtMs: number } | null = null;
  const PENDING_PERMISSION_CONNECT_TTL_MS = 2 * 60 * 1000;

  function anyPairedAdapterListed() {
    const select = el("deviceSelect") as HTMLSelectElement | null;
    return Boolean(select && Array.from(select.options).some((option) => String(option.value || "").trim()));
  }

  // Replaces the old one-size-fits-all "Pick a paired adapter" dead end: figure
  // out WHY no adapter is selectable and either fix it (fire the Android
  // permission prompt) or tell the user the exact next step.
  function explainMissingAdapter(scan: boolean, allowPermissionResume: boolean) {
    const permissions = (state.appState && state.appState.permissions) || {};
    if (bridge && permissions.bluetoothPermission === false && typeof bridge.requestPermissions === "function") {
      if (allowPermissionResume) pendingPermissionConnect = { scan, requestedAtMs: Date.now() };
      bridge.requestPermissions();
      showBlockedAdapterFeedback(
        'Bluetooth permission needed. Allow "Nearby devices" in the Android prompt' +
          (allowPermissionResume ? " and the connection will continue automatically." : ", then try again.")
      );
      return;
    }
    if (bridge && permissions.bluetoothEnabled === false) {
      showBlockedAdapterFeedback("Bluetooth is turned off. Turn it on in Android settings, then tap Connect again.");
      return;
    }
    if (bridge && !anyPairedAdapterListed()) {
      showBlockedAdapterFeedback(
        "No paired OBD adapters found. Pair the adapter in Android's Bluetooth settings, then tap Refresh."
      );
      return;
    }
    showBlockedAdapterFeedback("Pick a paired or remembered OBD adapter first.");
  }

  function maybeResumePendingConnect(status: VoltStatus) {
    const pending = pendingPermissionConnect;
    if (!pending) return;
    if (Date.now() - pending.requestedAtMs > PENDING_PERMISSION_CONNECT_TTL_MS) {
      pendingPermissionConnect = null;
      return;
    }
    // Only native broadcasts carry bluetoothReady; locally-set statuses (which
    // include the "waiting for permission" copy itself) must not disarm this.
    if (status.bluetoothReady === undefined) return;
    if (status.bluetoothReady !== true) {
      // A blocked broadcast while waiting means the permission was denied —
      // disarm so a much later grant doesn't start a connection out of nowhere.
      if (status.blocked || String(status.state || "") === "blocked") pendingPermissionConnect = null;
      return;
    }
    pendingPermissionConnect = null;
    if (VD.getSelectedDevice()) {
      connectSelected(pending.scan, null);
    } else {
      showBlockedAdapterFeedback(
        "Bluetooth is ready, but no paired OBD adapters were found. Pair the adapter in Android's Bluetooth settings, then tap Refresh."
      );
    }
  }

  function setEnhancedProbeBadge(label: string, tone: DataStateValue) {
    if (typeof VD.setEnhancedBadge === "function") {
      VD.setEnhancedBadge(label, tone);
      return;
    }
    VD.setText("enhancedBadge", label);
    setDataState(el("enhancedBadge"), tone);
  }

  function showConnectionProgress(device: VoltDevice, scan: boolean) {
    const label = device.name || device.address || "OBD adapter";
    const status: VoltStatus = {
      state: scan ? "scanning" : "connecting",
      detail: scan ? `Starting scan with ${label}...` : `Connecting to ${label}...`
    };
    if (device.address) status.lastAddress = String(device.address);
    if (device.name) status.lastName = String(device.name);
    VD.setStatus(status);
  }

  function connectSelected(scan: boolean, button?: BusyButton | null) {
    const selected = VD.getSelectedDevice();
    if (!selected) {
      explainMissingAdapter(scan, true);
      return;
    }
    if (!bridge) return;
    showConnectionProgress(selected, scan);
    // Guard the bridge call so a quick double-tap doesn't issue two
    // overlapping connect/scan invocations against the adapter.
    withBusy(button, () => {
      if (scan && typeof bridge.scan === "function") bridge.scan(selected.address, selected.name);
      else VD.callBridge("connect", selected.address, selected.name);
    });
  }

  function tpmsScanSelected(button?: BusyButton | null) {
    detailProbeSelected(button);
  }

  function detailProbeSelected(button?: BusyButton | null) {
    const selected = VD.getSelectedDevice();
    if (!selected) {
      explainMissingAdapter(false, false);
      setEnhancedProbeBadge("blocked", "blocked");
      return;
    }
    if (!bridge || typeof bridge.detailProbe !== "function") {
      VD.setStatus({ state: "idle", detail: "Detail Probe is only available inside the Android app." });
      setEnhancedProbeBadge("app only", "idle");
      return;
    }
    const stage = String(state.signalProbeStage || "tires");
    setEnhancedProbeBadge("probing", "working");
    withBusy(button, () => bridge.detailProbe(selected.address, selected.name, stage));
  }

  function connectLastAdapter(button?: BusyButton | null) {
    const last = typeof VD.getLastDevice === "function" ? VD.getLastDevice() : state.lastDevice;
    if (!last || !String(last.address || "").trim()) {
      showBlockedAdapterFeedback("Connect once or pick a paired adapter before using Last.");
      return;
    }
    showConnectionProgress(last, false);
    if (bridge && typeof bridge.connectLast === "function") {
      withBusy(button, () => bridge.connectLast());
    }
  }

  function handleAction(action: string | undefined, button: BusyButton | null = null) {
    if (action === "permissions") bridge && VD.callBridge("requestPermissions");
    if (action === "refresh") bridge && VD.callBridge("refreshDevices");
    if (action === "refreshStorage") refreshStorage();
    if (action === "clearStorage") clearStorage(button);
    if (action === "exportDebug") exportDebugBundle();
    if (action === "backup") shareBackup(button);
    if (action === "backupEncrypted") shareEncryptedBackup(button);
    if (action === "restore") restoreBackup(button);
    if (action === "restoreEncrypted") restoreEncryptedBackup(button);
    if (action === "last") connectLastAdapter(button);
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
    setDtcBackgroundInert(true);
    if (ack) ack.checked = false;
    if (confirm) confirm.disabled = true;
    panel.scrollIntoView({ behavior: "smooth", block: "nearest" });
    // Move focus into the alertdialog so keyboard/SR users land on the warning.
    if (typeof panel.focus === "function") panel.focus();
  }

  function closeClearDtcWarning() {
    const panel = el("dtcClearWarning");
    if (panel) panel.hidden = true;
    setDtcBackgroundInert(false);
    // Return focus to whatever opened the panel (the "Clear codes" button).
    if (clearDtcOpener instanceof HTMLElement && typeof clearDtcOpener.focus === "function") {
      clearDtcOpener.focus();
    }
    clearDtcOpener = null;
  }

  function dtcDialogOpen() {
    const panel = el("dtcClearWarning");
    return Boolean(panel && !panel.hidden);
  }

  function setDtcBackgroundInert(open: boolean) {
    if (!open) {
      dtcInertedNodes.forEach((node) => {
        (node as HTMLElement & { inert?: boolean }).inert = false;
        node.removeAttribute("aria-hidden");
        node.removeAttribute("data-dtc-dialog-inert");
      });
      dtcInertedNodes = [];
      return;
    }
    const panel = el("dtcClearWarning");
    if (!panel) return;
    const keep = new Set<Element>();
    let cursor: Element | null = panel;
    while (cursor && cursor !== document.body) {
      keep.add(cursor);
      cursor = cursor.parentElement;
    }
    const candidates = Array.from(document.querySelectorAll("body > *, main.app > *, .diagnostic-report-card > *"));
    dtcInertedNodes = [];
    candidates.forEach((node) => {
      if (!(node instanceof HTMLElement) || keep.has(node) || panel.contains(node) || node.contains(panel)) return;
      (node as HTMLElement & { inert?: boolean }).inert = true;
      node.setAttribute("aria-hidden", "true");
      node.setAttribute("data-dtc-dialog-inert", "true");
      dtcInertedNodes.push(node);
    });
  }

  function focusableInDtcDialog() {
    const panel = el("dtcClearWarning");
    if (!panel || panel.hidden) return [];
    const nodes = Array.from(
      panel.querySelectorAll<HTMLElement>(
        "button:not([disabled]), input:not([disabled]), [href], select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex='-1'])"
      )
    );
    return nodes.filter((node) => !node.hidden && node.offsetParent !== null);
  }

  function trapDtcDialogKeydown(event: KeyboardEvent) {
    if (!dtcDialogOpen()) return;
    if (event.key === "Escape") {
      event.preventDefault();
      closeClearDtcWarning();
      return;
    }
    if (event.key !== "Tab") return;
    const focusables = focusableInDtcDialog();
    if (focusables.length === 0) {
      event.preventDefault();
      const panel = el("dtcClearWarning");
      if (panel && typeof panel.focus === "function") panel.focus();
      return;
    }
    const first = focusables[0];
    const last = focusables[focusables.length - 1];
    const active = document.activeElement;
    if (!focusables.includes(active as HTMLElement)) {
      event.preventDefault();
      (event.shiftKey ? last : first).focus();
    } else if (event.shiftKey && active === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
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

  let mapLongPressTimer = 0;
  let suppressNextMapClick = false;
  let mapLongPressHandled = false;

  function mapSessionButtonFromEvent(event: Event) {
    const target = event.target as Element | null;
    return target && target.closest("[data-map-session]") as HTMLElement | null;
  }

  function clearMapLongPressTimer() {
    if (mapLongPressTimer) {
      window.clearTimeout(mapLongPressTimer);
      mapLongPressTimer = 0;
    }
  }

  function markMapSessionNotTrip(routeKey: string | undefined) {
    const clean = String(routeKey || "").trim();
    if (!clean || clean === "__live_current__") return;
    if (!bridge || typeof bridge.markTripNotTrip !== "function") {
      VD.setStatus({ state: "idle", detail: "Map cleanup is available inside the Android app." });
      return;
    }
    bridge.markTripNotTrip(clean);
  }

  function onMapSessionPointerDown(event: Event) {
    const button = mapSessionButtonFromEvent(event);
    if (!button) return;
    // A new gesture starts: drop any suppress flag left over from a contextmenu
    // that never produced a click (e.g. desktop right-click).
    suppressNextMapClick = false;
    mapLongPressHandled = false;
    clearMapLongPressTimer();
    mapLongPressTimer = window.setTimeout(() => {
      mapLongPressTimer = 0;
      mapLongPressHandled = true;
      suppressNextMapClick = true;
      markMapSessionNotTrip(button.dataset.mapSession);
    }, 650);
  }

  function onMapSessionPointerEnd() {
    clearMapLongPressTimer();
  }

  function onMapSessionContextMenu(event: Event) {
    const button = mapSessionButtonFromEvent(event);
    if (!button) return;
    event.preventDefault();
    // The WebView's own long-press contextmenu and the 650ms timer cover the same
    // gesture — whichever fires first wins so the confirm dialog shows only once.
    clearMapLongPressTimer();
    if (mapLongPressHandled) return;
    mapLongPressHandled = true;
    suppressNextMapClick = true;
    markMapSessionNotTrip(button.dataset.mapSession);
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

  // In-app DTC lookup over the shipped ~3,000-code Volt database. The dictionary
  // is a lazy chunk, so the first lookup loads it then re-renders. Falls back to a
  // web search (the existing [data-dtc-search] delegation) for unknown codes.
  function renderDtcLookup() {
    const input = el("dtcSearchInput") as HTMLInputElement | null;
    const out = el("dtcSearchResult");
    if (!input || !out) return;
    const raw = input.value.trim();
    if (!raw) {
      out.hidden = true;
      out.replaceChildren();
      return;
    }
    if (typeof VD.dtcInfo !== "function") {
      out.hidden = false;
      out.replaceChildren(document.createTextNode("Loading code database…"));
      if (typeof VD.ensureDtcData === "function") {
        VD.ensureDtcData().then(renderDtcLookup).catch(() => {
          out.replaceChildren(document.createTextNode("Code database could not be loaded."));
        });
      }
      return;
    }
    const info = VD.dtcInfo(raw);
    const code = (info && info.code) || raw.toUpperCase();
    out.hidden = false;
    out.replaceChildren();

    const head = document.createElement("div");
    head.className = "dtc-lookup-code";
    head.textContent = code;
    if (info && info.severity) {
      const sev = document.createElement("span");
      sev.className = "dtc-lookup-sev";
      sev.dataset.severity = String(info.severity).toLowerCase();
      sev.textContent = String(info.severity);
      head.appendChild(sev);
    }
    out.appendChild(head);

    const desc = document.createElement("p");
    desc.className = "dtc-lookup-desc";
    desc.textContent =
      info && info.description
        ? info.description
        : "Not in the on-device Volt database — try a web search.";
    out.appendChild(desc);

    const causes = info && Array.isArray(info.causes) ? info.causes : [];
    if (causes.length) {
      const label = document.createElement("div");
      label.className = "kicker dtc-lookup-causes-label";
      label.textContent = "Likely causes";
      out.appendChild(label);
      const list = document.createElement("ul");
      list.className = "dtc-lookup-causes";
      causes.slice(0, 5).forEach((cause) => {
        const li = document.createElement("li");
        li.textContent = String(cause);
        list.appendChild(li);
      });
      out.appendChild(list);
    }

    const web = document.createElement("button");
    web.type = "button";
    web.className = "link-btn dtc-lookup-web";
    web.dataset.dtcSearch = code;
    web.textContent = "Search the web for " + code;
    out.appendChild(web);
  }

  function startDemo() {
    VD.ensureDemoData((error) => {
      if (error) {
        VD.setStatus({ state: "blocked", detail: "Demo data could not be loaded." });
        return;
      }
      seedDemoScenario();
      VD.setDemoActive(true, "Demo preview is running.");
      // Choose by method availability, not bare bridge presence: an older APK's
      // bridge object may lack demo(), and callBridge would then no-op while the
      // UI claims the demo is running. Fall back to the browser demo instead.
      if (bridge && typeof bridge.demo === "function") VD.callBridge("demo");
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
    } else if (typeof VD.ensureMapModule === "function") {
      void VD.ensureMapModule()
        .then(() => {
          if (typeof VD.loadDemoScenario === "function") VD.loadDemoScenario(scenario);
          else if (typeof VD.loadSampleData === "function") VD.loadSampleData();
        })
        .catch(() => {});
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
      VD.renderMapIfLoaded();
      VD.renderInsightStats();
      return;
    }
    refreshStorage();
    if (typeof VD.loadTrips === "function") VD.loadTrips();
    if (typeof VD.loadInsights === "function") VD.loadInsights();
    if (typeof VD.renderRealV2Ui === "function") VD.renderRealV2Ui();
    VD.renderMapIfLoaded();
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
    if (bridge && state.demoActive) VD.callBridge("disconnect");
    VD.clearDemoTelemetry();
    if (typeof VD.clearLivePosition === "function") VD.clearLivePosition();
    VD.setDemoActive(false);
    refreshNativeDataAfterDemo();
    VD.updateLiveUi();
    VD.setStatus({ state: "idle", detail: "Demo stopped. Real data and captured history will appear here." });
  }

  function stopAll() {
    const wasDemo = state.demoActive;
    window.clearInterval(window.__voltDemoTimer ?? undefined);
    if (bridge) VD.callBridge("disconnect");
    VD.clearDemoTelemetry();
    if (typeof VD.clearLivePosition === "function") VD.clearLivePosition();
    VD.setDemoActive(false);
    // If a demo was running, state.storage still holds the synthetic DB summary and the
    // demoPreview* shadow fields are still set. setDemoActive(false) reloads trips/insights but
    // NOT storage, so without this the Settings DB card keeps showing demo counts next to real
    // trips until the next native push. Mirror stopDemo()'s cleanup.
    if (wasDemo) refreshNativeDataAfterDemo();
    VD.updateLiveUi();
    VD.setStatus({ state: "idle", detail: "Stopped." });
  }

  function runBrowserDemo() {
    runBrowserDemoStream(VD, state);
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
    // [data-nav-jump] is delegated at the document level (mirroring the
    // [data-map-session] click delegation) because drive.ts builds link chips
    // with dataset.navJump at render time — a boot-time per-node binding would
    // never see them. One mechanism covers static partials and dynamic chips,
    // so static elements can't double-fire.
    document.addEventListener("click", (event) => {
      const target = event.target as Element | null;
      const button = target && (target.closest("[data-nav-jump]") as HTMLElement | null);
      if (!button) return;
      VD.setView(button.dataset.navJump ?? "");
      button.blur();
    }, opts);
    document.querySelectorAll("[data-action]").forEach((node) => {
      const button = node as HTMLElement;
      button.addEventListener("click", (event) => handleAction(button.dataset.action, event.currentTarget as BusyButton), opts);
    });
    document.querySelectorAll("[data-scenario]").forEach((node) => {
      const button = node as HTMLElement;
      button.addEventListener("click", () => {
        const scenario = button.dataset.scenario;
        if (typeof VD.loadDemoScenario === "function") {
          VD.loadDemoScenario(scenario);
        } else if (typeof VD.ensureMapModule === "function") {
          void VD.ensureMapModule()
            .then(() => {
              if (typeof VD.loadDemoScenario === "function") VD.loadDemoScenario(scenario);
            })
            .catch(() => {});
        }
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
        void VD.requestMapRender()
          .then(() => {
            window.setTimeout(VD.renderMap, 80);
          })
          .catch(() => {});
      }, opts);
    });
    // Bind through bindListenerGuarded so a renamed partial ID logs a warn + skips
    // rather than throwing and aborting every binding below it.
    const onSessionClick = (event: Event) => {
      if (suppressNextMapClick) {
        suppressNextMapClick = false;
        event.preventDefault();
        return;
      }
      const target = event.target as Element | null;
      const button = target && target.closest("[data-map-session]");
      if (!button) return;
      VD.setState({ selectedMapSessionId: (button as HTMLElement).dataset.mapSession as string });
      void VD.requestMapRender().catch(() => {});
    };
    VD.bindListenerGuarded("mapSessionList", "click", onSessionClick, opts);
    VD.bindListenerGuarded("mapSessionList", "pointerdown", onMapSessionPointerDown, opts);
    VD.bindListenerGuarded("mapSessionList", "pointerup", onMapSessionPointerEnd, opts);
    VD.bindListenerGuarded("mapSessionList", "pointerleave", onMapSessionPointerEnd, opts);
    VD.bindListenerGuarded("mapSessionList", "pointercancel", onMapSessionPointerEnd, opts);
    VD.bindListenerGuarded("mapSessionList", "contextmenu", onMapSessionContextMenu, opts);
    // The new drive-chip strip uses the same [data-map-session] attribute, so
    // share the handler. Without this, tapping a chip did nothing.
    VD.bindListenerGuarded("mapDriveChips", "click", onSessionClick, opts);
    VD.bindListenerGuarded("mapDriveChips", "pointerdown", onMapSessionPointerDown, opts);
    VD.bindListenerGuarded("mapDriveChips", "pointerup", onMapSessionPointerEnd, opts);
    VD.bindListenerGuarded("mapDriveChips", "pointerleave", onMapSessionPointerEnd, opts);
    VD.bindListenerGuarded("mapDriveChips", "pointercancel", onMapSessionPointerEnd, opts);
    VD.bindListenerGuarded("mapDriveChips", "contextmenu", onMapSessionContextMenu, opts);
    VD.bindListenerGuarded("mapFullBtn", "click", () => {
      state.mapFull = !state.mapFull;
      void VD.requestMapRender().catch(() => {});
    }, opts);
    VD.bindListenerGuarded("errorBannerHelp", "click", () => {
      if (typeof VD.ensureTroubleshooterModule !== "function") return;
      void VD.ensureTroubleshooterModule()
        .then((dashboard) => {
          const ts = dashboard.troubleshooter;
          if (ts && typeof ts.open === "function") ts.open();
        })
        .catch(() => {});
    }, opts);
    document.addEventListener("click", handleDtcSearch, opts);
    VD.bindListenerGuarded("dtcSearchInput", "input", renderDtcLookup, opts);
    document.addEventListener("change", (event) => {
      const target = event.target as HTMLInputElement | null;
      if (target && target.id === "dtcClearAckBox") {
        const confirm = el("dtcClearConfirmBtn") as HTMLButtonElement | null;
        if (confirm) confirm.disabled = !target.checked;
      }
    }, opts);
    document.addEventListener("keydown", trapDtcDialogKeydown, opts);
    // Tapping outside the open Clear-codes dialog dismisses it, matching the
    // Esc path. The background is inert while the dialog is open, so this
    // click lands on a non-interactive ancestor and can't trigger anything
    // else. Skip the opener's own click (it bubbles here after opening).
    document.addEventListener("click", (event) => {
      if (!dtcDialogOpen()) return;
      const target = event.target as Element | null;
      if (target && target.closest("[data-action='openClearDtc']")) return;
      const panel = el("dtcClearWarning");
      if (panel && (!target || !panel.contains(target))) closeClearDtcWarning();
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
    VD.bindListenerGuarded("demoStopBtn", "click", stopDemo, opts);
    bindPageDragScroll(VD, opts);
  }

  // Reset hook. Aborts every listener bound by bindListeners() (and the
  // window-level handlers in core.js if you also reset VD.errorController) and
  // re-arms them with a fresh AbortController.
  function resetListeners() {
    clearMapLongPressTimer();
    suppressNextMapClick = false;
    mapLongPressHandled = false;
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
    setRestoreProgress: VD.setRestoreProgress,
    updateTelemetry: VD.updateTelemetry
  };

  function maybeLoadTroubleshooterForStatus(payload: unknown) {
    if (typeof VD.ensureTroubleshooterModule !== "function") return;
    const status = VD.parsePayload<VoltStatus>(payload, {});
    const stateName = String(status.state || "").toLowerCase();
    const detail = String(status.detail || "").toLowerCase();
    const hasFailureClass = Boolean(status.failureClass || status.blocked);
    const needsHelp =
      hasFailureClass ||
      stateName === "failed" ||
      stateName === "blocked" ||
      detail.includes("retrying");
    if (!needsHelp) return;
    void VD.ensureTroubleshooterModule()
      .then((dashboard) => {
        const ts = dashboard.troubleshooter;
        if (ts && typeof ts.noteStatus === "function") ts.noteStatus(status);
      })
      .catch(() => {});
  }

  const priorSetStatus = VD.setStatus;
  const statusWithTroubleshooterLoader = function (payload: unknown) {
    const parsed = VD.parsePayload<VoltStatus>(payload, {});
    const result = priorSetStatus(parsed);
    maybeLoadTroubleshooterForStatus(payload);
    maybeResumePendingConnect(parsed);
    return result;
  };
  VD.setStatus = statusWithTroubleshooterLoader;
  window.VoltTrackerNative.setStatus = statusWithTroubleshooterLoader;

  bindListeners();
  VD.setDemoActive(false);
  VD.renderOperationalState();
  VD.updateLiveUi();
  VD.renderRealV2Ui();
  VD.renderMapIfLoaded();
  VD.loadTrips();
  VD.loadInsights();
  if (typeof VD.updateDiagnosticCodeUi === "function") VD.updateDiagnosticCodeUi();
  // Initial paint of the Drive-tab live polish — without this the session chip
  // strip + micro-charts stay empty until the first telemetry sample arrives.
  if (typeof VD.renderDriveLive === "function") VD.renderDriveLive();
  refreshDevices();
  refreshStorage();
  if (bridge && typeof bridge.dashboardReady === "function") bridge.dashboardReady();
  requestAnimationFrame(() => VD.scrollAppToTop());
  setTimeout(() => VD.scrollAppToTop(), 200);

export {};
