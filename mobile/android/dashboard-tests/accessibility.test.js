// Checks the generated dashboard shell for basic accessibility invariants that
// are easy to regress when editing partials.
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { beforeEach, describe, expect, it } from 'vitest';
import { JSDOM } from 'jsdom';

const HERE = dirname(fileURLToPath(import.meta.url));
const DASHBOARD_HTML = resolve(HERE, '../app/src/main/assets/dashboard/index.html');
const DRIVE_PARTIAL_HTML = resolve(HERE, '../app/src/main/dashboard-src/partials/drive.html');

function loadDocument() {
  return new JSDOM(readFileSync(DASHBOARD_HTML, 'utf8')).window.document;
}

// The generated index.html is assembled by the Gradle generateDashboardHtml
// task, which the Vitest suite cannot run — so structural invariants that were
// just changed in a partial are asserted against the partial SOURCE here (the
// generated shell test above keeps covering the assembled output).
function loadDrivePartial() {
  return new JSDOM(readFileSync(DRIVE_PARTIAL_HTML, 'utf8')).window.document;
}

function accessibleName(node) {
  return (node.getAttribute('aria-label') || node.textContent || '').trim();
}

describe('generated dashboard accessibility shell', () => {
  it('does not ship duplicate element ids', () => {
    const document = loadDocument();
    const seen = new Set();
    const duplicates = new Set();
    document.querySelectorAll('[id]').forEach((node) => {
      const id = node.id;
      if (seen.has(id)) duplicates.add(id);
      seen.add(id);
    });
    expect([...duplicates]).toEqual([]);
  });

  it('marks exactly one bottom-nav destination as current', () => {
    const document = loadDocument();
    const navButtons = [...document.querySelectorAll('nav.bottom-nav [data-nav]')];
    expect(navButtons.map((button) => button.dataset.nav)).toEqual([
      'drive',
      'map',
      'charge',
      'insights',
      'diagnostics',
      'settings',
    ]);
    expect(navButtons.every((button) => accessibleName(button).length > 0)).toBe(true);
    expect(navButtons.filter((button) => button.getAttribute('aria-current') === 'page')).toHaveLength(1);
  });

  it('keeps live vehicle readouts in polite live regions', () => {
    const document = loadDocument();
    const liveRegions = [...document.querySelectorAll('[aria-live="polite"]')];
    expect(liveRegions.length).toBeGreaterThanOrEqual(2);
    expect(liveRegions.some((node) => node.querySelector('#speedValue'))).toBe(true);
    expect(liveRegions.some((node) => node.querySelector('#driveSocValue'))).toBe(true);
  });

  it('announces drive readings as atomic per-cluster live regions, not card-wide fragments', () => {
    // Card-wide aria-live="polite" aria-atomic="false" made screen readers
    // stutter every changed text node separately ("45" … "MPH" … "72 km/h").
    // The live regions now sit on the small reading clusters with
    // aria-atomic="true" so each update announces as one coherent phrase.
    const document = loadDrivePartial();

    const speedRow = document.querySelector('.speed-row');
    expect(speedRow.getAttribute('aria-live')).toBe('polite');
    expect(speedRow.getAttribute('aria-atomic')).toBe('true');
    expect(speedRow.querySelector('#speedValue')).not.toBeNull();

    const powerBlock = document.querySelector('.power-block');
    expect(powerBlock.getAttribute('aria-live')).toBe('polite');
    expect(powerBlock.getAttribute('aria-atomic')).toBe('true');
    expect(powerBlock.querySelector('#powerValue')).not.toBeNull();

    const batteryHead = document.querySelector('.battery-head');
    expect(batteryHead.getAttribute('aria-live')).toBe('polite');
    expect(batteryHead.getAttribute('aria-atomic')).toBe('true');
    expect(batteryHead.querySelector('#driveSocValue')).not.toBeNull();

    // The wrapping cards must NOT be live regions anymore — that was the
    // stutter source (every tile inside re-announced independently).
    expect(document.querySelector('.live-card').hasAttribute('aria-live')).toBe(false);
    expect(document.querySelector('.battery-strip').hasAttribute('aria-live')).toBe(false);
  });


  it('announces the map empty-state cover as a polite live region', () => {
    // #mapEmpty is toggled on by map.ts when there is no route to draw. Marking
    // it role="status" aria-live="polite" means screen readers hear "No GPS
    // route yet…" when it appears, instead of the cover swapping in silently.
    const document = loadDocument();
    const mapEmpty = document.getElementById('mapEmpty');
    expect(mapEmpty).not.toBeNull();
    expect(mapEmpty.getAttribute('role')).toBe('status');
    expect(mapEmpty.getAttribute('aria-live')).toBe('polite');
  });

  it('gives every button an accessible name', () => {
    const document = loadDocument();
    const unnamed = [...document.querySelectorAll('button')]
      .filter((button) => accessibleName(button).length === 0)
      .map((button) => button.id || button.outerHTML.slice(0, 80));
    expect(unnamed).toEqual([]);
  });
});

// Behavioral keyboard/focus checks (the structural sweeps above can't catch a
// broken focus trap). Exercises the real app-dialog module — the dashboard's
// only modal with scripted focus management — in the live jsdom document:
// initial focus placement, Tab/Shift+Tab wrapping, Escape dismissal, focus
// restoration to the opener, and background inerting. Contrast checking is
// deliberately out of scope for jsdom.
describe('app-dialog keyboard navigation and focus management', () => {
  let confirmAppDialog;

  // Markup mirrors partials/app-dialog.html: every id nodes() dereferences,
  // plus the background nodes setBackgroundInert() toggles and an opener
  // button to restore focus to.
  const DIALOG_DOM = `
    <main class="app"><button id="opener" type="button">Open</button></main>
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

  beforeEach(async () => {
    ({ confirmAppDialog } = await import('../app/src/main/dashboard-src/js/app-dialog.ts'));
    document.body.innerHTML = DIALOG_DOM;
  });

  function pressKey(key, options = {}) {
    document.activeElement.dispatchEvent(
      new window.KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...options }),
    );
  }

  async function flushFrame() {
    await new Promise((resolve) => window.requestAnimationFrame(resolve));
  }

  it('moves focus into the dialog and inerts the background', async () => {
    const opener = document.getElementById('opener');
    opener.focus();

    const promise = confirmAppDialog({ title: 'T', message: 'M' });
    await flushFrame();

    expect(document.activeElement).toBe(document.getElementById('appDialogConfirm'));
    expect(document.querySelector('.app').getAttribute('aria-hidden')).toBe('true');
    expect(document.querySelector('.bottom-nav').getAttribute('aria-hidden')).toBe('true');

    document.getElementById('appDialogConfirm').click();
    await expect(promise).resolves.toBe(true);
  });

  it('traps Tab and Shift+Tab inside the open dialog', async () => {
    const promise = confirmAppDialog({ title: 'T', message: 'M' });
    await flushFrame();

    // Focusable order in the dialog: close, cancel, confirm (input is hidden
    // in confirm mode). Tab from the LAST target wraps to the first…
    document.getElementById('appDialogConfirm').focus();
    pressKey('Tab');
    expect(document.activeElement).toBe(document.getElementById('appDialogClose'));

    // …and Shift+Tab from the FIRST wraps back to the last.
    pressKey('Tab', { shiftKey: true });
    expect(document.activeElement).toBe(document.getElementById('appDialogConfirm'));

    document.getElementById('appDialogCancel').click();
    await expect(promise).resolves.toBe(false);
  });

  it('Escape cancels the dialog and restores focus to the opener', async () => {
    const opener = document.getElementById('opener');
    opener.focus();

    const promise = confirmAppDialog({ title: 'T', message: 'M' });
    await flushFrame();
    expect(document.activeElement).not.toBe(opener);

    pressKey('Escape');

    await expect(promise).resolves.toBe(false);
    expect(document.getElementById('appDialog').hidden).toBe(true);
    expect(document.activeElement).toBe(opener);
    // Background nodes are interactive again.
    expect(document.querySelector('.app').hasAttribute('aria-hidden')).toBe(false);
  });
});
