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
    reporters: [['default', { summary: false }]],
    coverage: {
      provider: 'istanbul',
      all: true,
      allowExternal: true,
      include: ['**/app/src/main/assets/dashboard/js/**/*.js'],
      exclude: ['**/app/src/main/assets/dashboard/lib/**/*'],
      reporter: ['text-summary', 'html'],
      thresholds: {
        lines: 47,
        statements: 45,
        functions: 46,
        branches: 36,
      },
    },
  },
};
