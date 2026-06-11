// app-dialog.ts — the in-app modal that replaces window.confirm/prompt.
//
// Focus: promise-settlement guarantees. The module keeps one AbortController
// for the open dialog's listeners; opening a second dialog force-closes the
// first. closeDialog() must settle the pending promise DIRECTLY (with the
// cancel value) — if it only aborted the controller, the first caller's
// promise would hang forever because the listeners that would have resolved
// it are gone.
import { beforeEach, describe, expect, it } from 'vitest';

import { confirmAppDialog, promptAppDialog } from '../app/src/main/dashboard-src/js/app-dialog.ts';

// Minimal production markup (see partials/app-dialog.html) — every id
// nodes() dereferences, plus a backdrop cancel target and background nodes
// for the inert toggling.
const DIALOG_DOM = `
  <main class="app"></main>
  <nav class="bottom-nav"></nav>
  <div id="appDialog" hidden>
    <div data-app-dialog-cancel></div>
    <h2 id="appDialogTitle"></h2>
    <button id="appDialogClose" type="button"></button>
    <p id="appDialogMessage"></p>
    <label id="appDialogInputWrap" hidden>
      <span id="appDialogInputLabel"></span>
      <input id="appDialogInput" type="password" />
    </label>
    <button id="appDialogCancel" type="button"></button>
    <button id="appDialogConfirm" type="button"></button>
  </div>
`;

function click(id) {
  document.getElementById(id).click();
}

// Resolves with undefined if `promise` hasn't settled within a few microtask
// turns — lets us assert "still pending" without hanging the test.
async function settledValue(promise) {
  const PENDING = Symbol('pending');
  let result = PENDING;
  promise.then((value) => { result = value; });
  for (let i = 0; i < 5; i += 1) await Promise.resolve();
  return result === PENDING ? { pending: true } : { pending: false, value: result };
}

describe('app-dialog.ts — promise settlement', () => {
  beforeEach(() => {
    document.body.innerHTML = DIALOG_DOM;
  });

  it('the normal confirm path resolves true exactly once', async () => {
    let resolutions = 0;
    const promise = confirmAppDialog({ title: 'T', message: 'M' });
    promise.then(() => { resolutions += 1; });
    expect(document.getElementById('appDialog').hidden).toBe(false);

    click('appDialogConfirm');
    await expect(promise).resolves.toBe(true);
    expect(document.getElementById('appDialog').hidden).toBe(true);

    // Listeners are aborted on close: stray clicks can't settle again.
    click('appDialogConfirm');
    click('appDialogCancel');
    for (let i = 0; i < 5; i += 1) await Promise.resolve();
    expect(resolutions).toBe(1);
  });

  it('the cancel path resolves false', async () => {
    const promise = confirmAppDialog({ title: 'T', message: 'M' });
    click('appDialogCancel');
    await expect(promise).resolves.toBe(false);
  });

  it('opening a second dialog settles the first promise with its cancel value', async () => {
    const first = confirmAppDialog({ title: 'First', message: 'M1' });

    // Open a second dialog while the first is still up. The first promise
    // must resolve (cancel value), not hang with its listeners aborted.
    const second = confirmAppDialog({ title: 'Second', message: 'M2' });
    const firstOutcome = await settledValue(first);
    expect(firstOutcome).toEqual({ pending: false, value: false });

    // The second dialog owns the modal now and still works end-to-end.
    expect(document.getElementById('appDialogTitle').textContent).toBe('Second');
    click('appDialogConfirm');
    await expect(second).resolves.toBe(true);
  });

  it('a stacked dialog cancels a pending prompt with null', async () => {
    const first = promptAppDialog({ title: 'Pass', message: 'M', inputLabel: 'Passphrase' });
    const second = confirmAppDialog({ title: 'Second', message: 'M2' });

    const firstOutcome = await settledValue(first);
    expect(firstOutcome).toEqual({ pending: false, value: null });

    click('appDialogCancel');
    await expect(second).resolves.toBe(false);
  });
});
