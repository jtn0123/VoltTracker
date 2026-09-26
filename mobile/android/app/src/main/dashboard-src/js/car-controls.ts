// car-controls.ts — the experimental "Controls" card on Drive and its Settings opt-in.
//
// The dashboard is deliberately the LEAST trusted part of car controls. It can only:
//   - read the native opt-in/PIN-window state (getCarControlState),
//   - ask native to start the opt-in flow or turn controls off (setCarControlsEnabled),
//   - ASK for one command by name (requestCarControl) — native then shows its own PIN +
//     confirmation dialog, and the polling engine re-checks the opt-in, the one-shot
//     confirmation, the adapter and the parked/stopped gate before anything is transmitted.
// Gate state and the last command's outcome arrive on the live sample (carControl* keys),
// straight from the engine that will do the sending.
//
// Demo / Testing mode simulates a confirmed command locally and never calls the bridge.
//
// VD: this file is a LAZY chunk (own esbuild bundle) — every call into the eager bundle and
// every entry point it publishes crosses the chunk boundary through the VD registry.
import { VD } from "./vd-registry";
(function () {
  "use strict";

  const state = VD.state;
  const el = VD.el;

  type NativeCarControlState = {
    available?: boolean;
    enabled?: boolean;
    unlocked?: boolean;
    unlockedRemainingMs?: number;
    pinLockedOut?: boolean;
  };

  type CommandResult = { command: string; outcome: string; detail: string };

  const LABELS: Record<string, string> = {
    lock: "Lock",
    unlock: "Unlock",
    flash: "Flash lights",
    locate: "Horn + lights",
    remote_start: "Remote start",
    remote_stop: "Stop remote start",
    windows_down: "Windows down",
    windows_up: "Windows up",
  };

  const OUTCOMES: Record<string, { text: string; tone: string }> = {
    confirmed: { text: "confirmed by the car", tone: "ok" },
    sent_unconfirmed: { text: "sent, not confirmed", tone: "" },
    failed: { text: "failed", tone: "warn" },
    refused: { text: "not sent", tone: "warn" },
  };

  // Demo-only result; bumping the version re-runs the renderer through its signature.
  let demoResult: CommandResult | null = null;
  let demoVersion = 0;

  function bridgeCall(method: keyof VoltBridge, ...args: unknown[]): unknown {
    const bridge = VD.bridge || window.VoltTrackerAndroid || null;
    const fn = bridge ? bridge[method] : null;
    if (typeof fn !== "function") return undefined;
    try {
      return (fn as (...a: unknown[]) => unknown).apply(bridge, args);
    } catch (ignored) {
      return undefined;
    }
  }

  function readNative(): NativeCarControlState {
    const raw = bridgeCall("getCarControlState");
    if (typeof raw !== "string" || !raw.trim()) return {};
    try {
      const parsed = JSON.parse(raw);
      return parsed && typeof parsed === "object" ? (parsed as NativeCarControlState) : {};
    } catch (ignored) {
      return {};
    }
  }

  function labelFor(command: string): string {
    return LABELS[command] || command;
  }

  function setText(node: HTMLElement | null, text: string, tone?: string) {
    if (!node) return;
    if (node.textContent !== text) node.textContent = text;
    if (tone) node.setAttribute("data-tone", tone);
    else node.removeAttribute("data-tone");
  }

  function renderSettings(native: NativeCarControlState) {
    const toggle = el("carControlsToggle") as HTMLButtonElement | null;
    if (!toggle) return;
    const available = native.available === true;
    const enabled = available && native.enabled === true;
    toggle.disabled = !available;
    toggle.setAttribute("data-on", String(enabled));
    toggle.setAttribute("aria-pressed", String(enabled));
    toggle.textContent = enabled ? "On" : "Off";
  }

  function gateView(tm: VoltTelemetry, demo: boolean): { text: string; tone: string; ready: boolean } {
    if (demo) return { text: "Demo: commands are simulated. Nothing is sent to a car.", tone: "ok", ready: true };
    const gate = typeof tm.carControlGate === "string" ? tm.carControlGate : "";
    if (!gate) return { text: "Connect to the car to use controls.", tone: "warn", ready: false };
    if (gate === "ready") return { text: "Ready: the car is parked and the adapter can reach it.", tone: "ok", ready: true };
    if (gate === "busy") return { text: "Sending a command…", tone: "", ready: false };
    const detail = typeof tm.carControlGateDetail === "string" ? tm.carControlGateDetail : "";
    return { text: detail || "Controls are not available right now.", tone: "warn", ready: false };
  }

  function lastResult(tm: VoltTelemetry, demo: boolean): CommandResult | null {
    if (demo) return demoResult;
    const command = typeof tm.carControlLastCommand === "string" ? tm.carControlLastCommand : "";
    const outcome = typeof tm.carControlLastOutcome === "string" ? tm.carControlLastOutcome : "";
    if (!command || !outcome) return null;
    const detail = typeof tm.carControlLastDetail === "string" ? tm.carControlLastDetail : "";
    return { command, outcome, detail };
  }

  function renderPinState(native: NativeCarControlState, demo: boolean) {
    const relock = el("carControlsRelockBtn");
    let text = "PIN needed for each command";
    let unlocked = false;
    if (demo) {
      text = "Demo: no PIN needed";
    } else if (native.pinLockedOut === true) {
      text = "PIN locked after wrong attempts. Try again in a few minutes.";
    } else if (native.unlocked === true) {
      unlocked = true;
      const minutes = Math.max(1, Math.ceil(Number(native.unlockedRemainingMs || 0) / 60000));
      text = "PIN entered · valid for about " + minutes + " min";
    }
    setText(el("carControlsPinState"), text);
    if (relock) relock.hidden = !unlocked;
  }

  function renderCarControls() {
    const native = readNative();
    const demo = VD.isDemoActive();
    renderSettings(native);
    const card = el("carControlsCard");
    if (!card) return;
    const enabled = native.available === true && native.enabled === true;
    const visible = enabled || demo;
    card.hidden = !visible;
    if (!visible) return;
    const tm = (state.telemetry || {}) as VoltTelemetry;
    const gate = gateView(tm, demo);
    setText(el("carControlsGate"), gate.text, gate.tone);
    document.querySelectorAll<HTMLButtonElement>("#carControlsCard [data-car-control]").forEach((btn) => {
      btn.disabled = !gate.ready;
    });
    const result = lastResult(tm, demo);
    const resultNode = el("carControlsResult");
    if (resultNode) {
      resultNode.hidden = result === null;
      if (result) {
        const outcome = OUTCOMES[result.outcome] || { text: result.outcome, tone: "" };
        const detail = result.detail ? " " + result.detail : "";
        setText(resultNode, labelFor(result.command) + ": " + outcome.text + "." + detail, outcome.tone);
      }
    }
    renderPinState(native, demo);
  }

  async function onCommand(command: string) {
    if (VD.isDemoActive()) {
      const ok = await VD.confirmAppDialog({
        title: labelFor(command) + "?",
        message: "Demo / Testing: this is simulated. Nothing is sent to a car.",
        confirmLabel: labelFor(command),
      });
      if (!ok) return;
      demoResult = { command, outcome: "confirmed", detail: "Demo: simulated, nothing was sent." };
      demoVersion += 1;
      VD.requestRender();
      return;
    }
    // Native owns the PIN + confirmation dialog and every safety check from here on.
    bridgeCall("requestCarControl", command);
  }

  function onSettingsToggle(toggle: HTMLElement) {
    const status = el("carControlsSettingsStatus");
    if (toggle.getAttribute("aria-pressed") === "true") {
      bridgeCall("setCarControlsEnabled", false);
      setText(status, "Car controls off. The PIN was erased.");
    } else {
      bridgeCall("setCarControlsEnabled", true);
      setText(status, "Read the warning and set a PIN in the Android prompt to turn car controls on.");
    }
  }

  function bind() {
    document.querySelectorAll<HTMLButtonElement>("#carControlsCard [data-car-control]").forEach((btn) => {
      const command = btn.getAttribute("data-car-control") || "";
      btn.addEventListener("click", () => {
        void onCommand(command);
      });
    });
    const relock = el("carControlsRelockBtn");
    if (relock) relock.addEventListener("click", () => bridgeCall("lockCarControls"));
    const toggle = el("carControlsToggle");
    if (toggle) toggle.addEventListener("click", () => onSettingsToggle(toggle));
  }

  bind();
  VD.renderCarControls = renderCarControls;
  VD.registerRenderer("carControls", renderCarControls, () => [
    state.telemetry,
    state.appState,
    state.demoActive,
    demoVersion,
  ]);
  // First paint through the pass (the renderer is new, so its signature forces a run).
  VD.requestRender();
})();

export {};
