// Regression guard for the DTC list entrance cascade (storage-status.ts).
// updateDiagnosticCodeUi prefixes its memo signature with raw:/db: depending on
// whether the lazy dtc-causes DB is loaded. On a fresh scan the codes render
// first with raw:, then the DB resolves and the signature flips to db:, forcing
// a rebuild. That rebuild used to re-add the .dtc-item-enter class to every row,
// so already-visible rows flashed out to opacity 0 and cascaded in a SECOND
// time. The fix only animates the first empty->populated render.
import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

describe('DTC list entrance animation gating', () => {
  let VD;
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
    VD = window.VoltDashboard;
    // Freeze the lazy causes-DB auto-load so this test drives the raw->db
    // transition by hand instead of racing the async chunk load.
    VD.ensureDtcData = () => new Promise(() => {});
  });

  const rows = () => Array.from(document.querySelectorAll('#dtcList .dtc-item'));

  it('cascades on first population but not on the causes-DB signature rebuild', () => {
    // Raw pass — dtcInfo not yet available.
    expect(typeof VD.dtcInfo).not.toBe('function');
    VD.setStorage({
      diagnosticCodeCount: 2,
      latestDiagnosticCodes: [
        { dtc: 'P0420', status: 'stored', firstSeenMs: 1000, lastSeenMs: 2000 },
        { dtc: 'P0AA6', status: 'pending', firstSeenMs: 1000, lastSeenMs: 2000 },
      ],
    });
    const firstRows = rows();
    expect(firstRows.length).toBe(2);
    // The first empty->populated render animates.
    expect(firstRows.every((r) => r.classList.contains('dtc-item-enter'))).toBe(true);

    // DB resolves: dtcInfo becomes a function, the signature flips raw:->db:, and
    // the rows rebuild. They must NOT replay the entrance cascade.
    VD.dtcInfo = () => ({ severity: 'warning', description: 'x', causes: [] });
    VD.updateDiagnosticCodeUi();

    const secondRows = rows();
    expect(secondRows.length).toBe(2);
    expect(secondRows.some((r) => r.classList.contains('dtc-item-enter'))).toBe(false);
    expect(secondRows.every((r) => !r.style.animationDelay)).toBe(true);
  });
});
