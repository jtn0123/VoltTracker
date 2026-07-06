function hexByte(value: number) {
  const clamped = Math.max(0, Math.min(255, Math.round(value)));
  return clamped.toString(16).toUpperCase().padStart(2, "0");
}

function hexWord(value: number) {
  const clamped = Math.max(0, Math.min(65535, Math.round(value)));
  return clamped.toString(16).toUpperCase().padStart(4, "0");
}

function demoRawFrames(sample: {
  t: number;
  speedKph: number;
  rpm: number;
  coolantC: number;
  loadPct: number;
  throttlePct: number;
  voltage: number;
  soc: number;
}) {
  const rpmWord = hexWord(sample.rpm * 4);
  const voltageWord = hexWord(sample.voltage * 1000);
  return [
    `demo sample ${sample.t}`,
    ">010D",
    `41 0D ${hexByte(sample.speedKph)}`,
    ">010C",
    `41 0C ${rpmWord.slice(0, 2)} ${rpmWord.slice(2)}`,
    ">0105",
    `41 05 ${hexByte(sample.coolantC + 40)}`,
    ">0104",
    `41 04 ${hexByte((sample.loadPct / 100) * 255)}`,
    ">0111",
    `41 11 ${hexByte((sample.throttlePct / 100) * 255)}`,
    ">0142",
    `41 42 ${voltageWord.slice(0, 2)} ${voltageWord.slice(2)}`,
    ">22 43 34",
    `62 43 34 ${hexByte(sample.soc)}`
  ].join("\n");
}

// A compressed "day with the car" cycle: 60 s of driving (30 EV + 30 gas),
// then 30 s parked on a Level-2 charger. The charge window is what feeds the
// Charge tab's live time-to-full hero in demo mode — without it the most
// prominent Charge component could never be previewed. Mirrors
// DemoPollingLoop.kt's native cycle; keep the two in step.
const DEMO_DRIVE_PHASE_S = 60;
const DEMO_CYCLE_S = 90;
const DEMO_CHARGER_KW = 7.2;
// Exaggerated vs the real ~0.014%/s a 7.2 kW charger manages, so the SOC
// visibly climbs within the 30 s demo charge window. The drive-phase drain is
// matched so each cycle is SOC-neutral (0.06 * 60 == 0.12 * 30): the sawtooth
// repeats forever instead of drifting into a cap and plateauing there.
const DEMO_CHARGE_SOC_PER_S = 0.12;
const DEMO_DRIVE_SOC_PER_S = 0.06;
const DEMO_SOC_START = 77.8;

export function runBrowserDemoStream(
  VD: VoltDashboard,
  state: DashboardState,
) {
  // Defense in depth against the start→stop-during-chunk-load race: if the demo
  // was stopped before this lazily-loaded stream began, don't start the interval
  // or flip status back to "connected".
  if (!state.demoActive) return;
  let t = 0;
  let driveT = 0;
  VD.setStatus({ state: "connected", detail: "Browser-only demo is running." });
  // Begin from an empty live route so a re-started browser demo doesn't append onto the
  // previous run's track (stopDemo/stopAll clear it, but a bare start would not).
  if (typeof VD.clearLivePosition === "function") VD.clearLivePosition();
  else { state.liveRoutePoints = []; state.liveRouteStartedAtMs = null; }
  window.clearInterval(window.__voltDemoTimer ?? undefined);
  const emitSample = () => {
    t += 1;
    const phase = t % DEMO_CYCLE_S;
    const charging = phase >= DEMO_DRIVE_PHASE_S;
    // The route clock only advances while driving, so the map marker parks
    // during the charge window instead of orbiting an unplugged charger.
    if (!charging) driveT += 1;
    const gas = !charging && Math.floor(driveT / 30) % 2 === 1;
    state.mode = gas ? "gas" : "ev";
    const powerKw = charging ? 0
      : gas ? 30 + Math.sin(driveT / 3) * 9
      : 9 + Math.sin(driveT / 2.2) * 22;
    // 0 while driving (not omitted: samples merge into state.telemetry, so a
    // stale charger reading from the last charge window would otherwise pin
    // the live charge card open forever).
    const chargerPowerKw = charging
      ? Number((DEMO_CHARGER_KW + 0.3 * Math.sin(t / 5)).toFixed(1))
      : 0;
    const routeDrift = Math.sin(driveT / 40);
    const lat = 34.11872 + routeDrift * 0.004;
    const lng = -118.30064 - Math.abs(routeDrift) * 0.012;
    const speedKph = charging ? 0 : Math.round(54 + 23 * Math.sin(driveT / 3.4));
    const rpm = gas ? Math.round(1260 + 420 * Math.sin(driveT / 2.1)) : 0;
    const coolantC = Math.round(82 + 4 * Math.sin(t / 8));
    const loadPct = charging ? 4 : Math.round(34 + 18 * Math.sin(driveT / 4.4));
    const throttlePct = charging ? 0 : Math.round(18 + 14 * Math.sin(driveT / 2.7));
    const voltage = charging ? 14.2 : 13.8;
    // Continuous periodic sawtooth (74.2..77.8), derived from the cycle phase
    // rather than accumulated — always below the 100% default target, so the
    // charge hero stays visible for the whole window. Mirrors demoSoc() in
    // DemoPollingLoop.kt.
    const soc = DEMO_SOC_START -
      DEMO_DRIVE_SOC_PER_S * Math.min(phase, DEMO_DRIVE_PHASE_S) +
      DEMO_CHARGE_SOC_PER_S * Math.max(0, phase - DEMO_DRIVE_PHASE_S);
    // HV cell-group balance for the Battery-tab cell card: a healthy pack with
    // a gentle 10–20 mV wobble around ~3.9 V. Cell 47 rides the low side to
    // match the "Cell 47 trending low" demo insight.
    const cellAvgV = 3.85 + (soc - 50) * 0.003;
    const cellSpreadMv = Math.round(14 + 6 * Math.sin(t / 9));
    const minCellVoltage = Number((cellAvgV - cellSpreadMv / 2000).toFixed(3));
    const maxCellVoltage = Number((cellAvgV + cellSpreadMv / 2000).toFixed(3));
    // Raw (unrounded) HV pack voltage — reused for packVoltage and the
    // packCurrentA denominator so the two stay in step (mirrors DemoPollingLoop.kt).
    const rawPackV = 353 + (soc - 50) * 0.2;
    VD.updateTelemetry({
      source: "demo",
      connected: true,
      sampleCount: t,
      sessionMs: t * 1000,
      supportedPids: "browser demo",
      vehicleState: charging ? "charging"
        : powerKw < -0.5 ? "regen"
        : gas ? "driving (gas)"
        : "driving",
      speedKph,
      rpm,
      coolantC,
      loadPct,
      throttlePct,
      voltage,
      soc,
      // °C, matching DemoPollingLoop.kt's 24.0 + sin(t/8) — telemetry.ts renders
      // batteryTemp via units.tempText which converts °C→°F when needed, so a
      // Fahrenheit-shaped value here would show as ~162 °F pack temp.
      batteryTemp: 24 + Math.sin(t / 8),
      minCellVoltage,
      maxCellVoltage,
      cellBalanceMv: cellSpreadMv,
      minCellNumber: 47,
      maxCellNumber: 12,
      socVariationPct: 0.4,
      powerKw: powerKw,
      chargerPowerKw,
      // Extra PIDs a real Volt answers, so the Live-signals console shows a
      // populated "reporting" list in demo (mirrors DemoPollingLoop.kt).
      packVoltage: Number(rawPackV.toFixed(1)),
      packCurrentA: Number(
        (((charging ? -chargerPowerKw : powerKw) * 1000) / rawPackV).toFixed(1),
      ),
      controlModuleVoltage: voltage,
      odometerKm: 77593,
      intakeAirTempC: Number((22 + 3 * Math.sin(t / 11)).toFixed(1)),
      outsideTempC: Number((18 + 2 * Math.sin(t / 13)).toFixed(1)),
      sohPct: 91,
      packEnergyKwh: Number(((soc / 100) * 14).toFixed(1)),
      hvBatteryRawSoc: Number((soc + 2).toFixed(1)),
      motorAPowerKw: charging ? 0 : Number((powerKw * 0.6).toFixed(1)),
      transmissionTempC: Number((68 + 3 * Math.sin(t / 7)).toFixed(1)),
      prndlState: charging ? "P" : "D",
      latitude: lat,
      longitude: lng,
      updatedAt: Date.now(),
      raw: demoRawFrames({ t, speedKph, rpm, coolantC, loadPct, throttlePct, voltage, soc })
    });
  };
  emitSample();
  window.__voltDemoTimer = window.setInterval(emitSample, 1000);
}

window.VoltDashboardActionModules = window.VoltDashboardActionModules || {};
window.VoltDashboardActionModules.runBrowserDemoStream = runBrowserDemoStream;
