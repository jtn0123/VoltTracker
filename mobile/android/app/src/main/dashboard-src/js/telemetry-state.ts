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
 * Running totals for the in-progress drive: the peak OBD speed and the net HV
 * energy (pack power integrated over time, drive minus regen — the live twin of
 * a stored trip row's maxSpeedKph / energyKwh). Native samples carry the
 * service's own (driveMaxSpeedKph / driveEnergyKwh), which telemetry.ts#
 * recordSampleHistory copies in; a sample without them is folded there instead.
 * `sessionEnergyAtMs` is the previous folded sample's clock, so the integral can
 * size each step and skip gaps. Reset alongside sessionDistanceM so every
 * live-drive figure shares one span.
 */
export function initialSessionTotals() {
  return { sessionMaxSpeedKph: 0, sessionEnergyKwh: 0, sessionEnergyAtMs: 0 };
}

/**
 * How long the live drive has run, on the same span as sessionDistanceM: from the
 * service's drive start when the sample carries one (driveStartedAtMs), else the
 * adapter session's clock (samples without service totals are folded per session).
 * 0 when neither is known.
 */
export function liveDriveDurationMs(telemetry: VoltTelemetry, session: VoltSessionState): number {
  const driveStartedAt = Number(telemetry.driveStartedAtMs) || 0;
  return driveStartedAt > 0
    ? Math.max(0, Date.now() - driveStartedAt)
    : Number(telemetry.sessionMs || session.sessionMs || 0);
}

/**
 * The SOC the live drive started from, on the same span as sessionDistanceM: the
 * service's driveStartSoc (on the sample's own `soc` scale) when the sample carries
 * the service totals, else the session-start SOC. NaN when unknown.
 */
export function liveDriveStartSoc(telemetry: VoltTelemetry, sessionStartSoc: unknown): number {
  const soc = Number(telemetry.driveStartedAtMs) > 0 ? telemetry.driveStartSoc : sessionStartSoc;
  return soc == null ? NaN : Number(soc);
}
