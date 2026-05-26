// Bucket 4b — proactive tools button wiring (C5/C7/C10).
//
// Wires:
//  - Test connection (C5)        → VoltTrackerAndroid.startTestConnection()
//  - Send diagnostics (C7)       → VoltTrackerAndroid.shareDiagnostics()
//                                   (also bound on the settings panel mirror)
//  - Notify when ready (C10)     → VoltTrackerAndroid.scheduleAdapterReadyNotify(mins)
//
// C9's low-voltage hint markup lives in the same partial but is owned by
// connection-status.js (it observes every status broadcast for `lastVoltage`).
(function () {
  "use strict";
  const VD = (window.VoltDashboard = window.VoltDashboard || {});
  const bridge = window.VoltTrackerAndroid || null;
  const el = (id) => document.getElementById(id);

  function safeCall(method, ...args) {
    if (!bridge || typeof bridge[method] !== "function") return;
    try {
      return bridge[method](...args);
    } catch (ignored) {
      // Bridge calls are fire-and-forget; failures surface via the status
      // pipeline rather than throwing into the dashboard.
    }
  }

  // C5: test-connection button. The Android side starts ObdService for the
  // last device and stops it after ~8s, so we just nudge the button to a
  // "running" state for a short window and let the status broadcasts paint
  // the rest of the UI.
  function bindTestConnection() {
    const btn = el("testConnectionBtn");
    if (!btn) return;
    btn.addEventListener("click", () => {
      btn.disabled = true;
      const original = btn.textContent;
      btn.textContent = "Probing…";
      safeCall("startTestConnection");
      // Match the Android-side TEST_CONNECTION_DURATION_MS (25s) so the UI
      // re-enables roughly when the service stops itself.
      setTimeout(() => {
        btn.disabled = false;
        btn.textContent = original;
      }, 25_500);
    });
  }

  // C7: send-diagnostics button. Two bindings — primary in connection-tools
  // and a mirror in the settings panel — funnel into the same bridge call.
  function bindSendDiagnostics() {
    ["sendDiagnosticsBtn", "sendDiagnosticsSettingsBtn"].forEach((id) => {
      const btn = el(id);
      if (!btn) return;
      btn.addEventListener("click", () => safeCall("shareDiagnostics"));
    });
  }

  // C10: notify-when-ready toggle. Enabling sends the clamped duration; disabling explicitly
  // cancels the schedule on the Android side so probes stop immediately rather than running on
  // until the (up to 30 min) deadline.
  function bindNotifyWhenReady() {
    const toggle = el("notifyWhenReadyToggle");
    const mins = el("notifyWhenReadyMinutes");
    const status = el("notifyWhenReadyStatus");
    if (!toggle || !mins) return;
    function applyToggleState() {
      if (!toggle.checked) {
        safeCall("cancelAdapterReadyNotify");
        if (status) {
          status.textContent =
            "Probes the last-used adapter every 30s and posts a notification when it responds.";
        }
        return;
      }
      const minutes = Math.max(1, Math.min(30, parseInt(mins.value, 10) || 10));
      // Reflect the clamped value back into the input so the UI never advertises a duration
      // (e.g., 999) that the bridge silently shrank to 30.
      mins.value = String(minutes);
      safeCall("scheduleAdapterReadyNotify", minutes);
      if (status) {
        status.textContent =
          "Checking every 30s for the next " + minutes + " min — you'll get a notification when the adapter responds.";
      }
    }
    toggle.addEventListener("change", applyToggleState);
    mins.addEventListener("change", () => {
      // Re-arm with the new duration when the user picks a different value while toggled on.
      if (toggle.checked) applyToggleState();
    });
  }

  bindTestConnection();
  bindSendDiagnostics();
  bindNotifyWhenReady();
  void VD;
})();
