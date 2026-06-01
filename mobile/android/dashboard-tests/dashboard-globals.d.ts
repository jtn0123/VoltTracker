// Ambient declarations for the globals the dashboard IIFEs share (I3).
//
// The dashboard is not a module graph: each js/*.js file is an IIFE that hangs its
// API off `window.VoltDashboard` (aliased `VD`) and talks to the native side via
// `window.VoltTrackerAndroid`. Typing those as broad shapes here keeps `tsc
// --checkJs` from drowning in "property does not exist on Window" noise while still
// catching real local bugs (undefined names, bad call arity against typed DOM APIs).
// Tighten these toward real interfaces as more files opt into `// @ts-check`.

export {};

declare global {
  interface Window {
    /** Shared dashboard namespace every IIFE extends. */
    VoltDashboard: any;
    /** Native -> dashboard entry points (updateTelemetry, setStatus, …). */
    VoltTrackerNative: any;
    /** Dashboard -> native bridge (@JavascriptInterface methods on VoltBridge). */
    VoltTrackerAndroid: any;
    /** Test seam: lets the Vitest harness intercept lazy script loads. */
    __VoltDashboardLoadScript?: (src: string) => unknown;
    /** Demo telemetry fixture factory (demo-data.js). */
    VoltDashboardDemoData?: () => any;
  }

  /** Leaflet, loaded as a side-effecting global from lib/leaflet/leaflet.js. */
  const L: any;
}
