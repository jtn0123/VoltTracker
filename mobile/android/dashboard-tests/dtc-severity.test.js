// M10b — DTC severity / "safe to drive" line. updateDiagnosticCodeUi /
// buildDtcItem (storage-status.ts) renders, for each scanned code, a severity
// pill and a plain-language drivability line. Severity comes from the
// dtc-causes metadata when present, otherwise from a conservative code-family
// heuristic. Rendering stays XSS-safe (textContent / createElement only).
import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

async function loadWithDtcData() {
  document.body.innerHTML = '';
  delete window.VoltDashboard;
  delete window.VoltTrackerNative;
  delete window.VoltTrackerAndroid;
  await loadDashboard();
  // The cause/severity database is lazy-loaded; ensure it's resolved so dtcInfo
  // returns real metadata before we render the scan results.
  await window.VoltDashboard.ensureDtcData();
  return window.VoltDashboard;
}

function dtcItems() {
  return Array.from(document.querySelectorAll('#dtcList .dtc-item:not([data-example])'));
}

describe('DTC severity badge + drivability line', () => {
  let VD;
  beforeEach(async () => {
    VD = await loadWithDtcData();
  });

  it('uses dtc-causes metadata: a critical code reads "Stop safely"', () => {
    VD.setStorage({
      diagnosticCodeCount: 1,
      latestDiagnosticCodes: [{ dtc: 'P0AA6', status: 'stored', firstSeenMs: Date.now(), lastSeenMs: Date.now() }],
    });
    const items = dtcItems();
    expect(items).toHaveLength(1);
    const item = items[0];
    expect(item.dataset.severity).toBe('critical');

    const sev = item.querySelector('.dtc-sev');
    expect(sev).not.toBeNull();
    expect(sev.dataset.severity).toBe('critical');
    expect(sev.textContent).toBe('Critical');

    const drive = item.querySelector('.dtc-drivability');
    expect(drive).not.toBeNull();
    expect(drive.dataset.severity).toBe('critical');
    expect(drive.textContent).toContain('Stop safely');
  });

  it('a warning-severity code reads "Service soon"', () => {
    VD.setStorage({
      diagnosticCodeCount: 1,
      latestDiagnosticCodes: [{ dtc: 'P0420', status: 'pending', firstSeenMs: Date.now(), lastSeenMs: Date.now() }],
    });
    const item = dtcItems()[0];
    expect(item.dataset.severity).toBe('warning');
    expect(item.querySelector('.dtc-sev').textContent).toBe('Warning');
    expect(item.querySelector('.dtc-drivability').textContent).toContain('Service soon');
  });

  it('an info-severity code reads "Safe to drive"', () => {
    // C07B0 is a TPMS code tagged "info" in the database — its severity must win
    // over the chassis-family heuristic that would otherwise call it critical.
    VD.setStorage({
      diagnosticCodeCount: 1,
      latestDiagnosticCodes: [{ dtc: 'C07B0', status: 'stored', firstSeenMs: Date.now(), lastSeenMs: Date.now() }],
    });
    const item = dtcItems()[0];
    expect(item.dataset.severity).toBe('info');
    expect(item.querySelector('.dtc-sev').textContent).toBe('Info');
    expect(item.querySelector('.dtc-drivability').textContent).toContain('Safe to drive');
  });

  it('falls back to a family heuristic when a code is not in the database', () => {
    // ZZZZZ is not a real DTC family, so dtcInfo carries no severity → the
    // heuristic applies. A chassis (C) code maps to critical; a fabricated
    // unknown family falls through to info.
    VD.setStorage({
      diagnosticCodeCount: 2,
      latestDiagnosticCodes: [
        { dtc: 'C9999', status: 'stored', firstSeenMs: Date.now(), lastSeenMs: Date.now() },
        { dtc: 'P3FFF', status: 'stored', firstSeenMs: Date.now(), lastSeenMs: Date.now() },
      ],
    });
    const items = dtcItems();
    expect(items).toHaveLength(2);
    // C-family (chassis) with no DB entry → critical / stop safely.
    expect(items[0].dataset.severity).toBe('critical');
    expect(items[0].querySelector('.dtc-drivability').textContent).toContain('Stop safely');
    // P-family with no DB entry → warning / service soon (powertrain default).
    expect(items[1].dataset.severity).toBe('warning');
    expect(items[1].querySelector('.dtc-drivability').textContent).toContain('Service soon');
  });

  it('renders the drivability line with textContent (no markup injection)', () => {
    VD.setStorage({
      diagnosticCodeCount: 1,
      latestDiagnosticCodes: [{ dtc: 'P0420', status: 'stored', firstSeenMs: Date.now(), lastSeenMs: Date.now() }],
    });
    const drive = dtcItems()[0].querySelector('.dtc-drivability');
    // The whole line is a single text node — no child elements were injected.
    expect(drive.children).toHaveLength(0);
    expect(drive.querySelector('*')).toBeNull();
  });

  // C6 — pin the family heuristic (severity with and without DB metadata) so the
  // doc-comment claim ("P default is warning, not critical; DB metadata always
  // wins") can't silently drift from the implementation again.
  describe('family heuristic, severity-with-and-without-DB-metadata (C6)', () => {
    function severityOf(dtc) {
      VD.setStorage({
        diagnosticCodeCount: 1,
        latestDiagnosticCodes: [{ dtc, status: 'stored', firstSeenMs: Date.now(), lastSeenMs: Date.now() }],
      });
      return dtcItems()[0].dataset.severity;
    }

    it('uses the family default when a code carries NO DB metadata', () => {
      // None of these fabricated codes exist in the dtc-causes database, so the
      // family heuristic decides:
      expect(severityOf('C9999')).toBe('critical'); // C (chassis) → critical
      expect(severityOf('P3FFF')).toBe('warning'); // P (powertrain) → warning (NOT critical)
      expect(severityOf('B9999')).toBe('warning'); // B (body) → warning
      expect(severityOf('U9999')).toBe('warning'); // U (network) → warning
    });

    it('lets DB metadata WIN over the family default in both directions', () => {
      // P0AA6 is a P-family code the family heuristic would call "warning", but
      // it's tagged "critical" in the DB (HV isolation fault) → critical.
      expect(severityOf('P0AA6')).toBe('critical');
      // C07B0 is a C-family code the heuristic would call "critical", but it's
      // tagged "info" (TPMS) in the DB → info. Metadata wins either way.
      expect(severityOf('C07B0')).toBe('info');
    });
  });
});
