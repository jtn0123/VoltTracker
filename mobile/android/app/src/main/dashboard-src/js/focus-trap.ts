// focus-trap.ts — one shared modal focus-trap helper (C8).
//
// Before this module, three dialogs (app-dialog, the DTC clear-codes warning,
// and the connection troubleshooter) each carried a bespoke copy of the same
// logic: collect focusable descendants, cycle Tab/Shift-Tab at the boundaries,
// inert + aria-hide the background, and restore focus to the opener on close.
// They drifted on the focusable visibility filter — this module standardises on
// the strictest one (see focusableNodes below).
//
// Usage:
//   const trap = createFocusTrap(rootEl, {
//     backgroundNodes: () => [...],   // nodes to inert while the trap is active
//     onEscape: () => closeMyDialog(),
//   });
//   trap.activate();    // save focus, inert background, arm Tab/Escape handling
//   trap.deactivate();  // restore background, abort listeners, refocus opener
//
// The trap owns ONLY focus + keyboard containment + background inerting. It does
// not show/hide the dialog or place initial focus inside it — callers keep doing
// that, since the right initial target (a specific input vs the dialog shell) is
// dialog-specific.

type InertElement = HTMLElement & { inert?: boolean };

// Captures a background node's inert / aria-hidden state at activate() time so deactivate() can
// restore exactly what was there before — rather than blindly clearing both, which would corrupt a
// node that was already inert or aria-hidden before the trap touched it.
type InertSnapshot = {
  node: InertElement;
  hadInert: boolean;
  inertValue: boolean;
  hadAriaHidden: boolean;
  ariaHiddenValue: string | null;
};

export type FocusTrapOptions = {
  // Nodes to mark inert + aria-hidden while the trap is active. A function is
  // re-evaluated at activate() time, which matters when the set depends on the
  // live DOM (e.g. the DTC dialog excludes the panel's own ancestor chain).
  backgroundNodes?: HTMLElement[] | (() => HTMLElement[]);
  // Invoked when Escape is pressed while the trap is active. Callers wire this to
  // their own close routine (which is expected to call deactivate()).
  onEscape?: () => void;
};

export type FocusTrap = {
  activate(): void;
  deactivate(): void;
};

// The canonical focusable selector — the union of every selector the three old
// traps used, deduplicated.
const FOCUSABLE_SELECTOR = [
  "a[href]",
  "button:not([disabled])",
  "input:not([disabled])",
  "select:not([disabled])",
  "textarea:not([disabled])",
  "[href]",
  "[tabindex]:not([tabindex='-1'])"
].join(", ");

function resolveBackgroundNodes(source: FocusTrapOptions["backgroundNodes"]): InertElement[] {
  const list = typeof source === "function" ? source() : source || [];
  return list.filter((item): item is InertElement => item instanceof HTMLElement);
}

export function createFocusTrap(rootEl: HTMLElement, opts: FocusTrapOptions = {}): FocusTrap {
  let controller: AbortController | null = null;
  let opener: Element | null = null;
  let inertedNodes: InertSnapshot[] = [];

  // Strictest of the three legacy filters: a node counts as focusable only when
  // it is not [hidden], not inside a [hidden] subtree, and not aria-hidden.
  function focusableNodes(): HTMLElement[] {
    return Array
      .from(rootEl.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
      .filter((node) =>
        !node.hidden &&
        !node.closest("[hidden]") &&
        node.getAttribute("aria-hidden") !== "true"
      );
  }

  function setBackgroundInert(inert: boolean) {
    if (!inert) {
      for (const snap of inertedNodes) {
        // Restore prior inert: if the node was inert before we touched it, leave it inert.
        snap.node.inert = snap.hadInert ? snap.inertValue : false;
        // Restore prior aria-hidden: re-apply the original value, or remove it if there was none.
        if (snap.hadAriaHidden && snap.ariaHiddenValue !== null) {
          snap.node.setAttribute("aria-hidden", snap.ariaHiddenValue);
        } else {
          snap.node.removeAttribute("aria-hidden");
        }
      }
      inertedNodes = [];
      return;
    }
    inertedNodes = resolveBackgroundNodes(opts.backgroundNodes).map((node) => ({
      node,
      hadInert: "inert" in node,
      inertValue: Boolean(node.inert),
      hadAriaHidden: node.hasAttribute("aria-hidden"),
      ariaHiddenValue: node.getAttribute("aria-hidden")
    }));
    for (const snap of inertedNodes) {
      snap.node.inert = true;
      snap.node.setAttribute("aria-hidden", "true");
    }
  }

  function handleKeydown(event: KeyboardEvent) {
    if (event.key === "Escape") {
      event.preventDefault();
      if (opts.onEscape) opts.onEscape();
      return;
    }
    if (event.key !== "Tab") return;
    const focusable = focusableNodes();
    if (!focusable.length) {
      event.preventDefault();
      if (typeof rootEl.focus === "function") rootEl.focus();
      return;
    }
    const first = focusable[0]!;
    const last = focusable[focusable.length - 1]!;
    const active = document.activeElement;
    // If focus has escaped the trap entirely, pull it back to the appropriate
    // edge rather than letting Tab walk into the inert background.
    if (!focusable.includes(active as HTMLElement)) {
      event.preventDefault();
      (event.shiftKey ? last : first).focus();
      return;
    }
    if (event.shiftKey && active === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }

  function activate() {
    // Re-entrant activate() (e.g. one dialog replacing another): tear the prior
    // arming down first so listeners and inert state never stack.
    if (controller) deactivate();
    opener = document.activeElement;
    setBackgroundInert(true);
    controller = new AbortController();
    document.addEventListener("keydown", handleKeydown, { signal: controller.signal });
  }

  function deactivate() {
    if (controller) {
      controller.abort();
      controller = null;
    }
    setBackgroundInert(false);
    const previous = opener;
    opener = null;
    if (previous instanceof HTMLElement && typeof previous.focus === "function" && document.contains(previous)) {
      previous.focus();
    }
  }

  return { activate, deactivate };
}
