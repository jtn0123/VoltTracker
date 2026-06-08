// Proactive connection-tools button wiring.
//
// Wires:
//  - Test connection             -> VoltTrackerAndroid.startTestConnection()
//  - Send diagnostics            -> VoltTrackerAndroid.shareDiagnostics()
//                                   (also bound on the settings panel mirror)
//  - Auto-connect last adapter   -> VoltTrackerAndroid.setAutoConnectEnabled(enabled)
//  - Notify when ready           -> VoltTrackerAndroid.scheduleAdapterReadyNotify(mins)
//
// The low-voltage hint markup lives in the same partial but is owned by
// connection-status.ts (it observes every status broadcast for `lastVoltage`).
const VD = (window.VoltDashboard = window.VoltDashboard || ({} as VoltDashboard));
const bridge = window.VoltTrackerAndroid || null;
const el = (id: string) => document.getElementById(id);

function setButtonBusy(btn: HTMLElement, busy: boolean, label?: string | null) {
  btn.setAttribute("aria-busy", busy ? "true" : "false");
  btn.dataset.busy = busy ? "true" : "false";
  if (btn instanceof HTMLButtonElement) btn.disabled = busy;
  if (label != null) btn.textContent = label;
}

function isBusy(node: HTMLElement | null) {
  return Boolean(node && node.dataset.busy === "true");
}

function safeCall(method: keyof VoltBridge, ...args: unknown[]): unknown {
  const fn = bridge ? bridge[method] : null;
  if (typeof fn !== "function") return undefined;
  try {
    // Spread-applying a union of method signatures isn't expressible without a
    // cast; the typeof-function guard above is the real runtime safety check.
    return (fn as (...a: unknown[]) => unknown)(...args);
  } catch (ignored) {
    // Bridge calls are fire-and-forget; failures surface via the status
    // pipeline rather than throwing into the dashboard.
    return undefined;
  }
}

function parseBridgeJson(value: unknown): Record<string, unknown> {
  if (typeof value !== "string" || !value.trim()) return {};
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch (ignored) {
    return {};
  }
}

function autoConnectStatusText(state: Record<string, unknown>) {
  const enabled = state.enabled !== false;
  const hasAdapter = Boolean(String(state.lastAddress || "").trim());
  const name = String(state.lastName || "").trim();
  const label = name || (hasAdapter ? "last adapter" : "last adapter");
  if (!enabled) {
    return "Off. Manual Connect still works.";
  }
  if (!hasAdapter || state.available === false) {
    return "On. Connect once to remember an adapter.";
  }
  return "On. Uses " + label + " when the app sees it, without Bluetooth discovery.";
}

function bindAutoConnect() {
  const toggle = el("autoConnectToggle") as HTMLInputElement | null;
  const status = el("autoConnectStatus");
  if (!toggle) return;
  const state = parseBridgeJson(safeCall("getAutoConnectState"));
  toggle.checked = state.enabled !== false;
  if (status) status.textContent = autoConnectStatusText(state);
  toggle.addEventListener("change", () => {
    safeCall("setAutoConnectEnabled", toggle.checked);
    const nextState = {
      ...state,
      enabled: toggle.checked,
    };
    if (status) status.textContent = autoConnectStatusText(nextState);
  });
}

// Test-connection button. The Android side starts ObdService for the
// last device and stops it after ~8s, so we just nudge the button to a
// "running" state for a short window and let the status broadcasts paint
// the rest of the UI.
function bindTestConnection() {
  const btn = el("testConnectionBtn") as HTMLButtonElement | null;
  if (!btn) return;
  btn.addEventListener("click", () => {
    if (isBusy(btn)) return;
    const original = btn.textContent;
    setButtonBusy(btn, true, "Probing...");
    safeCall("startTestConnection");
    // Match the Android-side TEST_CONNECTION_DURATION_MS (25s) so the UI
    // re-enables roughly when the service stops itself.
    setTimeout(() => {
      setButtonBusy(btn, false, original);
    }, 25_500);
  });
}

// Send-diagnostics button. Two bindings - primary in connection-tools
// and a mirror in the settings panel - funnel into the same bridge call.
function bindSendDiagnostics() {
  ["sendDiagnosticsBtn", "sendDiagnosticsSettingsBtn"].forEach((id) => {
    const btn = el(id) as HTMLButtonElement | null;
    if (!btn) return;
    btn.addEventListener("click", () => {
      if (isBusy(btn)) return;
      const original = btn.textContent;
      setButtonBusy(btn, true, "Preparing...");
      safeCall("shareDiagnostics");
      setTimeout(() => {
        setButtonBusy(btn, false, original);
      }, 1500);
    });
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
  const group = toggleInput.closest("fieldset") as HTMLElement | null;
  let busy = false;
  function setNotifyBusy(next: boolean) {
    busy = next;
    if (group) {
      group.setAttribute("aria-busy", next ? "true" : "false");
      group.dataset.busy = next ? "true" : "false";
    }
  }
  function applyToggleState() {
    if (busy) return;
    setNotifyBusy(true);
    if (!toggleInput.checked) {
      safeCall("cancelAdapterReadyNotify");
      if (status) {
        status.textContent =
          "Probes the last-used adapter every 30s and posts a notification when it responds.";
      }
      setTimeout(() => setNotifyBusy(false), 600);
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
    setTimeout(() => setNotifyBusy(false), 600);
  }
  toggleInput.addEventListener("change", applyToggleState);
  minsInput.addEventListener("change", () => {
    // Re-arm with the new duration when the user picks a different value while toggled on.
    if (toggleInput.checked) applyToggleState();
  });
}

bindTestConnection();
bindSendDiagnostics();
bindAutoConnect();
bindNotifyWhenReady();
void VD;

export {};
