// Vitest configuration for the dashboard JS smoke suite.
//
// The suite loads the production dashboard JS files into a jsdom window
// and pokes at the resulting `window.VoltDashboard` / `window.VoltTrackerNative`
// surface, so we want a real DOM (jsdom) but the test functions stay
// explicit (no auto-injected `describe`/`it` globals).
//
// Coverage: floors are deliberately conservative for the initial gate - set them at
// roughly today's measured level and ratchet upward as the suite grows. Mirror the Java
// JaCoCo ratchet pattern: never drop the floor, only raise it. CI runs `npm run test:coverage`
// in the dashboard-tests workflow and fails the build below these thresholds.
export default {
  test: {
    environment: 'jsdom',
    globals: false,
    include: ['**/*.test.js'],
    setupFiles: ['./setup/test-lifecycle.js'],
    reporters: [['default', { summary: false }]],
    coverage: {
      provider: 'istanbul',
      all: true,
      allowExternal: true,
      include: ['**/app/src/main/dashboard-src/js/**/*.js'],
      exclude: ['**/app/src/main/assets/dashboard/lib/**/*'],
      reporter: ['text-summary', 'html'],
      // Ratcheted 2026-05-29 to ~3pts below measured (lines 65.4 / stmts 62.2 /
      // funcs 62.6 / branches 48.7). Raise only; never lower.
      thresholds: {
        lines: 62,
        statements: 60,
        functions: 60,
        branches: 46,
      },
    },
  },
};
