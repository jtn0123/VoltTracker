// Proactive connection-tools button wiring.
//
// Wires:
//  - Test connection             -> VoltTrackerAndroid.startTestConnection()
//  - Send diagnostics            -> VoltTrackerAndroid.shareDiagnostics()
//                                   (also bound on the settings panel mirror)
//  - Notify when ready           -> VoltTrackerAndroid.scheduleAdapterReadyNotify(mins)
//
// The low-voltage hint markup lives in the same partial but is owned by
// connection-status.ts (it observes every status broadcast for `lastVoltage`).
const VD = (window.VoltDashboard = window.VoltDashboard || ({} as VoltDashboard));
const bridge = window.VoltTrackerAndroid || null;
const el = (id: string) => document.getElementById(id);

function safeCall(method: keyof VoltBridge, ...args: any[]) {
  const fn = bridge ? bridge[method] : null;
  if (typeof fn !== "function") return;
  try {
    // Spread-applying a union of method signatures isn't expressible without a
    // cast; the typeof-function guard above is the real runtime safety check.
    return (fn as (...a: any[]) => any)(...args);
  } catch (ignored) {
    // Bridge calls are fire-and-forget; failures surface via the status
    // pipeline rather than throwing into the dashboard.
  }
}

// Test-connection button. The Android side starts ObdService for the
// last device and stops it after ~8s, so we just nudge the button to a
// "running" state for a short window and let the status broadcasts paint
// the rest of the UI.
function bindTestConnection() {
  const btn = el("testConnectionBtn") as HTMLButtonElement | null;
  if (!btn) return;
  btn.addEventListener("click", () => {
    btn.disabled = true;
    const original = btn.textContent;
    btn.textContent = "Probing...";
    safeCall("startTestConnection");
    // Match the Android-side TEST_CONNECTION_DURATION_MS (25s) so the UI
    // re-enables roughly when the service stops itself.
    setTimeout(() => {
      btn.disabled = false;
      btn.textContent = original;
    }, 25_500);
  });
}

// Send-diagnostics button. Two bindings - primary in connection-tools
// and a mirror in the settings panel - funnel into the same bridge call.
function bindSendDiagnostics() {
  ["sendDiagnosticsBtn", "sendDiagnosticsSettingsBtn"].forEach((id) => {
    const btn = el(id);
    if (!btn) return;
    btn.addEventListener("click", () => safeCall("shareDiagnostics"));
  });
}

// Notify-when-ready toggle. Enabling sends the clamped duration; disabling explicitly
// cancels the schedule on the Android side so probes stop immediately rather than running on
// until the (up to 30 min) deadline.
function bindNotifyWhenReady() {
  const toggle = el("notifyWhenReadyToggle") as HTMLInputElement | null;
  const mins = el("notifyWhenReadyMinutes") as HTMLInputElement | null;
  const status = el("notifyWhenReadyStatus");
  if (!toggle || !mins) return;
  const toggleInput = toggle;
  const minsInput = mins;
  function applyToggleState() {
    if (!toggleInput.checked) {
      safeCall("cancelAdapterReadyNotify");
      if (status) {
        status.textContent =
          "Probes the last-used adapter every 30s and posts a notification when it responds.";
      }
      return;
    }
    const minutes = Math.max(1, Math.min(30, parseInt(minsInput.value, 10) || 10));
    // Reflect the clamped value back into the input so the UI never advertises a duration
    // (e.g., 999) that the bridge silently shrank to 30.
    minsInput.value = String(minutes);
    safeCall("scheduleAdapterReadyNotify", minutes);
    if (status) {
      status.textContent =
        "Checking every 30s for the next " +
        minutes +
        " min - you'll get a notification when the adapter responds.";
    }
  }
  toggleInput.addEventListener("change", applyToggleState);
  minsInput.addEventListener("change", () => {
    // Re-arm with the new duration when the user picks a different value while toggled on.
    if (toggleInput.checked) applyToggleState();
  });
}

bindTestConnection();
bindSendDiagnostics();
bindNotifyWhenReady();
void VD;

export {};
