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

type InertElement = HTMLElement & { inert?: boolean };

let dialogController: AbortController | null = null;
let dialogOpener: Element | null = null;
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

function backgroundNodes(): InertElement[] {
  return [document.querySelector(".app"), document.querySelector(".bottom-nav")]
    .filter((item): item is InertElement => item instanceof HTMLElement);
}

function setBackgroundInert(inert: boolean) {
  for (const item of backgroundNodes()) {
    item.inert = inert;
    if (inert) item.setAttribute("aria-hidden", "true");
    else item.removeAttribute("aria-hidden");
  }
}

function focusableNodes(root: HTMLElement): HTMLElement[] {
  return Array
    .from(root.querySelectorAll<HTMLElement>(
      "button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [href], [tabindex]:not([tabindex='-1'])"
    ))
    .filter((item) => !item.hidden && !item.closest("[hidden]"));
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
  setBackgroundInert(false);
  const opener = dialogOpener;
  dialogOpener = null;
  if (opener instanceof HTMLElement && document.contains(opener)) {
    opener.focus();
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
  dialogOpener = document.activeElement;

  n.title.textContent = options.title;
  n.message.textContent = options.message;
  n.confirm.textContent = options.confirmLabel || "Continue";
  n.cancel.textContent = options.cancelLabel || "Cancel";
  n.inputWrap.hidden = options.mode !== "prompt";
  n.inputLabel.textContent = options.inputLabel || "Passphrase";
  n.input.value = "";
  n.confirm.disabled = options.mode === "prompt";
  n.root.hidden = false;
  setBackgroundInert(true);

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
    document.addEventListener("keydown", (event) => {
      if (n.root.hidden) return;
      if (event.key === "Escape") {
        event.preventDefault();
        cancelValue();
        return;
      }
      if (event.key !== "Tab") return;
      const focusable = focusableNodes(n.root);
      if (!focusable.length) {
        event.preventDefault();
        n.root.focus();
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }, { signal });
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
