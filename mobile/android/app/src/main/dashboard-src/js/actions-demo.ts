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

export function runBrowserDemoStream(
  VD: VoltDashboard,
  state: DashboardState,
) {
  let t = 0;
  VD.setStatus({ state: "connected", detail: "Browser-only demo is running." });
  // Begin from an empty live route so a re-started browser demo doesn't append onto the
  // previous run's track (stopDemo/stopAll clear it, but a bare start would not).
  if (typeof VD.clearLivePosition === "function") VD.clearLivePosition();
  else { state.liveRoutePoints = []; state.liveRouteStartedAtMs = null; }
  window.clearInterval(window.__voltDemoTimer ?? undefined);
  const emitSample = () => {
    t += 1;
    const gas = Math.floor(t / 30) % 2 === 1;
    state.mode = gas ? "gas" : "ev";
    const powerKw = gas ? 30 + Math.sin(t / 3) * 9 : 9 + Math.sin(t / 2.2) * 22;
    const routeDrift = Math.sin(t / 40);
    const lat = 34.11872 + routeDrift * 0.004;
    const lng = -118.30064 - Math.abs(routeDrift) * 0.012;
    const speedKph = Math.round(54 + 23 * Math.sin(t / 3.4));
    const rpm = gas ? Math.round(1260 + 420 * Math.sin(t / 2.1)) : 0;
    const coolantC = Math.round(82 + 4 * Math.sin(t / 8));
    const loadPct = Math.round(34 + 18 * Math.sin(t / 4.4));
    const throttlePct = Math.round(18 + 14 * Math.sin(t / 2.7));
    const voltage = 13.8;
    const soc = Math.max(13.4, 77.8 - t * 0.01);
    // HV cell-group balance for the Battery-tab cell card: a healthy pack with
    // a gentle 10–20 mV wobble around ~3.9 V. Cell 47 rides the low side to
    // match the "Cell 47 trending low" demo insight.
    const cellAvgV = 3.85 + (soc - 50) * 0.003;
    const cellSpreadMv = Math.round(14 + 6 * Math.sin(t / 9));
    const minCellVoltage = Number((cellAvgV - cellSpreadMv / 2000).toFixed(3));
    const maxCellVoltage = Number((cellAvgV + cellSpreadMv / 2000).toFixed(3));
    VD.updateTelemetry({
      source: "demo",
      connected: true,
      sampleCount: t,
      sessionMs: t * 1000,
      supportedPids: "browser demo",
      vehicleState: powerKw < -0.5 ? "regen" : (gas ? "driving (gas)" : "driving"),
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
