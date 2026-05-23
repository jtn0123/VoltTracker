/*
 * actions.js — wiring + lifecycle.
 *
 * C2 listener-discipline pattern
 * ------------------------------
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
(function () {
  "use strict";

  const VD = window.VoltDashboard;
  const state = VD.state;
  const bridge = VD.bridge;
  const el = VD.el;
  const data = VD.data;

  // C2: AbortController for every listener bound below. resetListeners() aborts
  // the current set and rebinds — useful for hot-reloading WebView content or
  // for tests that swap fixtures between runs.
  let controller = new AbortController();

  // C4: lightweight in-flight guard for bridge-triggering buttons. The Android
  // bridge calls are sync-fire-and-forget so we can't await completion; a short
  // 600ms cooldown is enough to swallow accidental double-taps without making
  // the button feel sticky on real-device latency.
  function withBusy(button, fn) {
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

  function refreshDevices() {
    if (!bridge) {
      VD.setStatus({ state: "demo", detail: "Android bridge is not available in this browser preview." });
      return;
    }
    VD.setDevices(bridge.listDevices());
    if (typeof bridge.getDeviceHistory === "function") VD.setHistory(bridge.getDeviceHistory());
  }

  function connectSelected(scan, button) {
    const selected = VD.getSelectedDevice();
    if (!selected) {
      VD.setStatus({ state: "blocked", detail: "Pick a paired or remembered OBD adapter first." });
      return;
    }
    if (!bridge) return;
    // C4: guard the bridge call so a quick double-tap doesn't issue two
    // overlapping connect/scan invocations against the adapter.
    withBusy(button, () => {
      bridge.rememberDevice(selected.address, selected.name);
      if (scan && typeof bridge.scan === "function") bridge.scan(selected.address, selected.name);
      else bridge.connect(selected.address, selected.name);
    });
  }

  function handleAction(action, button) {
    if (action === "permissions") bridge && bridge.requestPermissions();
    if (action === "refresh") bridge && bridge.refreshDevices();
    if (action === "refreshStorage") refreshStorage();
    if (action === "clearStorage") clearStorage(button);
    if (action === "exportDebug") exportDebugBundle();
    if (action === "backup") shareBackup(button);
    if (action === "restore") restoreBackup(button);
    if (action === "last") bridge && bridge.connectLast();
    if (action === "scan") connectSelected(true, button);
    if (action === "connect") connectSelected(false, button);
    if (action === "demo") startDemo();
    if (action === "stopDemo") stopDemo();
    if (action === "stop") stopAll();
  }

  function startDemo() {
    VD.setDemoActive(true, "Demo preview is running.");
    if (bridge) bridge.demo();
    else runBrowserDemo();
  }

  function stopDemo() {
    window.clearInterval(window.__voltDemoTimer);
    if (bridge && state.demoActive) bridge.disconnect();
    VD.clearDemoTelemetry();
    VD.setDemoActive(false);
    VD.updateLiveUi();
    VD.drawTrace();
    VD.setStatus({ state: "idle", detail: "Demo stopped. Real data and captured history will appear here." });
  }

  function stopAll() {
    window.clearInterval(window.__voltDemoTimer);
    if (bridge) bridge.disconnect();
    VD.clearDemoTelemetry();
    VD.setDemoActive(false);
    VD.updateLiveUi();
    VD.drawTrace();
    VD.setStatus({ state: "idle", detail: "Stopped." });
  }

  function refreshStorage() {
    if (bridge && typeof bridge.getStorageSummary === "function") {
      VD.setStorage(bridge.getStorageSummary());
    }
  }

  function clearStorage(button) {
    if (!bridge || typeof bridge.clearStoredData !== "function") return;
    const confirmed = window.confirm("Clear local OBD sessions, samples, and debug events from this phone?");
    if (!confirmed) return;
    // C4: guard the bridge call against accidental re-issue while the storage
    // wipe is still propagating.
    withBusy(button, () => {
      bridge.clearStoredData();
      setTimeout(refreshStorage, 250);
    });
  }

  function shareBackup(button) {
    if (!bridge || typeof bridge.shareBackup !== "function") {
      VD.setStatus({ state: "blocked", detail: "Backup is only available inside the Android app." });
      return;
    }
    // The backup contains every GPS sample and raw OBD response from this phone.
    // Confirm before handing it to the share sheet so a stray tap can't leak PII.
    var ok = window.confirm(
      "Backup includes your GPS routes, every OBD sample, and adapter history.\n\n" +
      "Share only with people you trust. Continue?"
    );
    if (!ok) {
      VD.setStatus({ state: "ready", detail: "Backup cancelled." });
      return;
    }
    // C4: guard the bridge call so a quick double-tap doesn't open two
    // share sheets.
    withBusy(button, () => bridge.shareBackup());
  }

  function restoreBackup(button) {
    if (!bridge || typeof bridge.restoreBackup !== "function") {
      VD.setStatus({ state: "blocked", detail: "Restore is only available inside the Android app." });
      return;
    }
    if (!window.confirm("Restore will REPLACE all data on this phone with the backup file. Continue?")) {
      return;
    }
    // C4: guard the bridge call so a quick double-tap doesn't launch two
    // file pickers.
    withBusy(button, () => bridge.restoreBackup());
  }

  function exportDebugBundle() {
    if (!bridge || typeof bridge.exportDebugBundle !== "function") {
      VD.setStatus({ state: "blocked", detail: "Debug export is only available inside the Android app." });
      return;
    }
    const result = VD.parsePayload(bridge.exportDebugBundle(), {});
    if (result.ok) {
      VD.setStatus({ state: "ready", detail: `Debug summary exported: ${result.path || "app files"}.` });
    } else {
      VD.setStatus({ state: "blocked", detail: result.error || "Debug export failed." });
    }
  }

  function runBrowserDemo() {
    let t = 0;
    VD.setStatus({ state: "connected", detail: "Browser-only demo is running." });
    window.clearInterval(window.__voltDemoTimer);
    window.__voltDemoTimer = window.setInterval(() => {
      t += 1;
      VD.updateTelemetry({
        source: "demo",
        connected: true,
        sampleCount: t,
        sessionMs: t * 1000,
        supportedPids: "browser demo",
        vehicleState: "demo-preview",
        speedKph: Math.round(54 + 23 * Math.sin(t / 3.4)),
        rpm: state.mode === "gas" ? Math.round(1260 + 420 * Math.sin(t / 2.1)) : 0,
        coolantC: Math.round(82 + 4 * Math.sin(t / 8)),
        loadPct: Math.round(34 + 18 * Math.sin(t / 4.4)),
        throttlePct: Math.round(18 + 14 * Math.sin(t / 2.7)),
        voltage: 13.8,
        soc: Math.max(13.4, 77.8 - t * 0.01),
        batteryTemp: 72 + Math.sin(t / 8),
        powerKw: state.mode === "gas" ? 32 + Math.sin(t / 3) * 8 : 16 + Math.sin(t / 2.2) * 12,
        updatedAt: Date.now(),
        raw: "browser demo"
      });
    }, 1000);
  }

  // C1: window resize handler is debounced to 100ms — drawTrace recomputes canvas
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

    document.querySelectorAll("[data-nav]").forEach((button) => {
      button.addEventListener("click", () => VD.setView(button.dataset.nav), opts);
    });
    document.querySelectorAll("[data-nav-jump]").forEach((button) => {
      button.addEventListener("click", () => VD.setView(button.dataset.navJump), opts);
    });
    document.querySelectorAll("[data-mode]").forEach((button) => {
      button.addEventListener("click", () => VD.setMode(button.dataset.mode), opts);
    });
    document.querySelectorAll("[data-action]").forEach((button) => {
      button.addEventListener("click", (event) => handleAction(button.dataset.action, event.currentTarget), opts);
    });
    document.querySelectorAll("[data-map-layer]").forEach((button) => {
      button.addEventListener("click", () => {
        state.mapLayer = button.dataset.mapLayer;
        VD.renderMap();
      }, opts);
    });
    const onSessionClick = (event) => {
      const button = event.target.closest("[data-map-session]");
      if (!button) return;
      state.selectedMapSessionId = button.dataset.mapSession;
      VD.renderMap();
    };
    el("mapSessionList").addEventListener("click", onSessionClick, opts);
    // The new drive-chip strip uses the same [data-map-session] attribute, so
    // share the handler. Without this, tapping a chip did nothing.
    const chips = el("mapDriveChips");
    if (chips) chips.addEventListener("click", onSessionClick, opts);
    el("mapFullBtn").addEventListener("click", () => {
      state.mapFull = !state.mapFull;
      VD.renderMap();
    }, opts);
    document.addEventListener("click", (event) => {
      const tripButton = event.target.closest("[data-trip-map]");
      if (!tripButton) return;
      const id = tripButton.dataset.tripMap;
      const trip = (state.trips || []).find((t) => String(t.id) === String(id));
      if (trip && trip.hasRoute) {
        state.selectedMapSessionId = id;
        VD.setView("map");
      } else {
        VD.setStatus({ state: "ready", detail: "This trip has no stored GPS route." });
      }
    }, opts);
    el("permissionBtn").addEventListener("click", () => handleAction("permissions"), opts);
    el("refreshBtn").addEventListener("click", () => handleAction("refresh"), opts);
    el("lastBtn").addEventListener("click", () => handleAction("last"), opts);
    el("scanBtn").addEventListener("click", (event) => handleAction("scan", event.currentTarget), opts);
    el("connectBtn").addEventListener("click", (event) => handleAction(el("connectBtn").dataset.primaryAction || "connect", event.currentTarget), opts);
    el("disconnectBtn").addEventListener("click", () => handleAction("stop"), opts);
    el("demoStopBtn").addEventListener("click", stopDemo, opts);
    el("driveModeSelect").addEventListener("change", (event) => {
      VD.setStatus({ state: "ready", detail: `Drive mode set to ${event.target.value}.` });
    }, opts);
    el("tripTabs").addEventListener("click", (event) => {
      const button = event.target.closest("button[data-filter]");
      if (!button) return;
      state.tripFilter = button.dataset.filter;
      document.querySelectorAll("#tripTabs button").forEach((node) => node.classList.toggle("is-active", node === button));
      VD.renderTrips();
    }, opts);
    el("addChargeBtn").addEventListener("click", () => {
      data.sessions.unshift({ date: "Today - 21:10", type: "L2", kwh: 10.8, soc: "31->90", location: "Home", cost: "$1.30" });
      VD.renderSessions();
      VD.setStatus({ state: "ready", detail: "Charging session staged locally." });
    }, opts);
    window.addEventListener("resize", debouncedResize, opts);
  }

  // C2: reset hook. Aborts every listener bound by bindListeners() (and the
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
    handleAction,
    startDemo,
    stopDemo,
    stopAll,
    refreshStorage,
    clearStorage,
    shareBackup,
    restoreBackup,
    exportDebugBundle,
    runBrowserDemo,
    resetListeners
  };
  Object.assign(VD, {
    refreshDevices,
    connectSelected,
    handleAction,
    startDemo,
    stopDemo,
    stopAll,
    refreshStorage,
    clearStorage,
    shareBackup,
    restoreBackup,
    exportDebugBundle,
    runBrowserDemo
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
  VD.drawTrace();
  refreshDevices();
  refreshStorage();
  if (!bridge) VD.loadSampleData();
  requestAnimationFrame(() => window.scrollTo({ top: 0, behavior: "auto" }));
  setTimeout(() => window.scrollTo({ top: 0, behavior: "auto" }), 200);
})();
