// actions-signals.ts — detailed-signal-log export / delete wiring.
//
// actions.test.js covers the assembled happy paths (export -> download or
// clipboard, confirm-then-delete). This spec imports the factory DIRECTLY and
// drives the branches that flow never reaches:
//   - missing bridge / missing method -> "only available inside the Android
//     app" idle status, no throw
//   - an { ok:false } export result -> blocked status surfacing parsed.message
//   - the clipboard fallback: when browser downloads are unavailable AND
//     navigator.clipboard is absent, deliverExport uses the execCommand("copy")
//     <textarea> path (and a clipboard rejection -> blocked status)
//   - deleteSignalLog confirm CANCEL must NOT call the destructive bridge method
//
// Convention follows app-dialog.test.js / actions-storage.test.js: import the
// real `.ts` module, stand up the minimal app-dialog DOM for the delete
// confirm, and hand-stub the VD seam + bridge so each test owns its state.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { createSignalActions } from '../app/src/main/dashboard-src/js/actions-signals.ts';

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

// VD seam: records setStatus and re-implements parsePayload with the core.ts
// contract (parse JSON string, pass objects through, fallback on failure).
function makeVd() {
  return {
    statuses: [],
    setStatus(payload) {
      this.statuses.push(payload);
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

async function clickConfirm() {
  document.getElementById('appDialogConfirm').click();
  await Promise.resolve();
}

async function clickCancel() {
  document.getElementById('appDialogCancel').click();
  await Promise.resolve();
}

// deliverExport tries a Blob download first. Force it off so the clipboard
// fallback runs: stub createObjectURL to undefined (downloadTextFile bails on
// the typeof check and returns false). Restored by restoreUrl().
function disableBrowserDownloads() {
  const original = Object.getOwnPropertyDescriptor(window.URL, 'createObjectURL');
  Object.defineProperty(window.URL, 'createObjectURL', { configurable: true, value: undefined });
  return () => {
    if (original) Object.defineProperty(window.URL, 'createObjectURL', original);
    else delete window.URL.createObjectURL;
  };
}

// Replace navigator.clipboard for one test. Pass null to simulate "no Clipboard
// API" (drives writeClipboard's execCommand fallback).
function setClipboard(value) {
  const original = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value });
  return () => {
    if (original) Object.defineProperty(navigator, 'clipboard', original);
    else delete navigator.clipboard;
  };
}

describe('actions-signals.ts — missing-bridge idle guards', () => {
  let VD;

  beforeEach(() => {
    document.body.innerHTML = DIALOG_DOM;
    VD = makeVd();
  });

  it('exportSignalLog() with no bridge shows the Android-only idle status', () => {
    const actions = createSignalActions({ VD, bridge: null });
    expect(() => actions.exportSignalLog(5)).not.toThrow();
    expect(VD.lastStatus()).toMatchObject({
      state: 'idle',
      detail: 'Signal log export is only available inside the Android app.',
    });
  });

  it('exportSignalLogs() without the bridge method shows the Android-only idle status', () => {
    const actions = createSignalActions({ VD, bridge: {} });
    actions.exportSignalLogs();
    expect(VD.lastStatus()).toMatchObject({
      state: 'idle',
      detail: 'Signal log export is only available inside the Android app.',
    });
  });

  it('deleteSignalLog() with no bridge shows the cleanup-only idle status (no dialog)', () => {
    const actions = createSignalActions({ VD, bridge: null });
    actions.deleteSignalLog(7);
    expect(appDialog().hidden).toBe(true);
    expect(VD.lastStatus()).toMatchObject({
      state: 'idle',
      detail: 'Signal log cleanup is only available inside the Android app.',
    });
  });
});

describe('actions-signals.ts — failed export result -> blocked', () => {
  let VD;

  beforeEach(() => {
    document.body.innerHTML = DIALOG_DOM;
    VD = makeVd();
  });

  it('exportSignalLog() surfaces parsed.message as a blocked status on ok:false', () => {
    const bridge = {
      exportDetailedSignalLog: vi.fn(() => '{"ok":false,"message":"No evidence row 5."}'),
    };
    const actions = createSignalActions({ VD, bridge });
    actions.exportSignalLog(5);
    expect(bridge.exportDetailedSignalLog).toHaveBeenCalledWith('5');
    expect(VD.lastStatus()).toMatchObject({
      state: 'blocked',
      detail: 'No evidence row 5.',
    });
  });

  it('exportSignalLog() blocked status falls back to generic copy when message is absent', () => {
    const bridge = { exportDetailedSignalLog: vi.fn(() => '{"ok":false}') };
    const actions = createSignalActions({ VD, bridge });
    actions.exportSignalLog('');
    expect(bridge.exportDetailedSignalLog).toHaveBeenCalledWith('');
    expect(VD.lastStatus()).toMatchObject({
      state: 'blocked',
      detail: 'Signal log export failed.',
    });
  });

  it('exportSignalLogs() surfaces parsed.message as a blocked status on ok:false', () => {
    const bridge = {
      exportDetailedSignalLogs: vi.fn(() => '{"ok":false,"message":"Evidence table locked."}'),
    };
    const actions = createSignalActions({ VD, bridge });
    actions.exportSignalLogs();
    expect(VD.lastStatus()).toMatchObject({
      state: 'blocked',
      detail: 'Evidence table locked.',
    });
  });
});

describe('actions-signals.ts — clipboard fallback (no browser download)', () => {
  let VD;
  let restoreUrl;
  let restoreClipboard;

  beforeEach(() => {
    document.body.innerHTML = DIALOG_DOM;
    VD = makeVd();
    restoreUrl = disableBrowserDownloads();
  });

  afterEach(() => {
    if (restoreUrl) restoreUrl();
    if (restoreClipboard) restoreClipboard();
    restoreClipboard = undefined;
    vi.restoreAllMocks();
  });

  it('uses the Clipboard API when downloads are unavailable -> ready "copied" status', async () => {
    const writeText = vi.fn(() => Promise.resolve());
    restoreClipboard = setClipboard({ writeText });
    const bridge = { exportDetailedSignalLog: vi.fn(() => '{"ok":true,"item":{"id":5}}') };
    const actions = createSignalActions({ VD, bridge });

    actions.exportSignalLog(5);
    await Promise.resolve();
    await Promise.resolve();

    expect(writeText).toHaveBeenCalledWith(expect.stringContaining('"id"'));
    expect(VD.lastStatus()).toMatchObject({
      state: 'ready',
      detail: 'Detailed signal log copied.',
    });
  });

  it('falls back to the execCommand("copy") <textarea> when there is no Clipboard API', async () => {
    restoreClipboard = setClipboard(null);
    // jsdom has no execCommand; stub it and assert writeClipboard drove it via
    // a momentarily-attached <textarea> carrying the export JSON.
    let copied = false;
    let textareaValue = '';
    document.execCommand = vi.fn((cmd) => {
      if (cmd === 'copy') {
        copied = true;
        const area = document.querySelector('textarea');
        textareaValue = area ? area.value : '';
      }
      return true;
    });
    const bridge = { exportDetailedSignalLogs: vi.fn(() => '{"ok":true,"items":[{"id":1}]}') };
    const actions = createSignalActions({ VD, bridge });

    actions.exportSignalLogs();
    await Promise.resolve();
    await Promise.resolve();

    expect(copied).toBe(true);
    expect(document.execCommand).toHaveBeenCalledWith('copy');
    expect(textareaValue).toContain('"items"');
    // The <textarea> is removed in the finally block after the copy.
    expect(document.querySelector('textarea')).toBeNull();
    expect(VD.lastStatus()).toMatchObject({
      state: 'ready',
      detail: 'Detailed signal logs copied.',
    });
  });

  it('a rejecting Clipboard API yields the "could not export" blocked status', async () => {
    const writeText = vi.fn(() => Promise.reject(new Error('denied')));
    restoreClipboard = setClipboard({ writeText });
    const bridge = { exportDetailedSignalLog: vi.fn(() => '{"ok":true,"item":{"id":9}}') };
    const actions = createSignalActions({ VD, bridge });

    actions.exportSignalLog(9);
    await Promise.resolve();
    await Promise.resolve();

    expect(VD.lastStatus()).toMatchObject({
      state: 'blocked',
      detail: 'Could not export detailed signal logs.',
    });
  });
});

describe('actions-signals.ts — deleteSignalLog confirm dialog', () => {
  let VD;

  beforeEach(() => {
    document.body.innerHTML = DIALOG_DOM;
    VD = makeVd();
  });

  it('cancel leaves the evidence row in place (no destructive bridge call)', async () => {
    const bridge = { deleteDetailedSignalLog: vi.fn() };
    const actions = createSignalActions({ VD, bridge });

    actions.deleteSignalLog(5);
    expect(appDialog().hidden).toBe(false);
    expect(appDialogMessage()).toMatch(/delete this saved detailed signal/i);

    await clickCancel();
    expect(bridge.deleteDetailedSignalLog).not.toHaveBeenCalled();
  });

  it('confirm deletes the evidence row through the bridge with the stringified id', async () => {
    const bridge = { deleteDetailedSignalLog: vi.fn() };
    const actions = createSignalActions({ VD, bridge });

    actions.deleteSignalLog(5);
    await clickConfirm();
    expect(bridge.deleteDetailedSignalLog).toHaveBeenCalledWith('5');
  });
});

describe('actions-signals.ts — successful download path', () => {
  let VD;
  let restoreUrl;
  let restoreRevoke;
  let clickSpy;

  beforeEach(() => {
    document.body.innerHTML = DIALOG_DOM;
    VD = makeVd();
    const originalCreate = Object.getOwnPropertyDescriptor(window.URL, 'createObjectURL');
    const originalRevoke = Object.getOwnPropertyDescriptor(window.URL, 'revokeObjectURL');
    Object.defineProperty(window.URL, 'createObjectURL', {
      configurable: true,
      value: vi.fn(() => 'blob:volt-signal'),
    });
    Object.defineProperty(window.URL, 'revokeObjectURL', { configurable: true, value: vi.fn() });
    restoreUrl = () => {
      if (originalCreate) Object.defineProperty(window.URL, 'createObjectURL', originalCreate);
      else delete window.URL.createObjectURL;
    };
    restoreRevoke = () => {
      if (originalRevoke) Object.defineProperty(window.URL, 'revokeObjectURL', originalRevoke);
      else delete window.URL.revokeObjectURL;
    };
    clickSpy = vi.spyOn(window.HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
  });

  afterEach(() => {
    if (restoreUrl) restoreUrl();
    if (restoreRevoke) restoreRevoke();
    vi.restoreAllMocks();
  });

  it('exportSignalLog() downloads a per-id file and reports an exported status', () => {
    const bridge = { exportDetailedSignalLog: vi.fn(() => '{"ok":true,"item":{"id":5}}') };
    const actions = createSignalActions({ VD, bridge });
    actions.exportSignalLog(5);
    expect(clickSpy).toHaveBeenCalledTimes(1);
    expect(VD.lastStatus()).toMatchObject({
      state: 'ready',
      detail: 'Detailed signal log exported.',
    });
  });
});
