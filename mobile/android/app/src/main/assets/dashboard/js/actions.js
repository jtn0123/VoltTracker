  function refreshDevices() {
    if (!bridge) {
      setStatus({ state: "demo", detail: "Android bridge is not available in this browser preview." });
      return;
    }
    setDevices(bridge.listDevices());
    if (typeof bridge.getDeviceHistory === "function") setHistory(bridge.getDeviceHistory());
  }

  function connectSelected(scan) {
    const selected = getSelectedDevice();
    if (!selected) {
      setStatus({ state: "blocked", detail: "Pick a paired or remembered OBD adapter first." });
      return;
    }
    if (!bridge) return;
    bridge.rememberDevice(selected.address, selected.name);
    if (scan && typeof bridge.scan === "function") bridge.scan(selected.address, selected.name);
    else bridge.connect(selected.address, selected.name);
  }

  function handleAction(action) {
    if (action === "permissions") bridge && bridge.requestPermissions();
    if (action === "refresh") bridge && bridge.refreshDevices();
    if (action === "refreshStorage") refreshStorage();
    if (action === "clearStorage") clearStorage();
    if (action === "exportDebug") exportDebugBundle();
    if (action === "backup") shareBackup();
    if (action === "restore") restoreBackup();
    if (action === "last") bridge && bridge.connectLast();
    if (action === "scan") connectSelected(true);
    if (action === "connect") connectSelected(false);
    if (action === "demo") startDemo();
    if (action === "stopDemo") stopDemo();
    if (action === "stop") stopAll();
  }

  function startDemo() {
    setDemoActive(true, "Demo preview is running.");
    if (bridge) bridge.demo();
    else runBrowserDemo();
  }

  function stopDemo() {
    window.clearInterval(window.__voltDemoTimer);
    if (bridge && state.demoActive) bridge.disconnect();
    clearDemoTelemetry();
    setDemoActive(false);
    updateLiveUi();
    drawTrace();
    setStatus({ state: "idle", detail: "Demo stopped. Real data and captured history will appear here." });
  }

  function stopAll() {
    window.clearInterval(window.__voltDemoTimer);
    if (bridge) bridge.disconnect();
    clearDemoTelemetry();
    setDemoActive(false);
    updateLiveUi();
    drawTrace();
    setStatus({ state: "idle", detail: "Stopped." });
  }

  function refreshStorage() {
    if (bridge && typeof bridge.getStorageSummary === "function") {
      setStorage(bridge.getStorageSummary());
    }
  }

  function clearStorage() {
    if (!bridge || typeof bridge.clearStoredData !== "function") return;
    const confirmed = window.confirm("Clear local OBD sessions, samples, and debug events from this phone?");
    if (!confirmed) return;
    bridge.clearStoredData();
    setTimeout(refreshStorage, 250);
  }

  function shareBackup() {
    if (!bridge || typeof bridge.shareBackup !== "function") {
      setStatus({ state: "blocked", detail: "Backup is only available inside the Android app." });
      return;
    }
    bridge.shareBackup();
  }

  function restoreBackup() {
    if (!bridge || typeof bridge.restoreBackup !== "function") {
      setStatus({ state: "blocked", detail: "Restore is only available inside the Android app." });
      return;
    }
    if (!window.confirm("Restore will REPLACE all data on this phone with the backup file. Continue?")) {
      return;
    }
    bridge.restoreBackup();
  }

  function exportDebugBundle() {
    if (!bridge || typeof bridge.exportDebugBundle !== "function") {
      setStatus({ state: "blocked", detail: "Debug export is only available inside the Android app." });
      return;
    }
    const result = parsePayload(bridge.exportDebugBundle(), {});
    if (result.ok) {
      setStatus({ state: "ready", detail: `Debug summary exported: ${result.path || "app files"}.` });
    } else {
      setStatus({ state: "blocked", detail: result.error || "Debug export failed." });
    }
  }

  function runBrowserDemo() {
    let t = 0;
    setStatus({ state: "connected", detail: "Browser-only demo is running." });
    window.clearInterval(window.__voltDemoTimer);
    window.__voltDemoTimer = window.setInterval(() => {
      t += 1;
      updateTelemetry({
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

  document.querySelectorAll("[data-nav]").forEach((button) => {
    button.addEventListener("click", () => setView(button.dataset.nav));
  });
  document.querySelectorAll("[data-nav-jump]").forEach((button) => {
    button.addEventListener("click", () => setView(button.dataset.navJump));
  });
  document.querySelectorAll("[data-mode]").forEach((button) => {
    button.addEventListener("click", () => setMode(button.dataset.mode));
  });
  document.querySelectorAll("[data-action]").forEach((button) => {
    button.addEventListener("click", () => handleAction(button.dataset.action));
  });
  document.querySelectorAll("[data-map-layer]").forEach((button) => {
    button.addEventListener("click", () => {
      state.mapLayer = button.dataset.mapLayer;
      renderMap();
    });
  });
  el("mapSessionList").addEventListener("click", (event) => {
    const button = event.target.closest("[data-map-session]");
    if (!button) return;
    state.selectedMapSessionId = button.dataset.mapSession;
    renderMap();
  });
  el("mapFullBtn").addEventListener("click", () => {
    state.mapFull = !state.mapFull;
    renderMap();
  });
  document.addEventListener("click", (event) => {
    const tripButton = event.target.closest("[data-trip-map]");
    if (!tripButton) return;
    const id = tripButton.dataset.tripMap;
    const trip = (state.trips || []).find((t) => String(t.id) === String(id));
    if (trip && trip.hasRoute) {
      state.selectedMapSessionId = id;
      setView("map");
    } else {
      setStatus({ state: "ready", detail: "This trip has no stored GPS route." });
    }
  });
  el("permissionBtn").addEventListener("click", () => handleAction("permissions"));
  el("refreshBtn").addEventListener("click", () => handleAction("refresh"));
  el("lastBtn").addEventListener("click", () => handleAction("last"));
  el("scanBtn").addEventListener("click", () => handleAction("scan"));
  el("connectBtn").addEventListener("click", () => handleAction(el("connectBtn").dataset.primaryAction || "connect"));
  el("disconnectBtn").addEventListener("click", () => handleAction("stop"));
  el("demoStopBtn").addEventListener("click", stopDemo);
  el("driveModeSelect").addEventListener("change", (event) => {
    setStatus({ state: "ready", detail: `Drive mode set to ${event.target.value}.` });
  });
  el("tripTabs").addEventListener("click", (event) => {
    const button = event.target.closest("button[data-filter]");
    if (!button) return;
    state.tripFilter = button.dataset.filter;
    document.querySelectorAll("#tripTabs button").forEach((node) => node.classList.toggle("is-active", node === button));
    renderTrips();
  });
  el("addChargeBtn").addEventListener("click", () => {
    data.sessions.unshift({ date: "Today - 21:10", type: "L2", kwh: 10.8, soc: "31->90", location: "Home", cost: "$1.30" });
    renderSessions();
    setStatus({ state: "ready", detail: "Charging session staged locally." });
  });

  window.VoltTrackerNative = { setDevices, setHistory, setStatus, setStorage, setAppState, updateTelemetry };

  setDemoActive(false);
  renderOperationalState();
  updateLiveUi();
  renderRealV2Ui();
  renderMap();
  loadTrips();
  loadInsights();
  drawTrace();
  refreshDevices();
  refreshStorage();
  if (!bridge) loadSampleData();
  window.addEventListener("resize", () => {
    drawTrace();
  });
  requestAnimationFrame(() => window.scrollTo({ top: 0, behavior: "auto" }));
  setTimeout(() => window.scrollTo({ top: 0, behavior: "auto" }), 200);
