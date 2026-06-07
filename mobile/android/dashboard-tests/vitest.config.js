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
      thresholds: {
        lines: 76,
        statements: 72,
        functions: 75,
        branches: 62,
      },
    },
  },
};
