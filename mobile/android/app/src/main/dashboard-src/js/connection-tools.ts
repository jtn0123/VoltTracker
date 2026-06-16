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
  const label = name || "last adapter";
  if (!enabled) {
    return "Off. Manual Connect still works.";
  }
  // Surface the post-failure cooldown so the user knows why auto-connect is
  // pausing rather than thinking it's broken. cooldownRemainingMs comes straight
  // from getAutoConnectState (AutoConnectController).
  const cooldownMs = Number(state.cooldownRemainingMs) || 0;
  if (cooldownMs > 0) {
    return `On. Cooling down ${Math.ceil(cooldownMs / 1000)}s before the next auto-connect attempt.`;
  }
  if (!hasAdapter || state.available === false) {
    return "On. Connect once to remember an adapter.";
  }
  return "On. Uses " + label + " when the app sees it, without Bluetooth discovery.";
}

let autoConnectCooldownTimer = 0;

// Busy-restore timers for the test-connection and diagnostics buttons. These outlive a single
// click handler, so a re-bind (hot reload / test-fixture swap) via bindConnectionTools() must
// clear any in-flight ones before re-querying the buttons, else a stale timer fires against the
// freshly-bound button. Tracked at module scope and cleared at the top of bindConnectionTools().
let testConnTimer = 0;
const diagnosticsTimers: number[] = [];

// Paints the status line; while a cooldown is counting down it re-polls the bridge
// once a second so the remaining seconds tick toward zero (then stops).
function applyAutoConnectStatus(state: Record<string, unknown>) {
  const status = el("autoConnectStatus");
  if (status) status.textContent = autoConnectStatusText(state);
  window.clearTimeout(autoConnectCooldownTimer);
  const cooldownMs = Number(state.cooldownRemainingMs) || 0;
  if (state.enabled !== false && cooldownMs > 0) {
    autoConnectCooldownTimer = window.setTimeout(() => {
      applyAutoConnectStatus(parseBridgeJson(safeCall("getAutoConnectState")));
    }, 1000);
  }
}

function bindAutoConnect(opts?: AddEventListenerOptions) {
  const toggle = el("autoConnectToggle") as HTMLInputElement | null;
  if (!toggle) return;
  const state = parseBridgeJson(safeCall("getAutoConnectState"));
  toggle.checked = state.enabled !== false;
  applyAutoConnectStatus(state);
  toggle.addEventListener("change", () => {
    safeCall("setAutoConnectEnabled", toggle.checked);
    // Re-poll instead of reusing the bind-time snapshot so the status line
    // reflects the bridge's CURRENT adapter name / cooldown, then overlay the
    // new toggle value (the bridge may not re-report it synchronously).
    const fresh = parseBridgeJson(safeCall("getAutoConnectState"));
    applyAutoConnectStatus({ ...fresh, enabled: toggle.checked });
  }, opts);
}

// Test-connection button. The Android side starts ObdService for the
// last device and stops it after ~8s, so we just nudge the button to a
// "running" state for a short window and let the status broadcasts paint
// the rest of the UI.
function bindTestConnection(opts?: AddEventListenerOptions) {
  const btn = el("testConnectionBtn") as HTMLButtonElement | null;
  if (!btn) return;
  btn.addEventListener("click", () => {
    if (isBusy(btn)) return;
    const original = btn.textContent;
    setButtonBusy(btn, true, "Probing...");
    safeCall("startTestConnection");
    // Match the Android-side TEST_CONNECTION_DURATION_MS (25s) so the UI
    // re-enables roughly when the service stops itself.
    testConnTimer = window.setTimeout(() => {
      setButtonBusy(btn, false, original);
    }, 25_500);
  }, opts);
}

// Send-diagnostics button. Two bindings - primary in connection-tools
// and a mirror in the settings panel - funnel into the same bridge call.
function bindSendDiagnostics(opts?: AddEventListenerOptions) {
  ["sendDiagnosticsBtn", "sendDiagnosticsSettingsBtn"].forEach((id) => {
    const btn = el(id) as HTMLButtonElement | null;
    if (!btn) return;
    btn.addEventListener("click", () => {
      if (isBusy(btn)) return;
      const original = btn.textContent;
      setButtonBusy(btn, true, "Preparing...");
      safeCall("shareDiagnostics");
      diagnosticsTimers.push(window.setTimeout(() => {
        setButtonBusy(btn, false, original);
      }, 1500));
    }, opts);
  });
}

// Send-AI-digest button. Funnels into shareDiagnosticsDigest(), which shares a single
// redacted, budget-bounded text file sized to hand straight to an AI debugging session
// (no unzipping). Mirrors the send-diagnostics busy-state handling.
function bindSendAiDigest(opts?: AddEventListenerOptions) {
  const btn = el("sendAiDigestBtn") as HTMLButtonElement | null;
  if (!btn) return;
  btn.addEventListener("click", () => {
    if (isBusy(btn)) return;
    const original = btn.textContent;
    setButtonBusy(btn, true, "Preparing...");
    safeCall("shareDiagnosticsDigest");
    diagnosticsTimers.push(window.setTimeout(() => {
      setButtonBusy(btn, false, original);
    }, 1500));
  }, opts);
}

// Notify-when-ready toggle. Enabling sends the clamped duration; disabling explicitly
// cancels the schedule on the Android side so probes stop immediately rather than running on
// until the (up to 30 min) deadline.
function bindNotifyWhenReady(opts?: AddEventListenerOptions) {
  const toggle = el("notifyWhenReadyToggle") as HTMLInputElement | null;
  const mins = el("notifyWhenReadyMinutes") as HTMLInputElement | null;
  const status = el("notifyWhenReadyStatus");
  if (!toggle || !mins) return;
  const toggleInput = toggle;
  const minsInput = mins;
  // The minutes select only re-arms while background checking is on, so grey it
  // out until the toggle is checked to match its actual behavior.
  minsInput.disabled = !toggleInput.checked;
  const group = toggleInput.closest("fieldset") as HTMLElement | null;
  let busy = false;
  function setNotifyBusy(next: boolean) {
    busy = next;
    if (group) {
      group.setAttribute("aria-busy", next ? "true" : "false");
      group.dataset.busy = next ? "true" : "false";
    }
  }
  function scheduleNotify() {
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
  function applyToggleState() {
    if (busy) return;
    setNotifyBusy(true);
    minsInput.disabled = !toggleInput.checked;
    if (!toggleInput.checked) {
      safeCall("cancelAdapterReadyNotify");
      if (status) {
        status.textContent =
          "Probes the last-used adapter every 30s and posts a notification when it responds.";
      }
      setTimeout(() => setNotifyBusy(false), 600);
      return;
    }
    scheduleNotify();
    setTimeout(() => setNotifyBusy(false), 600);
  }
  toggleInput.addEventListener("change", applyToggleState, opts);
  minsInput.addEventListener("change", () => {
    // Re-arm with the new duration when toggled on. Re-arming is idempotent native-side, so call
    // scheduleNotify() directly rather than applyToggleState() - the latter early-returns during
    // the 600ms busy window after a toggle/change, which would drop the new duration.
    if (toggleInput.checked) scheduleNotify();
  }, opts);
}

// Writes a short confirmation into an aria-live status line so screen-reader users hear that a
// toggle/threshold change took effect (sighted users see it too). Mirrors the notify-when-ready /
// auto-connect status pattern. No-op when the target node is absent.
function announceStatus(id: string, message: string) {
  const status = el(id);
  if (status) status.textContent = message;
}

// Event-notification + auto-scan toggles (M1/M3). Each toggle reflects the native-owned setting
// read once from getEventNotificationState, and writes back through its own bridge setter. Threshold
// <select>s ride along with the low-SOC / high-temp toggles; the Android side clamps the values.
// Every change also confirms itself in an aria-live status line (eventNotifyStatus / autoScanStatus).
function bindEventNotifications(opts?: AddEventListenerOptions) {
  const state = parseBridgeJson(safeCall("getEventNotificationState"));
  const onOff = (checked: boolean) => (checked ? "on" : "off");
  bindBoolToggle("notifyChargeCompleteToggle", state.chargeComplete !== false, (checked) => {
    safeCall("setChargeCompleteNotify", checked);
    announceStatus("eventNotifyStatus", "Charge-complete alerts " + onOff(checked) + ".");
  }, opts);
  bindBoolToggle("notifyNewDtcToggle", state.newDtc !== false, (checked) => {
    safeCall("setNewDtcNotify", checked);
    announceStatus("eventNotifyStatus", "New diagnostic-code alerts " + onOff(checked) + ".");
  }, opts);
  bindThresholdToggle(
    "notifyLowSocToggle",
    "notifyLowSocThreshold",
    state.lowSoc === true,
    Number(state.lowSocThresholdPct) || 20,
    (checked, value) => {
      safeCall("setLowSocNotify", checked, value);
      announceStatus(
        "eventNotifyStatus",
        checked
          ? "Low-battery threshold set to " + value + "%."
          : "Low-battery alerts off.",
      );
    },
    opts,
  );
  bindThresholdToggle(
    "notifyHighTempToggle",
    "notifyHighTempThreshold",
    state.highPackTemp === true,
    Number(state.highPackTempThresholdC) || 45,
    (checked, value) => {
      safeCall("setHighPackTempNotify", checked, value);
      announceStatus(
        "eventNotifyStatus",
        checked
          ? "High-temperature threshold set to " + value + "°C."
          : "High-temperature alerts off.",
      );
    },
    opts,
  );
  bindBoolToggle("autoScanOnConnectToggle", state.autoScanOnConnect === true, (checked) => {
    safeCall("setAutoScanOnConnect", checked);
    announceStatus("autoScanStatus", "Auto-scan on connect " + onOff(checked) + ".");
  }, opts);
}

function bindBoolToggle(
  id: string,
  initial: boolean,
  onChange: (checked: boolean) => void,
  opts?: AddEventListenerOptions,
) {
  const toggle = el(id) as HTMLInputElement | null;
  if (!toggle) return;
  toggle.checked = initial;
  toggle.addEventListener("change", () => onChange(toggle.checked), opts);
}

function bindThresholdToggle(
  toggleId: string,
  selectId: string,
  initialEnabled: boolean,
  initialValue: number,
  onChange: (checked: boolean, value: number) => void,
  opts?: AddEventListenerOptions,
) {
  const toggle = el(toggleId) as HTMLInputElement | null;
  const select = el(selectId) as HTMLSelectElement | null;
  if (!toggle) return;
  toggle.checked = initialEnabled;
  if (select && initialValue > 0) select.value = String(initialValue);
  const currentValue = () => (select ? Number(select.value) || initialValue : initialValue);
  toggle.addEventListener("change", () => onChange(toggle.checked, currentValue()), opts);
  // Re-send when the threshold changes while the toggle is on, so the new value takes effect.
  if (select) {
    select.addEventListener("change", () => {
      if (toggle.checked) onChange(true, currentValue());
    }, opts);
  }
}

// All connection-tools listeners are bound under a module-owned AbortController so they can be
// torn down and re-armed together. Previously they were bound once at module load and were NOT
// reset by actions.resetListeners() (which only aborts its own controller), so an in-place
// re-bootstrap (hot reload / test-fixture swap) left them double-bound or dead. resetListeners
// now calls VD.bindConnectionTools() to re-arm them alongside the rest of the UI.
let connectionToolsController = new AbortController();

function bindConnectionTools() {
  connectionToolsController.abort();
  connectionToolsController = new AbortController();
  // Busy-restore / cooldown-poll timers aren't tied to the controller's signal, so clear any
  // in-flight handles before re-querying the buttons - otherwise a stale timer fires against the
  // freshly-bound element after a re-bind.
  window.clearTimeout(testConnTimer);
  testConnTimer = 0;
  diagnosticsTimers.splice(0).forEach((id) => window.clearTimeout(id));
  window.clearTimeout(autoConnectCooldownTimer);
  autoConnectCooldownTimer = 0;
  const opts: AddEventListenerOptions = { signal: connectionToolsController.signal };
  bindTestConnection(opts);
  bindSendDiagnostics(opts);
  bindSendAiDigest(opts);
  bindAutoConnect(opts);
  bindNotifyWhenReady(opts);
  bindEventNotifications(opts);
}

bindConnectionTools();
VD.bindConnectionTools = bindConnectionTools;

export {};
