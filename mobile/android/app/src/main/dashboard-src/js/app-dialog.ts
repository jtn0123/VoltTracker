import { createFocusTrap } from "./focus-trap";
import type { FocusTrap } from "./focus-trap";
// VD: this module owns mutable dialog state (controller / trap / settle) that
// drives the ONE #appDialog DOM node, so it must exist exactly once at
// runtime. It ships ONLY in the eager app.js bundle (via actions.ts) and
// publishes its API on the VD registry at the bottom of this file; lazy
// chunks (actions-storage.ts, actions-signals.ts, …) MUST call through
// VD.confirmAppDialog / VD.promptAppDialog / VD.choiceAppDialog instead of
// importing this module — a lazy-chunk import would bundle a second copy
// whose duplicate state stacks focus traps and settles two promises with a
// single Confirm tap (see vd-registry.ts).
import { VD } from "./vd-registry";

type AppDialogOptions = {
  title: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
};

type AppPromptOptions = AppDialogOptions & {
  inputLabel?: string;
  // Autocomplete token for the prompt input. "current-password" (the default) fits
  // the restore flow; the choose-a-new-passphrase (encrypt) flow passes "new-password"
  // so a password manager offers to save the freshly chosen passphrase; plain-text
  // prompts (trip rename) pass "off".
  autocomplete?: string;
  // Input rendering. The passphrase flows keep the "password" default; plain-text
  // prompts (trip rename) pass "text" so the typed value stays visible.
  inputType?: "text" | "password";
  // Prefill shown (and selected-for-overwrite via focus) when the dialog opens.
  initialValue?: string;
  // When true, confirming with a blank field resolves "" instead of being blocked —
  // the trip-rename flow uses an empty submission to clear the custom name.
  allowEmpty?: boolean;
};

type AppDialogNodes = {
  root: HTMLElement;
  title: HTMLElement;
  message: HTMLElement;
  inputWrap: HTMLElement;
  inputLabel: HTMLElement;
  input: HTMLInputElement;
  confirm: HTMLButtonElement;
  cancel: HTMLButtonElement;
  close: HTMLButtonElement;
};

let dialogController: AbortController | null = null;
// Focus trap for the open dialog: owns background inerting, Tab/Escape
// containment, and focus save/restore to the opener. closeDialog() deactivates
// it. Recreated per open() so onEscape points at that dialog's cancel value.
let dialogTrap: FocusTrap | null = null;
// Resolver for the currently-open dialog's promise. closeDialog() settles it
// directly — relying on the (abortable) listeners to resolve would leave the
// promise pending forever when a second dialog aborts the first one's
// controller before any listener fires.
let dialogSettle: ((value: boolean | string | null) => void) | null = null;

function node<T extends HTMLElement>(id: string): T | null {
  return document.getElementById(id) as T | null;
}

function nodes(): AppDialogNodes | null {
  const root = node("appDialog");
  const title = node("appDialogTitle");
  const message = node("appDialogMessage");
  const inputWrap = node("appDialogInputWrap");
  const inputLabel = node("appDialogInputLabel");
  const input = node<HTMLInputElement>("appDialogInput");
  const confirm = node<HTMLButtonElement>("appDialogConfirm");
  const cancel = node<HTMLButtonElement>("appDialogCancel");
  const close = node<HTMLButtonElement>("appDialogClose");
  if (!root || !title || !message || !inputWrap || !inputLabel || !input || !confirm || !cancel || !close) {
    return null;
  }
  return { root, title, message, inputWrap, inputLabel, input, confirm, cancel, close };
}

// Every top-level body child except the dialog itself (and any ancestor/
// descendant of it). Inerting only .app + .bottom-nav left sibling top-level
// nodes — the error banner (role=alert), status toast, restore-progress —
// reachable behind the modal. Mirrors troubleshooter.ts's modalBackgroundNodes.
function backgroundNodes(): HTMLElement[] {
  const dialog = node("appDialog");
  if (!dialog) return [];
  return Array.from(document.body.children).filter((child): child is HTMLElement =>
    child instanceof HTMLElement &&
    child !== dialog &&
    !dialog.contains(child) &&
    !child.contains(dialog)
  );
}

function closeDialog(result: boolean | string | null): boolean | string | null {
  const n = nodes();
  // Detach the resolver before aborting/settling so re-entrant closes (or a
  // stacked openDialog) can never settle the same promise twice.
  const settlePending = dialogSettle;
  dialogSettle = null;
  if (dialogController) {
    dialogController.abort();
    dialogController = null;
  }
  if (n) {
    n.root.hidden = true;
    n.input.value = "";
  }
  // Restore the inert background and return focus to the opener. Runs after the
  // dialog is hidden so focus lands cleanly back on the trigger.
  if (dialogTrap) {
    dialogTrap.deactivate();
    dialogTrap = null;
  }
  if (settlePending) settlePending(result);
  return result;
}

function openDialog(options: AppPromptOptions & { mode: "confirm" | "prompt" }): Promise<boolean | string | null> {
  const n = nodes();
  if (!n) {
    return Promise.resolve(options.mode === "prompt" ? null : false);
  }
  if (dialogController) closeDialog(null);
  dialogController = new AbortController();
  const signal = dialogController.signal;

  n.title.textContent = options.title;
  n.message.textContent = options.message;
  n.confirm.textContent = options.confirmLabel || "Continue";
  n.cancel.textContent = options.cancelLabel || "Cancel";
  n.inputWrap.hidden = options.mode !== "prompt";
  n.inputLabel.textContent = options.inputLabel || "Passphrase";
  n.input.type = options.inputType || "password";
  n.input.value = options.initialValue || "";
  // Per-use autocomplete: restore reuses the default "current-password"; the encrypt
  // flow passes "new-password" so the manager offers to save the chosen passphrase.
  n.input.autocomplete = (options.autocomplete || "current-password") as AutoFill;
  const allowEmpty = options.allowEmpty === true;
  n.confirm.disabled = options.mode === "prompt" && !allowEmpty && !n.input.value.trim();

  return new Promise((resolve) => {
    // closeDialog() resolves via dialogSettle, so the promise settles exactly
    // once even when a second dialog opens and force-closes this one.
    dialogSettle = resolve;
    const settle = (value: boolean | string | null) => closeDialog(value);
    const confirmValue = () => {
      if (options.mode === "prompt") {
        const trimmed = n.input.value.trim();
        // With allowEmpty a blank confirm resolves "" (distinct from the null
        // cancel value) so callers can treat it as "clear the stored value".
        if (!trimmed && !allowEmpty) return;
        settle(trimmed);
        return;
      }
      settle(true);
    };
    const cancelValue = () => settle(options.mode === "prompt" ? null : false);
    // Dismissal (Escape, the X, the backdrop) settles null — distinct from the
    // explicit cancel BUTTON, which settles false in confirm mode. confirm-
    // and prompt-mode callers collapse both to "not confirmed"; choice mode
    // (choiceAppDialog) needs the distinction so a dialog whose secondary
    // button carries a real action can still be backed out of safely.
    const dismissValue = () => settle(null);
    n.input.addEventListener("input", () => {
      n.confirm.disabled = !allowEmpty && !n.input.value.trim();
    }, { signal });
    n.confirm.addEventListener("click", confirmValue, { signal });
    n.cancel.addEventListener("click", cancelValue, { signal });
    n.close.addEventListener("click", dismissValue, { signal });
    n.root.querySelector("[data-app-dialog-cancel]")?.addEventListener("click", dismissValue, { signal });
    // The shared trap owns background inerting, Tab/Escape containment, and
    // focus save/restore. Activate it while the opener still holds focus (it
    // snapshots document.activeElement), then move focus into the dialog.
    dialogTrap = createFocusTrap(n.root, { backgroundNodes, onEscape: dismissValue });
    n.root.hidden = false;
    dialogTrap.activate();
    window.requestAnimationFrame(() => {
      if (options.mode === "prompt") n.input.focus();
      else n.confirm.focus();
    });
  });
}

export function confirmAppDialog(options: AppDialogOptions): Promise<boolean> {
  return openDialog({ ...options, mode: "confirm" }).then((result) => result === true);
}

export function promptAppDialog(options: AppPromptOptions): Promise<string | null> {
  return openDialog({ ...options, mode: "prompt" }).then((result) => typeof result === "string" ? result : null);
}

/**
 * Three-way confirm: resolves true for the primary (confirm) button, false for
 * the explicit secondary (cancel-slot) button, and null when the dialog is
 * dismissed (Escape / the X / the backdrop). Use where the safe choice is the
 * primary action but a deliberate riskier alternative must stay reachable —
 * e.g. the plain-backup share (E2), where "Use encrypted backup" is primary,
 * "Share unencrypted" is the explicit secondary, and backing out via Escape
 * must do nothing rather than trigger either.
 */
export function choiceAppDialog(options: AppDialogOptions): Promise<boolean | null> {
  return openDialog({ ...options, mode: "confirm" }).then((result) =>
    result === true ? true : result === false ? false : null
  );
}

// Cross-chunk registration: the lazy action chunks reach the ONE dialog
// instance through the VD registry (see the module comment up top).
Object.assign(VD, { confirmAppDialog, promptAppDialog, choiceAppDialog });
