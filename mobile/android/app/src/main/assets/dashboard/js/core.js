// @ts-check
// Initializes the VoltDashboard namespace shared by every dashboard JS file. Each
// of the 5 files is wrapped in its own IIFE; cross-file calls go through
// `window.VoltDashboard` (aliased locally as `VD`). `window.VoltTrackerAndroid`
// (the Android->WebView bridge) and `window.VoltTrackerNative` (the WebView<-Android
// callback surface) are preserved exactly as-is — those names are part of the ABI.
(function () {
  "use strict";

  const VD = /** @type {any} */ (window.VoltDashboard = window.VoltDashboard || {});
  VD.bridge = window.VoltTrackerAndroid || null;
  VD.el = (/** @type {any} */ id) => document.getElementById(id);

  // bindListenerGuarded attaches a listener but warns + skips if the element ID is
  // missing instead of throwing. The dashboard is assembled from partials at build time, so
  // a renamed/removed ID inside a partial would otherwise crash `bindListeners()` mid-way,
  // leaving every binding AFTER the missing one unwired with no surface signal. The warn is
  // piped through logClientError (window.error handler picks it up) so the regression is
  // visible in dev/test rather than silently swallowed.
  VD.bindListenerGuarded = function bindListenerGuarded(/** @type {any} */ id, /** @type {any} */ event, /** @type {any} */ handler, /** @type {any} */ opts) {
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
  const queryAll = (/** @type {any} */ selector) =>
    /** @type {NodeListOf<HTMLElement>} */ (document.querySelectorAll(selector));

  function readRemoteTilesPreference() {
    try {
      return window.localStorage.getItem("volttracker.map.remoteTiles") === "1";
    } catch (_err) {
      return false;
    }
  }

  function escapeHtml(/** @type {any} */ value) {
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
  let /** @type {any} */ historyController = null;

  function reportClientError(/** @type {any} */ label, /** @type {any} */ detail) {
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

  /** @type {{ trips: any[], sessions: any[], hourly: any[], insights: any[], demoLoaded: boolean }} */
  const data = { trips: [], sessions: [], hourly: [], insights: [], demoLoaded: false };
  VD.data = data;

  let demoDataLoading = false;
  const /** @type {any[]} */ demoDataCallbacks = [];

  function cloneArray(/** @type {any} */ value) {
    return Array.isArray(value) ? value.map((item) => (
      item && typeof item === "object" ? { ...item } : item
    )) : [];
  }

  function applyDemoData(/** @type {any} */ source) {
    const next = typeof source === "function" ? source() : source;
    data.trips = cloneArray(next && next.trips);
    data.sessions = cloneArray(next && next.sessions);
    data.hourly = cloneArray(next && next.hourly);
    data.insights = cloneArray(next && next.insights);
    data.demoLoaded = true;
    return data;
  }

  function flushDemoDataCallbacks(/** @type {any} */ error) {
    const callbacks = demoDataCallbacks.splice(0);
    callbacks.forEach((callback) => {
      try { callback(error, data); } catch (err) { reportClientError("demoData.callback", err && err.message); }
    });
  }

  function ensureDemoData(/** @type {any} */ callback) {
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

  function dashboardScriptAlreadyLoaded(/** @type {any} */ src) {
    return Boolean(document.querySelector(`script[src="${src}"][data-dashboard-lazy="true"]`));
  }

  function loadDashboardScript(/** @type {any} */ src) {
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
      script.onload = () => resolve();
      script.onerror = () => reject(new Error("Unable to load " + src));
      document.head.append(script);
    });
  }

  let /** @type {any} */ dtcDataPromise = null;

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

  function dtcSearchUrl(/** @type {any} */ code) {
    const key = String(code || "").trim().toUpperCase();
    const q = encodeURIComponent((key || "OBD-II") + " Chevy Volt DTC");
    return "https://www.google.com/search?q=" + q;
  }

  // The central runtime/UI state bag. Fields are assigned dynamically across
  // every dashboard file (telemetry samples, render selections, map layers),
  // so it is typed as an open record rather than a closed literal.
  /** @type {Record<string, any>} */
  const state = {
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
    /** @type {any[] | null} */
    demoSessions: null,
    appState: {},
    demoActive: false,
    mapLayer: "eff",
    mapRemoteTilesEnabled: readRemoteTilesPreference(),
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

  /** @type {Record<string, [string, string]>} */
  const realViewMeta = {
    drive: ["Volt Tracker Android", "Drive"],
    trips: ["Logged drives", "Trips"],
    map: ["GPS route", "Map"],
    charge: ["Real charging", "Charge"],
    insights: ["Vehicle health", "Insights"],
    settings: ["OBD bridge", "Diagnostics"],
    signals: ["Enhanced discovery", "Detailed Signals"]
  };

  /** @type {Record<string, string>} */
  const viewIconPaths = {
    drive: "M13 2 5 13h6l-1 9 9-13h-6z",
    trips: "M4 5h3v3H4V5zm5 0h11v3H9V5zM4 10.5h3v3H4v-3zm5 0h11v3H9v-3zM4 16h3v3H4v-3zm5 0h11v3H9v-3z",
    map: "M15 4 9 2 3 4v18l6-2 6 2 6-2V2l-6 2zm-1 15-4-1.35V5l4 1.35V19z",
    charge: "M14 2v7h5l-9 13v-7H5l9-13z",
    insights: "M4 19h16v2H2V3h2v16zm3-2V9h3v8H7zm5 0V5h3v12h-3zm5 0v-6h3v6h-3z",
    settings: "M12 2a3 3 0 0 1 3 3v1h2.2l1.1 1.9-1.6 1.6c.2.5.3 1 .3 1.5s-.1 1-.3 1.5l1.6 1.6-1.1 1.9H15v1a3 3 0 0 1-6 0v-1H6.8l-1.1-1.9 1.6-1.6A4.2 4.2 0 0 1 7 11c0-.5.1-1 .3-1.5L5.7 7.9 6.8 6H9V5a3 3 0 0 1 3-3zm0 7a2 2 0 1 0 0 4 2 2 0 0 0 0-4z",
    signals: "M4 6h4m4 0h8M4 12h10m4 0h2M4 18h6m4 0h6M8 4v4m6 8v4m4-10v4"
  };

  function parsePayload(/** @type {any} */ payload, /** @type {any} */ fallback) {
    if (!payload) return fallback;
    try { return typeof payload === "string" ? JSON.parse(payload) : payload; }
    catch (_err) { return fallback; }
  }

  // setText/setMeter null-guard on a missing element so a renamed/removed partial ID can't crash a
  // render mid-pass. The downside is silence: a live tile would sit at "--" forever with no signal.
  // So a miss now warns ONCE per id (deduped — these run ~1Hz) and pipes through logClientError, the
  // same surfacing the bindListenerGuarded path uses. Both helpers return whether the element was
  // found, so a caller can react to a false if it ever needs to. Mirrors VD.bindListenerGuarded.
  const warnedMissingTargets = new Set();
  function warnMissingTarget(/** @type {any} */ fn, /** @type {any} */ id) {
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

  function setText(/** @type {any} */ id, /** @type {any} */ value) {
    const node = el(id);
    if (!node) {
      warnMissingTarget("setText", id);
      return false;
    }
    node.textContent = value == null || value === "" ? "--" : value;
    return true;
  }

  function setMeter(/** @type {any} */ id, /** @type {any} */ value) {
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

  function setView(/** @type {any} */ view) {
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
    window.scrollTo({ top: 0, behavior: "auto" });
  }

  function updateViewHeading() {
    // Demo uses the same headings as real — it only simulates numbers, it doesn't relabel the UI.
    const meta = realViewMeta[state.view] || realViewMeta.drive;
    setText("screenKicker", meta[0]);
    setText("screenTitle", meta[1]);
    const icon = el("screenTitleIcon");
    if (icon) icon.setAttribute("d", viewIconPaths[state.view] || viewIconPaths.drive);
  }

  function setDemoActive(/** @type {any} */ active, /** @type {any} */ detail) {
    const next = Boolean(active);
    const changed = state.demoActive !== next;
    state.demoActive = next;
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
    // Drop any locally-staged demo rows so they don't reappear on the next demo toggle.
    state.demoSessions = null;
    state.telemetry = {
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
    };
    state.speedHistory = [];
    state.powerHistory = [];
    state.socHistory = [];
    state.sessionStartSoc = null;
    state.sessionDistanceM = 0;
    state.sessionLastLat = null;
    state.sessionLastLng = null;
  }

  function setDevices(/** @type {any} */ payload) {
    const devices = parsePayload(payload, []);
    const select = el("deviceSelect");
    const preferred = VD.getLastDevice();
    select.innerHTML = "";
    if (!devices.length) {
      const opt = document.createElement("option");
      opt.value = "";
      opt.textContent = "No paired adapters found";
      select.append(opt);
      return;
    }
    devices.forEach((/** @type {any} */ device) => {
      const option = document.createElement("option");
      option.value = device.address;
      option.dataset.name = device.name || "OBD adapter";
      option.textContent = `${device.name || "OBD adapter"} · ${device.address}`;
      select.append(option);
    });
    if (preferred.address) {
      const option = Array.from(select.options).find((item) => item.value === preferred.address);
      if (option) select.value = preferred.address;
    } else {
      const likely = devices.find((/** @type {any} */ device) => device.obdCandidate);
      if (likely) select.value = likely.address;
    }
  }

  function setHistory(/** @type {any} */ payload) {
    // Abort any listeners attached by the previous render so they don't
    // leak when the history list is rebuilt.
    historyController?.abort();
    historyController = new AbortController();
    const parsed = parsePayload(payload, []);
    state.deviceHistory = Array.isArray(parsed) ? parsed : [];
    const card = el("historyCard");
    const list = el("historyList");
    list.replaceChildren();
    card.hidden = state.deviceHistory.length === 0;
    if (!state.deviceHistory.length) return;
    const savedCount = state.deviceHistory.filter((/** @type {any} */ device) => !device.candidate).length;
    setText("historyHint", savedCount ? "tap to select" : "paired candidate");
    list.replaceChildren(...state.deviceHistory.map((/** @type {any} */ device, /** @type {number} */ index) => buildHistoryRow(device, index)));
    queryAll("[data-history-index]").forEach((button) => {
      button.addEventListener("click", () => {
        const device = state.deviceHistory[Number(button.dataset.historyIndex)];
        VD.selectDevice(device.address, device.name || "OBD adapter");
        VD.setStatus({ state: "ready", detail: `Selected ${device.name || device.address}.` });
      }, { signal: historyController.signal });
    });
  }

  function buildHistoryRow(/** @type {any} */ device, /** @type {any} */ index) {
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
    setView,
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
})();
