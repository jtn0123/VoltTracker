// Initializes the VoltDashboard namespace shared by every dashboard JS file. Each
// of the 5 files is wrapped in its own IIFE; cross-file calls go through
// `window.VoltDashboard` (aliased locally as `VD`). `window.VoltTrackerAndroid`
// (the Android->WebView bridge) and `window.VoltTrackerNative` (the WebView<-Android
// callback surface) are preserved exactly as-is — those names are part of the ABI.
(function () {
  "use strict";

  const VD = (window.VoltDashboard = window.VoltDashboard || {});
  VD.bridge = window.VoltTrackerAndroid || null;
  VD.el = (id) => document.getElementById(id);
  // Top-level abort controller for window-level error/rejection listeners, so the
  // C2 reset hook can tear them down with the rest of the actions.js listeners.
  VD.errorController = new AbortController();

  const bridge = VD.bridge;
  const el = VD.el;

  // C2: per-render AbortControllers so the listeners attached inside renderTrips()
  // and setHistory() don't accumulate across re-renders. Each render aborts the
  // previous batch before binding the new one.
  let tripsListController = null;
  let historyController = null;

  function reportClientError(label, detail) {
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

  // E2: forward CSP violations to the Android logger so blocked inline scripts,
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

  const data = {
    trips: [
      { id: 8421, label: "Home -> Office", date: "Apr 30 - 08:14", miles: 18.4, mins: 28, efficiency: "4.1 mi/kWh", mode: "ev", wh: 241 },
      { id: 8420, label: "Office -> Trader Joe's", date: "Apr 29 - 17:42", miles: 6.1, mins: 14, efficiency: "3.9 mi/kWh", mode: "ev", wh: 256 },
      { id: 8419, label: "Trader Joe's -> Home", date: "Apr 29 - 19:08", miles: 7.3, mins: 14, efficiency: "4.0 mi/kWh", mode: "ev", wh: 250 },
      { id: 8418, label: "Home -> Tahoe", date: "Apr 28 - 07:22", miles: 184.2, mins: 173, efficiency: "41.7 MPG", mode: "mixed", wh: 0 },
      { id: 8417, label: "Tahoe Loop", date: "Apr 27 - 10:00", miles: 22.0, mins: 47, efficiency: "3.6 mi/kWh", mode: "ev", wh: 278 },
      { id: 8416, label: "Tahoe -> Home", date: "Apr 26 - 16:48", miles: 178.5, mins: 160, efficiency: "43.2 MPG", mode: "gas", wh: 0 }
    ],
    sessions: [
      { date: "Apr 30 - 21:18", type: "L2", kwh: 11.8, soc: "24->91", location: "Home", cost: "$1.41" },
      { date: "Apr 29 - 22:04", type: "L2", kwh: 9.6, soc: "36->90", location: "Home", cost: "$1.15" },
      { date: "Apr 28 - 18:12", type: "L1", kwh: 5.2, soc: "58->88", location: "Office", cost: "$0.62" },
      { date: "Apr 27 - 20:48", type: "L2", kwh: 10.4, soc: "32->90", location: "Home", cost: "$1.25" }
    ],
    hourly: [8,12,18,24,16,10,4,0,0,2,4,8,12,10,6,4,8,14,22,30,42,68,82,54],
    insights: [
      { kind: "good", icon: "+", title: "Best month yet for EV ratio", body: "April hit 78% electric, up from 64% in March. Projected annual savings rises about $45." },
      { kind: "good", icon: "OK", title: "Battery degrading below average", body: "8.7% capacity loss across 38k miles vs about 12% expected for 2017 Volts." },
      { kind: "warn", icon: "!", title: "Cell 47 trending low", body: "Cell 47 has drifted 18 mV below the pack mean over the past two weeks." },
      { kind: "info", icon: "i", title: "Cheaper to charge after 21:00", body: "Shifting two L2 sessions per week saves roughly $8 per month." },
      { kind: "good", icon: "EV", title: "Tahoe trip MPG within 4% of route avg", body: "Apr 28's 184 mile roundtrip hit 41.7 MPG for that elevation profile." }
    ]
  };
  VD.data = data;

  const state = {
    view: "drive",
    mode: "ev",
    tripFilter: "all",
    selectedTripId: 8421,
    lastDevice: null,
    deviceHistory: [],
    storage: {},
    trips: [],
    insights: {},
    appState: {},
    demoActive: false,
    mapLayer: "eff",
    mapFull: false,
    selectedMapSessionId: null,
    status: {},
    speedHistory: [],
    // Tracked by telemetry.js for the C6 BT-disconnected stale-data indicator.
    lastSampleAt: 0,
    // C1: latest telemetry render is throttled via requestAnimationFrame; this
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
      raw: ""
    }
  };
  VD.state = state;

  const realViewMeta = {
    drive: ["Volt Tracker Android", "Drive"],
    trips: ["Logged drives", "Trips"],
    map: ["GPS route", "Map"],
    charge: ["Real charging", "Charge"],
    insights: ["Vehicle health", "Insights"],
    settings: ["OBD bridge", "Diagnostics"]
  };

  const demoViewMeta = {
    ...realViewMeta,
    trips: ["Preview sandbox", "Trips"],
    charge: ["Preview sandbox", "Charge"],
    insights: ["Preview sandbox", "Insights"]
  };

  function parsePayload(payload, fallback) {
    if (!payload) return fallback;
    try { return typeof payload === "string" ? JSON.parse(payload) : payload; }
    catch (err) { return fallback; }
  }

  function setText(id, value) {
    const node = el(id);
    if (node) node.textContent = value == null || value === "" ? "--" : value;
  }

  function setMeter(id, value) {
    const node = el(id);
    if (node) node.style.width = Math.max(0, Math.min(100, Number(value) || 0)) + "%";
  }

  function setView(view) {
    state.view = view;
    if (view !== "map" && state.mapFull) {
      state.mapFull = false;
      document.body.classList.remove("map-full-active");
      VD.renderMap();
    }
    document.querySelectorAll(".view").forEach((node) => node.classList.toggle("is-active", node.dataset.view === view));
    document.querySelectorAll("[data-nav]").forEach((node) => {
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
    const meta = (state.demoActive ? demoViewMeta : realViewMeta)[state.view] || realViewMeta.drive;
    setText("screenKicker", meta[0]);
    setText("screenTitle", meta[1]);
  }

  function setMode(mode) {
    state.mode = mode;
    document.querySelectorAll("[data-mode]").forEach((btn) => btn.classList.toggle("is-active", btn.dataset.mode === mode));
    const ratio = mode === "ev" ? 78 : 22;
    el("evRing").style.setProperty("--v", ratio);
    setText("evRatioValue", ratio + "%");
  }

  function setDemoActive(active, detail) {
    const next = Boolean(active);
    const changed = state.demoActive !== next;
    state.demoActive = next;
    document.body.classList.toggle("demo-active", next);
    document.querySelectorAll(".demo-only").forEach((node) => {
      node.hidden = !next;
    });
    document.querySelectorAll(".non-demo-only").forEach((node) => {
      node.hidden = next;
    });
    const banner = el("demoBanner");
    if (banner) banner.hidden = !next;
    const bannerStop = el("demoStopBtn");
    if (bannerStop) bannerStop.hidden = !next;
    document.querySelectorAll('[data-action="stopDemo"]').forEach((button) => {
      button.hidden = !next;
    });
    document.querySelectorAll('[data-action="demo"]').forEach((button) => {
      button.textContent = next ? "Demo on" : (button.dataset.demoLabel || "Demo");
      button.classList.toggle("is-active", next);
    });
    if (detail) VD.setStatus({ state: next ? "demo" : "ready", detail });
    if (!changed) return;
    updateViewHeading();
    renderTrips();
    renderSessions();
    renderInsights();
    VD.updateLiveUi();
    VD.drawTrace();
    VD.loadTrips();
    VD.loadInsights();
  }

  function clearDemoTelemetry() {
    const source = String(state.telemetry.source || "").toLowerCase();
    if (!source.includes("demo")) return;
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
      raw: ""
    };
    state.speedHistory = [];
  }

  function renderTrips() {
    // C2: abort any listeners attached by the previous render so they don't
    // leak when the trip list is rebuilt.
    tripsListController?.abort();
    tripsListController = new AbortController();
    const home = el("homeTrips");
    const list = el("tripList");
    if (!state.demoActive) {
      home.replaceChildren();
      list.replaceChildren();
      return;
    }
    home.replaceChildren(...data.trips.slice(0, 5).map(buildTripRow));
    const filtered = data.trips.filter((trip) => state.tripFilter === "all" || trip.mode === state.tripFilter);
    list.replaceChildren(...filtered.map(buildTripRow));
    document.querySelectorAll("[data-trip-id]").forEach((button) => {
      button.addEventListener("click", () => selectTrip(Number(button.dataset.tripId)), { signal: tripsListController.signal });
    });
    selectTrip(state.selectedTripId);
  }

  // C4: build a demo-trip row via DOM APIs instead of innerHTML += template
  // literals, so user-provided fields never get re-interpreted as markup.
  function buildTripRow(trip) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "trip-row";
    button.dataset.tripId = String(trip.id);
    button.dataset.mode = trip.mode;
    const dot = document.createElement("span");
    dot.className = "trip-dot";
    const center = document.createElement("span");
    const strong = document.createElement("strong");
    strong.textContent = trip.label;
    const small = document.createElement("small");
    small.textContent = `${trip.date} - ${trip.miles} mi - ${trip.mins}m`;
    center.append(strong, small);
    const right = document.createElement("b");
    right.textContent = trip.efficiency;
    button.append(dot, center, right);
    return button;
  }

  function selectTrip(id) {
    state.selectedTripId = id;
    const trip = data.trips.find((item) => item.id === id) || data.trips[0];
    if (!trip) return;
    setText("tripDetailTitle", trip.label);
    setText("tripDetailMeta", `${trip.date} - ${trip.miles} mi - ${trip.mins} min`);
    setText("tripEnergyTitle", trip.wh ? `${trip.wh} Wh/mi` : trip.efficiency);
  }

  function renderSessions() {
    const bars = el("hourBars");
    const list = el("sessionList");
    if (!state.demoActive) {
      bars.replaceChildren();
      list.replaceChildren();
      return;
    }
    bars.replaceChildren(...data.hourly.map((height, index) => {
      const off = index >= 21 || index < 6;
      const span = document.createElement("span");
      if (off) span.className = "is-offpeak";
      span.style.height = height + "%";
      return span;
    }));
    list.replaceChildren(...data.sessions.map(buildSessionRow));
  }

  function buildSessionRow(s) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "session-row";
    const badge = document.createElement("span");
    badge.className = "session-badge";
    badge.textContent = s.type;
    const center = document.createElement("span");
    const strong = document.createElement("strong");
    strong.textContent = s.location;
    const small = document.createElement("small");
    small.textContent = `${s.date} - SOC ${s.soc}%`;
    center.append(strong, small);
    const right = document.createElement("b");
    // Original markup was `${kwh} kWh<br>${cost}`; reproduce via a real <br>.
    right.append(document.createTextNode(`${s.kwh} kWh`), document.createElement("br"), document.createTextNode(s.cost));
    button.append(badge, center, right);
    return button;
  }

  function renderInsights() {
    const home = el("homeInsights");
    const list = el("insightList");
    const cells = el("cellGrid");
    if (!state.demoActive) {
      home.replaceChildren();
      list.replaceChildren();
      cells.replaceChildren();
      return;
    }
    list.replaceChildren(...data.insights.map((item) => buildInsightArticle(item, true)));
    home.replaceChildren(...data.insights.slice(0, 3).map((item) => buildInsightArticle(item, false)));
    cells.replaceChildren(...Array.from({ length: 96 }).map((_, i) => {
      const watch = i + 1 === 47;
      const c = watch ? 0.8 : 0.16 + ((i * 13 + 5) % 9) / 14;
      const span = document.createElement("span");
      span.className = "cell" + (watch ? " is-watch" : "");
      span.style.setProperty("--c", c);
      return span;
    }));
  }

  function buildInsightArticle(item, includePanelClass) {
    const article = document.createElement("article");
    article.className = (includePanelClass ? "panel insight" : "insight") + (item.kind === "warn" ? " warn" : "");
    const icon = document.createElement("span");
    icon.className = "insight-icon";
    icon.textContent = item.icon;
    const body = document.createElement("span");
    const strong = document.createElement("strong");
    strong.textContent = item.title;
    const p = document.createElement("p");
    p.textContent = item.body;
    body.append(strong, p);
    article.append(icon, body);
    return article;
  }

  function setDevices(payload) {
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
    devices.forEach((device) => {
      const option = document.createElement("option");
      option.value = device.address;
      option.dataset.name = device.name || "OBD adapter";
      option.textContent = `${device.name || "OBD adapter"} - ${device.address}`;
      select.append(option);
    });
    if (preferred.address) {
      const option = Array.from(select.options).find((item) => item.value === preferred.address);
      if (option) select.value = preferred.address;
    } else {
      const likely = devices.find((device) => device.obdCandidate);
      if (likely) select.value = likely.address;
    }
  }

  function setHistory(payload) {
    // C2: abort any listeners attached by the previous render so they don't
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
    const savedCount = state.deviceHistory.filter((device) => !device.candidate).length;
    setText("historyHint", savedCount ? "tap to select" : "paired candidate");
    list.replaceChildren(...state.deviceHistory.map((device, index) => buildHistoryRow(device, index)));
    document.querySelectorAll("[data-history-index]").forEach((button) => {
      button.addEventListener("click", () => {
        const device = state.deviceHistory[Number(button.dataset.historyIndex)];
        VD.selectDevice(device.address, device.name || "OBD adapter");
        VD.setStatus({ state: "ready", detail: `Selected ${device.name || device.address}.` });
      }, { signal: historyController.signal });
    });
  }

  function buildHistoryRow(device, index) {
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
    small.textContent = `${device.address || "unknown"} - ${meta}`;
    center.append(strong, small);
    const right = document.createElement("b");
    right.textContent = count;
    button.append(center, right);
    return button;
  }

  Object.assign(VD, {
    reportClientError,
    parsePayload,
    setText,
    setMeter,
    setView,
    updateViewHeading,
    setMode,
    setDemoActive,
    clearDemoTelemetry,
    renderTrips,
    selectTrip,
    renderSessions,
    renderInsights,
    setDevices,
    setHistory,
    realViewMeta,
    demoViewMeta
  });
})();
