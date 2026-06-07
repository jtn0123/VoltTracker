// Initializes the VoltDashboard namespace shared by every dashboard source file. Each
// source file attaches cross-file calls to `window.VoltDashboard` (aliased
// locally as `VD`). `window.VoltTrackerAndroid` (the Android->WebView bridge)
// and `window.VoltTrackerNative` (the WebView<-Android callback surface) are
// preserved exactly as-is — those names are part of the ABI.

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
  VD.el = (id: string) => document.getElementById(id);

  // bindListenerGuarded attaches a listener but warns + skips if the element ID is
  // missing instead of throwing. The dashboard is assembled from partials at build time, so
  // a renamed/removed ID inside a partial would otherwise crash `bindListeners()` mid-way,
  // leaving every binding AFTER the missing one unwired with no surface signal. The warn is
  // piped through logClientError (window.error handler picks it up) so the regression is
  // visible in dev/test rather than silently swallowed.
  VD.bindListenerGuarded = function bindListenerGuarded(
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
  };
  // Top-level abort controller for window-level error/rejection listeners, so the
  // reset hook can tear them down with the rest of the actions.js listeners.
  VD.errorController = new AbortController();

  const bridge = VD.bridge;
  const el = VD.el;

  // Typed querySelectorAll over elements (every dashboard selector targets HTMLElements), so
  // callers get .dataset/.hidden without a per-site cast.
  const queryAll = (selector: string) =>
    document.querySelectorAll(selector) as NodeListOf<HTMLElement>;

  function escapeHtml(value: unknown) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  // Per-render AbortController so the listeners attached inside setHistory()
  // don't accumulate across re-renders. Each render aborts the previous batch
  // before binding the new one.
  let historyController: AbortController | null = null;

  function reportClientError(label: unknown, detail?: unknown) {
    const message = String(detail || label || "Unknown error");
    try {
      const detailNode = el("errorBannerDetail");
      if (detailNode) detailNode.textContent = message;
      const node = el("errorBanner");
      if (node) node.hidden = false;
    } catch (ignored) {}
    try {
      if (bridge && typeof bridge.logClientError === "function") {
        bridge.logClientError(String(label || "error"), message);
      }
    } catch (ignored) {}
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

  const data: DashboardData = { trips: [], sessions: [], hourly: [], insights: [], demoLoaded: false };
  VD.data = data;

  let demoDataLoading = false;
  const demoDataCallbacks: DemoDataCallback[] = [];

  function cloneArray(value: unknown) {
    return Array.isArray(value) ? value.map((item) => (
      item && typeof item === "object" ? { ...item } : item
    )) : [];
  }

  function asRecord(value: unknown): Record<string, unknown> {
    return value != null && typeof value === "object" ? value as Record<string, unknown> : {};
  }

  function applyDemoData(source: unknown) {
    const next = typeof source === "function" ? source() : source;
    const record = asRecord(next);
    data.trips = cloneArray(record.trips);
    data.sessions = cloneArray(record.sessions);
    data.hourly = cloneArray(record.hourly);
    data.insights = cloneArray(record.insights);
    data.demoLoaded = true;
    return data;
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
      applyDemoData(window.VoltDashboardDemoData);
      flushDemoDataCallbacks(null);
      return true;
    }
    if (demoDataLoading) return false;
    demoDataLoading = true;
    const script = document.createElement("script");
    script.src = "js/demo-data.js";
    script.onload = () => {
      demoDataLoading = false;
      applyDemoData(window.VoltDashboardDemoData || {});
      flushDemoDataCallbacks(null);
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
      script.onerror = () => reject(new Error("Unable to load " + src));
      document.head.append(script);
    });
  }

  let dtcDataPromise: Promise<VoltDashboard> | null = null;

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
          reportClientError("dtcData.load", err && err.message);
          throw err;
        });
    }
    return dtcDataPromise;
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
    // Demo-only staged charge sessions (actions.js stages rows here so they
    // don't touch the real session list); null until demo mode adds the first.
    demoSessions: null,
    appState: {},
    demoActive: false,
    mapLayer: "eff",
    mapRemoteTilesEnabled: true,
    mapFull: false,
    selectedMapSessionId: null,
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
    telemetry: {
      speedKph: null,
      rpm: null,
      voltage: null,
      coolantC: null,
      loadPct: null,
      throttlePct: null,
      soc: null,
      batteryTemp: null,
      powerKw: null,
      updatedAt: null,
      // Mirrors the latest sample's `source` field (e.g. "demo") so
      // clearDemoTelemetry can tell whether the staged telemetry is demo data.
      source: "",
      raw: ""
    }
  };
  VD.state = state;

  // ----- shared-state accessor (C3) ----------------------------------------
  // The state bag is mutated from every module. For the fields that carry a
  // CROSS-MODULE invariant — the demo lifecycle (demoActive + the real/preview
  // shadow copies) and the map/trip selections — writes go through setState so
  // there is one typed, greppable seam instead of scattered `state.x = …`. It is
  // a plain patch-merge (Object.assign), so behaviour is identical to a direct
  // assignment; the value is letting future invariants hang off one place.
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
    trips: ["Logged drives", "Trips"],
    map: ["GPS route", "Map"],
    charge: ["Real charging", "Charge"],
    insights: ["Vehicle health", "Insights"],
    settings: ["Adapter setup", "Settings"],
    signals: ["Enhanced discovery", "Detailed Signals"]
  };

  const viewIconPaths: Record<string, string> = {
    drive: "M13 2 5 13h6l-1 9 9-13h-6z",
    trips: "M4 5h3v3H4V5zm5 0h11v3H9V5zM4 10.5h3v3H4v-3zm5 0h11v3H9v-3zM4 16h3v3H4v-3zm5 0h11v3H9v-3z",
    map: "M15 4 9 2 3 4v18l6-2 6 2 6-2V2l-6 2zm-1 15-4-1.35V5l4 1.35V19z",
    charge: "M14 2v7h5l-9 13v-7H5l9-13z",
    insights: "M4 19h16v2H2V3h2v16zm3-2V9h3v8H7zm5 0V5h3v12h-3zm5 0v-6h3v6h-3z",
    settings: "M12 2a3 3 0 0 1 3 3v1h2.2l1.1 1.9-1.6 1.6c.2.5.3 1 .3 1.5s-.1 1-.3 1.5l1.6 1.6-1.1 1.9H15v1a3 3 0 0 1-6 0v-1H6.8l-1.1-1.9 1.6-1.6A4.2 4.2 0 0 1 7 11c0-.5.1-1 .3-1.5L5.7 7.9 6.8 6H9V5a3 3 0 0 1 3-3zm0 7a2 2 0 1 0 0 4 2 2 0 0 0 0-4z",
    signals: "M4 6h4m4 0h8M4 12h10m4 0h2M4 18h6m4 0h6M8 4v4m6 8v4m4-10v4"
  };
  const strokedViewIcons = new Set(["signals"]);

  function parsePayload(payload: unknown, fallback: unknown = null) {
    if (!payload) return fallback;
    try { return typeof payload === "string" ? JSON.parse(payload) : payload; }
    catch (_err) { return fallback; }
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

  function appScroller(): HTMLElement | null {
    return document.querySelector(".app");
  }

  function scrollAppToTop() {
    const scroller = appScroller();
    if (scroller && typeof scroller.scrollTo === "function") {
      scroller.scrollTo({ top: 0, behavior: "auto" });
      return;
    }
    window.scrollTo({ top: 0, behavior: "auto" });
  }

  function scrollAppBy(deltaY: number) {
    const scroller = appScroller();
    if (scroller && typeof scroller.scrollBy === "function") {
      scroller.scrollBy({ top: deltaY, left: 0, behavior: "auto" });
      return;
    }
    window.scrollBy({ top: deltaY, left: 0, behavior: "auto" });
  }

  function canScrollApp() {
    const scroller = appScroller();
    if (scroller) return scroller.scrollHeight > scroller.clientHeight + 2;
    return document.documentElement.scrollHeight > window.innerHeight + 2;
  }

  function setView(view: string) {
    state.view = view;
    document.body.dataset.activeView = view;
    if (view !== "map" && state.mapFull) {
      state.mapFull = false;
      document.body.classList.remove("map-full-active");
      VD.renderMap();
    }
    queryAll(".view").forEach((node) => node.classList.toggle("is-active", node.dataset.view === view));
    queryAll("[data-nav]").forEach((node) => {
      const active = node.dataset.nav === view;
      node.classList.toggle("is-active", active);
      if (active) {
        node.setAttribute("aria-current", "page");
      } else {
        node.removeAttribute("aria-current");
      }
    });
    if (view === "trips") VD.loadTrips();
    else if (view === "insights") VD.loadInsights();
    else if (view === "map") VD.renderMap();
    updateViewHeading();
    scrollAppToTop();
  }

  // Android hardware/gesture Back. The native OnBackPressedCallback calls this and only lets the
  // OS exit/background the app when it returns false. Dismiss the most-nested surface first: an
  // open troubleshooter modal, then a fullscreen map, then fall back to the Drive home tab.
  function handleAndroidBack(): boolean {
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
      if (typeof VD.renderMap === "function") VD.renderMap();
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
      if (strokedViewIcons.has(String(state.view))) {
        icon.setAttribute("fill", "none");
        icon.setAttribute("stroke", "currentColor");
        icon.setAttribute("stroke-width", "2");
        icon.setAttribute("stroke-linecap", "round");
        icon.setAttribute("stroke-linejoin", "round");
      } else {
        icon.setAttribute("fill", "currentColor");
        icon.removeAttribute("stroke");
        icon.removeAttribute("stroke-width");
        icon.removeAttribute("stroke-linecap");
        icon.removeAttribute("stroke-linejoin");
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
    const bannerStop = el("demoStopBtn");
    if (bannerStop) bannerStop.hidden = !next;
    queryAll('[data-action="stopDemo"]').forEach((button) => {
      button.hidden = !next;
    });
    queryAll('[data-action="demo"]').forEach((button) => {
      button.textContent = next ? "Demo on" : (button.dataset.demoLabel || "Demo");
      button.classList.toggle("is-active", next);
    });
    if (detail) VD.setStatus({ state: next ? "demo" : "ready", detail });
    if (!changed) return;
    updateViewHeading();
    VD.updateLiveUi();
    VD.drawTrace();
    VD.loadTrips();
    VD.loadInsights();
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
      telemetry: {
        speedKph: null,
        rpm: null,
        voltage: null,
        coolantC: null,
        loadPct: null,
        throttlePct: null,
        soc: null,
        batteryTemp: null,
        powerKw: null,
        updatedAt: null,
        source: "",
        raw: ""
      },
      speedHistory: [],
      powerHistory: [],
      socHistory: [],
      sessionStartSoc: null,
      sessionDistanceM: 0,
      sessionLastLat: null,
      sessionLastLng: null
    });
  }

  function setDevices(payload: unknown) {
    const devices = parsePayload(payload, []);
    const select = el("deviceSelect") as HTMLSelectElement | null;
    const preferred = VD.getLastDevice();
    if (!select) return;
    select.innerHTML = "";
    if (!devices.length) {
      const opt = document.createElement("option");
      opt.value = "";
      opt.textContent = "No paired adapters found";
      select.append(opt);
      return;
    }
    devices.forEach((device: HistoryDevice) => {
      const option = document.createElement("option");
      option.value = String(device.address || "");
      option.dataset.name = device.name || "OBD adapter";
      option.textContent = `${device.name || "OBD adapter"} · ${device.address}`;
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
  }

  function setHistory(payload: unknown) {
    // Abort any listeners attached by the previous render so they don't
    // leak when the history list is rebuilt.
    historyController?.abort();
    const controller = new AbortController();
    historyController = controller;
    const parsed = parsePayload(payload, []);
    state.deviceHistory = Array.isArray(parsed) ? parsed : [];
    const card = el("historyCard");
    const list = el("historyList");
    if (!card || !list) return;
    list.replaceChildren();
    card.hidden = state.deviceHistory.length === 0;
    if (!state.deviceHistory.length) return;
    const savedCount = state.deviceHistory.filter((device: HistoryDevice) => !device.candidate).length;
    setText("historyHint", savedCount ? "tap to select" : "paired candidate");
    list.replaceChildren(...state.deviceHistory.map((device: HistoryDevice, index: number) => buildHistoryRow(device, index)));
    queryAll("[data-history-index]").forEach((button) => {
      button.addEventListener("click", () => {
        const device = state.deviceHistory[Number(button.dataset.historyIndex)];
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

  Object.assign(VD, {
    reportClientError,
    escapeHtml,
    parsePayload,
    setText,
    setMeter,
    scrollAppToTop,
    scrollAppBy,
    canScrollApp,
    setView,
    handleAndroidBack,
    updateViewHeading,
    setDemoActive,
    clearDemoTelemetry,
    ensureDemoData,
    ensureDtcData,
    dtcDataLoaded,
    dtcSearchUrl,
    setDevices,
    setHistory,
    realViewMeta
  });

export {};
