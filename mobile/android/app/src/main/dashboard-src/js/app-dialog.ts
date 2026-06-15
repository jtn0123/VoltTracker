import { createFocusTrap } from "./focus-trap";
import type { FocusTrap } from "./focus-trap";

type AppDialogOptions = {
  title: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
};

type AppPromptOptions = AppDialogOptions & {
  inputLabel?: string;
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

function backgroundNodes(): HTMLElement[] {
  return [document.querySelector(".app"), document.querySelector(".bottom-nav")]
    .filter((item): item is HTMLElement => item instanceof HTMLElement);
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
  n.input.value = "";
  n.confirm.disabled = options.mode === "prompt";

  return new Promise((resolve) => {
    // closeDialog() resolves via dialogSettle, so the promise settles exactly
    // once even when a second dialog opens and force-closes this one.
    dialogSettle = resolve;
    const settle = (value: boolean | string | null) => closeDialog(value);
    const confirmValue = () => {
      if (options.mode === "prompt") {
        const trimmed = n.input.value.trim();
        if (!trimmed) return;
        settle(trimmed);
        return;
      }
      settle(true);
    };
    const cancelValue = () => settle(options.mode === "prompt" ? null : false);
    n.input.addEventListener("input", () => {
      n.confirm.disabled = !n.input.value.trim();
    }, { signal });
    n.confirm.addEventListener("click", confirmValue, { signal });
    n.cancel.addEventListener("click", cancelValue, { signal });
    n.close.addEventListener("click", cancelValue, { signal });
    n.root.querySelector("[data-app-dialog-cancel]")?.addEventListener("click", cancelValue, { signal });
    // The shared trap owns background inerting, Tab/Escape containment, and
    // focus save/restore. Activate it while the opener still holds focus (it
    // snapshots document.activeElement), then move focus into the dialog.
    dialogTrap = createFocusTrap(n.root, { backgroundNodes, onEscape: cancelValue });
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
