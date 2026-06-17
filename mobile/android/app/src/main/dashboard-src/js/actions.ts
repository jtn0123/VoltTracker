import { confirmAppDialog } from "./app-dialog";
import { bindPageDragScroll } from "./actions-page-scroll";
import { bindListenerGuarded, el } from "./core";
import { setDataState } from "./dataset-state";
import type { DataStateValue } from "./dataset-state";
import { createFocusTrap } from "./focus-trap";
import type { FocusTrap } from "./focus-trap";

type BusyButton = HTMLElement & {
  disabled?: boolean;
};

type StorageActions = {
  refreshStorage(): void;
  clearStorage(button?: BusyButton | null): void;
  shareBackup(button?: BusyButton | null): void;
  shareEncryptedBackup(button?: BusyButton | null): void;
  restoreBackup(button?: BusyButton | null): void;
  restoreEncryptedBackup(button?: BusyButton | null): void;
  exportDebugBundle(): void;
};

type SignalActions = {
  exportSignalLog(id: unknown): void;
  exportSignalLogs(): void;
  deleteSignalLog(id: unknown): void;
};

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

  // AbortController for every listener bound below. resetListeners() aborts
  // the current set and rebinds — useful for hot-reloading WebView content or
  // for tests that swap fixtures between runs.
  let controller = new AbortController();

  // Focus trap for the clear-DTC alertdialog: owns background inerting,
  // Tab/Escape containment, and focus save/restore to the opener. Created on
  // open, deactivated (and dropped) on close.
  let dtcTrap: FocusTrap | null = null;
  // Focus trap for the per-trip detail sheet (M7), same lifecycle as dtcTrap.
  let tripDetailTrap: FocusTrap | null = null;
  // Route key of the row whose "Details" button opened the trip-detail sheet.
  // Used on close to restore focus to a stable target when the original opener
  // button was removed by a mid-open trip-list re-render (else focus falls to
  // <body>). Cleared on close.
  let tripDetailOpenerKey: string | null = null;

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

  const pendingActionLoads = new Set<Promise<unknown>>();
  let storageActions: StorageActions | null = null;
  let storageActionsPromise: Promise<StorageActions> | null = null;
  let signalActions: SignalActions | null = null;
  let signalActionsPromise: Promise<SignalActions> | null = null;
  let demoActionsPromise: Promise<(dashboard: VoltDashboard, dashboardState: DashboardState) => void> | null = null;

  function loadActionScript<T>(
    src: string,
    label: string,
    resolveModule: () => T | null
  ): Promise<T> {
    const existing = resolveModule();
    if (existing) return Promise.resolve(existing);
    if (typeof VD.loadDashboardScript !== "function") {
      return Promise.reject(new Error("Dashboard script loader is not available."));
    }
    const load = VD.loadDashboardScript(src)
      .then(() => {
        const loaded = resolveModule();
        if (!loaded) throw new Error(label + " loaded but did not register.");
        return loaded;
      })
      .catch((err) => {
        const message = err instanceof Error ? err.message : String(err || "unknown error");
        if (typeof VD.reportClientError === "function") VD.reportClientError(label + ".load", message);
        if (typeof VD.setStatus === "function") {
          VD.setStatus({ state: "blocked", detail: "Dashboard action module failed to load." });
        }
        throw err;
      });
    pendingActionLoads.add(load);
    load.finally(() => pendingActionLoads.delete(load)).catch(() => {});
    return load;
  }

  function ensureStorageActions(): Promise<StorageActions> {
    if (storageActions) return Promise.resolve(storageActions);
    if (!storageActionsPromise) {
      storageActionsPromise = loadActionScript(
        "js/actions-storage.js",
        "actions-storage",
        () => {
          const factory = window.VoltDashboardActionModules?.createStorageActions;
          if (typeof factory !== "function") return null;
          storageActions = factory({ VD, bridge, withBusy }) as StorageActions;
          return storageActions;
        }
      ).catch((err) => {
        storageActionsPromise = null;
        throw err;
      });
    }
    return storageActionsPromise;
  }

  function ensureSignalActions(): Promise<SignalActions> {
    if (signalActions) return Promise.resolve(signalActions);
    if (!signalActionsPromise) {
      signalActionsPromise = loadActionScript(
        "js/actions-signals.js",
        "actions-signals",
        () => {
          const factory = window.VoltDashboardActionModules?.createSignalActions;
          if (typeof factory !== "function") return null;
          signalActions = factory({ VD, bridge }) as SignalActions;
          return signalActions;
        }
      ).catch((err) => {
        signalActionsPromise = null;
        throw err;
      });
    }
    return signalActionsPromise;
  }

  function ensureBrowserDemoStream(): Promise<(dashboard: VoltDashboard, dashboardState: DashboardState) => void> {
    const loaded = window.VoltDashboardActionModules?.runBrowserDemoStream;
    if (typeof loaded === "function") return Promise.resolve(loaded);
    if (!demoActionsPromise) {
      demoActionsPromise = loadActionScript(
        "js/actions-demo.js",
        "actions-demo",
        () => {
          const run = window.VoltDashboardActionModules?.runBrowserDemoStream;
          return typeof run === "function" ? run : null;
        }
      ).catch((err) => {
        demoActionsPromise = null;
        throw err;
      });
    }
    return demoActionsPromise;
  }

  const priorPendingLazyLoads = VD.pendingLazyLoads;
  VD.pendingLazyLoads = function pendingLazyLoadsWithActions() {
    const core = typeof priorPendingLazyLoads === "function" ? priorPendingLazyLoads() : Promise.resolve([]);
    const actions = Array.from(pendingActionLoads, (promise) => promise.catch(() => undefined));
    return Promise.all([core, Promise.all(actions)]).then(([coreResults, actionResults]) =>
      ([] as unknown[]).concat(coreResults || [], actionResults || [])
    );
  };

  function withStorageActions(run: (actions: StorageActions) => void): Promise<void> {
    return ensureStorageActions().then((actions) => {
      run(actions);
    }).catch(() => {});
  }

  function withSignalActions(run: (actions: SignalActions) => void): Promise<void> {
    return ensureSignalActions().then((actions) => {
      run(actions);
    }).catch(() => {});
  }

  function refreshStorage(): Promise<void> {
    return withStorageActions((actions) => actions.refreshStorage());
  }

  function clearStorage(button?: BusyButton | null): Promise<void> {
    return withStorageActions((actions) => actions.clearStorage(button));
  }

  function shareBackup(button?: BusyButton | null): Promise<void> {
    return withStorageActions((actions) => actions.shareBackup(button));
  }

  function shareEncryptedBackup(button?: BusyButton | null): Promise<void> {
    return withStorageActions((actions) => actions.shareEncryptedBackup(button));
  }

  function restoreBackup(button?: BusyButton | null): Promise<void> {
    return withStorageActions((actions) => actions.restoreBackup(button));
  }

  function restoreEncryptedBackup(button?: BusyButton | null): Promise<void> {
    return withStorageActions((actions) => actions.restoreEncryptedBackup(button));
  }

  function exportDebugBundle(): Promise<void> {
    return withStorageActions((actions) => actions.exportDebugBundle());
  }

  function exportSignalLog(id: unknown): Promise<void> {
    return withSignalActions((actions) => actions.exportSignalLog(id));
  }

  function exportSignalLogs(): Promise<void> {
    return withSignalActions((actions) => actions.exportSignalLogs());
  }

  function deleteSignalLog(id: unknown): Promise<void> {
    return withSignalActions((actions) => actions.deleteSignalLog(id));
  }

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
    // Scan is a native-only capability: an older APK's bridge may lack scan(),
    // in which case we must NOT silently fall through to a plain connect after
    // showConnectionProgress() already painted "Starting scan...". Tell the user
    // instead of issuing a connect they didn't ask for.
    if (scan && typeof bridge.scan !== "function") {
      VD.setStatus({ state: "idle", detail: "Scan is only available inside the Android app." });
      return;
    }
    showConnectionProgress(selected, scan);
    // Guard the bridge call so a quick double-tap doesn't issue two
    // overlapping connect/scan invocations against the adapter.
    withBusy(button, () => {
      if (scan) bridge.scan(selected.address, selected.name);
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
      setEnhancedProbeBadge("in app", "idle");
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
    // Actions are mutually exclusive, so stop at the first match instead of
    // re-testing every branch. (tpmsScan/detailProbe both route to the same
    // detail-probe handler, but each is matched as its own case.)
    switch (action) {
      case "permissions": bridge && VD.callBridge("requestPermissions"); return;
      case "refresh": bridge && VD.callBridge("refreshDevices"); return;
      case "refreshStorage": void refreshStorage(); return;
      case "clearStorage": void clearStorage(button); return;
      case "exportDebug": void exportDebugBundle(); return;
      case "backup": void shareBackup(button); return;
      case "backupEncrypted": void shareEncryptedBackup(button); return;
      case "restore": void restoreBackup(button); return;
      case "restoreEncrypted": void restoreEncryptedBackup(button); return;
      case "last": connectLastAdapter(button); return;
      case "scan": connectSelected(true, button); return;
      case "tpmsScan": tpmsScanSelected(button); return;
      case "detailProbe": detailProbeSelected(button); return;
      case "connect": connectSelected(false, button); return;
      case "demo": startDemo(); return;
      case "stopDemo": stopDemo(); return;
      case "stop": stopAll(); return;
      case "openClearDtc": openClearDtcWarning(); return;
      case "cancelClearDtc": closeClearDtcWarning(); return;
      case "confirmClearDtc": confirmClearDtc(button); return;
      case "previewDtcCodes": void previewDtcCodes(); return;
      case "clearPreviewDtcCodes": clearPreviewDtcCodes(); return;
      case "addMaintenance": VD.addMaintenanceEntry(); return;
      case "cancelMaintenance": VD.closeMaintenanceForm(); return;
      case "exportAllTripsCsv": exportAllTripsCsv(); return;
      case "exportChargeSessionsCsv": VD.exportChargeSessionsCsv(); return;
      case "closeTripDetail": closeTripDetail(); return;
    }
  }

  // Bulk all-trips CSV export (M6): forwards to native, which serializes every logged drive into one
  // CSV and opens the share sheet. Degrades to a status hint when the bridge is absent (web preview).
  function exportAllTripsCsv() {
    if (!bridge || typeof bridge.exportAllTripsCsv !== "function") {
      VD.setStatus({ state: "idle", detail: "All-trips export is available inside the Android app." });
      return;
    }
    bridge.exportAllTripsCsv();
  }

  // Submit handler for the inline maintenance form (M1/C4). Intercepts the native submit so the
  // WebView doesn't reload, then hands the read+forward off to storage-status.
  function onMaintenanceFormSubmit(event: Event) {
    event.preventDefault();
    if (typeof VD.submitMaintenanceForm === "function") VD.submitMaintenanceForm();
  }

  // Delegated handler for the per-row "Remove" button on a maintenance entry (M5). Reads the entry
  // id off data-maint-delete and forwards it to native; the bridge refreshes the list on success.
  function onMaintenanceDeleteClick(event: Event) {
    const target = event.target as Element | null;
    const button = target && (target.closest("[data-maint-delete]") as HTMLElement | null);
    if (!button) return;
    event.preventDefault();
    event.stopPropagation();
    const id = String(button.dataset.maintDelete || "").trim();
    if (!id) return;
    if (!bridge || typeof bridge.deleteMaintenanceEntry !== "function") {
      VD.setStatus({ state: "idle", detail: "Maintenance logging is available inside the Android app." });
      return;
    }
    // Removal is a no-undo data loss; route through the shared confirm dialog
    // (focus trap + background inert) like the other destructive actions before
    // forwarding to native.
    void confirmAppDialog({
      title: "Remove maintenance entry",
      message: "Remove this maintenance entry? This can't be undone.",
      confirmLabel: "Remove"
    }).then((confirmed) => {
      if (!confirmed) return;
      if (!bridge || typeof bridge.deleteMaintenanceEntry !== "function") return;
      bridge.deleteMaintenanceEntry(id);
    });
  }

  // Background nodes the trap inerts while the clear-DTC dialog is open: every
  // top-level / app / report-card child that is neither an ancestor nor a
  // descendant of the panel.
  function dtcBackgroundNodes(panel: HTMLElement): HTMLElement[] {
    const keep = new Set<Element>();
    let cursor: Element | null = panel;
    while (cursor && cursor !== document.body) {
      keep.add(cursor);
      cursor = cursor.parentElement;
    }
    const candidates = Array.from(document.querySelectorAll("body > *, main.app > *, .diagnostic-report-card > *"));
    return candidates.filter((node): node is HTMLElement =>
      node instanceof HTMLElement && !keep.has(node) && !panel.contains(node) && !node.contains(panel)
    );
  }

  // Count of stored permanent (Mode 0A) DTCs — codes the Mode 04 clear cannot erase (M10).
  // Prefers the summary's status-count map (authoritative) and falls back to counting the
  // rendered code rows by status.
  function permanentDtcCount(): number {
    const storage = state.storage || {};
    const counts = storage.diagnosticCodeStatusCounts || {};
    const fromCounts = Number(counts.permanent);
    if (Number.isFinite(fromCounts) && fromCounts > 0) return fromCounts;
    const codes = Array.isArray(storage.latestDiagnosticCodes) ? storage.latestDiagnosticCodes : [];
    return codes.filter((code) => String(code.status || "").trim().toLowerCase() === "permanent").length;
  }

  function openClearDtcWarning() {
    const panel = el("dtcClearWarning");
    const ack = el("dtcClearAckBox") as HTMLInputElement | null;
    const confirm = el("dtcClearConfirmBtn") as HTMLButtonElement | null;
    if (!panel) return;
    // Surface the permanent-code caveat: Mode 04 won't erase them, so warn before the user
    // commits to a clear expecting the light to stay off (M10).
    const note = el("dtcClearPermanentNote");
    if (note) {
      const permanent = permanentDtcCount();
      if (permanent > 0) {
        note.textContent =
          `${permanent} permanent code${permanent === 1 ? "" : "s"} cannot be cleared by this command and will remain after the reset.`;
        note.hidden = false;
      } else {
        note.textContent = "";
        note.hidden = true;
      }
    }
    // The shared trap remembers the trigger (current focus) so it can be
    // restored on close, and inerts the background.
    dtcTrap = createFocusTrap(panel, {
      backgroundNodes: () => dtcBackgroundNodes(panel),
      onEscape: closeClearDtcWarning
    });
    panel.hidden = false;
    dtcTrap.activate();
    if (ack) ack.checked = false;
    if (confirm) confirm.disabled = true;
    panel.scrollIntoView({ behavior: "smooth", block: "nearest" });
    // Move focus into the alertdialog so keyboard/SR users land on the warning.
    if (typeof panel.focus === "function") panel.focus();
  }

  function closeClearDtcWarning() {
    const panel = el("dtcClearWarning");
    if (panel) panel.hidden = true;
    // Restore the inert background and return focus to whatever opened the panel.
    if (dtcTrap) {
      dtcTrap.deactivate();
      dtcTrap = null;
    }
  }

  function dtcDialogOpen() {
    const panel = el("dtcClearWarning");
    return Boolean(panel && !panel.hidden);
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

  // Delegated handler for the per-row "Export GPX / Export CSV" buttons (map session list +
  // charge rows). Reads the route key + format off data-trip-export(-key) and forwards to the
  // native exporter, which writes the file and opens the share sheet. Stops the click before it
  // bubbles to the row-select / long-press handlers on the same list.
  function onTripExportClick(event: Event) {
    const target = event.target as Element | null;
    const button = target && (target.closest("[data-trip-export]") as HTMLElement | null);
    if (!button) return;
    event.preventDefault();
    event.stopPropagation();
    suppressNextMapClick = true;
    const wantCsv = button.dataset.tripExport === "csv";
    const routeKey = String(button.dataset.tripExportKey || "").trim();
    if (!routeKey) return;
    const fn = wantCsv ? bridge?.exportTripCsv : bridge?.exportTripGpx;
    if (!bridge || typeof fn !== "function") {
      VD.setStatus({ state: "idle", detail: "Drive export is available inside the Android app." });
      return;
    }
    fn.call(bridge, routeKey);
  }

  // Delegated handler for the per-row "Rename / Name" button on a stored map route (M4). Reads the
  // route key + current label off data-trip-rename(-label), prompts for a new name, and forwards it
  // to the native setTripLabel. An empty/blank submission clears the label; cancel is a no-op.
  function onTripRenameClick(event: Event) {
    const target = event.target as Element | null;
    const button = target && (target.closest("[data-trip-rename]") as HTMLElement | null);
    if (!button) return;
    event.preventDefault();
    event.stopPropagation();
    suppressNextMapClick = true;
    const routeKey = String(button.dataset.tripRename || "").trim();
    if (!routeKey) return;
    if (!bridge || typeof bridge.setTripLabel !== "function") {
      VD.setStatus({ state: "idle", detail: "Trip rename is available inside the Android app." });
      return;
    }
    const current = String(button.dataset.tripRenameLabel || "");
    const next = window.prompt("Name this drive (leave blank to clear):", current);
    // prompt returns null on Cancel — do nothing. An empty string clears the label.
    if (next === null) return;
    bridge.setTripLabel(routeKey, next.trim());
  }

  // Delegated handler for the per-row favorite star on a stored map route (M4 favorites half).
  // Reads the route key + current state off data-trip-favorite(-state) and forwards the FLIPPED
  // value to native; the bridge persists it and refreshes the list so the star re-renders. Stops
  // the click before it bubbles to the row-select / long-press handlers on the same list.
  function onTripFavoriteClick(event: Event) {
    const target = event.target as Element | null;
    const button = target && (target.closest("[data-trip-favorite]") as HTMLElement | null);
    if (!button) return;
    event.preventDefault();
    event.stopPropagation();
    suppressNextMapClick = true;
    const routeKey = String(button.dataset.tripFavorite || "").trim();
    if (!routeKey) return;
    if (!bridge || typeof bridge.setTripFavorite !== "function") {
      VD.setStatus({ state: "idle", detail: "Trip favorites are available inside the Android app." });
      return;
    }
    const next = button.dataset.tripFavoriteState !== "1";
    // Optimistically paint the new favorite state on the tapped star before the
    // bridge call so the user gets instant feedback; the subsequent native list
    // re-render reconciles. Mirrors buildFavoriteButton()'s render contract so a
    // rapid second tap (which re-reads tripFavoriteState) flips correctly too.
    button.dataset.tripFavoriteState = next ? "1" : "0";
    button.setAttribute("aria-pressed", next ? "true" : "false");
    button.classList.toggle("is-favorite", next);
    button.title = next ? "Remove this drive from favorites." : "Add this drive to favorites.";
    button.setAttribute("aria-label", next ? "Unfavorite this drive" : "Favorite this drive");
    button.textContent = next ? "★" : "☆";
    bridge.setTripFavorite(routeKey, next);
  }

  // ---- M7 per-trip detail sheet --------------------------------------------
  // Background nodes the trap inerts while the detail sheet is open: every
  // top-level / app child that isn't an ancestor/descendant of the sheet.
  function tripDetailBackgroundNodes(panel: HTMLElement): HTMLElement[] {
    const keep = new Set<Element>();
    let cursor: Element | null = panel;
    while (cursor && cursor !== document.body) {
      keep.add(cursor);
      cursor = cursor.parentElement;
    }
    const candidates = Array.from(document.querySelectorAll("body > *, main.app > *"));
    return candidates.filter((node): node is HTMLElement =>
      node instanceof HTMLElement && !keep.has(node) && !panel.contains(node) && !node.contains(panel)
    );
  }

  // Delegated handler for a map-session row's "Details" button (M7). Reads the
  // route key, asks the map module to populate + show the detail sheet, then
  // activates a focus trap. Stops the click so it doesn't also select the row.
  function onTripDetailClick(event: Event) {
    const target = event.target as Element | null;
    const button = target && (target.closest("[data-trip-detail]") as HTMLElement | null);
    if (!button) return;
    event.preventDefault();
    event.stopPropagation();
    suppressNextMapClick = true;
    const routeKey = String(button.dataset.tripDetail || "").trim();
    if (!routeKey) return;
    openTripDetail(routeKey);
  }

  function openTripDetail(routeKey: string) {
    if (typeof VD.openTripDetail !== "function") return;
    const opened = VD.openTripDetail(routeKey);
    if (!opened) return;
    const panel = el("tripDetailSheet");
    if (!panel) return;
    tripDetailOpenerKey = routeKey || null;
    tripDetailTrap = createFocusTrap(panel, {
      backgroundNodes: () => tripDetailBackgroundNodes(panel),
      onEscape: closeTripDetail,
    });
    panel.hidden = false;
    tripDetailTrap.activate();
    if (typeof panel.scrollIntoView === "function") panel.scrollIntoView({ behavior: "smooth", block: "nearest" });
    if (typeof panel.focus === "function") panel.focus();
  }

  // Find a stable focus target for closeTripDetail() when the original opener
  // button has been removed by a mid-open trip-list re-render. Prefer the row
  // matching the opener's route key (the Details button if it survived, else the
  // row-select button), and fall back to the trip-list container so focus never
  // lands on <body>. Iterates rather than building an attribute selector because
  // the route key is an untyped native value (CSS-escape-unsafe).
  function tripDetailFocusFallback(): HTMLElement | null {
    const key = String(tripDetailOpenerKey || "").trim();
    if (key) {
      const detailBtns = document.querySelectorAll<HTMLElement>("[data-trip-detail]");
      for (const node of Array.from(detailBtns)) {
        if (String(node.dataset.tripDetail || "").trim() === key) return node;
      }
      const rows = document.querySelectorAll<HTMLElement>("[data-map-session]");
      for (const node of Array.from(rows)) {
        if (String(node.dataset.mapSession || "").trim() === key) return node;
      }
    }
    return el("mapSessionList");
  }

  function closeTripDetail() {
    if (typeof VD.closeTripDetail === "function") VD.closeTripDetail();
    const panel = el("tripDetailSheet");
    if (panel) panel.hidden = true;
    if (tripDetailTrap) {
      // deactivate() restores focus to the saved opener only when it still exists
      // in the DOM; a mid-open list re-render can remove it, dropping focus to
      // <body>. Detect that and redirect to a stable target instead.
      tripDetailTrap.deactivate();
      tripDetailTrap = null;
      const active = document.activeElement;
      if (!active || active === document.body) {
        const fallback = tripDetailFocusFallback();
        if (fallback && typeof fallback.focus === "function") {
          // The list container is a plain <div> (not in the Tab order); give it a
          // programmatic-only tabindex so .focus() actually lands. Buttons already
          // focus natively, so the no-op assignment there is harmless.
          if (!fallback.hasAttribute("tabindex")) fallback.setAttribute("tabindex", "-1");
          fallback.focus();
        }
      }
    }
    tripDetailOpenerKey = null;
  }

  // ---- M4 trip-list search / sort / favorites controls ---------------------
  // Each handler mutates only the control's own UI state (input value lives on
  // the input; the active sort button + favorites toggle carry their state in
  // class/data attrs) and then re-renders ONLY the list via refreshMapSessionList
  // so the map view isn't tugged. Defensive about the bridge-less/older host: the
  // controls are pure client-side, so they always work.
  function refreshTripList() {
    if (typeof VD.refreshMapSessionList === "function") VD.refreshMapSessionList();
  }

  function onMapSessionSearchInput() {
    refreshTripList();
  }

  function onMapSortClick(event: Event) {
    const target = event.target as Element | null;
    const button = target && (target.closest("[data-map-sort]") as HTMLElement | null);
    if (!button) return;
    event.preventDefault();
    const chosen = button.dataset.mapSort === "distance" ? "distance" : "recent";
    document.querySelectorAll<HTMLElement>("[data-map-sort]").forEach((node) => {
      const on = node.dataset.mapSort === chosen;
      node.classList.toggle("is-active", on);
      node.setAttribute("aria-pressed", String(on));
    });
    refreshTripList();
  }

  function onMapFavoritesOnlyClick(event: Event) {
    const target = event.target as Element | null;
    const button = target && (target.closest("[data-map-favorites-only]") as HTMLElement | null);
    if (!button) return;
    event.preventDefault();
    const next = button.dataset.on !== "true";
    button.dataset.on = String(next);
    button.setAttribute("aria-pressed", String(next));
    button.setAttribute("aria-label", next ? "Showing favorites only" : "Show favorites only");
    button.textContent = next ? "★ Favorites" : "☆ Favorites";
    refreshTripList();
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
      // Start every demo run from a clean live route so a previous demo/live track isn't
      // stitched onto the new synthetic GPS (stop→start again, or a scenario re-seed that
      // keeps demo active). clearLivePosition lives in the lazy map module; fall back to
      // clearing state directly when it hasn't loaded yet (map.ts seeds from state on load).
      if (typeof VD.clearLivePosition === "function") VD.clearLivePosition();
      else { state.liveRoutePoints = []; state.liveRouteStartedAtMs = null; }
      seedDemoScenario();
      VD.setDemoActive(true, "Demo preview is running.");
      // Choose by method availability, not bare bridge presence: an older APK's
      // bridge object may lack demo(), and callBridge would then no-op while the
      // UI claims the demo is running. Fall back to the browser demo instead.
      if (bridge && typeof bridge.demo === "function") VD.callBridge("demo");
      else void runBrowserDemo();
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
    void refreshStorage();
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

  function runBrowserDemo(): Promise<void> {
    return ensureBrowserDemoStream().then((stream) => {
      stream(VD, state);
    }).catch(() => {});
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
    // Bound before the row-select click so the export/rename buttons' stopPropagation keeps a tap
    // from also selecting the session.
    bindListenerGuarded("mapSessionList", "click", onTripFavoriteClick, opts);
    bindListenerGuarded("mapSessionList", "click", onTripDetailClick, opts);
    bindListenerGuarded("mapSessionList", "click", onTripRenameClick, opts);
    bindListenerGuarded("mapSessionList", "click", onTripExportClick, opts);
    bindListenerGuarded("mapSessionList", "click", onSessionClick, opts);
    // Per-entry "Remove" on a maintenance row (M5) — the list is re-rendered dynamically, so
    // delegate off the stable container.
    bindListenerGuarded("maintenanceList", "click", onMaintenanceDeleteClick, opts);
    bindListenerGuarded("maintenanceForm", "submit", onMaintenanceFormSubmit, opts);
    // M4 trip-list controls: search (live filter), sort toggle, favorites-only.
    bindListenerGuarded("mapSessionSearch", "input", onMapSessionSearchInput, opts);
    bindListenerGuarded("mapSessionSort", "click", onMapSortClick, opts);
    bindListenerGuarded("mapSessionFavoritesOnly", "click", onMapFavoritesOnlyClick, opts);
    bindListenerGuarded("mapSessionList", "pointerdown", onMapSessionPointerDown, opts);
    bindListenerGuarded("mapSessionList", "pointerup", onMapSessionPointerEnd, opts);
    bindListenerGuarded("mapSessionList", "pointerleave", onMapSessionPointerEnd, opts);
    bindListenerGuarded("mapSessionList", "pointercancel", onMapSessionPointerEnd, opts);
    bindListenerGuarded("mapSessionList", "contextmenu", onMapSessionContextMenu, opts);
    // The new drive-chip strip uses the same [data-map-session] attribute, so
    // share the handler. Without this, tapping a chip did nothing.
    bindListenerGuarded("mapDriveChips", "click", onSessionClick, opts);
    bindListenerGuarded("mapDriveChips", "pointerdown", onMapSessionPointerDown, opts);
    bindListenerGuarded("mapDriveChips", "pointerup", onMapSessionPointerEnd, opts);
    bindListenerGuarded("mapDriveChips", "pointerleave", onMapSessionPointerEnd, opts);
    bindListenerGuarded("mapDriveChips", "pointercancel", onMapSessionPointerEnd, opts);
    bindListenerGuarded("mapDriveChips", "contextmenu", onMapSessionContextMenu, opts);
    bindListenerGuarded("mapFullBtn", "click", () => {
      state.mapFull = !state.mapFull;
      void VD.requestMapRender().catch(() => {});
    }, opts);
    bindListenerGuarded("liveSignalsFilter", "click", (event: Event) => {
      const target = (event.target as HTMLElement | null)?.closest("[data-live-signal-filter]") as HTMLElement | null;
      if (!target) return;
      state.liveSignalsFilter = target.dataset.liveSignalFilter === "missing" ? "missing" : "all";
      if (typeof VD.updateDiagnostics === "function") VD.updateDiagnostics();
    }, opts);
    bindListenerGuarded("mapFollowBtn", "click", () => {
      // Toggle live-follow; turning it on recenters on the current drive. The map
      // module owns the recenter + button state (it may not be loaded yet, hence
      // the guard — the button is only visible once a live route exists).
      if (typeof VD.setMapFollowLive === "function") VD.setMapFollowLive();
    }, opts);
    bindListenerGuarded("errorBannerHelp", "click", () => {
      if (typeof VD.ensureTroubleshooterModule !== "function") return;
      void VD.ensureTroubleshooterModule()
        .then((dashboard) => {
          const ts = dashboard.troubleshooter;
          if (ts && typeof ts.open === "function") ts.open();
        })
        .catch(() => {});
    }, opts);
    document.addEventListener("click", handleDtcSearch, opts);
    bindListenerGuarded("dtcSearchInput", "input", renderDtcLookup, opts);
    document.addEventListener("change", (event) => {
      const target = event.target as HTMLInputElement | null;
      if (target && target.id === "dtcClearAckBox") {
        const confirm = el("dtcClearConfirmBtn") as HTMLButtonElement | null;
        if (confirm) confirm.disabled = !target.checked;
      }
    }, opts);
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
        void exportSignalLog((signalExport as HTMLElement).dataset.signalExport);
        return;
      }
      const signalDelete = target && target.closest("[data-signal-delete]");
      if (signalDelete) {
        void deleteSignalLog((signalDelete as HTMLElement).dataset.signalDelete);
        return;
      }
    }, opts);
    bindListenerGuarded("permissionBtn", "click", () => handleAction("permissions"), opts);
    bindListenerGuarded("refreshBtn", "click", () => handleAction("refresh"), opts);
    bindListenerGuarded("lastBtn", "click", () => handleAction("last"), opts);
    bindListenerGuarded("scanBtn", "click", (event) => handleAction("scan", event.currentTarget as BusyButton), opts);
    bindListenerGuarded("tpmsScanBtn", "click", (event) => handleAction("tpmsScan", event.currentTarget as BusyButton), opts);
    bindListenerGuarded("exportSignalLogsBtn", "click", () => { void exportSignalLogs(); }, opts);
    bindListenerGuarded("connectBtn", "click", (event) => {
      const btn = el("connectBtn");
      const action = (btn && btn.dataset.primaryAction) || "connect";
      handleAction(action, event.currentTarget as BusyButton);
    }, opts);
    bindListenerGuarded("demoStopBtn", "click", stopDemo, opts);
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
    // The connection-tools module owns its own AbortController; re-arm it here so its
    // proactive-tools buttons are reset alongside the rest of the UI rather than left
    // double-bound or dead after an in-place re-bootstrap.
    if (typeof VD.bindConnectionTools === "function") VD.bindConnectionTools();
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
  if (typeof VD.updateDiagnosticCodeUi === "function") VD.updateDiagnosticCodeUi();
  // Initial paint of the Drive-tab live polish — without this the session chip
  // strip + micro-charts stay empty until the first telemetry sample arrives.
  if (typeof VD.renderDriveLive === "function") VD.renderDriveLive();
  // refreshDevices() re-renders the adapter picker + connect button, so it stays on the
  // bootstrap path — deferring it to idle could re-render the button mid-interaction.
  refreshDevices();
  if (bridge && typeof bridge.dashboardReady === "function") bridge.dashboardReady();
  // refreshStorage()/loadTrips()/loadInsights() each make a synchronous bridge call into
  // SQLite to populate the storage summary + Trips/Insights tabs — none is the first visible
  // (Drive/Status) view, and none mutates an interactive control, yet on the old ordering they
  // ran on the bootstrap path and added latency before first paint. Defer them off the critical
  // path (after the dashboardReady handshake) so the live view renders immediately; the data
  // fills in a frame later.
  const loadDeferredPanels = () => {
    // Guarded like the other VD.* cross-module calls: this runs a frame after the
    // dashboardReady handshake, so a bootstrap context missing a loader must not
    // throw asynchronously and destabilize startup.
    void refreshStorage();
    if (typeof VD.loadTrips === "function") VD.loadTrips();
    if (typeof VD.loadInsights === "function") VD.loadInsights();
  };
  if (typeof window.requestIdleCallback === "function") {
    window.requestIdleCallback(loadDeferredPanels, { timeout: 1500 });
  } else {
    setTimeout(loadDeferredPanels, 0);
  }
  requestAnimationFrame(() => VD.scrollAppToTop());
  setTimeout(() => VD.scrollAppToTop(), 200);

export {};
