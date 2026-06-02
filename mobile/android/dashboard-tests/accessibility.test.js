// Checks the generated dashboard shell for basic accessibility invariants that
// are easy to regress when editing partials.
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';
import { JSDOM } from 'jsdom';

const HERE = dirname(fileURLToPath(import.meta.url));
const DASHBOARD_HTML = resolve(HERE, '../app/src/main/assets/dashboard/index.html');

function loadDocument() {
  return new JSDOM(readFileSync(DASHBOARD_HTML, 'utf8')).window.document;
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
      'trips',
      'map',
      'charge',
      'insights',
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

  it('gives the trip lists list semantics', () => {
    const document = loadDocument();
    expect(document.querySelector('#realTripsList')?.getAttribute('role')).toBe('list');
  });

  it('gives every button an accessible name', () => {
    const document = loadDocument();
    const unnamed = [...document.querySelectorAll('button')]
      .filter((button) => accessibleName(button).length === 0)
      .map((button) => button.id || button.outerHTML.slice(0, 80));
    expect(unnamed).toEqual([]);
  });
});
