/**
 * Tests for src/chargingWiring.ts — the glue that ties the template's
 * `data-action` attributes and the `#charging-form` submit event to the
 * lazy-loaded charging module.
 *
 * These tests are regression guards for JTN-484 ("+ Add Session" button does
 * nothing) and JTN-485 (charging form never saves).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { wireChargingActions } from '../chargingWiring';

describe('wireChargingActions', () => {
  beforeEach(() => {
    document.body.innerHTML = `
      <section id="charging-section">
        <div class="section-header">
          <h2 class="section-title">Charging History</h2>
          <button
            class="btn-add"
            data-action="open-add-charging"
            aria-label="Add new charging session"
          >+ Add Session</button>
        </div>
      </section>

      <dialog class="modal" id="charging-modal" aria-hidden="true">
        <div class="modal-backdrop" data-action="close-charging-modal"></div>
        <button
          class="modal-close"
          data-action="close-charging-modal"
          aria-label="Close charging form"
        >&times;</button>
        <form
          id="charging-form"
          data-action="submit-charging-session"
          method="post"
          action="#"
        >
          <input id="charge-start" value="2025-01-01T10:00" />
          <input id="charge-end" />
          <input id="charge-start-soc" />
          <input id="charge-end-soc" />
          <input id="charge-kwh" value="10.5" />
          <input id="charge-type" value="l2" />
          <input id="charge-cost" />
          <input id="charge-location" />
          <input id="charge-notes" />
          <button type="button" data-action="close-charging-modal">Cancel</button>
          <button type="submit" class="btn-primary">Save Session</button>
        </form>
      </dialog>
    `;
  });

  it('clicking the "+ Add Session" button invokes openAddChargingModal (JTN-484)', () => {
    const openAddChargingModal = vi.fn();
    const closeChargingModal = vi.fn();
    const submitChargingSession = vi.fn();

    wireChargingActions(document, {
      openAddChargingModal,
      closeChargingModal,
      submitChargingSession,
    });

    const button = document.querySelector<HTMLButtonElement>(
      '[data-action="open-add-charging"]',
    );
    expect(button).not.toBeNull();
    button!.click();

    expect(openAddChargingModal).toHaveBeenCalledTimes(1);
    expect(submitChargingSession).not.toHaveBeenCalled();
    expect(closeChargingModal).not.toHaveBeenCalled();
  });

  it('clicking a close-charging-modal element invokes closeChargingModal', () => {
    const openAddChargingModal = vi.fn();
    const closeChargingModal = vi.fn();
    const submitChargingSession = vi.fn();

    wireChargingActions(document, {
      openAddChargingModal,
      closeChargingModal,
      submitChargingSession,
    });

    const cancelBtn = document.querySelector<HTMLButtonElement>(
      'button[type="button"][data-action="close-charging-modal"]',
    );
    cancelBtn!.click();

    expect(closeChargingModal).toHaveBeenCalledTimes(1);
  });

  it('clicking inside a nested element walks up to the [data-action] ancestor', () => {
    const openAddChargingModal = vi.fn();
    const closeChargingModal = vi.fn();
    const submitChargingSession = vi.fn();

    // Replace the button with one that has a nested span (simulating an icon).
    const header = document.querySelector('.section-header')!;
    header.innerHTML = `
      <button class="btn-add" data-action="open-add-charging">
        <span class="icon">+</span><span class="label">Add Session</span>
      </button>
    `;

    wireChargingActions(document, {
      openAddChargingModal,
      closeChargingModal,
      submitChargingSession,
    });

    const span = header.querySelector<HTMLSpanElement>('.label');
    span!.click();

    expect(openAddChargingModal).toHaveBeenCalledTimes(1);
  });

  it('ignores clicks on unrelated data-action elements', () => {
    const openAddChargingModal = vi.fn();
    const closeChargingModal = vi.fn();
    const submitChargingSession = vi.fn();

    // Inject an unrelated data-action that this wiring must NOT hijack.
    const unrelated = document.createElement('button');
    unrelated.setAttribute('data-action', 'toggle-theme');
    unrelated.textContent = 'theme';
    document.body.appendChild(unrelated);

    wireChargingActions(document, {
      openAddChargingModal,
      closeChargingModal,
      submitChargingSession,
    });

    unrelated.click();

    expect(openAddChargingModal).not.toHaveBeenCalled();
    expect(closeChargingModal).not.toHaveBeenCalled();
    expect(submitChargingSession).not.toHaveBeenCalled();
  });

  it('submitting #charging-form calls submitChargingSession and prevents default (JTN-485)', () => {
    const openAddChargingModal = vi.fn();
    const closeChargingModal = vi.fn();
    const submitChargingSession = vi.fn();

    wireChargingActions(document, {
      openAddChargingModal,
      closeChargingModal,
      submitChargingSession,
    });

    const form = document.getElementById('charging-form') as HTMLFormElement;
    const submitEvent = new Event('submit', { cancelable: true, bubbles: true });
    form.dispatchEvent(submitEvent);

    expect(submitChargingSession).toHaveBeenCalledTimes(1);
    expect(submitChargingSession).toHaveBeenCalledWith(submitEvent);
    expect(submitEvent.defaultPrevented).toBe(true);
  });

  it('submitting the form routes to POST /api/charging/add through the real charging module', async () => {
    // Integration-flavored test: wire the real submitChargingSession from
    // charging.ts and verify it calls fetch (via the `api` helper) with the
    // expected URL, method, and payload. This is the end-to-end regression
    // guard for JTN-485.

    // Re-import inside the test so the api mock applies.
    vi.doMock('@/api', () => ({
      api: vi.fn().mockResolvedValue({ data: { id: 1 }, error: null }),
    }));

    const { submitChargingSession } = await import('../charging');
    const { api } = await import('@/api');
    const apiMock = api as unknown as ReturnType<typeof vi.fn>;

    wireChargingActions(document, {
      openAddChargingModal: vi.fn(),
      closeChargingModal: vi.fn(),
      submitChargingSession: (event: Event) => submitChargingSession(event),
    });

    const form = document.getElementById('charging-form') as HTMLFormElement;
    const submitEvent = new Event('submit', { cancelable: true, bubbles: true });
    form.dispatchEvent(submitEvent);

    // submitChargingSession is async; flush microtasks.
    await Promise.resolve();
    await Promise.resolve();

    const addCall = apiMock.mock.calls.find((call) => call[0] === '/api/charging/add');
    expect(addCall, 'expected a POST to /api/charging/add').toBeTruthy();
    expect(addCall?.[1]?.method).toBe('POST');
    const body = JSON.parse(addCall?.[1]?.body as string);
    expect(body.start_time).toBe('2025-01-01T10:00');
    expect(body.kwh_added).toBe(10.5);
    expect(body.charge_type).toBe('l2');
    expect(submitEvent.defaultPrevented).toBe(true);

    vi.doUnmock('@/api');
  });
});
