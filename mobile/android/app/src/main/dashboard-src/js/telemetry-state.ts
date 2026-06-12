// telemetry-state.ts — single source of truth for the empty live-telemetry
// sample shape on the shared state bag.
//
// core.ts seeds state.telemetry from this at boot (and rebuilds it in
// clearDemoTelemetry), and telemetry.ts rebuilds it in resetTelemetry. Before
// this factory existed each site hand-built the object, and the copies had
// already drifted (resetTelemetry dropped the `source` key). Always build the
// initial shape here so init and reset can never disagree again.

/** A fresh, empty telemetry sample — every reading null, identity strings empty. */
export function initialTelemetryState(): VoltTelemetry {
  return {
    speedKph: null,
    rpm: null,
    voltage: null,
    coolantC: null,
    loadPct: null,
    throttlePct: null,
    soc: null,
    batteryTemp: null,
    powerKw: null,
    updatedAt: null,
    // Mirrors the latest sample's `source` field (e.g. "demo") so
    // clearDemoTelemetry can tell whether the staged telemetry is demo data.
    source: "",
    raw: ""
  };
}
