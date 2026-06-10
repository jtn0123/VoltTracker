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

// Committed baseline for the GLOBAL (aggregate) floors. RAISE these in lockstep
// when you raise vitest.config.js thresholds; NEVER lower them. They are the
// ratchet's floor of record.
const COVERAGE_BASELINE = Object.freeze({
  lines: 76,
  statements: 72,
  functions: 75,
  branches: 62,
});

// Committed baseline for the FOCUSED per-glob floors that guard the critical
// connection-recovery modules. Each entry is a glob key from
// coverage.thresholds mapping to the minimum per-metric floor we expect to see
// configured. Same ratchet rule: raise in lockstep, never lower. Adding a new
// focused floor in vitest.config.js without registering it here trips the
// "no unexpected coverage metric escaped the ratchet" guard below.
const PER_GLOB_BASELINE = Object.freeze({
  // Glob keys must match vitest.config.js verbatim. The `../` prefix is
  // load-bearing: the instrumented modules live one dir up from the Vitest
  // root, so the path Vitest matches against starts with `../` and a bare
  // `**/…` glob would match zero files.
  '../**/dashboard-src/js/troubleshooter.ts': Object.freeze({
    statements: 85,
    branches: 68,
    functions: 89,
    lines: 89,
  }),
  '../**/dashboard-src/js/connection-tools.ts': Object.freeze({
    statements: 85,
    branches: 56,
    functions: 95,
    lines: 92,
  }),
  '../**/dashboard-src/js/map-route-utils.ts': Object.freeze({
    statements: 90,
    branches: 75,
    functions: 90,
    lines: 90,
  }),
  '../**/dashboard-src/js/core.ts': Object.freeze({
    statements: 61,
    branches: 41,
    functions: 60,
    lines: 62,
  }),
  '../**/dashboard-src/js/map.ts': Object.freeze({
    statements: 64,
    branches: 48,
    functions: 71,
    lines: 66,
  }),
  '../**/dashboard-src/js/telemetry.ts': Object.freeze({
    statements: 77,
    branches: 72,
    functions: 81,
    lines: 81,
  }),
});

// Vitest treats any non-metric key in coverage.thresholds as a glob target.
const GLOBAL_METRIC_KEYS = Object.keys(COVERAGE_BASELINE);

describe('coverage ratchet', () => {
  const thresholds = vitestConfig?.test?.coverage?.thresholds;

  it('vitest.config.js still defines coverage thresholds', () => {
    expect(thresholds, 'coverage.thresholds vanished from vitest.config.js').toBeTruthy();
    expect(typeof thresholds).toBe('object');
  });

  for (const metric of GLOBAL_METRIC_KEYS) {
    it(`global ${metric} threshold is >= committed baseline (${COVERAGE_BASELINE[metric]})`, () => {
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

  for (const [glob, metrics] of Object.entries(PER_GLOB_BASELINE)) {
    describe(`focused floor: ${glob}`, () => {
      it('is still configured as an object of per-metric floors', () => {
        const configured = thresholds?.[glob];
        expect(
          configured && typeof configured === 'object',
          `focused floor for ${glob} vanished from vitest.config.js`,
        ).toBe(true);
      });

      for (const [metric, baseline] of Object.entries(metrics)) {
        it(`${metric} floor is >= committed baseline (${baseline})`, () => {
          const configured = thresholds?.[glob]?.[metric];
          expect(
            typeof configured,
            `coverage.thresholds['${glob}'].${metric} is missing from vitest.config.js`,
          ).toBe('number');
          expect(configured).toBeGreaterThanOrEqual(baseline);
        });
      }
    });
  }

  it('no unexpected coverage metric escaped the ratchet', () => {
    // Every configured threshold key must be either a known global metric or a
    // registered focused-glob baseline. If someone adds a new threshold key
    // (another global metric or another per-glob floor), make them also add it
    // to one of the baselines above so it can't regress unwatched.
    const configuredKeys = Object.keys(thresholds ?? {}).sort();
    const expectedKeys = [...GLOBAL_METRIC_KEYS, ...Object.keys(PER_GLOB_BASELINE)].sort();
    expect(configuredKeys).toEqual(expectedKeys);
  });
});
