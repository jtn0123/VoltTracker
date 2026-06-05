/*
 * troubleshooter.ts — connection troubleshooter modal behavior and the
 * stuck-bond auto-suggest hook.
 *
 * The modal opens automatically when:
 *   - the current session has produced >=3 retry attempts (detail contains
 *     "retrying"), OR
 *   - two consecutive session terminations were both `failed`.
 *
 * Step 3 ("stop competing apps") stays hidden until `competingApps` on the
 * status payload is non-empty. Each comma-separated package gets a one-tap
 * Force-stop button that calls `VoltTrackerAndroid.forceStopPackage(pkg)`.
 *
 * Stuck-bond auto-suggest: every time the modal opens we ask the
 * `getRecentSessions(3)` bridge for the last three sessions. If all three failed
 * with no success in between, we swap the primary action to "Forget adapter &
 * re-pair". If the bridge returns `[]`, the code path is dormant.
 *
 * CSS namespace: every selector this file mutates is prefixed `.troubleshooter`
 * (see css/troubleshooter.css) so connection-tool styling cannot collide.
 */
  const VD = window.VoltDashboard;
  const state = VD.state;
  const bridge = VD.bridge;
  const el = VD.el;

  type FailureCopy = {
    title: string;
    hint: string;
  };

  type StaleField = {
    key: string;
    label: string;
  };

  type StaleEntry = {
    label: string;
    staleMs: number;
  };

  type RecentSession = {
    status?: unknown;
  };

  type PayloadHandler = (payload: unknown) => unknown;

  // Trigger thresholds: keep low so users see help fast in real-world
  // flaky-adapter scenarios.
  const FAILED_SESSION_OPEN_THRESHOLD = 2;
  const RETRY_OPEN_THRESHOLD = 3;

  // Slow-tier PID staleness fields the polling engine emits, and the
  // user-facing label for each. Threshold is the same 4s used elsewhere for
  // "the adapter has stopped answering this PID."
  const STALE_THRESHOLD_MS = 4000;
  const STALE_FIELDS: StaleField[] = [
    { key: "voltageStaleMs", label: "Adapter voltage (ATRV)" },
    { key: "socStaleMs", label: "HV battery SOC" },
    { key: "coolantCStaleMs", label: "Coolant temperature" },
    { key: "batteryTempStaleMs", label: "HV pack temperature" }
  ];

  // Map FailureClass.name() to user-facing copy. Keys MUST match the
  // string values produced by FailureClass.java; unknown / missing falls
  // through to the generic message.
  const FAILURE_CLASS_COPY: Record<string, FailureCopy> = {
    INSTANT_DROP: {
      title: "Adapter answers but isn't talking",
      hint: "Unplug it for 10 seconds, then try again."
    },
    CONNECT_TIMEOUT: {
      title: "Can't reach the adapter",
      hint: "Check it's plugged in and the LED is on."
    },
    SDP_FAILURE: {
      title: "Bluetooth pairing looks stale",
      hint: "Forget the adapter in Settings and re-pair."
    },
    BT_OFF: {
      title: "Bluetooth is off",
      hint: "Turn Bluetooth on, then try again."
    },
    BOND_LOST: {
      title: "Phone lost its pairing with the adapter",
      hint: "Re-pair it in Bluetooth settings."
    },
    REMOTE_REFUSED: {
      title: "The adapter refused the connection",
      hint: "Power-cycle it."
    }
  };

  // Internal trip-counter state. Lives on the VD.state object so refresh
  // cycles / tests can poke it.
  state.troubleshooter = state.troubleshooter || {
    consecutiveFailedSessions: 0,
    retriesThisBurst: 0,
    autoOpened: false,
    forgetMode: false,
    lastSessionState: "",
    // Latest telemetry payload — captured by the observer so the stale step
    // can re-render on open even if the telemetry stream is quiescent.
    lastTelemetry: null
  };

  // Listener discipline: every addEventListener attached from this file
  // passes { signal } so a single controller.abort() tears all bindings down.
  // Without this, hot-reloading the WebView or re-running bootstrap would
  // accumulate duplicate handlers across reloads.
  const listenerController = new AbortController();
  const LISTEN_OPTS = { signal: listenerController.signal };

  function modal(): HTMLElement | null { return el("troubleshooterModal"); }

  function isOpen() {
    const node = modal();
    return Boolean(node && !node.hidden);
  }

  function open(reason: unknown) {
    const node = modal();
    if (!node) return;
    // Remember the focused element so it can be restored when the modal closes.
    state.troubleshooter.priorFocus = document.activeElement;
    node.hidden = false;
    if (typeof node.focus === "function") node.focus();
    renderForRetry();
    renderCompeting((state.status || {}).competingApps);
    renderStaleTelemetry(state.troubleshooter.lastTelemetry);
    refreshStuckBondSuggestion();
    try {
      if (bridge && typeof bridge.logClientError === "function" && reason) {
        // Re-use logClientError as a lightweight "telemetry" channel — it's
        // already a one-liner into adb logcat.
        bridge.logClientError("troubleshooter.open", String(reason));
      }
    } catch (ignored) {}
  }

  function close() {
    const node = modal();
    if (!node) return;
    node.hidden = true;
    state.troubleshooter.autoOpened = false;
    // Restore focus to whatever opened the modal so keyboard users aren't stranded.
    const prior = state.troubleshooter.priorFocus;
    if (prior && typeof prior.focus === "function" && document.contains(prior)) {
      prior.focus();
    }
    state.troubleshooter.priorFocus = null;
  }

  // Collapsible step head. Toggles aria-expanded + body[hidden].
  function bindStepToggles() {
    const root = modal();
    if (!root) return;
    root.querySelectorAll(".troubleshooter-step-head").forEach((head) => {
      head.addEventListener("click", () => {
        const expanded = head.getAttribute("aria-expanded") === "true";
        head.setAttribute("aria-expanded", expanded ? "false" : "true");
        const id = head.getAttribute("aria-controls");
        const body = id ? document.getElementById(id) : null;
        if (body) body.hidden = expanded;
      }, LISTEN_OPTS);
    });
  }

  function bindDismiss() {
    const root = modal();
    if (!root) return;
    root.querySelectorAll("[data-troubleshooter-dismiss]").forEach((node) => {
      node.addEventListener("click", close, LISTEN_OPTS);
    });
  }

  function bindPrimary() {
    const btn = el("troubleshooterPrimary");
    if (!btn) return;
    btn.addEventListener("click", () => {
      if (state.troubleshooter.forgetMode) {
        // Deep-link to the system Bluetooth settings so the user can forget the adapter and
        // re-pair. Android handles the activity.
        openBluetoothSettings();
        return;
      }
      if (bridge && typeof bridge.tryReconnectNow === "function") {
        bridge.tryReconnectNow();
        close();
        return;
      }
      // Fallback for browser preview / before bridge lands.
      if (bridge && typeof bridge.connectLast === "function") {
        bridge.connectLast();
        close();
      }
    }, LISTEN_OPTS);
  }

  function openBluetoothSettings() {
    if (bridge && typeof bridge.openBluetoothSettings === "function") {
      bridge.openBluetoothSettings();
      return;
    }
    // Without a dedicated bridge call, surface a status so the user knows the path even when the
    // helper is missing.
    if (typeof VD.setStatus === "function") {
      VD.setStatus({
        state: "blocked",
        detail:
          "Open Android Settings > Bluetooth, forget the adapter, then pair it again."
      });
    }
  }

  function renderForRetry() {
    const intro = el("troubleshooterIntro");
    const title = el("troubleshooterTitle");
    const primary = el("troubleshooterPrimary");
    if (!intro || !primary) return;
    if (state.troubleshooter.forgetMode) {
      if (title) title.textContent = "Adapter pairing looks stuck";
      intro.textContent =
        "The last few sessions all failed. The fastest fix is usually to forget the adapter in Android Bluetooth settings and pair it fresh.";
      primary.textContent = "Forget adapter & re-pair";
    } else {
      if (title) title.textContent = "Adapter isn't connecting";
      intro.textContent =
        "Walk through these one by one — most flaky sessions are fixed by step 1 or 2.";
      primary.textContent = "Try again";
    }
  }

  // Render Force-stop buttons for every competing package. `csv` may be null or ""; in that case
  // the whole step stays hidden.
  function renderCompeting(csv: unknown) {
    const stepNode = el("troubleshooterStepCompeting");
    const listNode = el("troubleshooterCompetingList");
    if (!stepNode || !listNode) return;
    const packages = parsePackageCsv(csv);
    if (!packages.length) {
      stepNode.hidden = true;
      listNode.replaceChildren();
      return;
    }
    stepNode.hidden = false;
    listNode.replaceChildren(...packages.map(buildCompetingRow));
  }

  function parsePackageCsv(csv: unknown): string[] {
    if (!csv) return [];
    const raw = String(csv).split(",");
    const seen = new Set<string>();
    const out: string[] = [];
    raw.forEach((entry) => {
      const trimmed = String(entry || "").trim();
      if (!trimmed) return;
      if (seen.has(trimmed)) return;
      seen.add(trimmed);
      out.push(trimmed);
    });
    return out;
  }

  function buildCompetingRow(pkg: string) {
    const row = document.createElement("div");
    row.className = "troubleshooter-competing-row";
    const label = document.createElement("span");
    label.className = "troubleshooter-competing-pkg";
    label.textContent = pkg;
    const button = document.createElement("button");
    button.type = "button";
    button.className = "troubleshooter-force-stop";
    button.textContent = "Force-stop";
    button.addEventListener("click", () => {
      // Disable + relabel so the user gets immediate feedback even though
      // killBackgroundProcesses returns void on the Android side.
      button.disabled = true;
      button.textContent = "Sent";
      if (bridge && typeof bridge.forceStopPackage === "function") {
        try {
          bridge.forceStopPackage(pkg);
        } catch (ignored) {
          // Match the eslint pattern for intentionally-unused catch bindings; we revert the
          // button so the user can retry rather than silently leaving "Sent" stuck.
          button.disabled = false;
          button.textContent = "Force-stop";
        }
      }
    }, LISTEN_OPTS);
    row.append(label, button);
    return row;
  }

  // Render the "telemetry isn't refreshing" step from the latest telemetry
  // payload. Shows one <li> per slow-tier PID whose *StaleMs field exceeds
  // STALE_THRESHOLD_MS. Hidden entirely when no field is stale or when the
  // adapter hasn't started reporting staleness yet.
  function renderStaleTelemetry(payload: unknown) {
    const stepNode = el("troubleshooterStepStale");
    const listNode = el("troubleshooterStaleList");
    if (!stepNode || !listNode) return;
    const stale = pickStaleFields(payload);
    if (!stale.length) {
      stepNode.hidden = true;
      listNode.replaceChildren();
      return;
    }
    stepNode.hidden = false;
    listNode.replaceChildren(...stale.map(buildStaleRow));
  }

  function pickStaleFields(payload: unknown): StaleEntry[] {
    if (!payload || typeof payload !== "object") return [];
    const out: StaleEntry[] = [];
    STALE_FIELDS.forEach((field) => {
      const value = Number((payload as Record<string, unknown>)[field.key]);
      if (Number.isFinite(value) && value > STALE_THRESHOLD_MS) {
        out.push({ label: field.label, staleMs: value });
      }
    });
    return out;
  }

  function buildStaleRow(entry: StaleEntry) {
    const row = document.createElement("li");
    row.className = "troubleshooter-stale-row";
    const label = document.createElement("span");
    label.className = "troubleshooter-stale-label";
    label.textContent = entry.label;
    const age = document.createElement("span");
    age.className = "troubleshooter-stale-age";
    const seconds = Math.round(entry.staleMs / 1000);
    age.textContent = "last update " + seconds + "s ago";
    row.append(label, age);
    return row;
  }

  function noteTelemetry(payload: unknown) {
    if (!payload || typeof payload !== "object") return;
    state.troubleshooter.lastTelemetry = payload;
    if (isOpen()) {
      renderStaleTelemetry(payload);
    }
  }

  // Switch the primary action when the last 3 sessions all failed.
  function refreshStuckBondSuggestion() {
    state.troubleshooter.forgetMode = false;
    let recent: RecentSession[] = [];
    if (bridge && typeof bridge.getRecentSessions === "function") {
      try {
        const raw = bridge.getRecentSessions(3);
        // Defensive: if panels.js failed to load or was renamed, VD.parsePayload may not exist.
        // Fall back to JSON.parse + Array check so the stuck-bond path is not silently disabled.
        if (typeof VD.parsePayload === "function") {
          recent = VD.parsePayload(raw, []) as RecentSession[];
        } else {
          const parsed = JSON.parse(raw || "[]");
          recent = Array.isArray(parsed) ? parsed : [];
        }
      } catch (ignored) {
        recent = [];
      }
    }
    if (!Array.isArray(recent) || recent.length < 3) {
      renderForRetry();
      return;
    }
    const failedSessionStatuses = ["failed", "error", "disconnected"];
    const allFailed = recent
      .slice(0, 3)
      .every((s) => failedSessionStatuses.includes(String((s && s.status) || "").toLowerCase()));
    if (!allFailed) {
      renderForRetry();
      return;
    }
    state.troubleshooter.forgetMode = true;
    renderForRetry();
  }

  // Rewrite the error-banner title/hint based on failureClass and toggle
  // the actions row (cancel + help) so the banner is actionable instead of
  // a plain "Dashboard error" string. Called from noteStatus on every status
  // payload.
  function renderErrorBannerCopy(status: Record<string, any> | null | undefined) {
    const titleNode = el("errorBannerTitle");
    const hintNode = el("errorBannerHint");
    const actionsNode = el("errorBannerActions");
    const cancelNode = el("errorBannerCancelRetry");
    const helpNode = el("errorBannerHelp");
    if (!titleNode) return;
    const stateName = String((status && status.state) || "").toLowerCase();
    const detail = String((status && status.detail) || "").toLowerCase();
    const failureClass = String((status && status.failureClass) || "").toUpperCase();
    const isFailure =
      stateName === "failed" ||
      stateName === "blocked" ||
      Boolean(status && status.blocked);
    const isRetrying = stateName === "connecting" && detail.indexOf("retry") !== -1;

    const copy = FAILURE_CLASS_COPY[failureClass];
    if (copy && (isFailure || isRetrying)) {
      titleNode.textContent = copy.title;
      if (hintNode) {
        hintNode.textContent = copy.hint;
        hintNode.hidden = false;
      }
    } else if (isFailure) {
      // Generic / UNKNOWN — keep existing detail line, don't add a hint.
      titleNode.textContent = "Couldn't reach the adapter";
      if (hintNode) {
        hintNode.textContent = "";
        hintNode.hidden = true;
      }
    } else if (!isRetrying) {
      // Reset to the default banner label when there's no live failure to
      // describe (e.g. a generic JS error coming from window.error).
      titleNode.textContent = "Dashboard error";
      if (hintNode) {
        hintNode.textContent = "";
        hintNode.hidden = true;
      }
    }

    // Surface the in-flight Cancel button only while a retry burst is
    // actively running. Help button shows whenever we have failure copy or
    // an active retry.
    const showCancel = isRetrying;
    const showHelp = isFailure || isRetrying;
    if (cancelNode) cancelNode.hidden = !showCancel;
    if (helpNode) helpNode.hidden = !showHelp;
    if (actionsNode) actionsNode.hidden = !(showCancel || showHelp);
  }

  // Counts a status payload toward the auto-open trigger.
  function noteStatus(status: Record<string, any> | null | undefined) {
    if (!status) return;
    const stateName = String(status.state || "").toLowerCase();
    const detail = String(status.detail || "").toLowerCase();
    const t = state.troubleshooter;
    renderErrorBannerCopy(status);

    // Retries within an active connect burst.
    if (stateName === "connecting" && detail.indexOf("retry") !== -1) {
      t.retriesThisBurst += 1;
      if (
        t.retriesThisBurst >= RETRY_OPEN_THRESHOLD &&
        !t.autoOpened &&
        !isOpen()
      ) {
        t.autoOpened = true;
        open("retry-threshold");
      }
    } else if (stateName === "connected" || stateName === "scanning" || stateName === "scan-complete") {
      // A successful connect/scan resets the retry burst + failure counter.
      t.retriesThisBurst = 0;
      t.consecutiveFailedSessions = 0;
      t.autoOpened = false;
    }

    // Track session terminations: edge from connecting/scanning -> failed/idle.
    const prev = t.lastSessionState;
    if (
      (prev === "connecting" || prev === "connected" || prev === "initializing") &&
      (stateName === "failed" || (stateName === "idle" && Boolean(status.blocked)))
    ) {
      t.consecutiveFailedSessions += 1;
      if (
        t.consecutiveFailedSessions >= FAILED_SESSION_OPEN_THRESHOLD &&
        !t.autoOpened &&
        !isOpen()
      ) {
        t.autoOpened = true;
        open("consecutive-failures");
      }
    }
    if (stateName) t.lastSessionState = stateName;

    // Refresh competing-apps list whenever a status comes through and the modal is open.
    if (isOpen()) {
      renderCompeting(status.competingApps);
    }
  }

  // Manual entry-point so other panels (or a "Help" link in the error banner)
  // can open the modal on demand.
  function show() {
    state.troubleshooter.autoOpened = true;
    open("manual");
  }

  function bindHelpAffordance() {
    const help = el("errorBannerHelp");
    if (!help) return;
    help.addEventListener("click", () => show());
  }

  // Hook into the existing setStatus pipeline without touching telemetry.js
  // or actions.js. Both VD.setStatus and window.VoltTrackerNative.setStatus
  // are wrapped so every status flow — whether the Android side calls
  // VoltTrackerNative.setStatus or the JS side calls VD.setStatus directly
  // — flows through our observer.
  function installStatusObserver() {
    const wrap = (prior: unknown): PayloadHandler =>
      function (payload: unknown) {
        let result: unknown;
        if (typeof prior === "function") {
          result = prior(payload);
        }
        try {
          const parsed = VD.parsePayload(payload, {});
          noteStatus(parsed);
        } catch (ignored) {
          // Observer must never break the underlying setStatus call.
        }
        return result;
      };
    VD.setStatus = wrap(VD.setStatus);
    if (window.VoltTrackerNative) {
      window.VoltTrackerNative.setStatus = wrap(window.VoltTrackerNative.setStatus);
    }
  }

  // Same pattern as installStatusObserver but for the telemetry stream. The
  // *StaleMs fields ride on every telemetry payload, so we just need to read
  // them out and refresh the step. Observer must never break the underlying
  // updateTelemetry call.
  function installTelemetryObserver() {
    const wrap = (prior: unknown): PayloadHandler =>
      function (payload: unknown) {
        let result: unknown;
        if (typeof prior === "function") {
          result = prior(payload);
        }
        try {
          const parsed = VD.parsePayload(payload, {});
          noteTelemetry(parsed);
        } catch (ignored) {}
        return result;
      };
    if (typeof VD.updateTelemetry === "function") {
      VD.updateTelemetry = wrap(VD.updateTelemetry);
    }
    if (window.VoltTrackerNative) {
      window.VoltTrackerNative.updateTelemetry = wrap(
        window.VoltTrackerNative.updateTelemetry
      );
    }
  }

  // Bind once on DOM ready (the partial is included at build time so the
  // nodes exist before this file runs).
  bindStepToggles();
  bindDismiss();
  bindPrimary();
  bindHelpAffordance();
  installStatusObserver();
  installTelemetryObserver();

  VD.troubleshooter = {
    open: show,
    close,
    isOpen,
    noteStatus,
    noteTelemetry,
    renderCompeting,
    renderStaleTelemetry,
    pickStaleFields,
    parsePackageCsv,
    refreshStuckBondSuggestion,
    renderErrorBannerCopy,
    resetListeners: () => listenerController.abort(),
    FAILURE_CLASS_COPY,
    STALE_FIELDS,
    STALE_THRESHOLD_MS
  };

export {};
