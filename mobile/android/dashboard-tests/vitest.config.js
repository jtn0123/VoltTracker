// Vitest configuration for the dashboard JS smoke suite.
//
// The suite loads the production dashboard JS files into a jsdom window
// and pokes at the resulting `window.VoltDashboard` / `window.VoltTrackerNative`
// surface, so we want a real DOM (jsdom) but the test functions stay
// explicit (no auto-injected `describe`/`it` globals).
export default {
  test: {
    environment: 'jsdom',
    globals: false,
    include: ['**/*.test.js'],
  },
};
