// Selector / partial contract (F3).
//
// The dashboard wires behavior to markup by element id: TypeScript reads nodes
// with `el("someId")` / `document.getElementById("someId")`, and the ids live in
// the HTML partials that Gradle assembles into index.html. Nothing enforced that
// the two halves agree, so a renamed/removed id (or a partial edit that drops a
// node) would only surface as a silent no-op at runtime on a device.
//
// This test pins the contract: every STATIC string-literal id the TS sources read
// must exist as an `id="…"` in the generated index.html. Ids that are created at
// runtime and then read back (no partial owns them) are explicitly allowlisted so
// the contract stays meaningful instead of being weakened to a soft check.
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const HERE = dirname(fileURLToPath(import.meta.url));
const JS_DIR = resolve(HERE, '../app/src/main/dashboard-src/js');
const INDEX_HTML = resolve(HERE, '../app/src/main/assets/dashboard/index.html');

// Ids created in JS (document.createElement + .id = …) and then read back via
// el()/getElementById — no partial declares them, so they are not part of the
// HTML contract. Keep this list tight and documented; an entry here is a promise
// that the id is produced at runtime, not by a partial.
const RUNTIME_CREATED_IDS = new Set([
  // (none today — every el()/getElementById literal resolves to a partial node)
]);

// Recursively collect every .ts source under JS_DIR so the contract keeps
// holding if selector files are later organized into subfolders.
function readJsSources(dir = JS_DIR) {
  const out = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = resolve(dir, entry.name);
    if (entry.isDirectory()) {
      out.push(...readJsSources(full));
    } else if (entry.name.endsWith('.ts')) {
      out.push({ name: relative(JS_DIR, full), text: readFileSync(full, 'utf8') });
    }
  }
  return out;
}

function referencedIds(text) {
  const ids = new Set();
  // Match both quote styles: el("id") / el('id') / getElementById('id'). The
  // backreference \1 keeps the closing quote matched to the opening one.
  const re = /(?:\bel|\bgetElementById)\(\s*(['"])([A-Za-z][\w-]*)\1\s*\)/g;
  for (const match of text.matchAll(re)) ids.add(match[2]);
  return ids;
}

function declaredIds(html) {
  const ids = new Set();
  for (const match of html.matchAll(/\bid="([^"]+)"/g)) ids.add(match[1]);
  return ids;
}

describe('selector / partial contract', () => {
  const html = readFileSync(INDEX_HTML, 'utf8');
  const declared = declaredIds(html);
  const sources = readJsSources();

  it('every static id read by the TS exists in the generated index.html', () => {
    const missing = [];
    for (const { name, text } of sources) {
      for (const id of referencedIds(text)) {
        if (declared.has(id) || RUNTIME_CREATED_IDS.has(id)) continue;
        missing.push(`${name}: el("${id}") has no #${id} in index.html`);
      }
    }
    expect(missing).toEqual([]);
  });

  it('the recovery panel + drive source badge contract ids are present', () => {
    // The phone UX surfaces depend on these exact ids; pin them explicitly so a
    // partial rename can't silently break the recovery panel / provenance badge.
    const required = [
      'diagRecovery',
      'diagRecoveryKicker',
      'diagRecoveryTitle',
      'diagRecoveryNext',
      'diagRecoveryAdapter',
      'diagRecoveryBt',
      'diagRecoverySource',
      'diagRecoveryPill',
      'diagRecoveryAction',
      'driveSourceBadge',
      'driveSourceLabel',
      'driveSourceSub',
    ];
    const absent = required.filter((id) => !declared.has(id));
    expect(absent).toEqual([]);
  });

  it('keeps the runtime-created allowlist honest (no stale entries)', () => {
    // An allowlist entry that is ALSO declared in a partial is dead weight and
    // hides a real contract; flag it so the list can't rot.
    const stale = [...RUNTIME_CREATED_IDS].filter((id) => declared.has(id));
    expect(stale).toEqual([]);
  });
});
