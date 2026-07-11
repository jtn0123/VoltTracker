// Vitest configuration for the dashboard smoke suite.
//
// The suite loads the production dashboard TypeScript modules into a jsdom window
// and pokes at the resulting `window.VoltDashboard` / `window.VoltTrackerNative`
// surface, so we want a real DOM (jsdom) but the test functions stay
// explicit (no auto-injected `describe`/`it` globals).
//
// Coverage: floors are deliberately conservative for the initial gate - set them at
// roughly today's measured level and ratchet upward as the suite grows. Mirror the Android
// JaCoCo ratchet pattern: never drop the floor, only raise it. CI runs `npm run test:coverage`
// in the dashboard-tests workflow and fails the build below these thresholds.
export default {
  test: {
    environment: 'jsdom',
    globals: false,
    include: ['**/*.test.js'],
    setupFiles: ['./setup/test-lifecycle.js'],
    // The startup-budget spec measures wall-clock time. Keep dashboard files serial so
    // that budget reflects dashboard boot work, not neighboring Vitest workers.
    fileParallelism: false,
    testTimeout: 30_000,
    hookTimeout: 30_000,
    reporters: [['default', { summary: false }]],
    coverage: {
      provider: 'istanbul',
      all: true,
      allowExternal: true,
      include: ['**/app/src/main/dashboard-src/js/**/*.ts'],
      exclude: ['**/app/src/main/assets/dashboard/lib/**/*'],
      reporter: ['text-summary', 'html'],
      // Ratcheted 2026-06-07 to ~3pts below measured (lines 79.5 / stmts 76.0 /
      // funcs 78.1 / branches 65.4) after the troubleshooter + connection-tools
      // recovery-path specs landed. Raise only; never lower.
      //
      // Aggregate floors alone let a weak module hide behind a strong one. The
      // per-glob entries below add FOCUSED floors for the critical
      // connection-recovery modules so a regression there fails CI even when the
      // global average stays green. They are deliberately scoped (NOT a blanket
      // `perFile: true`, which would fail legitimately-lower modules like map.ts
      // / core.ts). Each glob floor is set ~5pts below its measured coverage so
      // it absorbs refactor churn but still catches real backsliding.
      //   troubleshooter.ts  (measured 2026-06-07: stmts 90.2 / branch 73.2 /
      //                       funcs 94.6 / lines 94.7)
      //   connection-tools.ts (measured 2026-06-07: stmts 90.4 / branch 61.5 /
      //                       funcs 100  / lines 97.8 — few branches, so the
      //                       branch floor is intentionally the lowest)
      //   core.ts       (measured 2026-06-09: stmts 66.1 / branch 46.7 /
      //                  funcs 65.6 / lines 67.3)
      //   map.ts        (measured 2026-06-09: stmts 69.2 / branch 53.5 /
      //                  funcs 76.6 / lines 71.5)
      //   telemetry.ts  (measured 2026-06-09: stmts 82.8 / branch 77.8 /
      //                  funcs 86.9 / lines 86.3)
      // Glob keys are matched (picomatch) against each file's path RELATIVE TO
      // the Vitest root, which is this `dashboard-tests/` dir. The instrumented
      // modules live one level up under `../app/src/main/dashboard-src/js/`, so
      // the relative path begins with `../` — hence the leading `../` in the
      // glob. A bare `**/…` does NOT match a `../`-prefixed path under picomatch
      // and would silently match zero files (a no-op floor), so the `../`
      // prefix is load-bearing, not cosmetic. Any non-metric key in
      // `thresholds` is treated as a glob by Vitest. Raise these in lockstep
      // with the modules; never lower.
      // Ratchet audit trail (raise only; never lower):
      //   2026-06-12: lines 76 -> 81, statements 72 -> 78, functions 75 -> 80,
      //   branches 62 -> 66. Measured 84.6 / 81.3 / 83.7 / 69.2 after the
      //   polish pass (parsePayload failure surfacing + tests, payload-
      //   validator cap test, shared telemetry-state factory + reset spec,
      //   app-dialog focus specs, CSS data-state/tone drift specs); floors set
      //   ~3pts below measured per the convention above. Baselines in
      //   coverage-ratchet.test.js raised in lockstep.
      //   2026-07-11 (D1): branches 66 -> 72. Measured 74.4% branches
      //   (6288/8452) — the old floor had drifted ~8pts below actual, so a
      //   sizeable branch-coverage regression could land without failing CI.
      //   Floor set ~2pts below measured per the ratchet convention.
      thresholds: {
        lines: 81,
        statements: 78,
        functions: 80,
        branches: 72,
        '../**/dashboard-src/js/troubleshooter.ts': {
          statements: 85,
          branches: 68,
          functions: 89,
          lines: 89,
        },
        '../**/dashboard-src/js/connection-tools.ts': {
          statements: 85,
          branches: 56,
          functions: 95,
          lines: 92,
        },
        '../**/dashboard-src/js/map-route-utils.ts': {
          statements: 90,
          branches: 75,
          functions: 90,
          lines: 90,
        },
        '../**/dashboard-src/js/core.ts': {
          statements: 61,
          branches: 41,
          functions: 60,
          lines: 62,
        },
        '../**/dashboard-src/js/map.ts': {
          statements: 64,
          branches: 48,
          functions: 71,
          lines: 66,
        },
        '../**/dashboard-src/js/telemetry.ts': {
          statements: 77,
          branches: 72,
          functions: 81,
          lines: 81,
        },
      },
    },
  },
};
