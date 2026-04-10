/**
 * Wire up the "Add Charging Session" UI flow.
 *
 * The template (`receiver/templates/index.html`) uses a `data-action`
 * convention to mark interactive elements (JTN-484 / JTN-485). Historically
 * nothing read those attributes, so clicking "+ Add Session" was a no-op and
 * submitting `#charging-form` never reached `/api/charging/add`.
 *
 * This module attaches a narrowly-scoped delegated click handler that only
 * handles the charging-related actions, plus a direct `submit` listener on
 * `#charging-form`. Scoping it to charging avoids touching unrelated
 * `data-action` attributes in the template.
 *
 * The actual business logic lives in `charging.ts` and is exposed on
 * `globalThis` by `main.ts` (lazy-loaded on first call).
 */

export interface ChargingWiringDeps {
  openAddChargingModal: () => void | Promise<void>;
  closeChargingModal: () => void | Promise<void>;
  submitChargingSession: (event: Event) => void | Promise<void>;
}

/**
 * Default dependencies that thunk through `globalThis`, which `main.ts`
 * populates with lazy-loaded wrappers around the real `charging.ts` exports.
 */
const defaultDeps: ChargingWiringDeps = {
  openAddChargingModal: () => globalThis.openAddChargingModal(),
  closeChargingModal: () => globalThis.closeChargingModal(),
  submitChargingSession: (event: Event) => globalThis.submitChargingSession(event),
};

/**
 * Attach click + submit listeners for the Add Charging Session flow.
 *
 * Safe to call once per page load. The click handler is scoped to
 * `data-action` values related to charging so it doesn't interfere with
 * other delegated wiring that may exist elsewhere.
 */
export function wireChargingActions(
  root: Document | HTMLElement = document,
  deps: ChargingWiringDeps = defaultDeps,
): void {
  root.addEventListener('click', (event: Event) => {
    const target = event.target as Element | null;
    const actionEl = target?.closest?.('[data-action]') as HTMLElement | null;
    if (!actionEl) return;
    const action = actionEl.dataset.action;
    switch (action) {
      case 'open-add-charging':
        event.preventDefault();
        void deps.openAddChargingModal();
        break;
      case 'close-charging-modal':
        event.preventDefault();
        void deps.closeChargingModal();
        break;
      default:
        // Intentionally leave other data-actions alone — other modules may
        // own them, and we don't want to double-handle or swallow events.
        break;
    }
  });

  const form = (root instanceof Document ? root : root.ownerDocument)
    ?.getElementById('charging-form') as HTMLFormElement | null;
  if (form) {
    form.addEventListener('submit', (event: Event) => {
      // `submitChargingSession` also calls preventDefault, but we call it
      // here too so that a lazy-load failure can never silently fall back to
      // the browser's default GET submission.
      event.preventDefault();
      void deps.submitChargingSession(event);
    });
  }
}
