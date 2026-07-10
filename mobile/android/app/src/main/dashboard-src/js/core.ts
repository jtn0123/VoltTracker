// Initializes the VoltDashboard namespace shared by every dashboard source file. Each
// source file attaches cross-file calls to `window.VoltDashboard` (aliased
// locally as `VD`). `window.VoltTrackerAndroid` (the Android->WebView bridge)
// and `window.VoltTrackerNative` (the WebView<-Android callback surface) are
// preserved exactly as-is — those names are part of the ABI.
import { asDataTone, setDataTone } from "./dataset-state";
import { createFocusTrap } from "./focus-trap";
import type { FocusTrap } from "./focus-trap";
import { initialTelemetryState } from "./telemetry-state";
import { validatePayload } from "./payload-validators";

  type DashboardData = {
    trips: unknown[];
    sessions: unknown[];
    hourly: unknown[];
    insights: unknown[];
    demoLoaded: boolean;
  };

  type DemoDataCallback = (error: Error | null, data: DashboardData) => void;

  type HistoryDevice = Record<string, unknown> & {
    address?: string;
    name?: string;
    candidate?: boolean;
    lastSeen?: unknown;
    connectCount?: number;
  };

  type RestoreProgressPayload = {
    visible?: boolean;
    busy?: boolean;
    title?: string;
    detail?: string;
    operation?: string;
    tone?: string;
    phase?: string;
    bytesDone?: number | string;
    bytesTotal?: number | string;
    rowsDone?: number | string;
    rowsTotal?: number | string;
    percent?: number | string;
    etaSeconds?: number | string;
  };

  function isPlainRecord(value: unknown): value is Record<string, unknown> {
    return value != null && typeof value === "object" && !Array.isArray(value);
  }

  function isHistoryDevice(value: unknown): value is HistoryDevice {
    return isPlainRecord(value);
  }

  function installLegacyWebViewPolyfills() {
    const elementProto = typeof Element !== "undefined" ? Element.prototype : null;
    if (elementProto && typeof elementProto.replaceChildren !== "function") {
      Object.defineProperty(elementProto, "replaceChildren", {
        configurable: true,
        writable: true,
        value: function replaceChildren(this: Element, ...nodes: Array<Node | string>) {
          while (this.firstChild) this.removeChild(this.firstChild);
          nodes.forEach((node) => {
            this.appendChild(typeof node === "string" ? document.createTextNode(node) : node);
          });
        }
      });
    }
  }

  installLegacyWebViewPolyfills();

  const VD = (window.VoltDashboard = window.VoltDashboard || ({} as VoltDashboard));
  VD.bridge = window.VoltTrackerAndroid || null;
  // Exported for in-bundle ESM consumers (A3): renderers import { el } from
  // "./core" instead of aliasing window.VoltDashboard.el. VD.el stays assigned
  // for any late-binding consumers / the global ABI.
  export const el = (id: string) => document.getElementById(id);
  VD.el = el;

  // Shared SVG helper: batch-set attributes on a namespaced element. Used by the
  // efficiency-scatter (insights-panel) and the battery SOH trend chart so they
  // don't each carry their own copy. Exported for in-bundle ESM consumers (A3);
  // VD.setSvgAttrs stays assigned for any late-binding consumers / the global ABI.
  export function setSvgAttrs(node: SVGElement, attrs: Record<string, string | number>) {
    Object.entries(attrs).forEach(([key, value]) => node.setAttribute(key, String(value)));
    return node;
  }
  VD.setSvgAttrs = setSvgAttrs;

  // bindListenerGuarded attaches a listener but warns + skips if the element ID is
  // missing instead of throwing. The dashboard is assembled from partials at build time, so
  // a renamed/removed ID inside a partial would otherwise crash `bindListeners()` mid-way,
  // leaving every binding AFTER the missing one unwired with no surface signal. The warn is
  // piped through logClientError (window.error handler picks it up) so the regression is
  // visible in dev/test rather than silently swallowed.
  // Exported for in-bundle ESM consumers (A3): actions.ts imports it directly.
  // VD.bindListenerGuarded stays assigned for any late-binding consumers.
  export function bindListenerGuarded(
    id: string,
    event: string,
    handler: EventListenerOrEventListenerObject,
    opts?: boolean | AddEventListenerOptions
  ) {
    const node = document.getElementById(id);
    if (!node) {
      const message =
        "listener bind skipped: missing #" + id + " (event=" + event + ")";
      try {
        if (typeof console !== "undefined" && console && console.warn) {
          console.warn(message);
        }
      } catch (ignored) {}
      try {
        if (
          window.VoltTrackerAndroid &&
          typeof window.VoltTrackerAndroid.logClientError === "function"
        ) {
          window.VoltTrackerAndroid.logClientError("bindListenerGuarded", message);
        }
      } catch (ignored) {}
      return false;
    }
    node.addEventListener(event, handler, opts);
    return true;
  }
  VD.bindListenerGuarded = bindListenerGuarded;
  // Top-level abort controller for window-level error/rejection listeners, so the
  // reset hook can tear them down with the rest of the actions.js listeners.
  VD.errorController = new AbortController();

  const bridge = VD.bridge;

  // Typed querySelectorAll over elements (every dashboard selector targets HTMLElements), so
  // callers get .dataset/.hidden without a per-site cast.
  const queryAll = (selector: string) =>
    document.querySelectorAll(selector) as NodeListOf<HTMLElement>;

  // Per-render AbortController so the listeners attached inside setHistory()
  // don't accumulate across re-renders. Each render aborts the previous batch
  // before binding the new one.
  let historyController: AbortController | null = null;
  let insightsModulePromise: Promise<VoltDashboard> | null = null;
  let mapModulePromise: Promise<VoltDashboard> | null = null;
  let troubleshooterModulePromise: Promise<VoltDashboard> | null = null;
  let signalsModulePromise: Promise<VoltDashboard> | null = null;
  let connectionToolsModulePromise: Promise<VoltDashboard> | null = null;

  // Error-banner dedupe: an error storm (e.g. a reconnect loop, or a render
  // error repeating once per telemetry tick) calls reportClientError with the
  // SAME message many times in a row. Re-rendering each time churns the DOM
  // and re-shows a banner the user just dismissed. Identical messages inside
  // this window only bump a repeat counter (reflected as an "(xN)" suffix
  // while the banner is visible); a different message, or the same one after
  // the window has passed, renders normally. Every call still flows to
  // bridge.logClientError so adb logcat keeps the full stream.
  const ERROR_BANNER_DEDUPE_MS = 2500;
  let lastErrorBannerText = "";
  let lastErrorBannerAt = 0;
  let errorBannerRepeatCount = 0;

  function reportClientError(label: unknown, detail?: unknown) {
    const message = String(detail || label || "Unknown error");
    // The banner gets a single readable line; the full detail (stack traces
    // included) still flows to logClientError below.
    const display = (message.split("\n")[0] ?? "").slice(0, 140);
    const now = Date.now();
    const duplicate =
      display === lastErrorBannerText && now - lastErrorBannerAt < ERROR_BANNER_DEDUPE_MS;
    try {
      if (duplicate) {
        // Slide the window on every repeat so a continuous same-message storm
        // (e.g. a ~1Hz reconnect loop) keeps counting as the SAME message. Only
        // refreshing this in the else branch anchored the window to the first
        // occurrence, so at ~2.5s the storm looked "new" and re-showed a banner
        // the user had dismissed. Now a dismissal sticks until the error clears.
        lastErrorBannerAt = now;
        errorBannerRepeatCount += 1;
        // Keep a visible banner's counter fresh, but never re-show one the
        // user dismissed for a message they have already seen.
        const node = el("errorBanner");
        const detailNode = el("errorBannerDetail");
        if (node && !node.hidden && detailNode) {
          detailNode.textContent = display + " (x" + errorBannerRepeatCount + ")";
        }
      } else {
        lastErrorBannerText = display;
        lastErrorBannerAt = now;
        errorBannerRepeatCount = 1;
        const detailNode = el("errorBannerDetail");
        if (detailNode) detailNode.textContent = display;
        const node = el("errorBanner");
        if (node) node.hidden = false;
      }
    } catch (ignored) {}
    try {
      if (bridge && typeof bridge.logClientError === "function") {
        bridge.logClientError(String(label || "error"), message);
      }
    } catch (ignored) {}
  }

  // callBridge invokes a dashboard->native bridge method ONLY if it exists on
  // this native build. The bridge ABI grows over time (docs/bridge-abi.md), so a
  // dashboard shipped inside a newer APK can momentarily run against an older
  // WebView-cached page or vice versa during development; a missing method used
  // to throw a TypeError mid-handler and abort the rest of the tap. Missing
  // methods console.warn + report once through logClientError and return
  // undefined so callers degrade gracefully.
  const warnedMissingBridgeMethods = new Set<string>();
  function callBridge<K extends keyof VoltBridge>(
    name: K,
    ...args: Parameters<VoltBridge[K]>
  ): ReturnType<VoltBridge[K]> | undefined {
    const target = VD.bridge;
    const fn = target ? (target[name] as unknown) : null;
    if (typeof fn !== "function") {
      if (!warnedMissingBridgeMethods.has(String(name))) {
        warnedMissingBridgeMethods.add(String(name));
        const message = "bridge." + String(name) + " is not available on this native version";
        try {
          console.warn(message);
        } catch (ignored) {}
        try {
          if (target && typeof target.logClientError === "function") {
            target.logClientError("bridge.missing", message);
          }
        } catch (ignored) {}
      }
      return undefined;
    }
    try {
      return (fn as (...callArgs: unknown[]) => ReturnType<VoltBridge[K]>).apply(target, args);
    } catch (err) {
      const detail = err instanceof Error ? err.message : String(err);
      const message = "bridge." + String(name) + " failed: " + detail;
      try {
        console.warn(message);
      } catch (ignored) {}
      try {
        if (target && typeof target.logClientError === "function") {
          target.logClientError("bridge.call_failed", message);
        }
      } catch (ignored) {}
      return undefined;
    }
  }

  // How long the restore dialog lingers on a successful finish before auto-hiding,
  // so the user can read the "Done" result before it disappears.
  const RESTORE_DONE_HIDE_MS = 2200;

  function clearRestoreProgressTimer() {
    if (restoreProgressHideTimer) {
      clearTimeout(restoreProgressHideTimer);
      restoreProgressHideTimer = null;
    }
  }

  function hideRestoreProgress() {
    clearRestoreProgressTimer();
    const node = el("restoreProgress");
    if (node) {
      node.hidden = true;
      node.dataset.busy = "false";
      setDataTone(node, "idle");
      node.dataset.progress = "indeterminate";
    }
    const close = el("restoreProgressClose");
    if (close) close.hidden = true;
    delete document.body.dataset.restoreBusy;
    // Release the focus trap (restores focus to the opener) once the dialog hides.
    if (restoreProgressTrap) {
      restoreProgressTrap.deactivate();
      restoreProgressTrap = null;
    }
  }

  // Inert everything behind the restore alertdialog — the app shell and the
  // bottom nav — while the trap is active, mirroring the other modal dialogs.
  function restoreProgressBackgroundNodes(): HTMLElement[] {
    return [document.querySelector(".app"), document.querySelector(".bottom-nav")]
      .filter((item): item is HTMLElement => item instanceof HTMLElement);
  }

  function progressNumber(value: unknown) {
    const numeric = Number(value);
    return Number.isFinite(numeric) ? numeric : null;
  }

  function knownProgressPercent(progress: RestoreProgressPayload, busy: boolean, tone: string) {
    // Negative percent is the native "unknown" sentinel — fall through to the
    // byte/row ratios instead of rendering a determinate 0%.
    const explicit = progressNumber(progress.percent);
    if (explicit != null && explicit >= 0) return Math.min(100, explicit);
    const bytesDone = progressNumber(progress.bytesDone);
    const bytesTotal = progressNumber(progress.bytesTotal);
    if (bytesDone != null && bytesTotal != null && bytesTotal > 0) {
      return Math.max(0, Math.min(100, (bytesDone / bytesTotal) * 100));
    }
    const rowsDone = progressNumber(progress.rowsDone);
    const rowsTotal = progressNumber(progress.rowsTotal);
    if (rowsDone != null && rowsTotal != null && rowsTotal > 0) {
      return Math.max(0, Math.min(100, (rowsDone / rowsTotal) * 100));
    }
    if (!busy && tone === "ok") return 100;
    return null;
  }

  function formatProgressBytes(value: number) {
    if (!Number.isFinite(value) || value <= 0) return "0 B";
    if (value < 1024) return `${Math.round(value)} B`;
    // `undefined` locale follows the device's runtime locale for the decimal separator.
    const oneDecimal = (scaled: number) =>
      scaled.toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 1 });
    if (value < 1024 * 1024) return `${oneDecimal(value / 1024)} KB`;
    if (value < 1024 * 1024 * 1024) return `${oneDecimal(value / 1024 / 1024)} MB`;
    return `${oneDecimal(value / 1024 / 1024 / 1024)} GB`;
  }

  function formatProgressCount(value: number) {
    if (!Number.isFinite(value) || value < 0) return "0";
    // `undefined` locale follows the device's runtime locale for grouping separators.
    return Math.round(value).toLocaleString(undefined);
  }

  function restoreProgressStats(progress: RestoreProgressPayload) {
    const bytesDone = progressNumber(progress.bytesDone);
    const bytesTotal = progressNumber(progress.bytesTotal);
    if (bytesDone != null && bytesTotal != null && bytesTotal > 0) {
      return `${formatProgressBytes(bytesDone)} of ${formatProgressBytes(bytesTotal)}`;
    }
    if (bytesDone != null) {
      return `${formatProgressBytes(bytesDone)} processed`;
    }
    const rowsDone = progressNumber(progress.rowsDone);
    const rowsTotal = progressNumber(progress.rowsTotal);
    if (rowsDone != null && rowsTotal != null && rowsTotal > 0) {
      return `${formatProgressCount(rowsDone)} of ${formatProgressCount(rowsTotal)} rows`;
    }
    if (rowsDone != null) {
      return `${formatProgressCount(rowsDone)} rows processed`;
    }
    return "Preparing work.";
  }

  function formatRestoreEta(progress: RestoreProgressPayload, busy: boolean, tone: string, percent: number | null) {
    if (!busy) return tone === "ok" ? "Done" : "Needs attention";
    const etaSeconds = progressNumber(progress.etaSeconds);
    if (etaSeconds == null || etaSeconds < 0) {
      return percent == null ? "ETA calculating" : "ETA soon";
    }
    if (etaSeconds <= 0) return "Finishing up";
    if (etaSeconds < 60) return `ETA ${Math.ceil(etaSeconds)}s`;
    const minutes = Math.ceil(etaSeconds / 60);
    if (minutes < 60) return `ETA ${minutes}m`;
    const hours = Math.floor(minutes / 60);
    const remainder = minutes % 60;
    return remainder > 0 ? `ETA ${hours}h ${remainder}m` : `ETA ${hours}h`;
  }

  function updateRestoreProgressMeter(node: HTMLElement, percent: number | null) {
    const meter = el("restoreProgressMeter");
    const fill = el("restoreProgressFill") as HTMLElement | null;
    const hasPercent = percent != null;
    node.dataset.progress = hasPercent ? "determinate" : "indeterminate";
    if (fill) {
      fill.style.width = hasPercent ? `${Math.round(percent)}%` : "";
    }
    if (!meter) return;
    if (hasPercent) {
      meter.setAttribute("aria-valuenow", String(Math.round(percent)));
    } else {
      meter.removeAttribute("aria-valuenow");
    }
  }

  function restoreProgressOperation(progress: RestoreProgressPayload): string {
    const explicit = String(progress.operation || "").trim().toLowerCase();
    if (explicit === "backup") return "Backup";
    if (explicit === "restore") return "Restore";
    const text = [progress.title, progress.detail, progress.phase].map((value) => String(value || "").toLowerCase()).join(" ");
    if (
      text.includes("restore") ||
      text.includes("restored") ||
      text.includes("reading backup") ||
      text.includes("merging backup")
    ) {
      return "Restore";
    }
    if (text.includes("backup")) return "Backup";
    return "Backup and restore";
  }

  function setRestoreProgress(payload: unknown) {
    const parsed = VD.parsePayload<unknown>(payload, {});
    validatePayload("setRestoreProgress", parsed);
    const progress = (isPlainRecord(parsed) ? parsed : {}) as RestoreProgressPayload;
    if (progress.visible === false) {
      hideRestoreProgress();
      return;
    }
    const node = el("restoreProgress");
    if (!node) return;
    clearRestoreProgressTimer();
    const wasHidden = node.hidden;
    const busy = progress.busy !== false;
    const tone = String(progress.tone || (busy ? "busy" : "idle"));
    node.hidden = false;
    node.dataset.busy = String(busy);
    setDataTone(node, asDataTone(tone));
    const percent = knownProgressPercent(progress, busy, tone);
    updateRestoreProgressMeter(node, percent);
    const operation = restoreProgressOperation(progress);
    VD.setText("restoreProgressKicker", operation);
    const meter = el("restoreProgressMeter");
    if (meter) meter.setAttribute("aria-label", `${operation} progress`);
    VD.setText(
      "restoreProgressTitle",
      progress.title ||
        (busy
          ? operation === "Backup"
            ? "Backing up data"
            : "Restoring backup"
          : "Restore status")
    );
    VD.setText(
      "restoreProgressPhase",
      progress.phase || (busy ? "Working" : tone === "ok" ? "Complete" : "Review needed")
    );
    VD.setText(
      "restoreProgressPercent",
      percent == null ? (busy ? "--" : tone === "ok" ? "100%" : "--") : `${Math.round(percent)}%`
    );
    VD.setText("restoreProgressEta", formatRestoreEta(progress, busy, tone, percent));
    VD.setText(
      "restoreProgressDetail",
      progress.detail || "Volt Tracker is processing the selected backup file."
    );
    VD.setText("restoreProgressStats", restoreProgressStats(progress));
    VD.setText(
      "restoreProgressNote",
      busy ? "Keep Volt Tracker open until this finishes." : "The status line below keeps the result visible."
    );
    const close = el("restoreProgressClose");
    if (close) close.hidden = busy;
    if (busy) document.body.dataset.restoreBusy = "true";
    else delete document.body.dataset.restoreBusy;
    if (!busy && tone === "ok") {
      if (operation === "Restore" && typeof VD.invalidateFetchedRouteCache === "function") {
        VD.invalidateFetchedRouteCache();
      }
      restoreProgressHideTimer = setTimeout(hideRestoreProgress, RESTORE_DONE_HIDE_MS);
    }
    // Only arm the trap + move focus when the dialog first appears — native
    // pushes progress continuously, and re-focusing on every tick steals
    // keyboard/SR focus. activate() snapshots the opener while it still holds
    // focus, inerts the background, and arms Tab/Escape (Escape -> hide).
    if (wasHidden) {
      if (!restoreProgressTrap) {
        restoreProgressTrap = createFocusTrap(node, {
          backgroundNodes: restoreProgressBackgroundNodes,
          // Escape dismisses only when the dialog is dismissible — a busy restore
          // is a deliberately non-dismissible modal (its close button stays hidden).
          onEscape: dismissRestoreProgressIfAllowed
        });
      }
      restoreProgressTrap.activate();
      try { node.focus({ preventScroll: true }); } catch (ignored) {}
    }
  }

  // Hide the restore alertdialog only while it is dismissible (not busy): the
  // close button is visible exactly when native reports !busy. Shared by Escape
  // (focus trap) and Android Back so both honor the non-dismissible busy modal.
  function dismissRestoreProgressIfAllowed(): boolean {
    const node = el("restoreProgress");
    const close = el("restoreProgressClose");
    if (node && !node.hidden && close && !close.hidden) {
      hideRestoreProgress();
      return true;
    }
    return false;
  }

  window.addEventListener("error", (event) => {
    const stack = event && event.error && event.error.stack;
    reportClientError("window.error", stack || (event && event.message) || "Script error");
  }, { signal: VD.errorController.signal });

  window.addEventListener("unhandledrejection", (event) => {
    const reason = event && event.reason;
    reportClientError(
      "unhandledrejection",
      (reason && reason.stack) || String(reason || "Unhandled promise rejection")
    );
  }, { signal: VD.errorController.signal });

  // Forward CSP violations to the Android logger so blocked inline scripts,
  // disallowed sources, or accidental eval show up in adb logcat without
  // surfacing through the user-facing error banner.
  document.addEventListener("securitypolicyviolation", (event) => {
    try {
      const directive = (event && event.violatedDirective) || "?";
      const blocked = (event && event.blockedURI) || "?";
      bridge?.logClientError?.("csp.violation", directive + " " + blocked);
    } catch (_err) { /* logClientError best-effort */ }
  }, { signal: VD.errorController.signal });

  (function bindErrorBannerDismiss() {
    const dismiss = el("errorBannerDismiss");
    if (dismiss) {
      dismiss.addEventListener("click", () => {
        const node = el("errorBanner");
        if (node) node.hidden = true;
      }, { signal: VD.errorController.signal });
    }
  })();

  (function bindRestoreProgressDismiss() {
    const close = el("restoreProgressClose");
    if (close) {
      close.addEventListener("click", hideRestoreProgress, { signal: VD.errorController.signal });
    }
  })();

  const data: DashboardData = { trips: [], sessions: [], hourly: [], insights: [], demoLoaded: false };
  VD.data = data;

  let demoDataLoading = false;
  const demoDataCallbacks: DemoDataCallback[] = [];
  let cachedViewNodes: HTMLElement[] | null = null;
  let cachedNavNodes: HTMLElement[] | null = null;
  let restoreProgressHideTimer: ReturnType<typeof setTimeout> | null = null;
  // Focus trap for the restore alertdialog: owns background inerting, Tab/Escape
  // containment, and focus save/restore to the opener — so role="alertdialog"
  // aria-modal="true" is real. Created the first time the dialog appears,
  // deactivated (and cleared) by hideRestoreProgress.
  let restoreProgressTrap: FocusTrap | null = null;

  function cloneDemoArray(record: Record<string, unknown>, key: "trips" | "sessions" | "hourly" | "insights") {
    const value = record[key];
    if (!Array.isArray(value)) {
      console.warn(`Ignoring malformed demo data: ${key} must be an array.`);
      return null;
    }
    const cloned: unknown[] = [];
    value.forEach((item, index) => {
      if (key === "hourly") {
        const count = Number(item);
        if (!Number.isFinite(count)) {
          console.warn(`Ignoring malformed demo data: ${key}[${index}] is not a number.`);
          return;
        }
        cloned.push(count);
        return;
      }
      if (item == null || typeof item !== "object") {
        console.warn(`Ignoring malformed demo data: ${key}[${index}] is not an object.`);
        return;
      }
      cloned.push({ ...item });
    });
    return cloned;
  }

  function asRecord(value: unknown): Record<string, unknown> {
    return value != null && typeof value === "object" ? value as Record<string, unknown> : {};
  }

  function applyDemoData(source: unknown) {
    const next = typeof source === "function" ? source() : source;
    const record = asRecord(next);
    const trips = cloneDemoArray(record, "trips");
    const sessions = cloneDemoArray(record, "sessions");
    const hourly = cloneDemoArray(record, "hourly");
    const insights = cloneDemoArray(record, "insights");
    if (!trips || !sessions || !hourly || !insights) {
      return false;
    }
    data.trips = trips;
    data.sessions = sessions;
    data.hourly = hourly;
    data.insights = insights;
    data.demoLoaded = true;
    return true;
  }

  function flushDemoDataCallbacks(error: Error | null) {
    const callbacks = demoDataCallbacks.splice(0);
    callbacks.forEach((callback) => {
      try { callback(error, data); } catch (err) { reportClientError("demoData.callback", err instanceof Error ? err.message : undefined); }
    });
  }

  function ensureDemoData(callback?: DemoDataCallback) {
    if (data.demoLoaded) {
      if (callback) callback(null, data);
      return true;
    }
    if (callback) demoDataCallbacks.push(callback);
    if (window.VoltDashboardDemoData) {
      if (applyDemoData(window.VoltDashboardDemoData)) {
        flushDemoDataCallbacks(null);
        return true;
      }
      const error = new Error("Malformed dashboard demo data.");
      reportClientError("demoData.shape", error.message);
      flushDemoDataCallbacks(error);
      return false;
    }
    if (demoDataLoading) return false;
    demoDataLoading = true;
    const script = document.createElement("script");
    script.src = "js/demo-data.js";
    script.onload = () => {
      demoDataLoading = false;
      if (applyDemoData(window.VoltDashboardDemoData || {})) {
        flushDemoDataCallbacks(null);
      } else {
        const error = new Error("Malformed dashboard demo data.");
        reportClientError("demoData.shape", error.message);
        flushDemoDataCallbacks(error);
      }
    };
    script.onerror = () => {
      demoDataLoading = false;
      const error = new Error("Unable to load dashboard demo data.");
      reportClientError("demoData.load", error.message);
      flushDemoDataCallbacks(error);
    };
    document.head.append(script);
    return false;
  }

  function dashboardScriptAlreadyLoaded(src: string) {
    return Boolean(document.querySelector(`script[src="${src}"][data-dashboard-lazy="true"]`));
  }

  function loadDashboardScript(src: string) {
    if (window.__VoltDashboardLoadScript) {
      return Promise.resolve(window.__VoltDashboardLoadScript(src));
    }
    if (dashboardScriptAlreadyLoaded(src)) {
      return Promise.resolve();
    }
    return new Promise((resolve, reject) => {
      const script = document.createElement("script");
      script.src = src;
      script.async = false;
      script.dataset.dashboardLazy = "true";
      script.onload = () => resolve(undefined);
      script.onerror = () => {
        // Remove the dead tag before rejecting. dashboardScriptAlreadyLoaded()
        // matches on the <script> being present, so a leftover failed node would
        // make every retry (ensureDtcData/ensureInsightsModule/ensureMapModule
        // null their cached promise and call back in) resolve instantly without
        // re-fetching — permanently wedging that chunk after one transient
        // failure. Removing it lets the intended retry re-append and re-execute.
        script.remove();
        reject(new Error("Unable to load " + src));
      };
      document.head.append(script);
    });
  }

  let dtcDataPromise: Promise<VoltDashboard> | null = null;
  let leafletRuntimePromise: Promise<void> | null = null;

  function dtcDataLoaded() {
    return typeof VD.dtcInfo === "function" && Array.isArray(VD.dtcSampleCodes);
  }

  function ensureDtcData() {
    if (dtcDataLoaded()) return Promise.resolve(VD);
    if (!dtcDataPromise) {
      dtcDataPromise = loadDashboardScript("js/dtc-causes.js")
        .then(() => loadDashboardScript("js/dtc-lookup.js"))
        .then(() => {
          if (!dtcDataLoaded()) {
            throw new Error("DTC scripts loaded but expected globals were not registered.");
          }
          return VD;
        })
        .catch((err) => {
          dtcDataPromise = null;
          // Surface the failure once, centrally: every ensureDtcData() caller
          // keeps a .catch so nothing crashes, but those catches used to be
          // silent — the user tapped "lookup"/"preview" and nothing happened.
          // console.warn for dev/logcat, plus the status toast so the user
          // sees it on whatever tab they are on.
          try {
            console.warn("DTC data chunk failed to load:", err && err.message);
          } catch (ignored) {}
          reportClientError("dtcData.load", err && err.message);
          if (typeof VD.setStatus === "function") {
            VD.setStatus({ state: "blocked", detail: "Diagnostic code database failed to load." });
          }
          throw err;
        });
    }
    return dtcDataPromise;
  }

  function mapModuleLoaded() {
    return VD.renderMapLoaded === true;
  }

  function leafletRuntimeLoaded() {
    return Boolean(window.L && typeof window.L.map === "function");
  }

  function ensureLeafletRuntime() {
    if (leafletRuntimeLoaded()) return Promise.resolve();
    if (!leafletRuntimePromise) {
      const loadedByHarness = Boolean(window.__VoltDashboardLoadScript);
      leafletRuntimePromise = loadDashboardScript("lib/leaflet/leaflet.js")
        .then(() => {
          if (!leafletRuntimeLoaded() && !loadedByHarness) {
            throw new Error("Leaflet script loaded but window.L.map was not registered.");
          }
        })
        .catch((err) => {
          leafletRuntimePromise = null;
          reportClientError("leaflet.load", err && err.message);
          throw err;
        });
    }
    return leafletRuntimePromise;
  }

  function ensureMapModule() {
    if (mapModuleLoaded()) return Promise.resolve(VD);
    if (!mapModulePromise) {
      mapModulePromise = ensureLeafletRuntime()
        .then(() => loadDashboardScript("js/scrubber.js"))
        .then(() => loadDashboardScript("js/map.js"))
        .then(() => {
          if (!mapModuleLoaded() || typeof VD.renderMap !== "function") {
            throw new Error("Map script loaded but expected globals were not registered.");
          }
          return VD;
        })
        .catch((err) => {
          mapModulePromise = null;
          reportClientError("map.load", err && err.message);
          throw err;
        });
    }
    return mapModulePromise;
  }

  function insightsModuleLoaded() {
    return typeof VD.loadTrips === "function" && typeof VD.loadInsights === "function";
  }

  function ensureInsightsModule() {
    if (insightsModuleLoaded()) return Promise.resolve(VD);
    if (!insightsModulePromise) {
      insightsModulePromise = loadDashboardScript("js/insights-panel.js")
        .then(() => {
          if (!insightsModuleLoaded()) {
            throw new Error("Insights script loaded but expected globals were not registered.");
          }
          return VD;
        })
        .catch((err) => {
          insightsModulePromise = null;
          reportClientError("insights.load", err && err.message);
          throw err;
        });
    }
    return insightsModulePromise;
  }

  function signalsModuleLoaded() {
    return typeof VD.updateEnhancedCapabilityUi === "function" && typeof VD.setEnhancedBadge === "function";
  }

  function ensureSignalsModule() {
    if (signalsModuleLoaded()) return Promise.resolve(VD);
    if (!signalsModulePromise) {
      signalsModulePromise = loadDashboardScript("js/signals-panel.js")
        .then(() => {
          if (!signalsModuleLoaded()) {
            throw new Error("Signals script loaded but expected globals were not registered.");
          }
          return VD;
        })
        .catch((err) => {
          signalsModulePromise = null;
          reportClientError("signals.load", err && err.message);
          throw err;
        });
    }
    return signalsModulePromise;
  }

  /**
   * Harness seam: settles once every in-flight lazy-chunk load (DTC data, map,
   * troubleshooter) has run its success/failure handlers — including the async
   * console.warn + status-toast work in the catch paths. Test teardown awaits
   * this so a late chunk rejection can never race the worker shutdown.
   * Never rejects.
   */
  function pendingLazyLoads(): Promise<unknown[]> {
    const pending: Array<Promise<unknown>> = [];
    if (dtcDataPromise) pending.push(dtcDataPromise.catch(() => undefined));
    if (insightsModulePromise) pending.push(insightsModulePromise.catch(() => undefined));
    if (signalsModulePromise) pending.push(signalsModulePromise.catch(() => undefined));
    if (connectionToolsModulePromise) pending.push(connectionToolsModulePromise.catch(() => undefined));
    if (leafletRuntimePromise) pending.push(leafletRuntimePromise.catch(() => undefined));
    if (mapModulePromise) pending.push(mapModulePromise.catch(() => undefined));
    if (troubleshooterModulePromise) {
      pending.push(troubleshooterModulePromise.catch(() => undefined));
    }
    return Promise.all(pending);
  }

  function renderMapIfLoaded() {
    if (mapModuleLoaded() && typeof VD.renderMap === "function") {
      VD.renderMap();
    }
  }

  function requestMapRender() {
    // Gate the first paint on the lazy Map stylesheets (leaflet.css + screens-map.css)
    // so the map never renders before its CSS applies. mapStylesReady is set by map.ts
    // as the chunk loads; until then it may be undefined, which resolves immediately.
    const stylesReady = () => Promise.resolve(VD.mapStylesReady);
    if (mapModuleLoaded() && typeof VD.renderMap === "function") {
      return stylesReady().then(() => {
        VD.renderMap();
        return VD;
      });
    }
    return ensureMapModule().then((dashboard) =>
      stylesReady().then(() => {
        if (typeof dashboard.renderMap === "function") dashboard.renderMap();
        return dashboard;
      }),
    );
  }

  function troubleshooterModuleLoaded() {
    const ts = VD.troubleshooter;
    return Boolean(ts && typeof ts.open === "function" && typeof ts.noteStatus === "function");
  }

  function ensureTroubleshooterModule() {
    if (troubleshooterModuleLoaded()) return Promise.resolve(VD);
    if (!troubleshooterModulePromise) {
      troubleshooterModulePromise = loadDashboardScript("js/troubleshooter.js")
        .then(() => {
          if (!troubleshooterModuleLoaded()) {
            throw new Error("Troubleshooter script loaded but expected globals were not registered.");
          }
          return VD;
        })
        .catch((err) => {
          troubleshooterModulePromise = null;
          reportClientError("troubleshooter.load", err && err.message);
          throw err;
        });
    }
    return troubleshooterModulePromise;
  }

  function dtcSearchUrl(code: unknown) {
    const key = String(code || "").trim().toUpperCase();
    const q = encodeURIComponent((key || "OBD-II") + " Chevy Volt DTC");
    return "https://www.google.com/search?q=" + q;
  }

  // The central runtime/UI state bag. Fields are assigned across every dashboard
  // file (telemetry samples, render selections, map layers); the closed
  // DashboardState interface (dashboard-globals.d.ts) pins its shape, and
  // state-shape.test.js pins the seeded key set.
  const state: DashboardState = {
    view: "drive",
    mode: "ev",
    selectedRealTripId: null,
    signalProbeStage: "tires",
    lastDevice: null,
    deviceHistory: [],
    storage: {},
    trips: [],
    insights: {},
    // User-authored maintenance log (M5); populated from bridge.getMaintenanceLog().
    maintenanceLog: [],
    tripsReadError: null,
    insightsReadError: null,
    // Demo-only staged charge sessions (actions.js stages rows here so they
    // don't touch the real session list); null until demo mode adds the first.
    demoSessions: null,
    appState: {},
    demoActive: false,
    mapLayer: ["routes", "heat", "stops", "eff"].includes(VD.prefs.get<string>("mapLayer", "eff"))
      ? VD.prefs.get<string>("mapLayer", "eff")
      : "eff",
    mapRemoteTilesEnabled: true,
    mapFull: false,
    // The chip strip is the compact default. "All drives" opens the searchable
    // recorded-drive browser explicitly; keeping that intent in state prevents a
    // storage refresh from hiding the destination again while it is in use.
    mapBrowserOpen: false,
    tripReceiptMode: false,
    // Live-signals panel filter: "reporting" (default — only PIDs the car is
    // answering), "all", or "missing" (only PIDs it isn't answering) — see
    // telemetry.ts#renderLiveSignals.
    liveSignalsFilter: "reporting",
    // When viewing the in-progress drive, keep the map framed on the growing
    // track. Auto-disabled the moment the user pans the map (so they can inspect
    // freely), re-enabled via the Follow button — see map.ts#setMapFollowLive.
    mapFollowLive: true,
    selectedMapSessionId: null,
    liveRouteStartedAtMs: null,
    liveRoutePoints: [],
    status: {},
    speedHistory: [],
    // Drive-tab live charts: power bars (last ~60s) and SOC trace across the
    // current session. Capped lengths to keep render cheap.
    powerHistory: [],
    socHistory: [],
    // Captured the first time SOC is observed in a session. Anchors the
    // "Δ since session start" chip on the SOC micro-card so it stays honest
    // even after the rolling-window socHistory has rotated out the start.
    sessionStartSoc: null,
    // Running session distance in meters, derived from haversine-stepped GPS
    // samples. Reset on session start; surfaced in the Drive "Recording" chip.
    sessionDistanceM: 0,
    sessionLastLat: null,
    sessionLastLng: null,
    // Tracked by telemetry.js for the BT-disconnected stale-data indicator.
    lastSampleAt: 0,
    // Latest telemetry render is throttled via requestAnimationFrame; this
    // is the rAF id, cleared once the scheduled render runs.
    rafPending: 0,
    telemetry: initialTelemetryState()
  };
  VD.state = state;

  // ----- shared-state accessor (C3) ----------------------------------------
  // The state bag is mutated from every module. The seam policy (enforced by
  // dashboard-tests/state-seam.test.js) is deliberately scoped, not all-or-nothing:
  //   - demoActive (demo isolation) is written ONLY here, via setState()/setDemoActive() — the
  //     guard test fails on any direct `state.demoActive =` elsewhere.
  //   - The live-route buffer (liveRoutePoints / liveRouteStartedAtMs) is OWNED by map.ts, which
  //     keeps a module-local reference to it; cross-module seeding goes through VD.setLiveRoutePoints
  //     (report item C1) so map.ts's reference can't desync from `state`. Writes are confined to
  //     map.ts + the demo/teardown reset fallbacks.
  //   - selectedMapSessionId is a free scalar, read/written directly (not a cross-module invariant).
  // setState is a plain patch-merge (Object.assign), so behaviour is identical to a direct
  // assignment; the value is one typed, greppable seam for the invariants that matter.
  function setState(patch: Partial<DashboardState>) {
    Object.assign(state, patch);
    return state;
  }
  // Typed getters for the most cross-referenced invariant fields. Thin reads —
  // they exist so other modules can ask "is demo on?" / "which session?" without
  // reaching into the raw bag, mirroring setState on the write side.
  function isDemoActive() {
    return state.demoActive === true;
  }
  function getSelectedMapSessionId() {
    return state.selectedMapSessionId;
  }
  VD.setState = setState;
  VD.isDemoActive = isDemoActive;
  VD.getSelectedMapSessionId = getSelectedMapSessionId;

  const realViewMeta: Record<string, [string, string]> = {
    drive: ["Volt Tracker Android", "Drive"],
    map: ["GPS route", "Map"],
    charge: ["Real charging", "Charge"],
    insights: ["Vehicle health", "Insights"],
    settings: ["Adapter setup", "Settings"],
    diagnostics: ["Adapter & debug", "Diagnostics"]
  };

  // v2 design header glyphs (VoltTracker v2.dc.html#screenIconPath): each view's
  // title icon mirrors its bottom-nav icon — house / folded map / bolt / line
  // chart / pulse / gear-sun.
  const viewIconPaths: Record<string, string> = {
    drive: "M4 11.5 12 5l8 6.5V20h-5v-5H9v5H4z",
    map: "m4 6 5-2 6 2 5-2v14l-5 2-6-2-5 2zM9 4v14M15 6v14",
    charge: "M13 2 5 13h6l-1 9 9-13h-6z",
    insights: "M4 19V5M4 19h16M8 15l3-4 3 2 5-7",
    settings: "M12 8a4 4 0 1 1 0 8 4 4 0 0 1 0-8Zm0-5v3m0 12v3M4.2 4.2l2.1 2.1m11.4 11.4 2.1 2.1M1 12h3m16 0h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1",
    diagnostics: "M3 12h4l2.5-7 4 14 2.5-7H21"
  };
  // Design: only the Charge bolt is a filled glyph; every other header icon is a
  // stroked outline (matching the nav icons).
  const filledViewIcons = new Set(["charge"]);

  function parsePayload(payload: unknown, fallback: unknown = null) {
    if (!payload) return fallback;
    try { return typeof payload === "string" ? JSON.parse(payload) : payload; }
    catch (err) {
      // A native payload that fails JSON.parse used to vanish silently into the
      // fallback — a renamed/garbled bridge return read as "no data" with no
      // signal anywhere. Surface it through reportClientError (banner dedupe +
      // bridge.logClientError) with a short payload snippet for triage, but
      // never throw: callers rely on the fallback contract.
      const snippet = String(payload).slice(0, 80);
      reportClientError(
        "parsePayload",
        "Malformed JSON payload (" +
          (err instanceof Error ? err.message : String(err)) +
          "): " + snippet
      );
      return fallback;
    }
  }

  // setText/setMeter null-guard on a missing element so a renamed/removed partial ID can't crash a
  // render mid-pass. The downside is silence: a live tile would sit at "--" forever with no signal.
  // So a miss now warns ONCE per id (deduped — these run ~1Hz) and pipes through logClientError, the
  // same surfacing the bindListenerGuarded path uses. Both helpers return whether the element was
  // found, so a caller can react to a false if it ever needs to. Mirrors VD.bindListenerGuarded.
  const warnedMissingTargets = new Set<string>();
  function warnMissingTarget(fn: string, id: string) {
    if (warnedMissingTargets.has(id)) return;
    warnedMissingTargets.add(id);
    const message = fn + " skipped: missing #" + id;
    try {
      if (typeof console !== "undefined" && console && console.warn) {
        console.warn(message);
      }
    } catch (ignored) {}
    try {
      if (
        window.VoltTrackerAndroid &&
        typeof window.VoltTrackerAndroid.logClientError === "function"
      ) {
        window.VoltTrackerAndroid.logClientError("setTarget", message);
      }
    } catch (ignored) {}
  }

  function setText(id: string, value: unknown) {
    const node = el(id);
    if (!node) {
      warnMissingTarget("setText", id);
      return false;
    }
    node.textContent = String(value == null || value === "" ? "--" : value);
    return true;
  }

  function setMeter(id: string, value: unknown) {
    const node = el(id);
    if (!node) {
      warnMissingTarget("setMeter", id);
      return false;
    }
    const numeric = Number(value);
    const hasValue = Number.isFinite(numeric);
    const pct = hasValue ? Math.max(0, Math.min(100, numeric)) : 0;
    node.style.width = pct + "%";
    // Keep the meter's accessible value in sync with the visual fill so screen
    // readers announce the current reading (the element carries role="meter").
    // When the reading is missing/non-numeric, drop aria-valuenow so the meter
    // is announced as indeterminate rather than as a false 0.
    if (node.hasAttribute("role") || node.hasAttribute("aria-valuemin")) {
      if (hasValue) {
        node.setAttribute("aria-valuenow", String(pct));
      } else {
        node.removeAttribute("aria-valuenow");
      }
    }
    return true;
  }

  function scrollAppToTop() {
    window.scrollTo({ top: 0, behavior: "auto" });
  }

  function scrollAppBy(deltaY: number) {
    window.scrollBy({ top: deltaY, left: 0, behavior: "auto" });
  }

  function canScrollApp() {
    const root = document.scrollingElement || document.documentElement;
    const bodyHeight = document.body ? document.body.scrollHeight : 0;
    return Math.max(root.scrollHeight, bodyHeight) > window.innerHeight + 2;
  }

  // Keep the page's bottom clearance tied to the nav that is actually on
  // screen. The Map scrubber compacts that nav, and font/device differences can
  // shift its rendered height, so a fixed CSS estimate is needlessly fragile.
  // The wide layout uses a side rail and intentionally keeps the CSS fallback.
  function syncBottomNavHeight() {
    const nav = document.querySelector<HTMLElement>(".bottom-nav");
    const root = document.documentElement;
    if (!nav || window.innerWidth >= 900) {
      root.style.removeProperty("--nav-h");
      return;
    }
    const height = Math.ceil(nav.getBoundingClientRect().height);
    if (height > 0) root.style.setProperty("--nav-h", `${height}px`);
  }

  const bottomNav = document.querySelector<HTMLElement>(".bottom-nav");
  syncBottomNavHeight();
  window.addEventListener("resize", syncBottomNavHeight, { passive: true });
  if (bottomNav && typeof ResizeObserver === "function") {
    new ResizeObserver(syncBottomNavHeight).observe(bottomNav);
  }

  function startupMark(name: string) {
    const bridge = VD.bridge;
    if (bridge && typeof bridge.startupMark === "function") {
      try {
        bridge.startupMark(name);
      } catch (err) {
        reportClientError("bridge.call_failed", err instanceof Error ? err.message : String(err || ""));
      }
    }
  }

  function afterNextPaint(work: () => void) {
    if (typeof window.requestAnimationFrame === "function") {
      window.requestAnimationFrame(() => {
        setTimeout(work, 0);
      });
    } else {
      setTimeout(work, 0);
    }
  }

  function viewNodes(): HTMLElement[] {
    return cachedViewNodes || (cachedViewNodes = Array.from(document.querySelectorAll<HTMLElement>(".view")));
  }

  function navNodes(): HTMLElement[] {
    return cachedNavNodes || (cachedNavNodes = Array.from(document.querySelectorAll<HTMLElement>("[data-nav]")));
  }

  function hydrateConnectionTools(): boolean {
    const mount = el("connectionToolsMount");
    const template = el("connectionToolsTemplate") as HTMLTemplateElement | null;
    if (!mount || !template) return false;
    if (!el("connectionToolsPanel")) mount.appendChild(template.content.cloneNode(true));
    if (typeof VD.bindConnectionTools === "function") {
      VD.bindConnectionTools();
    } else {
      void ensureConnectionToolsModule().catch(() => {});
    }
    return true;
  }

  function ensureConnectionToolsModule(): Promise<VoltDashboard> {
    if (typeof VD.bindConnectionTools === "function") return Promise.resolve(VD);
    if (!connectionToolsModulePromise) {
      connectionToolsModulePromise = loadDashboardScript("js/connection-tools.js")
        .then(() => {
          if (typeof VD.bindConnectionTools !== "function") {
            throw new Error("Connection tools loaded without registering their binder.");
          }
          VD.bindConnectionTools();
          return VD;
        })
        .catch((err) => {
          connectionToolsModulePromise = null;
          reportClientError("connectionTools.load", err && err.message);
          VD.showToast?.("Connection tools failed to load", true);
          throw err;
        });
    }
    return connectionToolsModulePromise;
  }

  // "5738 rows" overflowed the Settings state chip into "5738 r…" — compact
  // thousands ("5.7k rows") keep the figure readable at chip width. Zero rows
  // reads as the chip's old idle label.
  function formatRowCount(count: unknown): string {
    const n = Number(count) || 0;
    if (!n) return "ready";
    const label =
      n >= 10000 ? `${Math.round(n / 1000)}k`
      : n >= 1000 ? `${(n / 1000).toFixed(1)}k`
      : String(n);
    return `${label} ${n === 1 ? "row" : "rows"}`;
  }

  function setBackupReceipt(payload: unknown): void {
    const parsed = parsePayload(payload, {}) as Record<string, unknown>;
    const atMs = Number(parsed.lastBackupAtMs || 0);
    const trips = Math.max(0, Number(parsed.lastBackupTrips || 0));
    const node = el("lastBackupReceipt");
    if (!node) return;
    if (!(atMs > 0)) {
      node.textContent = "No successful backup recorded on this phone yet.";
      node.dataset.state = "empty";
      return;
    }
    const date = new Date(atMs).toLocaleString(undefined, {
      year: "numeric",
      month: "short",
      day: "numeric",
      hour: "numeric",
      minute: "2-digit"
    });
    node.textContent = `Backup prepared ${date} · ${trips} drive${trips === 1 ? "" : "s"}`;
    node.dataset.state = "saved";
  }

  // Tab order for directional transitions and swipe navigation — matches the
  // bottom-nav left→right order, not the DOM order of the view sections
  // (Settings' section sits before Diagnostics' in the markup).
  const NAV_VIEW_ORDER = ["drive", "map", "charge", "insights", "diagnostics", "settings"];

  function setView(view: string) {
    // An unknown view name (stale deep link, removed tab like the old "trips")
    // would deactivate every section and leave a blank screen with no way
    // back — fall back to Drive instead of rendering nothing.
    if (!viewNodes().some((node) => node.dataset.view === view)) view = "drive";
    if (view === "settings") hydrateConnectionTools();
    startupMark("tab:" + view + ":start");
    // Slide the incoming view from the side it lives on (nav order), so tab
    // changes read as lateral movement. First paint keeps the plain rise.
    const fromIdx = NAV_VIEW_ORDER.indexOf(state.view);
    const toIdx = NAV_VIEW_ORDER.indexOf(view);
    if (fromIdx >= 0 && toIdx >= 0 && fromIdx !== toIdx) {
      document.body.dataset.vtDir = toIdx < fromIdx ? "back" : "fwd";
    } else {
      delete document.body.dataset.vtDir;
    }
    state.view = view;
    if (VD.bridge && typeof VD.bridge.setActiveDashboardView === "function") {
      try {
        VD.bridge.setActiveDashboardView(view);
      } catch (_err) {
        /* an older native host simply does not receive view-aware window policy */
      }
    }
    document.body.dataset.activeView = view;
    if (view !== "map" && state.mapFull) {
      state.mapFull = false;
      document.body.classList.remove("map-full-active");
      VD.renderMapIfLoaded();
    }
    if (view !== "map") document.body.classList.remove("map-scrubber-active");
    viewNodes().forEach((node) => node.classList.toggle("is-active", node.dataset.view === view));
    navNodes().forEach((node) => {
      const active = node.dataset.nav === view;
      node.classList.toggle("is-active", active);
      if (active) {
        node.setAttribute("aria-current", "page");
      } else {
        node.removeAttribute("aria-current");
      }
    });
    if (view === "insights") {
      void ensureInsightsModule()
        .then(() => {
          VD.loadTrips();
          VD.loadInsights();
        })
        .catch(() => {});
    } else if (view === "map") {
      void ensureInsightsModule()
        .catch(() => undefined)
        .then(() => {
          if (typeof VD.loadTrips === "function") VD.loadTrips();
          return VD.requestMapRender();
        })
        .catch(() => {});
    }
    updateViewHeading();
    scrollAppToTop();
    startupMark("tab:" + view + ":end");
    afterNextPaint(() => startupMark("tab:" + view + ":paint"));
  }

  function openTripFromNative(routeKey: string, receipt = false) {
    const clean = String(routeKey || "").trim();
    if (!clean) return;
    state.selectedMapSessionId = clean;
    state.tripReceiptMode = receipt;
    setView("map");
    void ensureMapModule()
      .then(() => {
        if (typeof VD.loadTrips === "function") VD.loadTrips();
        if (VD.bridge && typeof VD.bridge.requestTripRoute === "function") {
          try {
            VD.bridge.requestTripRoute(clean);
          } catch (_err) {
            /* the trips payload may already contain enough route data */
          }
        }
        return VD.requestMapRender();
      })
      .then(() => {
        window.setTimeout(() => {
          if (typeof VD.openTripDetail === "function") VD.openTripDetail(clean);
        }, 120);
      })
      .catch(() => VD.showToast?.("The saved drive could not be opened", true));
  }

  // ── Swipe between tabs ──────────────────────────────────────────────────
  // Fast, mostly-horizontal strokes move one tab left/right. Deliberately
  // conservative so it never steals gestures from surfaces that own them:
  // form fields, the pannable Leaflet map, horizontal scrollers marked
  // [data-noswipe], any open dialog, and the fullscreen map.
  let swipeStart: { x: number; y: number; at: number } | null = null;

  function swipeBlocked(target: EventTarget | null): boolean {
    if (document.body.classList.contains("map-full-active")) return true;
    // The DTC detail sheet's backdrop is a plain sibling <div> (no dialog
    // role), so a swipe starting on it wouldn't be caught by closest() below.
    if (document.body.classList.contains("dtc-detail-active")) return true;
    const node = target instanceof Element ? target : null;
    if (!node) return true;
    return Boolean(
      node.closest(
        "input, textarea, select, [data-noswipe], [role='dialog'], [aria-modal='true'], .leaflet-container"
      )
    );
  }

  document.addEventListener(
    "touchstart",
    (event) => {
      const touch = event.touches[0];
      swipeStart =
        !touch || swipeBlocked(event.target)
          ? null
          : { x: touch.clientX, y: touch.clientY, at: Date.now() };
    },
    { passive: true }
  );

  document.addEventListener(
    "touchend",
    (event) => {
      const start = swipeStart;
      swipeStart = null;
      if (!start || Date.now() - start.at > 600) return;
      const touch = event.changedTouches[0];
      if (!touch) return;
      const dx = touch.clientX - start.x;
      const dy = touch.clientY - start.y;
      // ≥64px of travel, ≤48px of drift, and clearly more horizontal than
      // vertical — anything less is a scroll, not a tab gesture.
      if (Math.abs(dx) < 64 || Math.abs(dy) > 48 || Math.abs(dx) < Math.abs(dy) * 1.6) return;
      const index = NAV_VIEW_ORDER.indexOf(state.view);
      const next = dx < 0 ? index + 1 : index - 1;
      const nextView = NAV_VIEW_ORDER[next];
      if (index >= 0 && nextView) setView(nextView);
    },
    { passive: true }
  );

  // Android hardware/gesture Back. The native OnBackPressedCallback calls this and only lets the
  // OS exit/background the app when it returns false. Dismiss the most-nested surface first: any
  // open modal (trip-detail sheet, then the restore alertdialog, then the app confirm/prompt), then
  // the topbar status popover, then an open troubleshooter modal, then a fullscreen map, then fall
  // back to the Drive home tab.
  function dismissOpenDialogs(): boolean {
    // DTC detail bottom sheet (storage-status.ts) — click its Close control so
    // the trap-deactivate + focus-restore path that owns it runs.
    const dtcDetail = el("dtcDetailSheet");
    if (dtcDetail && !dtcDetail.hidden) {
      el("dtcDetailClose")?.click();
      return true;
    }
    // Trip-detail sheet (map.ts) — click its Close control so actions.ts runs the
    // trap-deactivate + focus-restore path that owns it.
    const tripDetail = el("tripDetailSheet");
    if (tripDetail && !tripDetail.hidden) {
      el("tripDetailClose")?.click();
      return true;
    }
    // Clear-DTC warning alertdialog (actions.ts) — a modal that inerts the whole
    // shell via dtcTrap. Click its Cancel control so actions.ts runs the
    // trap-deactivate + focus-restore path; skipping it here would leave the
    // background inert after Back navigated away, freezing the app.
    const dtcClear = el("dtcClearWarning");
    if (dtcClear && !dtcClear.hidden) {
      (dtcClear.querySelector('[data-action="cancelClearDtc"]') as HTMLElement | null)?.click();
      return true;
    }
    // Restore alertdialog — only dismissible while NOT busy (the busy restore is a
    // deliberately non-dismissible modal). hideRestoreProgress releases the trap.
    if (dismissRestoreProgressIfAllowed()) {
      return true;
    }
    // App confirm/prompt (app-dialog.ts) — click Cancel so it settles its promise
    // and runs its own trap-deactivate + focus-restore path.
    const appDialog = el("appDialog");
    if (appDialog && !appDialog.hidden) {
      el("appDialogCancel")?.click();
      return true;
    }
    return false;
  }

  function handleAndroidBack(): boolean {
    if (dismissOpenDialogs()) {
      return true;
    }
    if (typeof VD.closeStatusPopover === "function" && VD.closeStatusPopover()) {
      return true;
    }
    const ts = VD.troubleshooter;
    const isOpen = ts && ts.isOpen;
    const close = ts && ts.close;
    if (typeof isOpen === "function" && typeof close === "function" && isOpen()) {
      close();
      return true;
    }
    if (state.mapFull) {
      state.mapFull = false;
      document.body.classList.remove("map-full-active");
      VD.renderMapIfLoaded();
      return true;
    }
    if (state.view && state.view !== "drive") {
      setView("drive");
      return true;
    }
    return false;
  }

  function updateViewHeading() {
    // Demo uses the same headings as real — it only simulates numbers, it doesn't relabel the UI.
    const meta = realViewMeta[String(state.view)] || realViewMeta.drive;
    if (!meta) return;
    setText("screenKicker", meta[0]);
    setText("screenTitle", meta[1]);
    const icon = el("screenTitleIcon");
    const iconPath = viewIconPaths[String(state.view)] || viewIconPaths.drive;
    if (icon && iconPath) {
      icon.setAttribute("d", iconPath);
      if (filledViewIcons.has(String(state.view))) {
        icon.setAttribute("fill", "currentColor");
        icon.removeAttribute("stroke");
        icon.removeAttribute("stroke-width");
        icon.removeAttribute("stroke-linecap");
        icon.removeAttribute("stroke-linejoin");
      } else {
        icon.setAttribute("fill", "none");
        icon.setAttribute("stroke", "currentColor");
        icon.setAttribute("stroke-width", "2");
        icon.setAttribute("stroke-linecap", "round");
        icon.setAttribute("stroke-linejoin", "round");
      }
    }
  }

  function setDemoActive(active: unknown, detail?: string) {
    const next = Boolean(active);
    const changed = state.demoActive !== next;
    setState({ demoActive: next });
    document.body.classList.toggle("demo-active", next);
    // Demo no longer swaps in a parallel mockup UI: it streams demo telemetry through the same
    // real components, so the real UI stays on screen and only the live numbers animate. (The old
    // .demo-only / .non-demo-only show/hide swap and its mockup cards have been removed.)
    const banner = el("demoBanner");
    if (banner) banner.hidden = !next;
    // Single morphing demo toggle: "Start Demo / Testing" <-> "Stop Demo / Testing".
    // Rewriting data-action in place keeps the existing [data-action] click
    // delegation routing to startDemo / stopDemo with no extra binding, so the
    // sandbox never shows a Start and a Stop button at the same time.
    queryAll("[data-demo-toggle]").forEach((button) => {
      button.dataset.action = next ? "stopDemo" : "demo";
      button.textContent = next ? "Stop Demo / Testing" : "Start Demo / Testing";
      button.classList.toggle("demo", !next);
      button.classList.toggle("demo-stop", next);
    });
    if (detail) VD.setStatus({ state: next ? "demo" : "ready", detail });
    if (!changed) return;
    updateViewHeading();
    VD.updateLiveUi();
    void ensureInsightsModule()
      .then(() => {
        VD.loadTrips(true);
        VD.loadInsights(true);
      })
      .catch(() => {});
  }

  function clearDemoTelemetry() {
    const source = String(state.telemetry.source || "").toLowerCase();
    if (!source.includes("demo")) return;
    // Drop any locally-staged demo rows + the live telemetry/session derivations
    // they fed, so they don't reappear on the next demo toggle. Routed through
    // setState since several of these (demoSessions, the session-distance anchors)
    // are read cross-module by the drive/charge renders.
    setState({
      demoSessions: null,
      telemetry: initialTelemetryState(),
      speedHistory: [],
      powerHistory: [],
      socHistory: [],
      sessionStartSoc: null,
      sessionDistanceM: 0,
      sessionLastLat: null,
      sessionLastLng: null,
      liveRouteStartedAtMs: null,
      liveRoutePoints: []
    });
    if (typeof VD.clearLivePosition === "function") VD.clearLivePosition();
  }

  function setDevices(payload: unknown) {
    // Guard the array invariant like setHistory does: parsePayload only falls
    // back to [] for a falsy/unparseable payload, so a payload that parses to a
    // non-array JSON value would reach .length/.forEach/.find and throw.
    const parsed = parsePayload(payload, []);
    validatePayload("setDevices", parsed);
    const devices: HistoryDevice[] = Array.isArray(parsed) ? parsed.filter(isHistoryDevice) : [];
    const select = el("deviceSelect") as HTMLSelectElement | null;
    const preferred = VD.getLastDevice();
    if (!select) return;
    select.replaceChildren();
    if (!devices.length) {
      const opt = document.createElement("option");
      opt.value = "";
      opt.textContent = "No paired adapters found";
      select.append(opt);
      if (typeof VD.refreshConnectionToolsAvailability === "function") {
        VD.refreshConnectionToolsAvailability();
      }
      return;
    }
    devices.forEach((device: HistoryDevice) => {
      const option = document.createElement("option");
      option.value = String(device.address || "");
      option.dataset.name = device.name || "OBD adapter";
      option.textContent = `${device.name || "OBD adapter"} · ${device.address || "unknown"}`;
      select.append(option);
    });
    if (preferred.address) {
      const preferredAddress = String(preferred.address);
      const option = Array.from(select.options).find((item) => item.value === preferredAddress);
      if (option) select.value = preferredAddress;
    } else {
      const likely = devices.find((device: HistoryDevice) => device.obdCandidate);
      if (likely) select.value = String(likely.address || "");
    }
    if (typeof VD.refreshConnectionToolsAvailability === "function") {
      VD.refreshConnectionToolsAvailability();
    }
  }

  function setHistory(payload: unknown) {
    // Abort any listeners attached by the previous render so they don't
    // leak when the history list is rebuilt.
    historyController?.abort();
    const controller = new AbortController();
    historyController = controller;
    const parsed = parsePayload(payload, []);
    validatePayload("setHistory", parsed);
    state.deviceHistory = Array.isArray(parsed)
      ? parsed.filter(isHistoryDevice)
      : [];
    const card = el("historyCard");
    const list = el("historyList");
    if (!card || !list) return;
    card.hidden = state.deviceHistory.length === 0;
    if (!state.deviceHistory.length) {
      list.replaceChildren();
      return;
    }
    const savedCount = state.deviceHistory.filter((device: HistoryDevice) => !device.candidate).length;
    setText("historyHint", savedCount ? "tap to select" : "paired candidate");
    list.replaceChildren(...state.deviceHistory.map((device: HistoryDevice, index: number) => buildHistoryRow(device, index)));
    queryAll("[data-history-index]").forEach((button) => {
      button.addEventListener("click", () => {
        const device = state.deviceHistory[Number(button.dataset.historyIndex)];
        if (!device) return;
        VD.selectDevice(device.address, device.name || "OBD adapter");
        VD.setStatus({ state: "ready", detail: `Selected ${device.name || device.address}.` });
      }, { signal: controller.signal });
    });
  }

  function buildHistoryRow(device: HistoryDevice, index: number) {
    const meta = device.candidate ? "paired candidate" : VD.relativeTime(device.lastSeen);
    const count = device.candidate ? "new" : (device.connectCount ? `${device.connectCount}x` : "");
    const button = document.createElement("button");
    button.type = "button";
    button.className = "history-row";
    button.dataset.historyIndex = String(index);
    const center = document.createElement("span");
    const strong = document.createElement("strong");
    strong.textContent = device.name || "OBD adapter";
    const small = document.createElement("small");
    small.textContent = `${device.address || "unknown"} · ${meta}`;
    center.append(strong, small);
    const right = document.createElement("b");
    right.textContent = count;
    button.append(center, right);
    return button;
  }

  // v2 design: the Demo/Testing sandbox ships expanded — the scenario picker is
  // a first-class control. Opened here at boot rather than via a static `open`
  // attribute, which trips a jsdom HTMLDetailsElement parse bug under the
  // vitest fake-timer harness.
  const sandboxDetails = document.querySelector<HTMLDetailsElement>("details.sandbox-tools");
  if (sandboxDetails) sandboxDetails.open = true;

  Object.assign(VD, {
    reportClientError,
    callBridge,
    setRestoreProgress,
    parsePayload,
    setText,
    setMeter,
    scrollAppToTop,
    scrollAppBy,
    canScrollApp,
    setView,
    openTripFromNative,
    formatRowCount,
    setBackupReceipt,
    handleAndroidBack,
    updateViewHeading,
    setDemoActive,
    clearDemoTelemetry,
    ensureDemoData,
    loadDashboardScript,
    ensureDtcData,
    dtcDataLoaded,
    ensureInsightsModule,
    ensureSignalsModule,
    hydrateConnectionTools,
    ensureConnectionToolsModule,
    ensureMapModule,
    pendingLazyLoads,
    requestMapRender,
    renderMapIfLoaded,
    ensureTroubleshooterModule,
    dtcSearchUrl,
    setDevices,
    setHistory,
    realViewMeta
  });
  if (typeof VD.applyDiagnosticsMode === "function") VD.applyDiagnosticsMode();

export {};
