// The Kotlin -> dashboard live-sample contract.
//
// Native builds each live sample as a hand-assembled JSONObject; the dashboard reads the
// fields straight off state.telemetry. Nothing connects the two: VoltTelemetry ends with
// `[key: string]: unknown`, so renaming a field in Kotlin still typechecks on the JS side
// and simply produces a tile that never updates again. That index signature is deliberate
// (an older dashboard must tolerate a newer native payload), which is exactly why the
// contract needs a test rather than a type.
//
// The load-bearing list is AUTHORITATIVE_READING_KEYS in telemetry.ts. Native samples are
// whole snapshots, not patches: a field DISAPPEARS when its PID ages out. Every key on that
// list is nulled before the new sample is applied, so an omitted reading blanks instead of
// keeping the value the previous sample left behind. A sensor field missing from the list
// therefore shows a stale number on screen indefinitely — the bug the list exists to stop.
//
// So the list must be exactly: every field native puts in a live sample, minus the metadata
// that legitimately persists across samples. This test derives that from the Kotlin sources
// and fails when the two drift, which is what turns "someone remembered" into "the build
// checks".
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const ANDROID = join(import.meta.dirname, '..');
const KOTLIN = join(ANDROID, 'app/src/main/kotlin/com/volttracker/obdpoc');
const TELEMETRY_TS = join(ANDROID, 'app/src/main/dashboard-src/js/telemetry.ts');

// Fields that ride along in every sample but are NOT sensor readings, so carrying them
// across an omission is correct rather than stale. Adding a key here is a claim that the
// value stays true until native says otherwise; make that claim explicitly in review.
const METADATA_KEYS = new Set([
  // Sample envelope — identity of the sample itself, not something measured.
  'source', 'connected', 'adapter', 'sampleCount', 'sessionMs', 'supportedPids',
  'updatedAt', 'raw',
  // Session-health diagnostics (SessionHealthTracker). Monotonic counters and service
  // state; blanking them between samples would erase the very history they report.
  'appForeground', 'foregroundServiceActive', 'backgroundSampleCount', 'sampleGapCount',
  'lastSampleGapMs', 'maxSampleGapMs',
]);

function stripKotlinComments(text) {
  return text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
}

// Every string literal that names a key written into a live sample. Two call shapes exist
// in the Kotlin, and missing either silently understates the contract:
//   sample.put("speedKph", v)                       -> literal is the first argument
//   putNumeric(sample, "intakeAirTempC", "010F", 0) -> literal is the SECOND argument
// The stale-age helpers also take a second key:
//   putStaleMsForPresentValue(sample, "fuelLevelPct", "fuelLevelStaleMs", ...)
function sampleKeysIn(text) {
  const src = stripKotlinComments(text);
  const keys = new Set();
  for (const m of src.matchAll(/\.put\(\s*"([A-Za-z0-9_]+)"/g)) keys.add(m[1]);
  for (const m of src.matchAll(/\bput[A-Za-z]*\(\s*\w+\s*,\s*"([A-Za-z0-9_]+)"/g)) keys.add(m[1]);
  for (const m of src.matchAll(
    /\bput[A-Za-z]*\(\s*\w+\s*,\s*"[A-Za-z0-9_]+"\s*,\s*"([A-Za-z0-9_]+StaleMs)"/g,
  )) {
    keys.add(m[1]);
  }
  return keys;
}

// Only the region of a file that contributes to the live sample. ObdPollingEngine is a
// large class whose other JSON has nothing to do with this payload, so it is sliced to the
// one override that appends to the sample.
function sliceFunction(text, signature) {
  const start = text.indexOf(signature);
  expect(start, `${signature} not found — was it renamed?`).toBeGreaterThan(-1);
  let depth = 0;
  let seen = false;
  for (let i = text.indexOf('{', start); i < text.length; i += 1) {
    if (text[i] === '{') {
      depth += 1;
      seen = true;
    } else if (text[i] === '}') {
      depth -= 1;
      if (seen && depth === 0) return text.slice(start, i + 1);
    }
  }
  throw new Error(`unbalanced braces after ${signature}`);
}

// Each source is narrowed to the function that actually appends to the live sample.
// Scanning whole files would fold unrelated JSON — a debug dump, a log payload, a future
// helper — into the contract and fail the build for something that never reaches the
// dashboard. LiveSampleReader is the exception: read() delegates across many private
// putX helpers in the same class, all of which exist only to fill the sample, so the file
// IS the sample assembly. The plausibility check below is what catches a bad slice.
function nativeSampleKeys() {
  const read = (rel) => stripKotlinComments(readFileSync(join(KOTLIN, rel), 'utf8'));
  return new Set([
    ...sampleKeysIn(read('LiveSampleReader.kt')),
    ...sampleKeysIn(sliceFunction(read('engine/ObdPollingEngine.kt'), 'override fun appendLocation(')),
    ...sampleKeysIn(sliceFunction(read('SessionHealthTracker.kt'), 'fun append(')),
  ]);
}

function authoritativeReadingKeys() {
  const src = readFileSync(TELEMETRY_TS, 'utf8');
  const block = src.match(/const AUTHORITATIVE_READING_KEYS = \[([\s\S]*?)\] as const;/);
  expect(block, 'AUTHORITATIVE_READING_KEYS not found in telemetry.ts').not.toBeNull();
  return new Set([...block[1].matchAll(/"([A-Za-z0-9_]+)"/g)].map((m) => m[1]));
}

// Keys the dashboard clears that native never puts in a live sample. Harmless at runtime
// (nulling an absent key is a no-op) but each one is either a rename native already made or
// a field delivered by a payload this contract does not cover, so they are listed rather
// than ignored.
const CLEARED_BUT_NOT_IN_LIVE_SAMPLE = new Set([
  // Delivered by the cell-voltage probe payload (CellVoltageProbeRunner), not the live poll.
  'cellVoltages',
]);

describe('native -> dashboard live-sample contract', () => {
  const native = nativeSampleKeys();
  const cleared = authoritativeReadingKeys();

  it('extracts a plausible key set from the Kotlin sources', () => {
    // Guards against a silent extraction failure making every assertion below vacuous.
    expect(native.size).toBeGreaterThan(80);
    for (const anchor of ['speedKph', 'soc', 'outsideTempC', 'latitude', 'appForeground']) {
      expect(native.has(anchor), `expected ${anchor} among the extracted native keys`).toBe(true);
    }
  });

  it('clears every native sensor reading when a sample omits it', () => {
    const shouldClear = [...native].filter((k) => !METADATA_KEYS.has(k)).sort();
    const missing = shouldClear.filter((k) => !cleared.has(k));
    expect(
      missing,
      'These fields are written into the live sample by native but are NOT in\n' +
        'AUTHORITATIVE_READING_KEYS, so the previous value survives a sample that omits\n' +
        'them — the tile keeps showing a reading the car stopped reporting.\n' +
        'Add each to AUTHORITATIVE_READING_KEYS, or to METADATA_KEYS in this test if it\n' +
        'genuinely is not a reading.',
    ).toEqual([]);
  });

  it('does not clear the sample envelope or session-health counters', () => {
    const wrongly = [...METADATA_KEYS].filter((k) => cleared.has(k)).sort();
    expect(
      wrongly,
      'These are metadata, not readings. Nulling them between samples throws away the\n' +
        'sample envelope (or the health counters), so either they belong on the reading\n' +
        'list and should leave METADATA_KEYS, or the clearing list is wrong.',
    ).toEqual([]);
  });

  it('does not clear keys native never sends', () => {
    const orphans = [...cleared]
      .filter((k) => !native.has(k) && !CLEARED_BUT_NOT_IN_LIVE_SAMPLE.has(k))
      .sort();
    expect(
      orphans,
      'AUTHORITATIVE_READING_KEYS names fields no native live sample writes. Harmless at\n' +
        'runtime, but it usually means native renamed the field and the dashboard is now\n' +
        'clearing (and reading) a key that will never arrive.',
    ).toEqual([]);
  });
});

// --- matcher self-checks -----------------------------------------------------------------
// The extractor is a regex over Kotlin source, so it can be quietly WRONG while the suite
// stays green — it just measures less than its name claims. The first version of this
// extractor matched only `sample.put("x", ...)` and so missed every `putNumeric(sample,
// "x", ...)` field, understating the native surface by roughly 70 keys and hiding the very
// gap this file exists to catch. These pin each call shape.
describe('native-sample-contract — matcher self-checks', () => {
  it('matches the direct put form', () => {
    expect(sampleKeysIn('sample.put("speedKph", speed)').has('speedKph')).toBe(true);
  });

  it('matches the helper form where the key is the second argument', () => {
    expect(sampleKeysIn('putNumeric(sample, "intakeAirTempC", "010F", 0)').has('intakeAirTempC'))
      .toBe(true);
  });

  it('matches both keys of the paired value/stale helpers', () => {
    const keys = sampleKeysIn('putStaleMsForPresentValue(sample, "fuelLevelPct", "fuelLevelStaleMs", "012F", now)');
    expect(keys.has('fuelLevelPct')).toBe(true);
    expect(keys.has('fuelLevelStaleMs')).toBe(true);
  });

  it('ignores commented-out puts', () => {
    expect(sampleKeysIn('// sample.put("ghost", 1)').size).toBe(0);
    expect(sampleKeysIn('/* sample.put("ghost", 1) */').size).toBe(0);
  });

  it('slices a single function body rather than the rest of the file', () => {
    const text = [
      'override fun appendLocation(sample: JSONObject) {',
      '    sample.put("latitude", lat)',
      '    if (x) { sample.put("bearingDeg", b) }',
      '}',
      'fun somethingElse() { sample.put("notMine", 1) }',
    ].join('\n');
    const keys = sampleKeysIn(sliceFunction(text, 'override fun appendLocation('));
    expect([...keys].sort()).toEqual(['bearingDeg', 'latitude']);
  });
});
