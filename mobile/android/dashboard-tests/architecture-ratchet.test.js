// architecture-ratchet.test.js — direction guard for the dashboard's structural coupling.
//
// WHY THIS EXISTS
//
// The dashboard's recurring "fix one thing, break another" bugs are not type bugs — the
// TypeScript config is already strict (strict + noUncheckedIndexedAccess +
// exactOptionalPropertyTypes) and the values involved are all correctly typed. They are
// SHARED-STATE bugs: the same fact is stored in more than one place, and nothing forces the
// copies to agree. Types cannot see that class of defect, because both copies type-check.
//
// This file measures the three structural couplings that produce it and pins their DIRECTION,
// exactly like coverage-ratchet.test.js pins coverage floors. It does not fix anything. It
// stops the hole getting deeper while it is being filled in:
//
//   1. DIRECT STATE WRITES — `state.foo = …` outside the setState() seam. Every direct write
//      is a mutation that no other module can observe, log, or react to.
//   2. EAGER->EAGER VD CALLS — modules inside the SAME bundle calling each other through the
//      `window.VoltDashboard` global instead of a typed ESM import. vd-registry.ts already
//      states this rule ("Inside one bundle, modules must NOT call each other through this
//      object"); this counts the places that don't follow it. Cross-CHUNK calls are excluded:
//      lazy chunks are separate esbuild builds where ESM imports genuinely cannot reach, so
//      those calls are correct by design and are not counted.
//   3. DATASET-AS-STATE READS — `el.dataset.foo` reads that pull application state back out
//      of the DOM, making the DOM a third source of truth alongside `state` and module locals.
//   4. ALIAS NESTED WRITES — `const s = state.storage; s.field = …`. `Readonly<DashboardState>`
//      is shallow, so an alias bound off a state field is still writable and slips past both
//      the compiler and metric 1. This catches that bypass; the fix is to replace the bag via
//      setState({ storage: { ...s, field } }) instead of mutating it in place.
//   5. MANUAL RENDER CALLS — direct calls to renderers that are registered with the shared
//      render pass (Phase 2). Those renderers are driven by the pass now; hand-calling one
//      re-creates the "remember to repaint" cluster the pass removed, and double-paints
//      whatever the pass is already about to draw. Change state, or flushRender().
//
// HOW TO WORK WITH IT
//
//   * Improved a number? LOWER the matching baseline in the same commit. The slack check below
//     will tell you the new value.
//   * Need to raise one? Don't. Raising a baseline means the regression class this file exists
//     to contain just got bigger — say why in the PR description if it is genuinely justified.
//
// The metrics are computed from source text (not the type checker) so this stays a fast, plain
// *.test.js in the normal `npm test` sweep with no build step.
import { readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

const JS_DIR = resolve('../app/src/main/dashboard-src/js');
const BUILD_SCRIPT = resolve('./build.mjs');

// ---------------------------------------------------------------------------
// Committed baselines. LOWER as the codebase improves; never raise silently.
//
// 2026-07-24 (Phase 0): recorded at 120 / 58 / 183 — the state of the tree when
//   this guard was introduced. (120 counted writes outside core.ts, which owned the
//   seam; the raw grep across every file was 128.)
// 2026-07-24 (Phase 1): directStateWrites 120 -> 0. `state` is now exported as
//   Readonly<DashboardState> (core.ts) and declared readonly on the VD global
//   (dashboard-globals.d.ts), so setState() is the only writer and the compiler
//   enforces it. This metric is now a backstop against the readonly view being
//   widened again rather than the primary guard.
// 2026-07-24 (Phase 2): added manualRenderCalls (0) — direct calls to pass-registered
//   renderers — alongside aliasNestedWrites (0) from the alias-bypass sweep.
// 2026-07-25 (Phase 3): datasetStateReads 183 -> 21. Not an improvement, a CORRECTION.
//   The old matcher's `\w+` backtracked, so `el.dataset.on = "x"` matched `.dataset.o` and
//   every WRITE was counted as a read; the true read count was 75. Of those 75, 54 are
//   markup config or event-delegation identifiers (see DELEGATION_AND_CONFIG_KEYS) and are
//   excluded as legitimate. 21 is the real DOM-as-state-store residue — and unlike the old
//   number, it can actually reach 0. Also excludes `delete el.dataset.x`, which is a write
//   with no `=` after it and was inflating the count by a further 6.
// 2026-07-25 (Phase 3): datasetStateReads 21 -> 15. `body[data-active-view]` is no longer
//   read back as the current view — that fact is `state.view`. The attribute is still
//   WRITTEN by setView for CSS to hook; the DOM stays an output, just not an input.
// ---------------------------------------------------------------------------
const BASELINE = Object.freeze({
  directStateWrites: 0,
  eagerToEagerVdCalls: 58,
  datasetStateReads: 15,
  aliasNestedWrites: 0,
  manualRenderCalls: 0,
});

// How far below baseline a metric may drift before this test asks you to lower the
// baseline. Keeps the ratchet converging instead of quietly going slack, without
// nagging over a one-site change.
const SLACK = Object.freeze({
  directStateWrites: 5,
  eagerToEagerVdCalls: 10,
  datasetStateReads: 6,
  aliasNestedWrites: 5,
  manualRenderCalls: 3,
});

// No file is exempt. core.ts owns the bag, but it writes through the PRIVATE `mutableState`
// binding rather than the exported readonly `state` view, so it needs no allowance here —
// and a `state.x =` appearing in core.ts would be a real regression, not the seam.
const ALLOWED_DIRECT_STATE_WRITE_FILES = new Set();

// vd-registry.ts rule (c): symbols deliberately re-wrapped at runtime, which every caller must
// reach late-bound through VD even inside one bundle. Calling these through VD is CORRECT.
const LATE_BOUND_MEMBERS = new Set([
  'setStatus',
  'updateTelemetry',
  'pendingLazyLoads',
  'dtcSearchUrl',
  'state',
]);

function sourceFiles() {
  return readdirSync(JS_DIR)
    .filter((name) => name.endsWith('.ts'))
    .map((name) => ({ name, text: readFileSync(resolve(JS_DIR, name), 'utf8') }));
}

// Strip line comments and block comments so commented-out code and prose that happens to
// mention `state.foo =` or `VD.bar` never counts against a metric.
function stripComments(text) {
  return text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^[^\n"'`]*?\/\/.*$/gm, (line) => {
    const idx = line.indexOf('//');
    return idx === -1 ? line : line.slice(0, idx);
  });
}

// Read the EAGER / LAZY module lists out of build.mjs rather than duplicating them, so a
// module moving between the eager bundle and a lazy chunk is picked up automatically. A
// rename of either binding fails loudly here instead of passing vacuously.
function bundleLists() {
  const src = readFileSync(BUILD_SCRIPT, 'utf8');
  const listFor = (binding) => {
    const anchor = src.indexOf(`const ${binding} = [`);
    if (anchor === -1) {
      throw new Error(`architecture-ratchet: could not find \`const ${binding} = [\` in build.mjs`);
    }
    const open = src.indexOf('[', anchor);
    const close = src.indexOf(']', open);
    if (close === -1) throw new Error(`architecture-ratchet: unterminated ${binding} array`);
    return [...src.slice(open + 1, close).matchAll(/"([^"]+)"/g)].map((m) => m[1]);
  };
  const eager = listFor('EAGER');
  const lazy = listFor('LAZY');
  if (eager.length === 0 || lazy.length === 0) {
    throw new Error('architecture-ratchet: EAGER/LAZY parsed empty — build.mjs shape changed');
  }
  return { eager, lazy };
}

// --- metric 1: direct `state.x = …` writes outside the seam -------------------------------
// Covers every mutating form, not just `=`: compound assignment (`+=`, `|=`, …), logical and
// nullish assignment (`||=`, `&&=`, `??=`) and the update operators (`++`, `--`). The
// `(?!=)` guard keeps `==`/`===` out, and `<=`/`>=`/`!==` never reach an alternative.
function directStateWrites(files) {
  const re = /\bstate\.\w+\s*(?:[-+*/|&]?=(?!=)|(?:\|\||&&|\?\?)=|\+\+|--)/g;
  const perFile = [];
  let total = 0;
  for (const file of files) {
    if (ALLOWED_DIRECT_STATE_WRITE_FILES.has(file.name)) continue;
    const count = (stripComments(file.text).match(re) || []).length;
    if (count > 0) perFile.push(`${file.name}:${count}`);
    total += count;
  }
  return { total, perFile };
}

// --- metric 2: eager-bundle modules calling each other through VD -------------------------
function vdMemberOwners(files) {
  const owners = new Map();
  for (const { name, text } of files) {
    const mod = name.replace(/\.ts$/, '');
    const src = stripComments(text);
    for (const m of src.matchAll(/\bVD\.(\w+)\s*=(?!=)/g)) owners.set(m[1], mod);
    // Object.assign(VD, { … }) — brace-match the literal, then take its top-level keys.
    for (const m of src.matchAll(/Object\.assign\(VD,\s*\{/g)) {
      const open = m.index + m[0].length - 1;
      let depth = 0;
      let end = -1;
      for (let i = open; i < src.length; i += 1) {
        if (src[i] === '{') depth += 1;
        else if (src[i] === '}') {
          depth -= 1;
          if (depth === 0) {
            end = i;
            break;
          }
        }
      }
      if (end === -1) continue;
      const body = src.slice(open + 1, end);
      let nested = 0;
      for (const km of body.matchAll(/([{}])|(?:^|,)\s*(\w+)\s*[:,}]/gm)) {
        if (km[1] === '{') nested += 1;
        else if (km[1] === '}') nested -= 1;
        else if (km[2] && nested === 0) owners.set(km[2], mod);
      }
    }
  }
  return owners;
}

function eagerToEagerVdCalls(files, eager) {
  const owners = vdMemberOwners(files);
  const eagerSet = new Set(eager);
  const perPair = new Map();
  let total = 0;
  for (const { name, text } of files) {
    const mod = name.replace(/\.ts$/, '');
    if (!eagerSet.has(mod)) continue;
    for (const line of stripComments(text).split('\n')) {
      // The registration line itself (`VD.foo = foo`) is not a call.
      if (/\bVD\.\w+\s*=(?!=)/.test(line)) continue;
      for (const m of line.matchAll(/\bVD\.(\w+)/g)) {
        const member = m[1];
        if (LATE_BOUND_MEMBERS.has(member)) continue;
        const owner = owners.get(member);
        // Unknown owner (external ABI / harness-only surface) and self-registration: skip.
        if (!owner || owner === mod) continue;
        // Cross-chunk call: required, because ESM imports cannot cross an esbuild boundary.
        if (!eagerSet.has(owner)) continue;
        total += 1;
        const key = `${mod} -> ${owner}`;
        perPair.set(key, (perPair.get(key) || 0) + 1);
      }
    }
  }
  return { total, perPair };
}

// --- metric 3: application state read back out of the DOM ---------------------------------
//
// NOT every `.dataset.foo` read is a defect, and an undifferentiated count could never reach
// zero — which would make it a number nobody could act on. Two kinds are legitimate and are
// allowlisted below:
//
//   * MARKUP CONFIG — the value only ever comes from a partial (JS never writes it). Reading
//     it is reading declarative configuration, not state.
//   * EVENT DELEGATION — a render stamps a row's identity onto its own button
//     (`data-trip-detail="<id>"`), and the click handler reads it back to learn WHICH row was
//     acted on. The DOM is the input device here; the value is an identifier being passed
//     along, not a fact being stored.
//
// What is left is the real class: a boolean, mode or guard flag written to an element and
// later read back AS THE TRUTH — a second copy of something that belongs in `state`. Those
// are what this metric counts, and the target is 0.
//
// Adding a key here is a claim that it is config or an identifier. Make that claim explicitly
// in review; do not add a key just to make the number go down.
const DELEGATION_AND_CONFIG_KEYS = new Set([
  // Markup config — present in the partials, never assigned from JS.
  'mapSort', 'scenario', 'nav', 'mapLayer', 'liveSignalFilter', 'view', 'diagnosticsMode',
  'chargeTarget', 'chargeTargetInput', 'signalFilter', 'signalStage', 'tileKey',
  // Event-delegation identifiers — stamped during render, read back in the handler to
  // identify the target row/section/action.
  'mapSession', 'tripDetail', 'routeKey', 'maintDelete', 'tripNotTrip', 'tripExport',
  'tripExportKey', 'tripRename', 'tripFavorite', 'dtcSearch', 'signalExport', 'signalDelete',
  'historyIndex', 'name', 'settingsTarget', 'settingsFocus', 'navJump', 'action',
  'drivePreset', 'level',
]);

function datasetStateReads(files) {
  // `.dataset.foo` NOT followed by `=`. The trailing-char guard is load-bearing: a bare
  // `\w+` backtracks, so `el.dataset.on = "true"` would match `.dataset.o` and a WRITE would
  // be counted as a read. (That bug is why this metric's first baseline read 183 against an
  // actual 75.)
  // `delete el.dataset.foo` clears the attribute — a write in read's clothing, since nothing
  // follows it with `=`. Stripped before matching so it can't inflate the count.
  const DELETE_FORM = /delete\s+[\w.()[\]"'\s]*\.dataset\.[A-Za-z0-9_$]+/g;
  const re = /\.dataset\.([A-Za-z0-9_$]+)(?![A-Za-z0-9_$])(?!\s*=(?!=))/g;
  const perFile = [];
  const perKey = new Map();
  let total = 0;
  for (const file of files) {
    let count = 0;
    for (const m of stripComments(file.text).replace(DELETE_FORM, '').matchAll(re)) {
      if (DELEGATION_AND_CONFIG_KEYS.has(m[1])) continue;
      count += 1;
      perKey.set(m[1], (perKey.get(m[1]) || 0) + 1);
    }
    if (count > 0) perFile.push(`${file.name}:${count}`);
    total += count;
  }
  return { total, perFile, perKey };
}

// --- metric 4: writes through an alias bound off a state field ----------------------------
// Two passes per file: bind `const <alias> = state.<field>`, then look for a mutating write
// on `<alias>.<something>`. Deliberately shallow (one hop) — that is where the real bypasses
// live, and a deeper chase would produce false positives on unrelated locals.
function aliasNestedWrites(files) {
  const WRITE = String.raw`\s*(?:[-+*/|&]?=(?!=)|(?:\|\||&&|\?\?)=|\+\+|--)`;
  const perFile = [];
  let total = 0;
  for (const { name, text } of files) {
    const src = stripComments(text);
    const aliases = new Set();
    for (const m of src.matchAll(/\b(?:const|let|var)\s+(\w+)\s*=\s*state\.\w+/g)) aliases.add(m[1]);
    if (!aliases.size) continue;
    let count = 0;
    for (const alias of aliases) {
      count += (src.match(new RegExp(String.raw`\b${alias}\.\w+${WRITE}`, 'g')) || []).length;
    }
    if (count > 0) perFile.push(`${name}:${count}`);
    total += count;
  }
  return { total, perFile };
}

// --- metric 5: hand-calling a renderer the pass already drives -----------------------------
// The registered names are read out of the source (registerRenderer("name", fn, …)) so this
// tracks whatever is currently on the pass rather than a copy that can go stale. Registration
// lines and the function declarations themselves are not calls.
/** Escape a source identifier for safe literal use inside a RegExp. */
function escapeRegExp(literal) {
  return literal.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function passDrivenRenderers(files) {
  const names = new Set();
  for (const { text } of files) {
    // `[A-Za-z_$][A-Za-z0-9_$]*`, not `\w+`: `$` is a legal JS identifier character but is
    // absent from `\w`, so a renderer named `update$StorageUi` would be captured as
    // `update` and the guard would then look for the wrong symbol.
    const re = /registerRenderer\(\s*"[^"]*"\s*,\s*([A-Za-z_$][A-Za-z0-9_$]*)/g;
    for (const m of stripComments(text).matchAll(re)) {
      names.add(m[1]);
    }
  }
  return names;
}

function manualRenderCalls(files) {
  const driven = passDrivenRenderers(files);
  const perFile = [];
  let total = 0;
  if (driven.size === 0) return { total, perFile, driven };
  for (const { name, text } of files) {
    let count = 0;
    for (const line of stripComments(text).split('\n')) {
      if (/registerRenderer\(/.test(line)) continue;
      for (const fn of driven) {
        // A call, not a declaration, an import, or a re-export on the VD registry.
        //
        // Match ANY argument list, not just `()`. These renderers happen to take no
        // arguments today, but matching only `fn()` meant `fn(undefined)` — or a renderer
        // that later grows a parameter — would drive a pass-owned renderer by hand and slip
        // past this guard entirely.
        //
        // `fn` is escaped before interpolation. It comes from this repo's own source rather
        // than user input, so this is not a ReDoS surface — but `$` is a legal identifier
        // character AND regex syntax, so an unescaped `update$StorageUi` would compile to a
        // pattern that silently fails to match its own call sites.
        if (new RegExp(String.raw`(?<!function\s)(?<!\.)\b${escapeRegExp(fn)}\s*\(`).test(line)) {
          count += 1;
        }
      }
    }
    if (count > 0) perFile.push(`${name}:${count}`);
    total += count;
  }
  return { total, perFile, driven };
}

function expectRatchet(metric, actual, detail) {
  const baseline = BASELINE[metric];
  expect(
    actual,
    `${metric} rose to ${actual} (baseline ${baseline}).\n` +
      `This is the coupling class that produces "fix one thing, break another" regressions.\n` +
      `Route the new code through the existing seam instead of adding to it.\n` +
      `Detail: ${detail}`,
  ).toBeLessThanOrEqual(baseline);

  expect(
    actual,
    `${metric} is now ${actual}, well under its ${baseline} baseline — nice.\n` +
      `Lower BASELINE.${metric} to ${actual} in this commit so the ratchet keeps its grip.`,
  ).toBeGreaterThan(baseline - SLACK[metric] - 1);
}

describe('architecture ratchet', () => {
  const files = sourceFiles();
  const { eager } = bundleLists();

  it('does not add direct `state.x =` writes outside the setState() seam', () => {
    const { total, perFile } = directStateWrites(files);
    expectRatchet('directStateWrites', total, perFile.join(', ') || 'none');
  });

  it('does not add eager-bundle VD calls that a typed import would serve', () => {
    const { total, perPair } = eagerToEagerVdCalls(files, eager);
    const detail = [...perPair.entries()]
      .sort((a, b) => b[1] - a[1])
      .map(([pair, n]) => `${pair} (${n})`)
      .join(', ');
    expectRatchet('eagerToEagerVdCalls', total, detail || 'none');
  });

  it('does not mutate state through an alias, bypassing the shallow readonly view', () => {
    const { total, perFile } = aliasNestedWrites(files);
    expectRatchet('aliasNestedWrites', total, perFile.join(', ') || 'none');
  });

  it('does not hand-call a renderer that the shared render pass already drives', () => {
    const { total, perFile, driven } = manualRenderCalls(files);
    expect(driven.size, 'no renderers found on the pass — did registerRenderer get renamed?')
      .toBeGreaterThan(0);
    expectRatchet('manualRenderCalls', total, perFile.join(', ') || 'none');
  });

  it('does not add application state read back out of the DOM', () => {
    const { total, perFile } = datasetStateReads(files);
    expectRatchet('datasetStateReads', total, perFile.join(', ') || 'none');
  });

  it('reads the bundle lists from build.mjs rather than a stale copy', () => {
    const { eager: e, lazy } = bundleLists();
    // Sanity: the lists must be disjoint and cover the modules the ratchet reasons about.
    expect(e.filter((m) => lazy.includes(m))).toEqual([]);
    expect(e).toContain('core');
    expect(lazy).toContain('map');
  });
});

// ---------------------------------------------------------------------------
// Matcher self-checks.
//
// These metrics are regexes over source text, so a matcher can be quietly WRONG while the
// suite stays green — the number just means something other than its name. That already
// happened twice: `manualRenderCalls` matched only `renderer()` and missed `renderer(x)`,
// and `datasetStateReads` used a bare `\w+` that backtracked, so `el.dataset.on = "x"`
// matched `.dataset.o` and every write was counted as a read (baseline 183 vs a true 75).
//
// Both were found by reading, not by failing. These cases run the REAL metric functions
// against synthetic sources so the next such bug fails the suite instead.
// ---------------------------------------------------------------------------
const one = (text) => [{ name: 'probe.ts', text }];

describe('architecture ratchet — matcher self-checks', () => {
  it('directStateWrites counts writes and ignores comparisons', () => {
    for (const src of ['state.a = 1;', 'state.a += 1;', 'state.a ||= 1;', 'state.a++;', 'state.a--;']) {
      expect(directStateWrites(one(src)).total, src).toBe(1);
    }
    for (const src of [
      'if (state.a === 1) {}', 'if (state.a == 1) {}', 'if (state.a <= 1) {}',
      'if (state.a !== 1) {}', 'const x = state.a;', 'const y = state.a - 1;',
    ]) {
      expect(directStateWrites(one(src)).total, src).toBe(0);
    }
  });

  it('datasetStateReads counts reads only — not writes, deletes, or allowlisted keys', () => {
    expect(datasetStateReads(one('if (el.dataset.on === "true") {}')).total).toBe(1);
    // The backtracking regression: a write must never register as a read.
    expect(datasetStateReads(one('el.dataset.on = "true";')).total).toBe(0);
    expect(datasetStateReads(one('el.dataset.reconnectActive = "false";')).total).toBe(0);
    // `delete` is a write with no `=` after it.
    expect(datasetStateReads(one('delete el.dataset.on;')).total).toBe(0);
    // Event-delegation identifiers and markup config are allowlisted, not defects.
    expect(datasetStateReads(one('const id = el.dataset.tripDetail;')).total).toBe(0);
    expect(datasetStateReads(one('const l = el.dataset.mapLayer;')).total).toBe(0);
  });

  it('aliasNestedWrites sees writes through an alias but not plain reads', () => {
    expect(aliasNestedWrites(one('const s = state.storage;\ns.count = 1;')).total).toBe(1);
    expect(aliasNestedWrites(one('const s = state.storage;\ns.count ??= 1;')).total).toBe(1);
    expect(aliasNestedWrites(one('const s = state.storage;\nif (s.count === 1) {}')).total).toBe(0);
  });

  it('manualRenderCalls catches a hand-call with any argument list', () => {
    const registered = 'registerRenderer("probe", paint, () => [1]);';
    expect(manualRenderCalls(one(`${registered}\npaint();`)).total).toBe(1);
    // The regression review caught: an argument must not let the call slip past.
    expect(manualRenderCalls(one(`${registered}\npaint(undefined);`)).total).toBe(1);
    // Declarations, member access on the registry, and the registration itself are not calls.
    expect(manualRenderCalls(one(`${registered}\nfunction paint() {}`)).total).toBe(0);
    expect(manualRenderCalls(one(`${registered}\nVD.paint();`)).total).toBe(0);
  });

  it('manualRenderCalls handles a renderer name containing `$`', () => {
    // `$` is a legal JS identifier character but absent from `\w`, and it is regex syntax.
    // Capturing with `\w+` would truncate the name; interpolating it unescaped would compile
    // a pattern that fails to match its own call sites. Either way the guard goes blind.
    const src = 'registerRenderer("probe", update$Ui, () => [1]);\nupdate$Ui();';
    const { total, driven } = manualRenderCalls(one(src));
    expect([...driven], 'the name must be captured whole, including the $').toEqual(['update$Ui']);
    expect(total, 'and the hand-call must still be caught').toBe(1);
  });

  it('comments never count against a metric', () => {
    expect(directStateWrites(one('// state.a = 1;')).total).toBe(0);
    expect(datasetStateReads(one('/* if (el.dataset.on === "x") {} */')).total).toBe(0);
  });
});
