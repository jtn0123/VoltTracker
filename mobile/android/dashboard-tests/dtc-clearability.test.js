// M10 — permanent-DTC clearability guidance. Two surfaces:
//   1. buildDtcItem (storage-status.ts) renders a per-code "permanent — won't
//      clear" / "clearable" tag derived from code.status.
//   2. openClearDtcWarning (actions.ts) adds a "N permanent code(s) cannot be
//      cleared" line to the Mode 04 clear dialog when permanent codes exist.
// Both stay XSS-safe (textContent / createElement only).
import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

async function loadWithDtcData() {
  document.body.innerHTML = '';
  delete window.VoltDashboard;
  delete window.VoltTrackerNative;
  delete window.VoltTrackerAndroid;
  // jsdom has no scrollIntoView; openClearDtcWarning calls it after unhiding the
  // dialog. A no-op shim lets the real open path (focus trap included) run.
  if (typeof Element.prototype.scrollIntoView !== 'function') {
    Element.prototype.scrollIntoView = () => {};
  }
  await loadDashboard();
  await window.VoltDashboard.ensureDtcData();
  return window.VoltDashboard;
}

function dtcItems() {
  return Array.from(document.querySelectorAll('#dtcList .dtc-item:not([data-example])'));
}

describe('DTC clearability tag (M10)', () => {
  let VD;
  beforeEach(async () => {
    VD = await loadWithDtcData();
  });

  it('tags a permanent code as "permanent — won\'t clear"', () => {
    VD.setStorage({
      diagnosticCodeCount: 1,
      latestDiagnosticCodes: [{ dtc: 'P0420', status: 'permanent', firstSeenMs: Date.now(), lastSeenMs: Date.now() }],
    });
    const tag = dtcItems()[0].querySelector('.dtc-clearable');
    expect(tag).not.toBeNull();
    expect(tag.dataset.clearable).toBe('no');
    expect(tag.textContent).toContain("won't clear");
  });

  it('tags every other status as "clearable"', () => {
    VD.setStorage({
      diagnosticCodeCount: 3,
      latestDiagnosticCodes: [
        { dtc: 'P0420', status: 'stored', firstSeenMs: Date.now(), lastSeenMs: Date.now() },
        { dtc: 'P0171', status: 'pending', firstSeenMs: Date.now(), lastSeenMs: Date.now() },
        { dtc: 'P0300', status: 'current', firstSeenMs: Date.now(), lastSeenMs: Date.now() },
      ],
    });
    const tags = dtcItems().map((item) => item.querySelector('.dtc-clearable'));
    expect(tags).toHaveLength(3);
    tags.forEach((tag) => {
      expect(tag.dataset.clearable).toBe('yes');
      expect(tag.textContent).toBe('clearable');
    });
  });

  it('renders the tag with textContent only (no markup injection)', () => {
    VD.setStorage({
      diagnosticCodeCount: 1,
      latestDiagnosticCodes: [{ dtc: 'P0420', status: 'permanent', firstSeenMs: Date.now(), lastSeenMs: Date.now() }],
    });
    const tag = dtcItems()[0].querySelector('.dtc-clearable');
    expect(tag.children).toHaveLength(0);
    expect(tag.querySelector('*')).toBeNull();
  });
});

describe('clear-dialog permanent-code caveat (M10)', () => {
  let VD;
  beforeEach(async () => {
    VD = await loadWithDtcData();
  });

  it('adds a permanent-count line when permanent codes exist', () => {
    VD.setStorage({
      diagnosticCodeCount: 2,
      diagnosticCodeStatusCounts: { permanent: 2 },
      latestDiagnosticCodes: [
        { dtc: 'P0420', status: 'permanent', firstSeenMs: Date.now(), lastSeenMs: Date.now() },
        { dtc: 'P0171', status: 'permanent', firstSeenMs: Date.now(), lastSeenMs: Date.now() },
      ],
    });
    VD.handleAction('openClearDtc');
    const note = document.getElementById('dtcClearPermanentNote');
    expect(note.hidden).toBe(false);
    expect(note.textContent).toContain('2 permanent codes cannot be cleared');
  });

  it('singularizes the line for a single permanent code', () => {
    VD.setStorage({
      diagnosticCodeCount: 1,
      diagnosticCodeStatusCounts: { permanent: 1 },
      latestDiagnosticCodes: [{ dtc: 'P0420', status: 'permanent', firstSeenMs: Date.now(), lastSeenMs: Date.now() }],
    });
    VD.handleAction('openClearDtc');
    const note = document.getElementById('dtcClearPermanentNote');
    expect(note.hidden).toBe(false);
    expect(note.textContent).toContain('1 permanent code cannot be cleared');
  });

  it('keeps the line hidden when there are no permanent codes', () => {
    VD.setStorage({
      diagnosticCodeCount: 1,
      diagnosticCodeStatusCounts: { stored: 1 },
      latestDiagnosticCodes: [{ dtc: 'P0420', status: 'stored', firstSeenMs: Date.now(), lastSeenMs: Date.now() }],
    });
    VD.handleAction('openClearDtc');
    const note = document.getElementById('dtcClearPermanentNote');
    expect(note.hidden).toBe(true);
    expect(note.textContent).toBe('');
  });

  it('falls back to counting code rows when the status-count map is absent', () => {
    VD.setStorage({
      diagnosticCodeCount: 2,
      latestDiagnosticCodes: [
        { dtc: 'P0420', status: 'permanent', firstSeenMs: Date.now(), lastSeenMs: Date.now() },
        { dtc: 'P0171', status: 'stored', firstSeenMs: Date.now(), lastSeenMs: Date.now() },
      ],
    });
    VD.handleAction('openClearDtc');
    const note = document.getElementById('dtcClearPermanentNote');
    expect(note.hidden).toBe(false);
    expect(note.textContent).toContain('1 permanent code cannot be cleared');
  });
});
