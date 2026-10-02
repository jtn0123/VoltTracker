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

/**
 * Running totals for the in-progress drive, accumulated sample by sample in
 * telemetry.ts#recordSampleHistory: the peak OBD speed and the net HV energy
 * (pack power integrated over time, drive minus regen — the live twin of a
 * stored trip row's maxSpeedKph / energyKwh). `sessionEnergyAtMs` is the
 * previous sample's clock, so the integral can size each step and skip gaps.
 * Reset alongside sessionDistanceM so every live-drive figure shares one span.
 */
export function initialSessionTotals() {
  return { sessionMaxSpeedKph: 0, sessionEnergyKwh: 0, sessionEnergyAtMs: 0 };
}
