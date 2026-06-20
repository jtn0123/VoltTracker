// Structural invariants for the redesigned Diagnostics tab. The tab was
// reorganised into four labelled .diag-section bands so the page reads top-to-
// bottom (live signals -> enhanced probes -> session review & logs ->
// developer tools/sandbox) with at most one emphasised primary action per band.
//
// The generated index.html is assembled by the Gradle generateDashboardHtml
// task, which the Vitest suite cannot run — so these just-changed structural
// invariants are asserted against the partial SOURCE (mirrors the
// loadDrivePartial pattern in accessibility.test.js).
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';
import { JSDOM } from 'jsdom';

const HERE = dirname(fileURLToPath(import.meta.url));
const DIAGNOSTICS_PARTIAL_HTML = resolve(
  HERE,
  '../app/src/main/dashboard-src/partials/diagnostics.html',
);

function loadDiagnosticsPartial() {
  return new JSDOM(readFileSync(DIAGNOSTICS_PARTIAL_HTML, 'utf8')).window.document;
}

describe('diagnostics tab structure', () => {
  it('groups content into exactly four labelled diag-sections', () => {
    const document = loadDiagnosticsPartial();
    const sections = document.querySelectorAll('.diag-section');
    expect(sections).toHaveLength(4);
    sections.forEach((section) => {
      expect(section.querySelector('.diag-section__head')).not.toBeNull();
    });
  });

  it('keeps at most one emphasised primary action per diag-section', () => {
    // Two competing .primary-action buttons in one band fight for the eye and
    // dilute the "do this next" affordance; each band gets at most one.
    const document = loadDiagnosticsPartial();
    document.querySelectorAll('.diag-section').forEach((section) => {
      const primaries = section.querySelectorAll('.primary-action');
      expect(primaries.length).toBeLessThanOrEqual(1);
    });
  });

  it('places the preview sandbox after the live-signals card in reading order', () => {
    // Live signals are the everyday read; the demo/preview sandbox is debug-only
    // tooling, so it must follow the live card in document order, not precede it.
    const document = loadDiagnosticsPartial();
    const liveCard = document.getElementById('liveSignalsCard');
    const sandbox = document.querySelector('.sandbox-tools');
    expect(liveCard).not.toBeNull();
    expect(sandbox).not.toBeNull();
    expect(
      liveCard.compareDocumentPosition(sandbox) & document.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it('preserves every critical diagnostics id through the regroup', () => {
    const document = loadDiagnosticsPartial();
    for (const id of [
      'liveSignalsCard',
      'enhancedCard',
      'signalStageBar',
      'demoScenarioPicker',
      'detailProbeBtn',
      'rawFrames',
    ]) {
      expect(document.getElementById(id), `missing #${id}`).not.toBeNull();
    }
  });
});
