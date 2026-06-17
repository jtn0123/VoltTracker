// actions-storage.ts — backup / restore / clear-storage / debug-export wiring.
//
// actions.test.js exercises these through the assembled dashboard with a happy
// fixture (confirm-then-call, valid export payloads). This spec imports the
// factory DIRECTLY and drives the FAILURE / CANCEL / missing-bridge branches
// that the assembled flow never reaches:
//   - confirm-dialog CANCEL must NOT call the destructive bridge method
//   - passphrase-prompt CANCEL must NOT call the bridge
//   - a bridge that lacks the method (or no bridge at all) -> "only available
//     inside the Android app" idle status, no throw
//   - exportDebugBundle parse/error result -> blocked status with the error
//
// Convention follows app-dialog.test.js: import the real module by its `.ts`
// path, stand up the minimal app-dialog DOM, and drive the dialog buttons. The
// VD seam and the bridge are hand-stubbed (not the assembled dashboard) so each
// test owns exactly the state it asserts.
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { createStorageActions } from '../app/src/main/dashboard-src/js/actions-storage.ts';

// Minimal app-dialog markup — every id app-dialog's nodes() dereferences, plus
// the backdrop cancel target and the .app / .bottom-nav background nodes the
// focus trap inerts. Mirrors DIALOG_DOM in app-dialog.test.js.
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

// withBusy in production guards double-taps and releases the button on a timer.
// The factory only needs it to run the wrapped fn synchronously, so a pass-
// through stub keeps the assertions about whether the bridge was called honest.
function passthroughBusy(_button, fn) {
  return fn();
}

// A VD seam that records setStatus/setStorage and re-implements parsePayload
// with the same contract as core.ts: a JSON string is parsed, an object is
// returned as-is, anything unparseable yields the fallback.
function makeVd() {
  return {
    statuses: [],
    storage: undefined,
    setStatus(payload) {
      this.statuses.push(payload);
    },
    setStorage(payload) {
      this.storage = payload;
    },
    parsePayload(payload, fallback = {}) {
      if (payload && typeof payload === 'object') return payload;
      if (typeof payload === 'string') {
        try {
          return JSON.parse(payload);
        } catch {
          return fallback;
        }
      }
      return fallback;
    },
    lastStatus() {
      return this.statuses[this.statuses.length - 1];
    },
  };
}

function appDialog() {
  return document.getElementById('appDialog');
}

function appDialogMessage() {
  return document.getElementById('appDialogMessage').textContent;
}

function enterAppDialogInput(value) {
  const input = document.getElementById('appDialogInput');
  input.value = value;
  input.dispatchEvent(new window.Event('input', { bubbles: true }));
}

// The dialog's click handlers resolve a promise; one extra microtask turn lets
// the .then() in the action run before we assert.
async function clickConfirm() {
  document.getElementById('appDialogConfirm').click();
  await Promise.resolve();
}

async function clickCancel() {
  document.getElementById('appDialogCancel').click();
  await Promise.resolve();
}

describe('actions-storage.ts — confirm/prompt cancel paths', () => {
  let VD;

  beforeEach(() => {
    document.body.innerHTML = DIALOG_DOM;
    VD = makeVd();
  });

  it('clearStorage() cancel leaves the data untouched (no destructive bridge call)', async () => {
    const bridge = { clearStoredData: vi.fn() };
    const actions = createStorageActions({ VD, bridge, withBusy: passthroughBusy });

    actions.clearStorage(null);
    expect(appDialog().hidden).toBe(false);
    expect(appDialogMessage()).toMatch(/clear local obd sessions/i);

    await clickCancel();
    expect(bridge.clearStoredData).not.toHaveBeenCalled();
  });

  it('shareBackup() cancel sets a ready "cancelled" status and skips the bridge', async () => {
    const bridge = { shareBackup: vi.fn() };
    const actions = createStorageActions({ VD, bridge, withBusy: passthroughBusy });

    actions.shareBackup(null);
    expect(appDialogMessage()).toMatch(/plaintext backup/i);

    await clickCancel();
    expect(bridge.shareBackup).not.toHaveBeenCalled();
    expect(VD.lastStatus()).toMatchObject({ state: 'ready', detail: 'Backup cancelled.' });
  });

  it('shareEncryptedBackup() prompt cancel sets a ready status and skips the bridge', async () => {
    const bridge = { shareEncryptedBackup: vi.fn() };
    const actions = createStorageActions({ VD, bridge, withBusy: passthroughBusy });

    actions.shareEncryptedBackup(null);
    expect(appDialog().hidden).toBe(false);

    // Cancelling the prompt resolves with null -> the early "cancelled" return.
    await clickCancel();
    expect(bridge.shareEncryptedBackup).not.toHaveBeenCalled();
    expect(VD.lastStatus()).toMatchObject({ state: 'ready', detail: 'Encrypted backup cancelled.' });
  });

  it('restoreEncryptedBackup() prompt cancel sets a ready status and skips the bridge', async () => {
    const bridge = { restoreEncryptedBackup: vi.fn() };
    const actions = createStorageActions({ VD, bridge, withBusy: passthroughBusy });

    actions.restoreEncryptedBackup(null);
    expect(appDialog().hidden).toBe(false);

    await clickCancel();
    expect(bridge.restoreEncryptedBackup).not.toHaveBeenCalled();
    expect(VD.lastStatus()).toMatchObject({ state: 'ready', detail: 'Encrypted restore cancelled.' });
  });

  it('shareEncryptedBackup() forwards the entered passphrase on confirm', async () => {
    const bridge = { shareEncryptedBackup: vi.fn() };
    const actions = createStorageActions({ VD, bridge, withBusy: passthroughBusy });

    actions.shareEncryptedBackup(null);
    enterAppDialogInput('hunter2');
    await clickConfirm();
    expect(bridge.shareEncryptedBackup).toHaveBeenCalledWith('hunter2');
  });
});

describe('actions-storage.ts — missing-bridge idle guards', () => {
  let VD;

  beforeEach(() => {
    document.body.innerHTML = DIALOG_DOM;
    VD = makeVd();
  });

  it('clearStorage() with no bridge is a silent no-op (no dialog, no throw)', () => {
    const actions = createStorageActions({ VD, bridge: null, withBusy: passthroughBusy });
    expect(() => actions.clearStorage(null)).not.toThrow();
    // clearStorage has no idle-status branch — it just returns. Dialog stays shut.
    expect(appDialog().hidden).toBe(true);
    expect(VD.statuses).toHaveLength(0);
  });

  it('shareBackup() without the bridge method shows the Android-only idle status', () => {
    const actions = createStorageActions({ VD, bridge: {}, withBusy: passthroughBusy });
    actions.shareBackup(null);
    expect(VD.lastStatus()).toMatchObject({
      state: 'idle',
      detail: 'Backup is only available inside the Android app.',
    });
  });

  it('shareEncryptedBackup() with no bridge shows the encrypted-only idle status', () => {
    const actions = createStorageActions({ VD, bridge: null, withBusy: passthroughBusy });
    actions.shareEncryptedBackup(null);
    expect(VD.lastStatus()).toMatchObject({
      state: 'idle',
      detail: 'Encrypted backup is only available inside the Android app.',
    });
  });

  it('restoreBackup() with no bridge shows the restore-only idle status and never calls the bridge', () => {
    const bridge = null;
    const actions = createStorageActions({ VD, bridge, withBusy: passthroughBusy });
    actions.restoreBackup(null);
    expect(VD.lastStatus()).toMatchObject({
      state: 'idle',
      detail: 'Restore is only available inside the Android app.',
    });
  });

  it('restoreEncryptedBackup() with no bridge shows the encrypted-restore idle status', () => {
    const actions = createStorageActions({ VD, bridge: null, withBusy: passthroughBusy });
    actions.restoreEncryptedBackup(null);
    expect(VD.lastStatus()).toMatchObject({
      state: 'idle',
      detail: 'Encrypted restore is only available inside the Android app.',
    });
  });

  it('exportDebugBundle() with no bridge shows the debug-export idle status', () => {
    const actions = createStorageActions({ VD, bridge: null, withBusy: passthroughBusy });
    actions.exportDebugBundle();
    expect(VD.lastStatus()).toMatchObject({
      state: 'idle',
      detail: 'Debug export is only available inside the Android app.',
    });
  });

  it('refreshStorage() with no bridge does not touch storage state', () => {
    const actions = createStorageActions({ VD, bridge: null, withBusy: passthroughBusy });
    actions.refreshStorage();
    expect(VD.storage).toBeUndefined();
  });
});

describe('actions-storage.ts — exportDebugBundle result branches', () => {
  let VD;

  beforeEach(() => {
    document.body.innerHTML = DIALOG_DOM;
    VD = makeVd();
  });

  it('surfaces a ready status with the path on a successful export', () => {
    const bridge = { exportDebugBundle: vi.fn(() => '{"ok":true,"path":"/data/app/debug"}') };
    const actions = createStorageActions({ VD, bridge, withBusy: passthroughBusy });
    actions.exportDebugBundle();
    expect(VD.lastStatus()).toMatchObject({
      state: 'ready',
      detail: 'Debug summary exported: /data/app/debug.',
    });
  });

  it('falls back to "app files" when a successful export omits the path', () => {
    const bridge = { exportDebugBundle: vi.fn(() => '{"ok":true}') };
    const actions = createStorageActions({ VD, bridge, withBusy: passthroughBusy });
    actions.exportDebugBundle();
    expect(VD.lastStatus()).toMatchObject({
      state: 'ready',
      detail: 'Debug summary exported: app files.',
    });
  });

  it('surfaces the error message as a blocked status on a failed export result', () => {
    const bridge = {
      exportDebugBundle: vi.fn(() => '{"ok":false,"error":"Debug write denied."}'),
    };
    const actions = createStorageActions({ VD, bridge, withBusy: passthroughBusy });
    actions.exportDebugBundle();
    expect(VD.lastStatus()).toMatchObject({
      state: 'blocked',
      detail: 'Debug write denied.',
    });
  });

  it('blocked status uses the generic fallback when the export result is unparseable', () => {
    // parsePayload returns the {} fallback (ok undefined => falsy) -> blocked
    // branch with no error string -> generic copy.
    const bridge = { exportDebugBundle: vi.fn(() => 'not-json') };
    const actions = createStorageActions({ VD, bridge, withBusy: passthroughBusy });
    actions.exportDebugBundle();
    expect(VD.lastStatus()).toMatchObject({
      state: 'blocked',
      detail: 'Debug export failed.',
    });
  });
});

describe('actions-storage.ts — happy confirm/restore paths (companions to the cancel specs)', () => {
  let VD;

  beforeEach(() => {
    document.body.innerHTML = DIALOG_DOM;
    VD = makeVd();
  });

  it('clearStorage() invokes the bridge after confirmation', async () => {
    const bridge = { clearStoredData: vi.fn() };
    const actions = createStorageActions({ VD, bridge, withBusy: passthroughBusy });
    actions.clearStorage(null);
    await clickConfirm();
    expect(bridge.clearStoredData).toHaveBeenCalledTimes(1);
  });

  it('restoreBackup() invokes the bridge directly with no pre-pick dialog', () => {
    const bridge = { restoreBackup: vi.fn() };
    const actions = createStorageActions({ VD, bridge, withBusy: passthroughBusy });
    actions.restoreBackup(null);
    expect(appDialog().hidden).toBe(true);
    expect(bridge.restoreBackup).toHaveBeenCalledTimes(1);
  });

  it('refreshStorage() pushes the bridge summary into VD.setStorage', () => {
    const bridge = { getStorageSummary: vi.fn(() => '{"dbBytes":42}') };
    const actions = createStorageActions({ VD, bridge, withBusy: passthroughBusy });
    actions.refreshStorage();
    expect(bridge.getStorageSummary).toHaveBeenCalledTimes(1);
    expect(VD.storage).toBe('{"dbBytes":42}');
  });
});
