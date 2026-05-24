// Vitest configuration for the dashboard JS smoke suite.
//
// The suite loads the production dashboard JS files into a jsdom window
// and pokes at the resulting `window.VoltDashboard` / `window.VoltTrackerNative`
// surface, so we want a real DOM (jsdom) but the test functions stay
// explicit (no auto-injected `describe`/`it` globals).
//
// Coverage (D4): floors are deliberately conservative for the initial gate — set them at
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
      provider: 'v8',
      all: true,
      include: ['../app/src/main/assets/dashboard/js/**/*.js'],
      exclude: ['../app/src/main/assets/dashboard/lib/**/*'],
      reporter: ['text-summary', 'html'],
      // KNOWN LIMITATION (D4): the dashboard sources are loaded into the jsdom window via
      // `new Function()` in setup/load-dashboard.js because they're browser-script IIFEs,
      // not ESM modules — see app/src/main/dashboard-src/index.template.html. The v8
      // coverage instrumenter only sees code reached through real `import`, so eval'd
      // source reports as 0% executed even when the tests exercise it heavily.
      //
      // The thresholds below intentionally start at 0 to LAND the CI gate without false
      // failures, and `all: true` makes every source file appear in the report so future
      // additions are visible. The follow-up to actually exercise this gate is to switch
      // the loader to ESM (e.g. wrap the IIFEs in `export {}` and import them) or to a
      // source-text-module shim, then ratchet these floors upward like JaCoCo does on the
      // Java side. Until then the gate exists primarily to keep the wiring honest.
      thresholds: {
        lines: 0,
        statements: 0,
        functions: 0,
        branches: 0,
      },
    },
  },
};
