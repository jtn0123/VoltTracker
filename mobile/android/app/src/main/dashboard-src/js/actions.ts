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

  function bridgeFailureMessage(method: string, err: unknown) {
    const detail = err instanceof Error && err.message ? err.message : String(err || "");
    return `bridge.${method} failed${detail ? `: ${detail}` : ""}`;
  }

  function reportBridgeActionFailure(method: string, err: unknown, statusDetail?: string) {
    const message = bridgeFailureMessage(method, err);
    console.warn(message);
    let reported = false;
    if (typeof VD.reportClientError === "function") {
      try {
        VD.reportClientError("bridge.call_failed", message);
        reported = true;
      } catch (_ignored) {}
    }
    if (!reported && bridge && typeof bridge.logClientError === "function") {
      try {
        bridge.logClientError("bridge.call_failed", message);
      } catch (_ignored) {}
    }
    if (statusDetail) VD.setStatus({ state: "blocked", detail: statusDetail });
  }

  function bridgeFunction(method: string): ((...args: unknown[]) => unknown) | null {
    const target = bridge as unknown as Record<string, unknown> | null;
    const fn = target && target[method];
    return typeof fn === "function" ? fn.bind(bridge) as (...args: unknown[]) => unknown : null;
  }

  function callBridgeAction(method: string, args: unknown[] = [], statusDetail?: string) {
    const fn = bridgeFunction(method);
    if (!fn) return false;
    try {
      fn(...args);
      return true;
    } catch (err) {
      reportBridgeActionFailure(method, err, statusDetail);
      return false;
    }
  }

  function readBridgeValue(method: string, args: unknown[] = [], statusDetail?: string): unknown {
    const fn = bridgeFunction(method);
    if (!fn) return undefined;
    try {
      return fn(...args);
    } catch (err) {
      reportBridgeActionFailure(method, err, statusDetail);
      return undefined;
    }
  }

  function startupMark(name: string) {
    callBridgeAction("startupMark", [name]);
  }

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
      VD.setStatus({ state: "ready", detail: "Browser preview ready. Start Demo / Testing to view sample telemetry." });
      return;
    }
    if (typeof bridge.refreshDevices === "function") {
      callBridgeAction("refreshDevices", [], "Could not refresh adapter list.");
      return;
    }
    // callBridge tolerates a native build that predates a method (warns once,
    // returns undefined) instead of throwing mid-refresh.
    const devices = readBridgeValue("listDevices", [], "Could not refresh adapter list.");
    if (devices !== undefined) VD.setDevices(devices);
    if (typeof bridge.getDeviceHistory === "function") {
      const history = readBridgeValue("getDeviceHistory", [], "Could not refresh adapter history.");
      if (history !== undefined) VD.setHistory(history);
    }
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
      if (!callBridgeAction("requestPermissions", [], "Could not request Bluetooth permissions.")) {
        pendingPermissionConnect = null;
        return;
      }
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

  function connectSelected(scan: boolean, button?: BusyButton | null, quick = false) {
    const selected = VD.getSelectedDevice();
    if (!selected) {
      explainMissingAdapter(scan, true);
      return;
    }
    if (!bridge) return;
    // Scan is a native-only capability: an older APK's bridge may lack scan() (or
    // the newer quickScan()), in which case we must NOT silently fall through to a
    // plain connect after showConnectionProgress() already painted "Starting
    // scan...". Tell the user instead of issuing a connect they didn't ask for.
    const scanMethod = quick ? "quickScan" : "scan";
    if (scan && typeof bridge[scanMethod] !== "function") {
      VD.setStatus({
        state: "idle",
        detail: quick
          ? "Quick scan needs a newer version of the Android app."
          : "Scan is only available inside the Android app."
      });
      return;
    }
    showConnectionProgress(selected, scan);
    // Guard the bridge call so a quick double-tap doesn't issue two
    // overlapping connect/scan invocations against the adapter.
    withBusy(button, () => {
      callBridgeAction(
        scan ? scanMethod : "connect",
        [selected.address, selected.name],
        scan
          ? quick
            ? "Could not start quick scan."
            : "Could not start adapter scan."
          : "Could not start connection."
      );
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
    withBusy(button, () => {
      if (!callBridgeAction("detailProbe", [selected.address, selected.name, stage], "Could not start detail probe.")) {
        setEnhancedProbeBadge("blocked", "blocked");
      }
    });
  }

  // 96-cell voltage probe (Battery tab). Runs the dedicated cells stage against the
  // remembered adapter — no device picker on the Battery tab, so "last" is the right seam.
  function cellProbeLast(button?: BusyButton | null) {
    if (!bridge || typeof bridge.detailProbeLast !== "function") {
      VD.setStatus({ state: "idle", detail: "The cell probe is only available inside the Android app." });
      return;
    }
    withBusy(button, () => {
      callBridgeAction("detailProbeLast", ["cells"], "Could not start the cell probe.");
    });
  }

  function connectLastAdapter(button?: BusyButton | null) {
    const last = typeof VD.getLastDevice === "function" ? VD.getLastDevice() : state.lastDevice;
    if (!last || !String(last.address || "").trim()) {
      showBlockedAdapterFeedback("Connect once or pick a paired adapter before using Last.");
      return;
    }
    // Check the method BEFORE painting "Connecting…": on a bridge that lacks
    // connectLast (older APK / browser preview), painting first would leave the
    // UI stuck on "Connecting…" with nothing to reset it. connectSelected checks
    // availability up front for the same reason.
    if (!bridge || typeof bridge.connectLast !== "function") {
      VD.setStatus({ state: "idle", detail: "Reconnecting to the last adapter is only available inside the Android app." });
      return;
    }
    showConnectionProgress(last, false);
    withBusy(button, () => callBridgeAction("connectLast", [], "Could not reconnect to the last adapter."));
  }

  function handleAction(action: string | undefined, button: BusyButton | null = null) {
    // Actions are mutually exclusive, so stop at the first match instead of
    // re-testing every branch. (tpmsScan/detailProbe both route to the same
    // detail-probe handler, but each is matched as its own case.)
    switch (action) {
      case "permissions": bridge && callBridgeAction("requestPermissions", [], "Could not request Bluetooth permissions."); return;
      case "refresh": withBusy(button, () => refreshDevices()); return;
      case "refreshStorage": void refreshStorage(); return;
      case "clearStorage": void clearStorage(button); return;
      case "exportDebug": void exportDebugBundle(); return;
      case "backup": void shareBackup(button); return;
      case "backupEncrypted": void shareEncryptedBackup(button); return;
      case "restore": void restoreBackup(button); return;
      case "restoreEncrypted": void restoreEncryptedBackup(button); return;
      case "last": connectLastAdapter(button); return;
      case "scan": connectSelected(true, button); return;
      case "quickScan": connectSelected(true, button, true); return;
      case "tpmsScan": tpmsScanSelected(button); return;
      case "detailProbe": detailProbeSelected(button); return;
      case "cellProbe": cellProbeLast(button); return;
      case "connect": connectSelected(false, button); return;
      case "demo": startDemo(); return;
      case "stopDemo": stopDemo(); return;
      case "stop": stopAll(); return;
      case "openClearDtc": openClearDtcWarning(); return;
      case "openDebugLogs": {
        // Open + scroll to the collapsed session-review block instead of
        // re-selecting the tab (the old data-nav-jump just scrolled to top).
        const review = el("sessionReviewDetails") as HTMLDetailsElement | null;
        if (review) {
          review.open = true;
          review.scrollIntoView({ behavior: "smooth", block: "start" });
        }
        return;
      }
      case "cancelClearDtc": closeClearDtcWarning(); return;
      case "confirmClearDtc": confirmClearDtc(button); return;
      case "previewDtcCodes": void previewDtcCodes(); return;
      case "clearPreviewDtcCodes": clearPreviewDtcCodes(); return;
      case "addMaintenance": VD.addMaintenanceEntry(); return;
      case "cancelMaintenance": VD.closeMaintenanceForm(); return;
      case "exportAllTripsCsv": exportAllTripsCsv(); return;
      case "exportChargeSessionsCsv": VD.exportChargeSessionsCsv(); return;
      case "closeTripDetail": closeTripDetail(); return;
      case "shareTripCard": if (typeof VD.shareTripCard === "function") VD.shareTripCard(); return;
    }
  }

  // Bulk all-trips CSV export (M6): forwards to native, which serializes every logged drive into one
  // CSV and opens the share sheet. Degrades to a status hint when the bridge is absent (web preview).
  function exportAllTripsCsv() {
    if (!bridge || typeof bridge.exportAllTripsCsv !== "function") {
      VD.setStatus({ state: "idle", detail: "All-trips export is only available inside the Android app." });
      return;
    }
    callBridgeAction("exportAllTripsCsv", [], "All-trips export failed.");
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
      VD.setStatus({ state: "idle", detail: "Maintenance logging is only available inside the Android app." });
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
      callBridgeAction("deleteMaintenanceEntry", [id], "Could not remove maintenance entry.");
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
    if (typeof panel.scrollIntoView === "function") panel.scrollIntoView({ behavior: "smooth", block: "nearest" });
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
      if (callBridgeAction("clearVehicleDtcCodes", [], "Could not clear diagnostic codes.")) {
        closeClearDtcWarning();
      }
    });
  }

  // Snapshot of the real cached DTC storage taken before a Preview overwrites it,
  // so Clear can restore the real codes/counts/badge instead of blanking them
  // until the next native push. Null when no preview is currently shadowing real data.
  let dtcPreviewSnapshot: {
    latestDiagnosticCodes: VoltStorageSummary["latestDiagnosticCodes"];
    diagnosticCodeCount: VoltStorageSummary["diagnosticCodeCount"];
    diagnosticCodeStatusCounts: VoltStorageSummary["diagnosticCodeStatusCounts"];
  } | null = null;

  function previewDtcCodes(): Promise<void> | undefined {
    if (!Array.isArray(VD.dtcSampleCodes) && typeof VD.ensureDtcData === "function") {
      VD.setStatus({ state: "ready", detail: "Loading DTC examples…" });
      return VD.ensureDtcData()
        .then(previewDtcCodes)
        .catch(() => VD.setStatus({ state: "blocked", detail: "DTC examples could not be loaded." }));
    }
    const samples = Array.isArray(VD.dtcSampleCodes) ? VD.dtcSampleCodes : [];
    const storage = state.storage || (state.storage = {});
    // Snapshot the real DTC cache once so Clear can put it back — Preview must not
    // destroy real cached codes on a real device. Skip re-snapshotting while a
    // preview is already active, or it would capture the sample data instead.
    if (!dtcPreviewSnapshot) {
      dtcPreviewSnapshot = {
        latestDiagnosticCodes: storage.latestDiagnosticCodes,
        diagnosticCodeCount: storage.diagnosticCodeCount,
        diagnosticCodeStatusCounts: storage.diagnosticCodeStatusCounts,
      };
    }
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
    if (dtcPreviewSnapshot) {
      // Restore the real cached DTCs that Preview shadowed, rather than blanking
      // them until the next native push. A snapshot of an empty cache restores to
      // the same empty shape.
      storage.latestDiagnosticCodes = dtcPreviewSnapshot.latestDiagnosticCodes ?? [];
      storage.diagnosticCodeCount = dtcPreviewSnapshot.diagnosticCodeCount ?? 0;
      storage.diagnosticCodeStatusCounts = dtcPreviewSnapshot.diagnosticCodeStatusCounts ?? {};
      dtcPreviewSnapshot = null;
    } else {
      storage.latestDiagnosticCodes = [];
      storage.diagnosticCodeCount = 0;
      storage.diagnosticCodeStatusCounts = {};
    }
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
      VD.setStatus({ state: "idle", detail: "Map cleanup is only available inside the Android app." });
      return;
    }
    callBridgeAction("markTripNotTrip", [clean], "Could not hide this drive from Trips.");
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
      VD.setStatus({ state: "idle", detail: "Drive export is only available inside the Android app." });
      return;
    }
    callBridgeAction(wantCsv ? "exportTripCsv" : "exportTripGpx", [routeKey], "Drive export failed.");
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
      VD.setStatus({ state: "idle", detail: "Trip rename is only available inside the Android app." });
      return;
    }
    const current = String(button.dataset.tripRenameLabel || "");
    const next = window.prompt("Name this drive (leave blank to clear):", current);
    // prompt returns null on Cancel — do nothing. An empty string clears the label.
    if (next === null) return;
    callBridgeAction("setTripLabel", [routeKey, next.trim()], "Could not rename this drive.");
  }

  function paintFavoriteButton(button: HTMLElement, favorite: boolean) {
    button.dataset.tripFavoriteState = favorite ? "1" : "0";
    button.setAttribute("aria-pressed", favorite ? "true" : "false");
    button.classList.toggle("is-favorite", favorite);
    button.title = favorite ? "Remove this drive from favorites." : "Add this drive to favorites.";
    button.setAttribute("aria-label", favorite ? "Unfavorite this drive" : "Favorite this drive");
    button.textContent = favorite ? "★" : "☆";
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
      VD.setStatus({ state: "idle", detail: "Trip favorites are only available inside the Android app." });
      return;
    }
    const next = button.dataset.tripFavoriteState !== "1";
    // Optimistically paint the new favorite state on the tapped star before the
    // bridge call so the user gets instant feedback; the subsequent native list
    // re-render reconciles. Mirrors buildFavoriteButton()'s render contract so a
    // rapid second tap (which re-reads tripFavoriteState) flips correctly too.
    paintFavoriteButton(button, next);
    if (!callBridgeAction("setTripFavorite", [routeKey, next], "Could not update drive favorite.")) {
      paintFavoriteButton(button, !next);
    }
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

  // Debounced (140ms, matching the chart resize debouncers): each keystroke
  // used to synchronously rebuild up to 80 session rows (each a button plus a
  // five-button export row), which is visible jank while typing on-device.
  let mapSearchDebounce = 0;
  function onMapSessionSearchInput() {
    window.clearTimeout(mapSearchDebounce);
    mapSearchDebounce = window.setTimeout(refreshTripList, 140);
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
      callBridgeAction("openExternalSearch", [code], "Could not open external search.");
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
    // Same pill treatment as the .dtc-search chip in the codes list — the
    // bare link-btn styling read as plain text with no affordance.
    web.className = "dtc-search dtc-lookup-web";
    web.dataset.dtcSearch = code;
    web.textContent = "Search the web for " + code;
    out.appendChild(web);
  }

  // Shared by startDemo() and the scenario-picker handler so the status detail
  // can't drift between the two demo entry paths (it should also stay aligned
  // with #topDemoInfo's aria-label in topbar.html).
  const DEMO_RUNNING_DETAIL = "Demo / Testing is running.";

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
      VD.setDemoActive(true, DEMO_RUNNING_DETAIL);
      // Choose by method availability, not bare bridge presence: an older APK's
      // bridge object may lack demo(), and callBridge would then no-op while the
      // UI claims the demo is running. Fall back to the browser demo instead.
      if (bridge && typeof bridge.demo === "function") {
        if (!callBridgeAction("demo", [], "Native demo could not start. Running browser demo instead.")) {
          void runBrowserDemo();
        }
      } else {
        void runBrowserDemo();
      }
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
      if (typeof VD.renderInsightStats === "function") VD.renderInsightStats();
      if (typeof VD.renderInsightScatter === "function") VD.renderInsightScatter();
      return;
    }
    void refreshStorage();
    const refreshRollups = () => {
      if (typeof VD.loadTrips === "function") VD.loadTrips(VD.forceLazyStorageRead);
      if (typeof VD.loadInsights === "function") VD.loadInsights(VD.forceLazyStorageRead);
    };
    if (typeof VD.ensureInsightsModule === "function") {
      void VD.ensureInsightsModule().then(refreshRollups).catch(() => {});
    } else {
      refreshRollups();
    }
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
    const stopped = !bridge || !state.demoActive || callBridgeAction("disconnect", [], "Could not stop the native connection.");
    VD.clearDemoTelemetry();
    if (typeof VD.clearLivePosition === "function") VD.clearLivePosition();
    VD.setDemoActive(false);
    refreshNativeDataAfterDemo();
    VD.updateLiveUi();
    if (stopped) VD.setStatus({ state: "idle", detail: "Demo stopped. Real data and captured history will appear here." });
  }

  function stopAll() {
    const wasDemo = state.demoActive;
    window.clearInterval(window.__voltDemoTimer ?? undefined);
    const stopped = !bridge || callBridgeAction("disconnect", [], "Could not stop the native connection.");
    VD.clearDemoTelemetry();
    if (typeof VD.clearLivePosition === "function") VD.clearLivePosition();
    VD.setDemoActive(false);
    // If a demo was running, state.storage still holds the synthetic DB summary and the
    // demoPreview* shadow fields are still set. setDemoActive(false) reloads trips/insights but
    // NOT storage, so without this the Settings DB card keeps showing demo counts next to real
    // trips until the next native push. Mirror stopDemo()'s cleanup.
    if (wasDemo) refreshNativeDataAfterDemo();
    VD.updateLiveUi();
    if (stopped) VD.setStatus({ state: "idle", detail: "Stopped." });
  }

  function runBrowserDemo(): Promise<void> {
    return ensureBrowserDemoStream().then((stream) => {
      // The demo chunk loads asynchronously; if the user hit Stop during the load
      // window, demoActive is already false — starting the 1 Hz stream now would
      // resurrect the stopped demo (its first sample re-flips demoActive on via
      // telemetry.ts). Only start if the demo is still meant to be running.
      if (state.demoActive) stream(VD, state);
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
        // Tapping a scenario is an explicit preview action; keep demo isolation
        // active so native storage/app-state pushes cannot overwrite the sample.
        // Flip demoActive on ONLY AFTER loadDemoScenario has captured the preview
        // snapshot (captureDemoPreview populates demoPreviewStorage). Doing it
        // before the async map-module load resolved left a window where
        // demoActive was true but demoPreviewStorage was still null, so the demo
        // isolation guard (state.demoActive && state.demoPreviewStorage) let a
        // native setStorage push write real data over the demo view.
        const activateDemo = () => {
          if (typeof VD.setDemoActive === "function") VD.setDemoActive(true, DEMO_RUNNING_DETAIL);
        };
        // Only mark the tapped scenario button selected once the demo has actually
        // activated — otherwise a rejected ensureMapModule() (swallowed below) would
        // leave the picker showing a scenario as active that never loaded.
        const markScenarioActive = () => {
          const picker = el("demoScenarioPicker");
          if (picker) {
            picker.querySelectorAll("[data-scenario]").forEach((b) => {
              b.classList.toggle("is-active", b === button);
              b.setAttribute("aria-pressed", String(b === button));
            });
          }
        };
        if (typeof VD.loadDemoScenario === "function") {
          VD.loadDemoScenario(scenario);
          activateDemo();
          markScenarioActive();
        } else if (typeof VD.ensureMapModule === "function") {
          void VD.ensureMapModule()
            .then(() => {
              if (typeof VD.loadDemoScenario === "function") VD.loadDemoScenario(scenario);
              activateDemo();
              markScenarioActive();
            })
            .catch(() => {
              VD.setStatus({ state: "blocked", detail: "Could not load the demo scenario." });
            });
        } else {
          activateDemo();
          markScenarioActive();
        }
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
      const f = target.dataset.liveSignalFilter;
      state.liveSignalsFilter = f === "missing" || f === "all" ? f : "reporting";
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
        // Guard against a double-tap firing two identical .json downloads, the
        // same way the bulk export button does. withBusy keys the in-flight flag
        // on the row button so a second tap within the cooldown is swallowed.
        withBusy(signalExport as BusyButton, () => {
          void exportSignalLog((signalExport as HTMLElement).dataset.signalExport);
        });
        return;
      }
      const signalDelete = target && target.closest("[data-signal-delete]");
      if (signalDelete) {
        void deleteSignalLog((signalDelete as HTMLElement).dataset.signalDelete);
        return;
      }
    }, opts);
    bindListenerGuarded("permissionBtn", "click", () => handleAction("permissions"), opts);
    // Pass the button through like the scan/connect bindings do: "Reconnect
    // last" kicks off a real adapter connection and "Refresh" hits the bridge —
    // without the element, withBusy can't disable them or paint the busy state,
    // so a double-tap fired the action twice with zero feedback.
    bindListenerGuarded("refreshBtn", "click", (event) => handleAction("refresh", event.currentTarget as BusyButton), opts);
    bindListenerGuarded("lastBtn", "click", (event) => handleAction("last", event.currentTarget as BusyButton), opts);
    bindListenerGuarded("scanBtn", "click", (event) => handleAction("scan", event.currentTarget as BusyButton), opts);
    bindListenerGuarded("tpmsScanBtn", "click", (event) => handleAction("tpmsScan", event.currentTarget as BusyButton), opts);
    bindListenerGuarded("exportSignalLogsBtn", "click", (event) => {
      withBusy(event.currentTarget as BusyButton, () => { void exportSignalLogs(); });
    }, opts);
    bindListenerGuarded("connectBtn", "click", (event) => {
      const btn = el("connectBtn");
      const action = (btn && btn.dataset.primaryAction) || "connect";
      handleAction(action, event.currentTarget as BusyButton);
    }, opts);
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
    setTrips: (payload: unknown) => {
      if (typeof VD.setTrips === "function") VD.setTrips(payload);
    },
    setInsights: (payload: unknown) => {
      if (typeof VD.setInsights === "function") VD.setInsights(payload);
    },
    setTripRoute: (payload: unknown) => {
      if (typeof VD.setTripRoute === "function") VD.setTripRoute(payload);
    },
    setCurrentSessionRoute: (payload: unknown) => {
      if (typeof VD.setCurrentSessionRoute === "function") VD.setCurrentSessionRoute(payload);
    },
    setBatterySohHistory: (payload: unknown) => {
      if (typeof VD.setBatterySohHistory === "function") VD.setBatterySohHistory(payload);
    },
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

  const scheduleIdle = (work: () => void, timeout = 1500) => {
    if (typeof window.requestIdleCallback === "function") {
      window.requestIdleCallback(work, { timeout });
    } else {
      setTimeout(work, Math.min(timeout, 1500));
    }
  };

  const schedulePostStartupIdle = (work: () => void) => {
    setTimeout(() => scheduleIdle(work, 3000), 750);
  };

  const afterNextPaint = (work: () => void) => {
    if (typeof window.requestAnimationFrame === "function") {
      window.requestAnimationFrame(() => {
        setTimeout(work, 0);
      });
    } else {
      setTimeout(work, 0);
    }
  };

  startupMark("actions_bootstrap_start");
  bindListeners();
  startupMark("actions_bind_listeners_done");
  VD.setDemoActive(false);
  // updateLiveUi() already renders operational state, validation, diagnostics,
  // charge/cell panels, and the Drive live strip. Keep the first-paint path to
  // the Drive dashboard; storage/map/diagnostic-code summaries render after the
  // native ready handshake or when the storage payload arrives.
  VD.updateLiveUi();
  // Current Android builds publish devices/storage from onDashboardReady(). Only fall back to the
  // JS-side refresh path for browser preview or an older bridge that has no ready handshake.
  if (!bridge || typeof bridge.dashboardReady !== "function") refreshDevices();
  startupMark("actions_initial_render_done");
  afterNextPaint(() => {
    startupMark("actions_first_frame");
    callBridgeAction("dashboardReady");
    startupMark("actions_dashboard_ready_called");
    VD.scrollAppToTop();
  });
  // Storage overview is published by Android after the dashboardReady handshake. Trips/Insights
  // rollups are demand-loaded when the user opens Map/Insights, so startup no longer schedules
  // synchronous SQLite bridge reads unconditionally.
  const loadDeferredPanels = () => {
    // Hidden panel cleanup is deliberately post-startup. First frame and quick
    // tab taps get priority; storage payloads and active views still render on
    // demand through their normal handlers.
    startupMark("actions_secondary_render_start");
    if (typeof VD.renderRealV2Ui === "function") VD.renderRealV2Ui();
    if (typeof VD.renderMapIfLoaded === "function") VD.renderMapIfLoaded();
    if (typeof VD.updateDiagnosticCodeUi === "function") VD.updateDiagnosticCodeUi();
    startupMark("actions_secondary_render_end");
  };
  schedulePostStartupIdle(loadDeferredPanels);
  setTimeout(() => VD.scrollAppToTop(), 200);

export {};
