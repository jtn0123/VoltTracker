// vd-registry.ts — the ONE place dashboard source reads or creates the
// `window.VoltDashboard` global (C7 ESM migration).
//
// `window.VoltDashboard` has two jobs, and both genuinely need a window-level
// object rather than module state:
//
//   1. External ABI. The Android side drives the dashboard through
//      evaluateJavascript("window.VoltDashboard.…") and the e2e harness / unit
//      suite poke the same surface (docs/dashboard-script-contract.md,
//      docs/bridge-abi.md). core.ts assembles that surface explicitly; every
//      name Kotlin or the harness touches must stay on the window object with
//      an identical signature.
//
//   2. Cross-chunk registry. The dashboard ships as ONE eager bundle (app.js)
//      plus independently bundled lazy chunks (map.js, insights-panel.js,
//      dtc-detail.js, …). The chunks are separate esbuild builds, so ES imports
//      cannot cross that boundary at runtime — a lazy chunk registers its entry
//      points on this object as it loads (Object.assign(VD, { … })), and the
//      eager side calls them late-bound after the matching ensure*Module()
//      resolves (core.ts). NOTE: a lazy chunk that imports a module re-bundles
//      its own COPY of it, so only stateless leaf helpers (prefs reads,
//      cost-model, focus-trap, …) may be imported across the chunk boundary —
//      shared mutable state must live on `VD` (e.g. VD.state) or the DOM.
//
// Inside one bundle, modules must NOT call each other through this object:
// use typed ESM imports (e.g. `import { setText } from "./core"`). Import `VD`
// only to
//   (a) register a module's own ABI / cross-chunk entry points,
//   (b) call a symbol that lives in another chunk (guarded, after ensure*), or
//   (c) call a symbol that is deliberately re-wrapped at runtime and must stay
//       late-bound for every caller: setStatus / updateTelemetry (wrapped by
//       actions.ts, connection-status.ts and troubleshooter.ts),
//       pendingLazyLoads (wrapped by actions.ts) and dtcSearchUrl (overridden
//       by the dtc-lookup chunk).
//
// The `||` keeps re-execution idempotent: every lazy chunk carries its own
// copy of this module, and the unit suite re-imports the sources per test
// while the window object persists across loadDashboard() calls.
export const VD = (window.VoltDashboard = window.VoltDashboard || ({} as VoltDashboard));

// window.VoltDashboardActionModules is the same cross-chunk protocol for the
// secondary action groups (actions-storage / actions-signals / actions-demo):
// each chunk publishes its factory here and actions.ts pulls it out once the
// chunk has loaded. Both sides go through this accessor so the global has one
// owner; reading lazily (not caching) preserves the old semantics where the
// unit suite deletes the global between loadDashboard() runs.
export function actionModulesRegistry(): NonNullable<Window["VoltDashboardActionModules"]> {
  const registry = window.VoltDashboardActionModules || {};
  window.VoltDashboardActionModules = registry;
  return registry;
}
