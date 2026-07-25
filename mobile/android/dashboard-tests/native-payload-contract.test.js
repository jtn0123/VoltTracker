// The Kotlin -> dashboard payload contract, for the structured payloads.
//
// Companion to native-sample-contract.test.js, which covers the live telemetry sample and
// its clearing semantics. This one covers the other direction of the same problem: the
// SHAPE of every other payload that crosses the boundary.
//
// Native assembles each payload as a hand-built JSONObject. The dashboard describes the
// result with a TypeScript interface. Nothing connects the two, so renaming a field in
// Kotlin leaves the interface describing a payload that no longer exists — the dashboard
// still compiles, and the value silently reads `undefined` forever. That is not a type the
// compiler can check, because the JSON arrives at runtime from another process.
//
// So the check is textual and deliberately blunt: extract the key names each Kotlin builder
// puts, extract the fields each interface declares, and require them to agree. Differences
// are allowed but must be declared and justified below, which is what turns "nobody noticed"
// into "someone decided".
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const ANDROID = join(import.meta.dirname, '..');
const KOTLIN = join(ANDROID, 'app/src/main/kotlin/com/volttracker/obdpoc');
const GLOBALS = join(ANDROID, 'dashboard-tests/dashboard-globals.d.ts');

function stripKotlinComments(text) {
  return text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
}

// A Kotlin member: its signature line through to the next sibling `fun`. Deliberately not
// brace-matched — several of these builders are expression-bodied
// (`private fun adapterJson(): JSONObject = JSONObject().put(...)`) and have no block at all.
function kotlinMember(text, signature) {
  const lines = text.split('\n');
  const start = lines.findIndex((l) => l.includes(signature));
  expect(start, `Kotlin member not found: ${signature} — renamed?`).toBeGreaterThan(-1);
  const nextFun = /^\s{0,4}(@\w+\s*)?(private |internal |@JvmStatic )*fun /;
  for (let i = start + 1; i < lines.length; i += 1) {
    if (nextFun.test(lines[i])) return lines.slice(start, i).join('\n');
  }
  return lines.slice(start).join('\n');
}

function putKeysIn(text) {
  const keys = new Set();
  for (const m of text.matchAll(/\.put\(\s*"([A-Za-z0-9_]+)"/g)) keys.add(m[1]);
  for (const m of text.matchAll(/\bput[A-Za-z]*\(\s*\w+\s*,\s*"([A-Za-z0-9_]+)"/g)) keys.add(m[1]);
  return keys;
}

function nativeKeys(sources) {
  const keys = new Set();
  for (const [file, members] of sources) {
    const src = stripKotlinComments(readFileSync(join(KOTLIN, file), 'utf8'));
    for (const member of members) {
      for (const key of putKeysIn(kotlinMember(src, member))) keys.add(key);
    }
  }
  return keys;
}

// Top-level fields of an interface. The two-space indent restriction keeps nested object
// literals (`app?: { version?: string }`) from leaking their inner fields into the set.
function interfaceFields(name) {
  const src = readFileSync(GLOBALS, 'utf8');
  const block = src.match(new RegExp(`^interface ${name} \\{([\\s\\S]*?)^\\}`, 'm'));
  expect(block, `interface ${name} not found in dashboard-globals.d.ts`).not.toBeNull();
  return new Set([...block[1].matchAll(/^ {2}([A-Za-z0-9_]+)\??:/gm)].map((m) => m[1]));
}

// Each contract names the Kotlin members that build the payload and the interface that
// describes it. Scoping to members rather than whole files is load-bearing: StorageSummaryJson
// also builds the nested recent-session and DTC arrays, and folding those in would compare
// three payloads' keys against one interface.
//
// `dashboardOnly` is for fields the interface declares that native never sends. Every entry is
// a claim with a reason — an undeclared one is the bug this file exists to catch.
const CONTRACTS = [
  {
    name: 'VoltStatus',
    // Two native contributors: StatusPayload for live service status, DashboardPayloadJson
    // for the fields the Activity attaches (bluetooth readiness, remembered adapter).
    sources: [
      ['StatusPayload.kt', ['fun toJson()']],
      ['DashboardPayloadJson.kt', ['fun status(']],
    ],
    dashboardOnly: [],
  },
  {
    name: 'VoltRestoreProgress',
    sources: [['DashboardPayloadJson.kt', ['fun restoreProgress(']]],
    dashboardOnly: [],
  },
  {
    name: 'VoltAdapterState',
    sources: [['AppStatePayload.kt', ['private fun adapterJson()']]],
    dashboardOnly: [],
  },
  {
    name: 'VoltSessionState',
    sources: [['AppStatePayload.kt', ['private fun sessionJson()']]],
    dashboardOnly: [],
  },
  {
    name: 'VoltGpsState',
    sources: [['AppStatePayload.kt', ['private fun gpsJson()']]],
    dashboardOnly: [],
  },
  {
    name: 'VoltAppState',
    sources: [['AppStatePayload.kt', ['fun toJson()']]],
    dashboardOnly: [],
  },
  {
    name: 'VoltStorageSummary',
    sources: [['StorageSummaryJson.kt', ['private fun build(', 'private fun putLatestSession(']]],
    dashboardOnly: [
      // Set by the dashboard itself when a storage read fails, so the panel can render the
      // reason instead of an empty shell. Never present on a successful native payload.
      'error',
      'message',
      // Emitted by ObdStoreReports (the details request), not by the summary builder — the
      // dashboard merges the two into one storage object.
      'storageDetails',
    ],
  },
  {
    name: 'VoltRecentSession',
    sources: [['StorageSummaryJson.kt', ['private fun recentSessionsJson(']]],
    dashboardOnly: [],
  },
  {
    name: 'VoltDtcRow',
    sources: [['data/DiagnosticCodeReport.kt', ['fun toJson()']]],
    dashboardOnly: [
      // Read by dtc-detail.ts and the CSV export, but NO Kotlin builder emits it — the only
      // producer is the demo seed in map.ts. The freeze-frame section of the DTC sheet is
      // therefore empty on a real car. Left in place rather than deleted because the reader
      // side is written and working; closing the gap means emitting it from native, which is
      // a feature, not a cleanup. Recorded here so it stops being invisible.
      'freezeFrame',
    ],
  },
];

describe('native -> dashboard payload contract', () => {
  for (const contract of CONTRACTS) {
    describe(contract.name, () => {
      const native = nativeKeys(contract.sources);
      const declared = interfaceFields(contract.name);

      it('extracts a plausible key set from the Kotlin builder', () => {
        // Without this, a renamed builder yields an empty set and every assertion below
        // passes vacuously — which is exactly how a contract test rots into decoration.
        expect(native.size).toBeGreaterThan(0);
      });

      it('declares every field native sends', () => {
        const undeclared = [...native].filter((k) => !declared.has(k)).sort();
        expect(
          undeclared,
          `${contract.name} is missing fields that native puts in this payload.\n` +
            'The dashboard cannot read them without a cast, and a reader that adds one is\n' +
            'working from a shape nothing checks. Add them to the interface.',
        ).toEqual([]);
      });

      it('does not declare fields native never sends', () => {
        const allowed = new Set(contract.dashboardOnly);
        const phantom = [...declared].filter((k) => !native.has(k) && !allowed.has(k)).sort();
        expect(
          phantom,
          `${contract.name} declares fields no native builder emits, so every read of them\n` +
            'returns undefined forever. Either native stopped sending them (a rename — fix\n' +
            'the interface) or they were never sent (dead contract — delete them, or add to\n' +
            "this contract's dashboardOnly list with the reason).",
        ).toEqual([]);
      });

      it('keeps the dashboardOnly allowlist honest', () => {
        // An allowlisted key that native HAS started sending is stale scaffolding: it
        // suppresses a check that would now pass on its own.
        const stale = contract.dashboardOnly.filter((k) => native.has(k)).sort();
        expect(
          stale,
          `${contract.name} allowlists ${stale.join(', ')} as native-absent, but native now ` +
            'sends it. Remove the allowlist entry.',
        ).toEqual([]);
      });
    });
  }
});

// --- matcher self-checks -------------------------------------------------------------------
// Same reasoning as the live-sample contract: these are regexes over source text, so a broken
// matcher makes the suite green while measuring nothing.
describe('native-payload-contract — matcher self-checks', () => {
  it('slices an expression-bodied Kotlin member', () => {
    const text = [
      '    private fun adapterJson(): JSONObject =',
      '        JSONObject()',
      '            .put("name", name)',
      '            .put("address", address)',
      '',
      '    private fun other(): JSONObject = JSONObject().put("notMine", 1)',
    ].join('\n');
    const keys = putKeysIn(kotlinMember(text, 'private fun adapterJson()'));
    expect([...keys].sort()).toEqual(['address', 'name']);
  });

  it('slices a block-bodied Kotlin member', () => {
    const text = [
      '    private fun build(x: Int): JSONObject {',
      '        payload.put("a", 1)',
      '        return payload',
      '    }',
      '    private fun after(): JSONObject = JSONObject().put("notMine", 1)',
    ].join('\n');
    expect([...putKeysIn(kotlinMember(text, 'private fun build('))].sort()).toEqual(['a']);
  });

  it('ignores commented-out puts', () => {
    expect(putKeysIn(stripKotlinComments('// payload.put("ghost", 1)')).size).toBe(0);
    expect(putKeysIn(stripKotlinComments('/* payload.put("ghost", 1) */')).size).toBe(0);
  });

  it('reads only top-level interface fields, not nested object literals', () => {
    // VoltAppState declares `app?: { version?: string; schemaVersion?: number }`. If the
    // matcher picked up nested members, `version` would appear as a top-level field and the
    // contract would demand native send it at the top level.
    const fields = interfaceFields('VoltAppState');
    expect(fields.has('app')).toBe(true);
    expect(fields.has('version')).toBe(false);
    expect(fields.has('schemaVersion')).toBe(false);
  });
});
