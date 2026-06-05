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

  type BusyButton = HTMLElement & {
    disabled?: boolean;
  };

  type PageDragState = {
    pointerId: number;
    startX: number;
    startY: number;
    lastY: number;
    active: boolean;
  };

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

  function refreshDevices() {
    if (!bridge) {
      VD.setStatus({ state: "demo", detail: "Android bridge is not available in this browser preview." });
      return;
    }
    VD.setDevices(bridge.listDevices());
    if (typeof bridge.getDeviceHistory === "function") VD.setHistory(bridge.getDeviceHistory());
  }

  function connectSelected(scan: boolean, button?: BusyButton | null) {
    const selected = VD.getSelectedDevice();
    if (!selected) {
      VD.setStatus({ state: "blocked", detail: "Pick a paired or remembered OBD adapter first." });
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
      VD.setStatus({ state: "blocked", detail: "Pick a paired or remembered OBD adapter first." });
      return;
    }
    if (!bridge || typeof bridge.detailProbe !== "function") {
      VD.setStatus({ state: "idle", detail: "Detail Probe is only available inside the Android app." });
      return;
    }
    const stage = String(state.signalProbeStage || "tires");
    withBusy(button, () => bridge.detailProbe(selected.address, selected.name, stage));
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
    if (action === "last") bridge && bridge.connectLast();
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
    if (action === "previewDtcCodes") previewDtcCodes();
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
    storage.diagnosticCodeStatusCounts = samples.reduce((acc: Record<string, number>, s: any) => {
      const k = String(s.status || "stored").toLowerCase();
      acc[k] = (acc[k] || 0) + 1;
      return acc;
    }, {});
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
      VD.setDemoActive(true, "Demo preview is running.");
      if (bridge) bridge.demo();
      else runBrowserDemo();
    });
  }

  function stopDemo() {
    window.clearInterval(window.__voltDemoTimer ?? undefined);
    if (bridge && state.demoActive) bridge.disconnect();
    VD.clearDemoTelemetry();
    if (typeof VD.clearLivePosition === "function") VD.clearLivePosition();
    VD.setDemoActive(false);
    VD.updateLiveUi();
    VD.drawTrace();
    VD.setStatus({ state: "idle", detail: "Demo stopped. Real data and captured history will appear here." });
  }

  function stopAll() {
    window.clearInterval(window.__voltDemoTimer ?? undefined);
    if (bridge) bridge.disconnect();
    VD.clearDemoTelemetry();
    if (typeof VD.clearLivePosition === "function") VD.clearLivePosition();
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

  function clearStorage(button?: BusyButton | null) {
    if (!bridge || typeof bridge.clearStoredData !== "function") return;
    const confirmed = window.confirm("Clear local OBD sessions, samples, and debug events from this phone?");
    if (!confirmed) return;
    // Guard the bridge call against accidental re-issue while the storage
    // wipe is still propagating.
    withBusy(button, () => {
      bridge.clearStoredData();
    });
  }

  function shareBackup(button?: BusyButton | null) {
    if (!bridge || typeof bridge.shareBackup !== "function") {
      VD.setStatus({ state: "idle", detail: "Backup is only available inside the Android app." });
      return;
    }
    // Plaintext backup is an advanced compatibility escape hatch. The primary
    // UI path is encrypted backup; make this disclosure explicit before the
    // share sheet can receive a raw database copy.
    const ok = window.confirm(
      "Plaintext backup includes your GPS routes, every OBD sample, and adapter history.\n\n" +
      "Use encrypted backup unless another tool specifically needs the raw database. Continue?"
    );
    if (!ok) {
      VD.setStatus({ state: "ready", detail: "Backup cancelled." });
      return;
    }
    // Guard the bridge call so a quick double-tap doesn't open two
    // share sheets.
    withBusy(button, () => bridge.shareBackup());
  }

  function readBackupPassphrase(message: string) {
    const passphrase = window.prompt(message);
    if (passphrase == null) return null;
    const trimmed = String(passphrase).trim();
    return trimmed.length ? trimmed : null;
  }

  function shareEncryptedBackup(button?: BusyButton | null) {
    if (!bridge || typeof bridge.shareEncryptedBackup !== "function") {
      VD.setStatus({ state: "idle", detail: "Encrypted backup is only available inside the Android app." });
      return;
    }
    const passphrase = readBackupPassphrase("Choose a passphrase for this encrypted backup. You will need it to restore.");
    if (!passphrase) {
      VD.setStatus({ state: "ready", detail: "Encrypted backup cancelled." });
      return;
    }
    withBusy(button, () => bridge.shareEncryptedBackup(passphrase));
  }

  function restoreBackup(button?: BusyButton | null) {
    if (!bridge || typeof bridge.restoreBackup !== "function") {
      VD.setStatus({ state: "idle", detail: "Restore is only available inside the Android app." });
      return;
    }
    // The merge-vs-replace choice (and the destructive-replace warning) is shown
    // natively after the file is picked and verified, so no pre-pick confirm here.
    // Guard the bridge call so a quick double-tap doesn't launch two file pickers.
    withBusy(button, () => bridge.restoreBackup());
  }

  function restoreEncryptedBackup(button?: BusyButton | null) {
    if (!bridge || typeof bridge.restoreEncryptedBackup !== "function") {
      VD.setStatus({ state: "idle", detail: "Encrypted restore is only available inside the Android app." });
      return;
    }
    const passphrase = readBackupPassphrase("Enter the passphrase for this encrypted backup.");
    if (!passphrase) {
      VD.setStatus({ state: "ready", detail: "Encrypted restore cancelled." });
      return;
    }
    // Merge-vs-replace (and the destructive-replace warning) is chosen natively
    // once the file is picked, decrypted, and verified.
    withBusy(button, () => bridge.restoreEncryptedBackup(passphrase));
  }

  function exportDebugBundle() {
    if (!bridge || typeof bridge.exportDebugBundle !== "function") {
      VD.setStatus({ state: "idle", detail: "Debug export is only available inside the Android app." });
      return;
    }
    const result = VD.parsePayload(bridge.exportDebugBundle(), {});
    if (result.ok) {
      VD.setStatus({ state: "ready", detail: `Debug summary exported: ${result.path || "app files"}.` });
    } else {
      VD.setStatus({ state: "blocked", detail: result.error || "Debug export failed." });
    }
  }

  function writeClipboard(text: unknown) {
    const nav = window.navigator;
    if (nav.clipboard && typeof nav.clipboard.writeText === "function") {
      return nav.clipboard.writeText(String(text));
    }
    const area = document.createElement("textarea");
    area.value = String(text);
    area.setAttribute("readonly", "true");
    area.style.position = "fixed";
    area.style.left = "-9999px";
    document.body.append(area);
    area.select();
    try {
      document.execCommand("copy");
    } finally {
      area.remove();
    }
    return Promise.resolve();
  }

  function exportSignalLog(id: unknown) {
    if (!bridge || typeof bridge.exportDetailedSignalLog !== "function") {
      VD.setStatus({ state: "idle", detail: "Signal log export is only available inside the Android app." });
      return;
    }
    const result = bridge.exportDetailedSignalLog(String(id || ""));
    const parsed = VD.parsePayload(result, {});
    if (parsed.ok === false) {
      VD.setStatus({ state: "blocked", detail: parsed.message || "Signal log export failed." });
      return;
    }
    writeClipboard(JSON.stringify(parsed, null, 2))
      .then(() => VD.setStatus({ state: "ready", detail: "Detailed signal log copied." }))
      .catch(() => VD.setStatus({ state: "blocked", detail: "Could not copy detailed signal log." }));
  }

  function exportSignalLogs() {
    if (!bridge || typeof bridge.exportDetailedSignalLogs !== "function") {
      VD.setStatus({ state: "idle", detail: "Signal log export is only available inside the Android app." });
      return;
    }
    const result = bridge.exportDetailedSignalLogs();
    const parsed = VD.parsePayload(result, {});
    if (parsed.ok === false) {
      VD.setStatus({ state: "blocked", detail: parsed.message || "Signal log export failed." });
      return;
    }
    writeClipboard(JSON.stringify(parsed, null, 2))
      .then(() => VD.setStatus({ state: "ready", detail: "Detailed signal logs copied." }))
      .catch(() => VD.setStatus({ state: "blocked", detail: "Could not copy detailed signal logs." }));
  }

  function deleteSignalLog(id: unknown) {
    if (!bridge || typeof bridge.deleteDetailedSignalLog !== "function") {
      VD.setStatus({ state: "idle", detail: "Signal log cleanup is only available inside the Android app." });
      return;
    }
    const ok = window.confirm("Delete this saved detailed signal evidence row?");
    if (!ok) return;
    bridge.deleteDetailedSignalLog(String(id || ""));
  }

  function runBrowserDemo() {
    let t = 0;
    VD.setStatus({ state: "connected", detail: "Browser-only demo is running." });
    window.clearInterval(window.__voltDemoTimer ?? undefined);
    window.__voltDemoTimer = window.setInterval(() => {
      t += 1;
      // Alternate EV and gas every ~30s so both drivetrain states (RPM, gas
      // power, electric power) get exercised without a manual toggle.
      const gas = Math.floor(t / 30) % 2 === 1;
      state.mode = gas ? "gas" : "ev";
      // Electric power swings through regen (negative) so the regen state and
      // the negative half of the power meter are exercised too.
      const powerKw = gas ? 30 + Math.sin(t / 3) * 9 : 9 + Math.sin(t / 2.2) * 22;
      const routeDrift = Math.sin(t / 40);
      const lat = 32.80131 + routeDrift * 0.004;
      const lng = -116.9513 - Math.abs(routeDrift) * 0.012;
      VD.updateTelemetry({
        source: "demo",
        connected: true,
        sampleCount: t,
        sessionMs: t * 1000,
        supportedPids: "browser demo",
        vehicleState: powerKw < -0.5 ? "regen" : (gas ? "driving (gas)" : "driving"),
        speedKph: Math.round(54 + 23 * Math.sin(t / 3.4)),
        rpm: gas ? Math.round(1260 + 420 * Math.sin(t / 2.1)) : 0,
        coolantC: Math.round(82 + 4 * Math.sin(t / 8)),
        loadPct: Math.round(34 + 18 * Math.sin(t / 4.4)),
        throttlePct: Math.round(18 + 14 * Math.sin(t / 2.7)),
        voltage: 13.8,
        soc: Math.max(13.4, 77.8 - t * 0.01),
        batteryTemp: 72 + Math.sin(t / 8),
        powerKw: powerKw,
        // A slowly drifting coordinate so the demo also exercises the GPS lock
        // indicator and the live map position instead of sitting on "waiting".
        latitude: lat,
        longitude: lng,
        updatedAt: Date.now(),
        raw: "browser demo"
      });
      // Drop a live "you are here" breadcrumb on the map if it's mounted.
      if (typeof VD.updateLivePosition === "function") VD.updateLivePosition(lat, lng);
    }, 1000);
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

  const pageDragScrollBlockSelector = [
    "a",
    "button",
    "input",
    "select",
    "textarea",
    "summary",
    "[role='button']",
    "[data-nav]",
    "[data-action]",
    "[data-signal-stage]",
    "[data-signal-export]",
    "[data-signal-delete]",
    "[data-map-layer]",
    "[data-real-trip-id]",
    "[data-trip-map]",
    ".bottom-nav",
    ".map-card",
    ".map-frame",
    ".map-drive-chips",
    ".scrub-chart",
    ".scrub-track",
    ".route-box"
  ].join(",");

  let pageDragScroll: PageDragState | null = null;

  function canStartPageDragScroll(event: PointerEvent) {
    if (event.button !== 0) return false;
    if (event.pointerType && event.pointerType !== "mouse") return false;
    const target = event.target as Element | null;
    if (target && target.closest(pageDragScrollBlockSelector)) return false;
    return document.documentElement.scrollHeight > window.innerHeight + 2;
  }

  function bindPageDragScroll(opts: AddEventListenerOptions) {
    document.addEventListener("pointerdown", (event) => {
      if (!canStartPageDragScroll(event)) return;
      pageDragScroll = {
        pointerId: event.pointerId,
        startX: event.clientX,
        startY: event.clientY,
        lastY: event.clientY,
        active: false
      };
    }, opts);

    document.addEventListener("pointermove", (event) => {
      if (!pageDragScroll || event.pointerId !== pageDragScroll.pointerId) return;
      const totalX = Math.abs(event.clientX - pageDragScroll.startX);
      const totalY = Math.abs(event.clientY - pageDragScroll.startY);
      if (!pageDragScroll.active) {
        if (totalY < 8 || totalY <= totalX) return;
        pageDragScroll.active = true;
        document.body.classList.add("is-page-dragging");
      }
      event.preventDefault();
      window.scrollBy({ top: pageDragScroll.lastY - event.clientY, left: 0, behavior: "auto" });
      pageDragScroll.lastY = event.clientY;
    }, { ...opts, passive: false });

    const stopDragScroll = () => {
      pageDragScroll = null;
      document.body.classList.remove("is-page-dragging");
    };
    document.addEventListener("pointerup", stopDragScroll, opts);
    document.addEventListener("pointercancel", stopDragScroll, opts);
    window.addEventListener("blur", stopDragScroll, opts);
  }

  function bindListeners() {
    const opts = { signal: controller.signal };

    document.querySelectorAll("[data-nav]").forEach((node) => {
      const button = node as HTMLElement;
      button.addEventListener("click", () => VD.setView(button.dataset.nav ?? ""), opts);
    });
    document.querySelectorAll("[data-nav-jump]").forEach((node) => {
      const button = node as HTMLElement;
      button.addEventListener("click", () => VD.setView(button.dataset.navJump ?? ""), opts);
    });
    document.querySelectorAll("[data-action]").forEach((node) => {
      const button = node as HTMLElement;
      button.addEventListener("click", (event) => handleAction(button.dataset.action, event.currentTarget as BusyButton), opts);
    });
    document.querySelectorAll("[data-scenario]").forEach((node) => {
      const button = node as HTMLElement;
      button.addEventListener("click", () => {
        if (typeof VD.loadDemoScenario === "function") VD.loadDemoScenario(button.dataset.scenario);
        const picker = el("demoScenarioPicker");
        if (picker) picker.querySelectorAll("[data-scenario]").forEach((b) => b.classList.toggle("is-active", b === button));
      }, opts);
    });
    document.querySelectorAll("[data-map-layer]").forEach((node) => {
      const button = node as HTMLElement;
      button.addEventListener("click", () => {
        state.mapLayer = button.dataset.mapLayer;
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
      state.selectedMapSessionId = (button as HTMLElement).dataset.mapSession;
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
    VD.bindListenerGuarded("mapTilesBtn", "click", () => {
      state.mapRemoteTilesEnabled = !state.mapRemoteTilesEnabled;
      try {
        window.localStorage.setItem(
          "volttracker.map.remoteTiles",
          state.mapRemoteTilesEnabled ? "1" : "0"
        );
      } catch (_err) {
        // Preference persistence is best-effort; the visible state still updates.
      }
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
      const trip = (state.trips || []).find((t: any) => String(t.id) === String(id));
      if (trip && trip.hasRoute) {
        const route = typeof VD.ensureRouteForTrip === "function" ? VD.ensureRouteForTrip(trip) : null;
        if (route && route.session) {
          const routeKey = String(route.session.id || "");
          const routes = Array.isArray((state.storage || {}).recentRoutes)
            ? state.storage.recentRoutes
            : [];
          state.storage = state.storage || {};
          state.storage.recentRoutes = [
            route,
            ...routes.filter((existing: any) =>
              String((existing.session || {}).id || "") !== routeKey
            )
          ];
        }
        state.selectedMapSessionId = id;
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
    bindPageDragScroll(opts);
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
  if (!bridge) VD.loadSampleData();
  if (bridge && typeof bridge.dashboardReady === "function") bridge.dashboardReady();
  requestAnimationFrame(() => window.scrollTo({ top: 0, behavior: "auto" }));
  setTimeout(() => window.scrollTo({ top: 0, behavior: "auto" }), 200);

export {};
