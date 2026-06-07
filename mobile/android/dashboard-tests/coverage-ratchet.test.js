// coverage-ratchet.test.js — Meta-test that pins the Vitest coverage ratchet
// DIRECTION.
//
// vitest.config.js carries a "Raise only; never lower" COMMENT next to its
// coverage thresholds, but a comment enforces nothing — a future edit could
// silently drop the floor and CI would happily go green at the lower bar. This
// test locks the ratchet: it imports the live config and asserts each
// configured threshold is >= a committed baseline constant. Lowering any
// threshold below its baseline (without also lowering the baseline here, which
// shows up loudly in review) fails this test.
//
// Runs under the normal `npm test` (vitest run) sweep — it's a plain *.test.js
// in the suite's include glob, no coverage instrumentation required to execute.
import { describe, it, expect } from 'vitest';

import vitestConfig from './vitest.config.js';

// Committed baseline. RAISE these in lockstep when you raise vitest.config.js
// thresholds; NEVER lower them. They are the ratchet's floor of record.
const COVERAGE_BASELINE = Object.freeze({
  lines: 76,
  statements: 72,
  functions: 75,
  branches: 62,
});

describe('coverage ratchet', () => {
  const thresholds = vitestConfig?.test?.coverage?.thresholds;

  it('vitest.config.js still defines coverage thresholds', () => {
    expect(thresholds, 'coverage.thresholds vanished from vitest.config.js').toBeTruthy();
    expect(typeof thresholds).toBe('object');
  });

  for (const metric of Object.keys(COVERAGE_BASELINE)) {
    it(`${metric} threshold is >= committed baseline (${COVERAGE_BASELINE[metric]})`, () => {
      const configured = thresholds?.[metric];
      expect(
        typeof configured,
        `coverage.thresholds.${metric} is missing from vitest.config.js`,
      ).toBe('number');
      // The ratchet only turns one way: configured floor must never dip below
      // the baseline recorded here.
      expect(configured).toBeGreaterThanOrEqual(COVERAGE_BASELINE[metric]);
    });
  }

  it('no unexpected coverage metric escaped the ratchet', () => {
    // If someone adds a new threshold key (e.g. a per-file floor), make them
    // also add it to the baseline above so it can't regress unwatched.
    const configuredKeys = Object.keys(thresholds ?? {}).sort();
    const baselineKeys = Object.keys(COVERAGE_BASELINE).sort();
    expect(configuredKeys).toEqual(baselineKeys);
  });
});
