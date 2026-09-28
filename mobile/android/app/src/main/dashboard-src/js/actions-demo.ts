import { actionModulesRegistry } from "./vd-registry";
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

// A compressed "day with the car" cycle: a 60 s trip, then 30 s parked on a
// Level-2 charger. The trip is EV with regen dips until 36 s, the engine runs
// 36-48 s, regen braking to a stop 48-54 s, then parked in P until the charger
// is plugged in at 60 s. The charge window is what feeds the
// Charge tab's live time-to-full hero in demo mode — without it the most
// prominent Charge component could never be previewed. Mirrors
// DemoPollingLoop.kt's native cycle; keep the two in step.
const DEMO_DRIVE_PHASE_S = 60;
const DEMO_CYCLE_S = 90;
const DEMO_GAS_AT_S = 36;
const DEMO_BRAKE_AT_S = 48;
const DEMO_PARK_AT_S = 54;
const DEMO_BRAKE_S = 6; // DEMO_PARK_AT_S - DEMO_BRAKE_AT_S

// Legs of the cycle (DemoPollingLoop's DemoLeg); 3 = parked, 4 = charging.
const LEG_EV = 0;
const LEG_GAS = 1;
const LEG_BRAKING = 2;

// Speed follows the v2 design prototype's demo series (34 + 9sin + 4sin mph,
// converted to kph) — a gentle 25–47 mph urban band.
function demoCruiseKph(driveT: number): number {
  return (34 + 9 * Math.sin(driveT / 4.2) + 4 * Math.sin(driveT / 1.7)) * 1.609;
}
// 3.6 kW is the 2017 Volt's onboard-charger ceiling on Level 2.
const DEMO_CHARGER_KW = 3.6;
// Exaggerated vs the real ~0.007%/s a 3.6 kW charger manages, so the SOC
// visibly climbs within the 30 s demo charge window. The drive-phase drain is
// matched so each cycle is SOC-neutral (0.06 * 60 == 0.12 * 30): the sawtooth
// repeats forever instead of drifting into a cap and plateauing there.
const DEMO_CHARGE_SOC_PER_S = 0.12;
const DEMO_DRIVE_SOC_PER_S = 0.06;
// Starts the drive-phase sawtooth at ~65% so the hero SOC reads the design
// demo's ~63% mid-drive (declines to ~61% before the charge window refills it).
const DEMO_SOC_START = 64.8;

export function runBrowserDemoStream(
  VD: VoltDashboard,
  state: Readonly<DashboardState>,
) {
  // Defense in depth against the start→stop-during-chunk-load race: if the demo
  // was stopped before this lazily-loaded stream began, don't start the interval
  // or flip status back to "connected".
  if (!state.demoActive) return;
  let t = 0;
  let driveT = 0;
  let routeT = 0;
  VD.setStatus({ state: "connected", detail: "Browser-only demo is running." });
  // Begin from an empty live route so a re-started browser demo doesn't append onto the
  // previous run's track (stopDemo/stopAll clear it, but a bare start would not).
  if (typeof VD.clearLivePosition === "function") VD.clearLivePosition();
  else VD.setState({ liveRoutePoints: [], liveRouteStartedAtMs: null });
  window.clearInterval(window.__voltDemoTimer ?? undefined);
  const emitSample = () => {
    t += 1;
    const phase = t % DEMO_CYCLE_S;
    const charging = phase >= DEMO_DRIVE_PHASE_S;
    // Which leg of the cycle this second falls in (DemoPollingLoop.legAt).
    const leg = charging ? 4 : phase >= DEMO_PARK_AT_S ? 3
      : phase >= DEMO_BRAKE_AT_S ? LEG_BRAKING : phase >= DEMO_GAS_AT_S ? LEG_GAS : LEG_EV;
    const moving = leg <= LEG_BRAKING;
    // The sine clock runs for the whole trip; the route clock only while moving,
    // so the map marker stays put once the car parks.
    if (!charging) driveT += 1;
    if (moving) routeT += 1;
    const gas = leg === LEG_GAS;
    VD.setState({ mode: gas ? "gas" : "ev" });
    // EV power follows the v2 design prototype's demo bars (6 + 14sin + 5sin):
    // mostly drive with regen dips, peaking ~25 kW. Braking regenerates hard.
    const powerKw = leg === LEG_EV ? 6 + 14 * Math.sin(driveT / 3.1) + 5 * Math.sin(driveT / 1.3)
      : gas ? 30 + Math.sin(driveT / 3) * 9
      : leg === LEG_BRAKING ? -(8 + (10 * (DEMO_PARK_AT_S - phase)) / DEMO_BRAKE_S)
      : 0;
    // 0 while driving (not omitted: samples merge into state.telemetry, so a
    // stale charger reading from the last charge window would otherwise pin
    // the live charge card open forever).
    const chargerPowerKw = charging
      ? Number((DEMO_CHARGER_KW + 0.3 * Math.sin(t / 5)).toFixed(1))
      : 0;
    const routeDrift = Math.sin(routeT / 40);
    const lat = 34.11872 + routeDrift * 0.004;
    const lng = -118.30064 - Math.abs(routeDrift) * 0.012;
    // Braking eases linearly from the cruise speed at 48 s to a stop at 54 s.
    const speedKph = Math.round(leg < LEG_BRAKING ? demoCruiseKph(driveT)
      : leg === LEG_BRAKING
        ? (demoCruiseKph(driveT - (phase - DEMO_BRAKE_AT_S)) * (DEMO_PARK_AT_S - phase)) / DEMO_BRAKE_S
        : 0);
    const rpm = gas ? Math.round(1260 + 420 * Math.sin(driveT / 2.1)) : 0;
    // 80 °C = the design's steady 176 °F coolant.
    const coolantC = Math.round(80 + Math.sin(t / 8));
    // Throttle/load track the design's tile formulas (14+9sin / 20+7sin).
    const loadPct = moving ? Math.round(20 + 7 * Math.sin(driveT / 3.3)) : 4;
    const throttlePct = leg < LEG_BRAKING ? Math.round(14 + 9 * Math.sin(driveT / 2.2)) : 0;
    const voltage = 14.2;
    // Continuous periodic sawtooth (61.2..64.8), derived from the cycle phase
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
      // The classifier's payload keys (VehicleState.asPayloadKey); braking is still an EV drive.
      vehicleState: charging ? "charging" : !moving ? "parked" : gas ? "driving_gas" : "driving_ev",
      speedKph,
      rpm,
      coolantC,
      loadPct,
      throttlePct,
      voltage,
      soc,
      // °C — 22.8 °C reads as the design demo's steady 73 °F pack temp.
      // telemetry.ts renders batteryTemp via units.tempText (°C→°F when needed).
      batteryTemp: 22.8 + 0.3 * Math.sin(t / 8),
      // GPS fix quality for the design's "±4 m" GPS tile (±13 ft imperial).
      accuracyM: 4,
      // EV distance driven this cycle (222487) — ~0.9 km/min over the drive
      // phase. Distinct from the range estimate below; the UI must never label
      // this one as "EV range".
      evDistanceThisCycleKm: Number((Math.min(phase, DEMO_PARK_AT_S) * 0.015).toFixed(1)),
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
      motorAPowerKw: moving ? Number((powerKw * 0.6).toFixed(1)) : 0,
      transmissionTempC: Number((68 + 3 * Math.sin(t / 7)).toFixed(1)),
      prndlState: moving ? "D" : "P",
      // Raw PRNDL codes (VoltGear.kt): 8 = Park, 3 = Drive, both confirmed from field logs.
      prndlRaw: moving ? 3 : 8,
      gearConfidence: "confirmed",
      motorTempC: Number((55 + 5 * Math.sin(t / 9)).toFixed(1)),
      inverterTempC: Number((42 + 3 * Math.sin(t / 8)).toFixed(1)),
      displayedSocPct: Number(soc.toFixed(1)),
      packResistanceMohm: 148.5,
      hvIsolationKohm: 2000,
      motorBTempC: Number((48 + 4 * Math.sin(t / 10)).toFixed(1)),
      // The car's own remaining EV range (2241A6), SOC-proportional off a
      // ~66 km full-charge range — at the demo's ~63% SOC this reads
      // "≈ 26 mi EV range" under the SOC number, matching the design demo.
      evRangeKm: Math.round((soc / 100) * 66),
      batteryHeaterPct: 0,
      pemCoolantTempC: Number((38 + 2 * Math.sin(t / 11)).toFixed(1)),
      lifetimeChargeEnergyKwh: 2198.1,
      packSection1TempC: 23, packSection2TempC: 24, packSection3TempC: 22,
      packSection4TempC: 23, packSection5TempC: 24, packSection6TempC: 22,
      ...(charging ? { chargerAcVoltage: 240, chargerAcCurrentA: 14, chargerAcPowerKw: 3.4 } : {}),
      // SW-CAN (GMLAN) broadcasts heard by the OBDLink listen window (mirrors DemoPollingLoop.kt).
      aux12vVoltage: charging ? 13.9 : 14.1,
      aux12vSocPct: 86,
      aux12vCurrentA: charging ? 3.5 : 6,
      tirePressureFlKpa: 260,
      tirePressureFrKpa: 264,
      tirePressureRlKpa: 256,
      tirePressureRrKpa: 260,
      doorLockState: charging ? "unlocked" : "locked",
      doorLockSource: "fob",
      doorFlState: "closed",
      doorFrState: "closed",
      doorRlState: "closed",
      doorRrState: "closed",
      hoodState: "closed",
      trunkState: "closed",
      windowFlPct: 0,
      windowFrPct: 0,
      windowRlPct: 0,
      windowRrPct: 0,
      alarmState: "disarmed",
      blowerPct: 35,
      remoteStartState: "off",
      cabinTempEstC: Number((21 + Math.sin(t / 15)).toFixed(1)),
      acState: "on",
      peCoolantTempC: Number((32 + 2 * Math.sin(t / 10)).toFixed(1)),
      clusterEvRangeKm: Number(((soc / 100) * 66).toFixed(1)),
      fuelRangeKm: 471,
      ...(charging ? { chargeCurrentLimitA: 12 } : {}),
      latitude: lat,
      longitude: lng,
      updatedAt: Date.now(),
      raw: demoRawFrames({ t, speedKph, rpm, coolantC, loadPct, throttlePct, voltage, soc })
    });
  };
  emitSample();
  window.__voltDemoTimer = window.setInterval(emitSample, 1000);
}

actionModulesRegistry().runBrowserDemoStream = runBrowserDemoStream;
