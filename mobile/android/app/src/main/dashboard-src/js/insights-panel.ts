// insights-panel.ts — the Trips tab (logged-drive list, selection, per-trip
// route preview + Leaflet mini-maps) and the Insights tab stats + the
// efficiency-vs-speed scatter.
//
// Split out of the old panels.ts god-module (C2). Render entry points are
// attached to the shared VD global exactly as before. The native-read-error
// helpers (isNativeError / reportNativeReadError) and the toggleHidden helper
// are owned by storage-status.ts and read off VD here.
//
// haversineMetersJs comes from map-route-utils — a small shared module that is
// already part of the eager app.js bundle (telemetry.ts imports it), so this
// import does not drag the lazy map chunk into the main bundle.
import {
  ASSUMED_VOLT_MI_PER_KWH,
  computeSavingsVsGas,
  formatSignedMoney,
  savingsPrefsReady
} from "./cost-model";
import { haversineMetersJs, numOrNaN } from "./map-route-utils";
import { prefs, units } from "./prefs";

(function () {
  "use strict";

  const VD = window.VoltDashboard;
  const state = VD.state;
  const bridge = VD.bridge;
  const el = VD.el;
  const setSvgAttrs = VD.setSvgAttrs;
  const FORCE_LAZY_READ = true;
  let tripsReadInFlight = false;
  let insightsReadInFlight = false;

  function applyTripsPayload(payload: unknown) {
    tripsReadInFlight = false;
    const parsed = VD.parsePayload<VoltTrip[]>(payload, []);
    if (VD.isNativeError(parsed)) {
      const err = parsed as VoltNativeError;
      VD.reportNativeReadError(parsed, "Could not read logged trips.");
      state.tripsLoaded = false;
      state.tripsReadError = err.message || "Could not read logged trips.";
      state.trips = [];
    } else if (state.demoActive && Array.isArray(state.demoPreviewTrips)) {
      state.tripsLoaded = true;
      // Park real trips behind the demo preview (cross-module demo invariant).
      VD.setState({ realTrips: Array.isArray(parsed) ? parsed : [] });
    } else {
      state.tripsLoaded = true;
      state.tripsReadError = null;
      state.trips = Array.isArray(parsed) ? parsed : [];
    }
    VD.renderMapIfLoaded();
    renderDriveTrend();
    renderTempEfficiency();
    renderThisWeek();
  }

  function handleTripsBridgeFailure() {
    tripsReadInFlight = false;
    VD.reportNativeReadError(
      {
        ok: false,
        error: "trips_read_failed",
        message: "Could not read logged trips."
      },
      "Could not read logged trips."
    );
    state.tripsLoaded = false;
    state.tripsReadError = "Could not read logged trips.";
    state.trips = [];
    VD.renderMapIfLoaded();
  }

  function loadTrips(force = false) {
    if (!force && state.tripsLoaded) return;
    if (!force && tripsReadInFlight) return;
    try {
      if (bridge && typeof bridge.requestTrips === "function" && bridge.requestTrips()) {
        tripsReadInFlight = true;
        return;
      }
      if (bridge && typeof bridge.getTrips === "function") {
        applyTripsPayload(bridge.getTrips());
      } else {
        tripsReadInFlight = false;
        state.tripsLoaded = true;
      }
    } catch (_err) {
      handleTripsBridgeFailure();
    }
  }

  function applyInsightsPayload(payload: unknown) {
    insightsReadInFlight = false;
    const parsed = VD.parsePayload<VoltInsights>(payload, {});
    if (VD.isNativeError(parsed)) {
      const err = parsed as VoltNativeError;
      VD.reportNativeReadError(parsed, "Could not read vehicle insights.");
      state.insightsLoaded = false;
      state.insightsReadError = err.message || "Could not read vehicle insights.";
      state.insights = {};
    } else if (state.demoActive && state.demoPreviewInsights) {
      state.insightsLoaded = true;
      // Park real insights behind the demo preview (cross-module demo invariant).
      VD.setState({ realInsights: parsed });
    } else {
      state.insightsLoaded = true;
      state.insightsReadError = null;
      state.insights = parsed;
    }
    renderInsightStats();
    renderInsightScatter();
  }

  function handleInsightsBridgeFailure() {
    insightsReadInFlight = false;
    VD.reportNativeReadError(
      {
        ok: false,
        error: "insights_read_failed",
        message: "Could not read vehicle insights."
      },
      "Could not read vehicle insights."
    );
    state.insightsLoaded = false;
    state.insightsReadError = "Could not read vehicle insights.";
    state.insights = {};
    renderInsightStats();
    renderInsightScatter();
  }

  function loadInsights(force = false) {
    if (!force && state.insightsLoaded) {
      renderInsightStats();
      renderInsightScatter();
      return;
    }
    if (!force && insightsReadInFlight) return;
    try {
      if (bridge && typeof bridge.requestInsights === "function" && bridge.requestInsights()) {
        insightsReadInFlight = true;
        return;
      }
      if (bridge && typeof bridge.getInsights === "function") {
        applyInsightsPayload(bridge.getInsights());
      } else {
        insightsReadInFlight = false;
        state.insightsLoaded = true;
        renderInsightStats();
        renderInsightScatter();
      }
    } catch (_err) {
      handleInsightsBridgeFailure();
    }
  }

  function renderInsightStats() {
    const insights = state.insights || {};
    const trips = Number(insights.tripCount || 0);
    renderInsightsEmptyState();
    VD.setText("insightTripCount", trips ? `${trips} ${trips === 1 ? "drive" : "drives"}` : "--");
    VD.setText("insightTotalDistance", trips ? VD.formatDistance(Number(insights.totalDistanceMeters || 0)) : "--");
    VD.setText("insightDriveTime", Number(insights.totalDriveMs) > 0 ? VD.formatDuration(Number(insights.totalDriveMs)) : "--");
    VD.setText("insightTopSpeed", Number(insights.maxSpeedKph) > 0 ? units.speedText(Number(insights.maxSpeedKph)) : "--");
    VD.setText("insightLongest", Number(insights.longestTripMeters) > 0 ? VD.formatDistance(Number(insights.longestTripMeters)) : "--");
    VD.setText("insightGpsTrips", trips ? `${Number(insights.gpsTripCount || 0)}/${trips}` : "--");
    // Lifetime "% of driving on electric" (speed-weighted over classified EV vs
    // gas samples). Null until the classifier has seen real driving, so keep
    // the placeholder rather than a misleading 0%.
    const evPct = insights.electricDrivingPct == null ? NaN : Number(insights.electricDrivingPct);
    VD.setText("insightElectricPct", Number.isFinite(evPct) ? `${Math.round(evPct)}%` : "--");
    renderSavingsVsGas();
    renderDriveTrend();
    renderTempEfficiency();
    renderThisWeek();
  }

  // ----- temperature vs range (v2 design) -------------------------------------
  // Dot-per-drive scatter: x = the trip's window-averaged outside temperature,
  // y = the EV range that trip's REAL efficiency implies (mi/kWh × usable pack
  // energy) — the honest answer to "what does temperature do to my range?",
  // with the peak called out ("Range peaks near 71°F — about 54 mi"). Hidden
  // until three or more drives carry both an ambient reading and HV energy.

  // Mirrors telemetry.ts VOLT_USABLE_KWH (nominal Gen-2 usable pack energy).
  const TEMP_RANGE_USABLE_KWH = 14;
  // Guard against absurd short-trip artifacts (a 0.2 kWh rollup on a downhill
  // half-mile reads as 40 mi/kWh) — beyond any real Volt efficiency.
  const TEMP_RANGE_MAX_MI_PER_KWH = 10;

  function renderTempEfficiency() {
    const card = el("tempEffCard");
    if (!card) return;
    const trips = (Array.isArray(state.trips) ? state.trips : []) as VoltTrip[];
    const pts: Array<{ tempC: number; rangeMi: number }> = [];
    for (const trip of trips) {
      const tempC = trip.avgOutsideTempC == null ? NaN : Number(trip.avgOutsideTempC);
      const energy = trip.energyKwh == null ? NaN : Number(trip.energyKwh);
      const meters = Number(trip.distanceMeters);
      if (!Number.isFinite(tempC) || !Number.isFinite(energy) || energy <= 0 || !(meters > 0)) continue;
      const miPerKwh = meters / 1609.344 / energy;
      if (!(miPerKwh > 0) || miPerKwh > TEMP_RANGE_MAX_MI_PER_KWH) continue;
      pts.push({ tempC, rangeMi: miPerKwh * TEMP_RANGE_USABLE_KWH });
    }
    if (pts.length < 3) {
      card.hidden = true;
      return;
    }
    card.hidden = false;
    pts.sort((a, b) => a.tempC - b.tempC);
    // Peak = the best 5°C bucket mean (a single lucky drive shouldn't set the
    // headline); ties resolve to the warmer bucket, matching the physics.
    const buckets = new Map<number, { sum: number; n: number }>();
    for (const p of pts) {
      const key = Math.round(p.tempC / 5) * 5;
      const b = buckets.get(key) || { sum: 0, n: 0 };
      b.sum += p.rangeMi;
      b.n += 1;
      buckets.set(key, b);
    }
    let peakTempC = pts[0]!.tempC;
    let peakRangeMi = 0;
    for (const [key, b] of buckets) {
      const mean = b.sum / b.n;
      if (mean > peakRangeMi || (mean === peakRangeMi && key > peakTempC)) {
        peakRangeMi = mean;
        peakTempC = key;
      }
    }
    const peakRangeText = units.distanceText(peakRangeMi * KM_PER_MILE);
    VD.setText("tempEffHead", `Range peaks near ${units.tempText(peakTempC)} — about ${peakRangeText}`);
    const chart = el("tempEffChart");
    if (chart) {
      chart.replaceChildren(buildTempRangeSvg(pts, peakTempC));
    }
  }

  // The scatter SVG: temp on x (data-driven domain), estimated range on y,
  // one dot per drive, dots in the peak temperature bucket highlighted.
  function buildTempRangeSvg(pts: Array<{ tempC: number; rangeMi: number }>, peakTempC: number) {
    const token = (name: string, fallback: string) => {
      const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
      return v || fallback;
    };
    const lineColor = token("--line-soft", "rgba(255,255,255,0.06)");
    const axisColor = token("--soft", "#8b8c99");
    const dotColor = token("--ev", "#b8e63b");
    const svgNs = "http://www.w3.org/2000/svg";
    const w = 330;
    const h = 140;
    const padL = 30;
    const padR = 10;
    const padT = 10;
    const padB = 22;
    const tMin = Math.floor((pts[0]!.tempC - 2) / 5) * 5;
    const tMax = Math.ceil((pts[pts.length - 1]!.tempC + 2) / 5) * 5;
    const rMax = pts.reduce((m, p) => Math.max(m, p.rangeMi), 1);
    const rMin = pts.reduce((m, p) => Math.min(m, p.rangeMi), rMax);
    const rLo = Math.max(0, Math.floor(rMin - 3));
    const rHi = Math.ceil(rMax + 3);
    const xOf = (t: number) => padL + ((t - tMin) / Math.max(1, tMax - tMin)) * (w - padL - padR);
    const yOf = (r: number) => padT + (1 - (r - rLo) / Math.max(1, rHi - rLo)) * (h - padT - padB);
    const svg = document.createElementNS(svgNs, "svg");
    setSvgAttrs(svg, {
      viewBox: `0 0 ${w} ${h}`,
      role: "img",
      "aria-label": "Estimated EV range for each drive against outside temperature"
    });
    svg.style.width = "100%";
    svg.style.display = "block";
    const add = (tag: string, attrs: Record<string, string | number>, text?: string) => {
      const node = setSvgAttrs(document.createElementNS(svgNs, tag), attrs);
      if (text != null) node.textContent = text;
      svg.append(node);
    };
    // Horizontal range gridlines + labels (3 steps).
    for (let i = 0; i <= 2; i += 1) {
      const r = rLo + ((rHi - rLo) * i) / 2;
      add("line", { x1: padL, y1: yOf(r), x2: w - padR, y2: yOf(r), stroke: lineColor });
      add(
        "text",
        {
          x: padL - 5,
          y: yOf(r) + 3,
          fill: axisColor,
          "font-size": 9,
          "text-anchor": "end",
          "font-family": "ui-monospace,monospace"
        },
        units.distanceKm(r * KM_PER_MILE).value
      );
    }
    // Temperature ticks every 10 in display units' source scale (°C buckets).
    for (let t = tMin; t <= tMax; t += 10) {
      add(
        "text",
        {
          x: xOf(t),
          y: h - padB + 14,
          fill: axisColor,
          "font-size": 9,
          "text-anchor": "middle",
          "font-family": "ui-monospace,monospace"
        },
        units.tempText(t)
      );
    }
    // Trend path through the temp-sorted points (bucket means keep it calm).
    const meanByBucket = new Map<number, { sum: number; n: number }>();
    for (const p of pts) {
      const key = Math.round(p.tempC / 5) * 5;
      const b = meanByBucket.get(key) || { sum: 0, n: 0 };
      b.sum += p.rangeMi;
      b.n += 1;
      meanByBucket.set(key, b);
    }
    const meanPts = [...meanByBucket.entries()]
      .map(([t, b]) => ({ t, r: b.sum / b.n }))
      .sort((a, b) => a.t - b.t);
    if (meanPts.length >= 2) {
      const d = meanPts
        .map((p, i) => `${i ? "L" : "M"}${xOf(p.t).toFixed(1)},${yOf(p.r).toFixed(1)}`)
        .join(" ");
      add("path", {
        d,
        fill: "none",
        stroke: dotColor,
        "stroke-width": 2,
        "stroke-linejoin": "round",
        opacity: 0.5
      });
    }
    // One dot per drive; the peak bucket's dots read solid, the rest muted.
    for (const p of pts) {
      const inPeak = Math.round(p.tempC / 5) * 5 === peakTempC;
      add("circle", {
        cx: xOf(p.tempC).toFixed(1),
        cy: yOf(p.rangeMi).toFixed(1),
        r: 4,
        fill: dotColor,
        opacity: inPeak ? 1 : 0.45,
        stroke: token("--bg-top", "#12131a"),
        "stroke-width": 1.5
      });
    }
    return svg;
  }

  // ----- this week (per-day bars, Monday-start) ------------------------------
  // The last seven weekday slots of the CURRENT week, with a Distance /
  // Efficiency mode toggle (persisted like the efficiency-chart view). Hidden
  // until at least one drive landed this week.
  const WEEK_DAY_LABELS = ["M", "T", "W", "T", "F", "S", "S"];
  const WEEK_DAY_NAMES = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];
  const DAY_MS = 86400000;

  function weekChartMode(): "dist" | "eff" {
    return prefs.get<string>("weekChartMode", "dist") === "eff" ? "eff" : "dist";
  }

  function renderThisWeek() {
    const card = el("thisWeekCard");
    if (!card) return;
    const trips = (Array.isArray(state.trips) ? state.trips : []) as VoltTrip[];
    // Monday 00:00 local of the current week.
    const today = new Date();
    const midnight = new Date(today.getFullYear(), today.getMonth(), today.getDate());
    const monday = new Date(midnight.getTime() - ((midnight.getDay() + 6) % 7) * DAY_MS);
    const startMs = monday.getTime();
    const days = WEEK_DAY_LABELS.map(() => ({ miles: 0, kwh: 0, trips: 0 }));
    for (const trip of trips) {
      const at = Number(trip.startedAtMs);
      const meters = Number(trip.distanceMeters);
      if (!(at >= startMs) || !(meters > 0)) continue;
      // Index by local calendar day (not raw ms arithmetic) so a DST shift
      // mid-week can't push an evening drive into the next column.
      const when = new Date(at);
      const idx = (when.getDay() + 6) % 7;
      const day = days[idx];
      day.miles += meters / 1609.344;
      day.trips += 1;
      const energy = trip.energyKwh == null ? NaN : Number(trip.energyKwh);
      if (Number.isFinite(energy) && energy > 0) day.kwh += energy;
    }
    const totalTrips = days.reduce((acc, day) => acc + day.trips, 0);
    if (!totalTrips) {
      card.hidden = true;
      return;
    }
    card.hidden = false;
    const metric = units.system() === "metric";
    const mode = weekChartMode();
    // Per-day efficiency computed once — the chart values and the best-day
    // headline both read from it.
    const dayEffs = days.map((day) => (day.kwh > 0 ? day.miles / day.kwh : 0));
    const values = days.map((day, i) => {
      if (mode === "eff") {
        const eff = dayEffs[i] as number;
        return metric ? eff * KM_PER_MILE : eff;
      }
      return metric ? day.miles * KM_PER_MILE : day.miles;
    });
    if (mode === "eff") {
      let bestIdx = -1;
      let bestEff = 0;
      dayEffs.forEach((eff, i) => {
        if (eff > bestEff) {
          bestEff = eff;
          bestIdx = i;
        }
      });
      VD.setText(
        "thisWeekHead",
        bestIdx >= 0
          ? `Best day ${units.efficiencyText(bestEff)} — ${WEEK_DAY_NAMES[bestIdx]}`
          : "No HV energy logged this week yet."
      );
    } else {
      const totalMiles = days.reduce((acc, day) => acc + day.miles, 0);
      VD.setText(
        "thisWeekHead",
        `${totalTrips} drive${totalTrips === 1 ? "" : "s"} · ${VD.formatDistance(totalMiles * 1609.344)} this week`
      );
    }
    const chart = el("thisWeekChart");
    if (chart) {
      const aria = mode === "eff" ? "Efficiency per day this week" : "Distance per day this week";
      chart.replaceChildren(VD.buildMonthlyTrendSvg(WEEK_DAY_LABELS, values, aria, chart));
    }
  }

  function syncWeekModeButtons() {
    const active = weekChartMode();
    document.querySelectorAll<HTMLElement>("[data-week-mode]").forEach((btn) => {
      const on = btn.getAttribute("data-week-mode") === active;
      btn.classList.toggle("is-active", on);
      btn.setAttribute("aria-pressed", String(on));
    });
  }

  (function bindWeekModeSwitch() {
    const group = el("weekModeSwitch");
    if (!group) return;
    group.addEventListener("click", (event) => {
      const target =
        event.target instanceof Element ? event.target.closest("[data-week-mode]") : null;
      if (!target) return;
      prefs.set("weekChartMode", target.getAttribute("data-week-mode") || "dist");
      syncWeekModeButtons();
      renderThisWeek();
    });
    syncWeekModeButtons();
  })();

  // ----- monthly driving trend (Insights tab) --------------------------------
  // Buckets the logged trips by calendar month (distance always; energy when the
  // drive logged HV power) and plots monthly distance, mirroring the monthly
  // charging trend on the Battery tab. The stats row derives real efficiency
  // (distance over integrated energy) and, with an electricity rate set, an
  // estimated cost per mile/km. Hidden until two months of drives exist.
  function renderDriveTrend() {
    const card = el("driveTrendCard");
    if (!card) return;
    const trips = (Array.isArray(state.trips) ? state.trips : []) as VoltTrip[];
    type DriveBucket = { label: string; ms: number; meters: number };
    const byKey = new Map<string, DriveBucket>();
    let totalMeters = 0;
    // Efficiency pairs distance and energy from the SAME trips, so drives
    // without HV power data can't skew the mi/kWh figure.
    let energyKwh = 0;
    let energyMeters = 0;
    for (const trip of trips) {
      const ms = Number(trip.startedAtMs);
      const meters = Number(trip.distanceMeters);
      if (!Number.isFinite(ms) || ms <= 0 || !Number.isFinite(meters) || meters <= 0) continue;
      const { key, label, firstMs } = VD.monthBucketKey(ms);
      const bucket = byKey.get(key);
      if (bucket) {
        bucket.meters += meters;
      } else {
        byKey.set(key, { label, ms: firstMs, meters });
      }
      totalMeters += meters;
      const tripEnergy = trip.energyKwh == null ? NaN : Number(trip.energyKwh);
      if (Number.isFinite(tripEnergy) && tripEnergy > 0) {
        energyKwh += tripEnergy;
        energyMeters += meters;
      }
    }
    const buckets = Array.from(byKey.values()).sort((a, b) => a.ms - b.ms);
    if (buckets.length < 2) {
      card.hidden = true;
      return;
    }
    card.hidden = false;
    const distText = (meters: number) => units.distanceText(meters / 1000);
    const values = buckets.map((b) => Number(units.distanceMeters(b.meters).value));
    const latest = buckets[buckets.length - 1] as DriveBucket;
    VD.setText("driveTrendLatest", distText(latest.meters));
    VD.setText("driveTrendAvg", distText(totalMeters / buckets.length));
    const miles = energyMeters / 1609.344;
    const miPerKwh = energyKwh > 0 && miles > 0 ? miles / energyKwh : NaN;
    VD.setText("driveTrendEff", Number.isFinite(miPerKwh) ? units.efficiencyText(miPerKwh) : "--");
    // Estimated electric cost per displayed-distance unit: real integrated kWh
    // billed at the home rate. Approximate (public charging is billed at the
    // home rate here), so it reads "est".
    const price = prefs.get<number>("pricePerKwh", 0);
    const unitDist = units.distanceUnit();
    VD.setText("driveTrendCostLabel", `Est cost / ${unitDist}`);
    let costText = "--";
    if (price > 0 && Number.isFinite(miPerKwh)) {
      const displayDist = Number(units.distanceMeters(energyMeters).value);
      if (displayDist > 0) costText = `$${((energyKwh * price) / displayDist).toFixed(2)}`;
    }
    VD.setText("driveTrendCost", costText);
    const chart = el("driveTrendChart");
    if (chart) {
      const aria = `Monthly driving distance trend, latest ${distText(latest.meters)}`;
      chart.replaceChildren(VD.buildMonthlyTrendSvg(buckets.map((b) => b.label), values, aria, chart));
    }
  }

  // The EV cost / savings-vs-gas math (and the assumed Volt mi/kWh it leans on,
  // because the insights payload doesn't carry the EV energy used while driving)
  // now lives in the shared cost-model module so the lifetime figure here and
  // the per-trip figure in the map trip-detail sheet can't diverge. The savings
  // note states the assumed mi/kWh so the estimate reads as approximate.
  const KM_PER_MILE = 1.609344;
  const MPS_TO_MPH = 2.2369363;

  // Per-point speed in mph. Prefers the logged speedMps; when that's missing or
  // negative, derives it from the haversine distance between the neighboring
  // points over their elapsed time. Shared by enrichRouteEff and the scatter.
  function pointMph(pts: VoltRoutePoint[], i: number) {
    let mps = Number(pts[i].speedMps);
    if (!Number.isFinite(mps) || mps < 0) {
      const a = pts[Math.max(0, i - 1)];
      const b = pts[Math.min(pts.length - 1, i + 1)];
      const dt = Math.max(1, (Number(b.atMs) - Number(a.atMs)) / 1000);
      mps = haversineMetersJs(a.lat, a.lng, b.lat, b.lng) / dt;
    }
    return Math.max(0, mps) * MPS_TO_MPH;
  }

  // Renders the savings-row note as plain text (the normal "estimated vs …"
  // assumptions line). Replaces any prompt-state children with a single text
  // node so a later real estimate can't leave a stale "Open Settings" link.
  function setSavingsNoteText(text: string) {
    const note = el("insightSavingsNote");
    if (!note) return;
    note.replaceChildren(document.createTextNode(text));
  }

  // Renders the savings-row note in its "prompt" state: a short call-to-action
  // plus a tap-through that jumps to Settings → Preferences (delegated by the
  // document-level [data-nav-jump] handler in actions.ts, so a dynamically
  // injected button works without re-binding). Mirrors the chargeEnergyHint
  // pattern in storage-status.ts but is tappable. Built with createElement /
  // textContent only (XSS-safe).
  function setSavingsNotePrompt() {
    const note = el("insightSavingsNote");
    if (!note) return;
    const text = document.createTextNode(
      "Set your MPG, gas price, and rate in Settings to estimate savings. "
    );
    const link = document.createElement("button");
    link.type = "button";
    link.className = "link-btn";
    link.dataset.navJump = "settings";
    link.textContent = "Open Settings";
    note.replaceChildren(text, link);
  }

  // Estimated lifetime savings vs an equivalent gas car. Three states:
  //   • no logged distance        → row hidden (nothing to compare yet)
  //   • distance but prefs unset   → row shown in a "prompt" state with a
  //                                  tap-through to Settings, so the savings the
  //                                  empty state advertises is discoverable
  //                                  rather than silently invisible (C3)
  //   • distance + all prefs set   → the estimated savings figure
  //   gas cost = (miles / mpg) * gasPrice
  //   EV cost  = energy_kWh * pricePerKwh, with energy estimated from distance
  //              using ASSUMED_VOLT_MI_PER_KWH (EV energy isn't in the payload).
  function renderSavingsVsGas() {
    const row = el("insightSavingsRow");
    if (!row) return;
    const insights = state.insights || {};
    const meters = Number(insights.totalDistanceMeters || 0);
    const mpg = prefs.get<number>("mpg", 0);
    const gasPrice = prefs.get<number>("gasPricePerGal", 0);
    const pricePerKwh = prefs.get<number>("pricePerKwh", 0);
    const hasDistance = meters > 0;
    const prefsReady = savingsPrefsReady(mpg, gasPrice, pricePerKwh);
    if (!hasDistance) {
      // Nothing logged yet — keep the row hidden so the card never prompts for
      // prefs the user can't act on (there's no distance to estimate against).
      row.hidden = true;
      VD.setText("insightSavings", "--");
      setSavingsNoteText("");
      return;
    }
    if (!prefsReady) {
      // Distance exists but the comparison prefs are missing — surface a prompt
      // with a path to set them instead of leaving the advertised savings
      // permanently hidden.
      row.hidden = false;
      VD.setText("insightSavings", "--");
      setSavingsNotePrompt();
      return;
    }
    const { savings } = computeSavingsVsGas({
      meters,
      mpg,
      gasPricePerGal: gasPrice,
      pricePerKwh
    });
    row.hidden = false;
    // Show the magnitude; a leading "-" would read as "you spent more" only when
    // EV electricity is pricier than the gas it replaced (rare but possible).
    VD.setText("insightSavings", formatSignedMoney(savings));
    setSavingsNoteText(
      `Estimated vs a ${Math.round(mpg)} mpg car at $${gasPrice.toFixed(2)}/gal · assumes ${ASSUMED_VOLT_MI_PER_KWH} mi/kWh`
    );
  }

  function renderInsightsEmptyState() {
    const empty = el("insightsEmptyState");
    if (!empty) return;
    // Re-evaluate the gate here, AFTER loadInsights() has refreshed
    // state.insights. setStorage runs renderRealV2Ui (which also toggles this
    // node) before the insights payload lands, so without this re-toggle the
    // empty state would be gated on stale data.
    // A read error must stay visible (it carries the Retry affordance) even
    // when a stray battery reading would otherwise hide the guide.
    if (typeof VD.hasInsightContent === "function" && typeof VD.toggleHidden === "function") {
      VD.toggleHidden("insightsEmptyState", !state.insightsReadError && VD.hasInsightContent());
    }
    const title = empty.querySelector("h2");
    const copy = empty.querySelector("p");
    const hints = empty.querySelector(".empty-hints") as HTMLElement | null;
    const existingRetry = empty.querySelector("[data-retry-insights]");
    if (!state.insightsReadError) {
      if (title) title.textContent = "Not enough data yet.";
      if (copy) {
        copy.textContent =
          "Pack health, lifetime totals, and estimated savings vs gas appear here once you've logged a few drives with the adapter connected.";
      }
      if (hints) hints.hidden = false;
      existingRetry?.remove();
      return;
    }
    if (title) title.textContent = "Insights could not load.";
    if (copy) copy.textContent = state.insightsReadError;
    if (hints) hints.hidden = true;
    if (!existingRetry) {
      const retry = document.createElement("button");
      retry.type = "button";
      retry.className = "link-btn";
      retry.dataset.retryInsights = "true";
      retry.textContent = "Retry";
      retry.addEventListener("click", () => loadInsights());
      empty.append(retry);
    }
  }

  // ----- Efficiency vs Speed scatter (Insights tab) -------------------------
  // Pools per-point efficiency from every recent route. Efficiency is derived
  // by time-joining the route's `powerTrack` onto its points and computing
  // mi/kWh with a +/-8-sample window. The card stays hidden until enough
  // samples carry derived eff (which depends on the OBD loop having captured
  // battery current via the Volt 7E1 PIDs).

  function enrichRouteEff(route: VoltRoute) {
    if (!route || route._effDone) return;
    const pts = route.points || [];
    const track = (route.powerTrack || []).filter((s: PowerTrackSample) =>
      Number.isFinite(Number(s.powerKw))
    );
    if (pts.length < 2 || track.length < 2) return;
    route._effDone = true;
    const powerAt = (atMs: number) => {
      if (atMs <= track[0].atMs) return Number(track[0].powerKw);
      const last = track[track.length - 1];
      if (atMs >= last.atMs) return Number(last.powerKw);
      for (let i = 1; i < track.length; i += 1) {
        if (track[i].atMs >= atMs) {
          const a = track[i - 1];
          const b = track[i];
          const t = (atMs - a.atMs) / ((b.atMs - a.atMs) || 1);
          return Number(a.powerKw) + (Number(b.powerKw) - Number(a.powerKw)) * t;
        }
      }
      return Number(last.powerKw);
    };
    const mphArr = pts.map((_p, i) => pointMph(pts, i));
    // Drop regen samples (kW < 0) from the per-point efficiency average. Including them with
    // the old `Math.max(60, s/c)` clamp folded every regen-dominant segment into the same
    // upper-bound green color band as a high-efficiency cruise — visually identical and
    // misleading. Tagging them as null instead leaves the regen segments grey (the
    // mapEffColor `eff == null` branch in map.js), making downhill / regen runs visibly
    // distinct from drive efficiency.
    const whmiInst = pts.map((p, i) => {
      if (mphArr[i] <= 4) return NaN;
      const kW = powerAt(Number(p.atMs));
      if (!Number.isFinite(kW)) return NaN;
      if (kW <= 0) return NaN;
      return (kW * 1000) / mphArr[i];
    });
    for (let i = 0; i < pts.length; i += 1) {
      let s = 0;
      let c = 0;
      for (let k = -8; k <= 8; k += 1) {
        const j = i + k;
        if (j >= 0 && j < pts.length && Number.isFinite(whmiInst[j])) {
          s += whmiInst[j];
          c += 1;
        }
      }
      if (!c) {
        pts[i].eff = null;
        continue;
      }
      const whmi = s / c;
      pts[i].eff = Math.max(0.8, Math.min(6.5, 1000 / whmi));
    }
  }

  // Wh/mi removed per unit of road grade when grade-normalizing efficiency:
  // gravitational energy over one mile (m·g·1609 J), converted to Wh (/3600) and
  // scaled by a ~0.7 regen/drivetrain round-trip factor. Volt curb mass ≈ 1715 kg
  // → 1715·9.81·1609/3600·0.7 ≈ 5200. Subtracting grade·this from observed Wh/mi
  // recovers a flat-equivalent figure so a fast descent stops faking a high-speed
  // efficiency peak.
  const GRADE_WHMI_PER_UNIT = 5200;

  // User-selectable chart styles for the efficiency-vs-speed card (persisted via
  // prefs so the choice sticks across launches). "scatter" stays the default so
  // the existing per-sample view is unchanged for anyone who never toggles.
  const EFF_CHART_VIEWS = ["bars", "scatter", "curve"] as const;
  type EffChartView = (typeof EFF_CHART_VIEWS)[number];
  type EffBucket = { mid: number; n: number; med: number; q1: number; q3: number };
  type EffPoint = { mph: number; eff: number; effFlat: number; grade: number };

  function scatterView(): EffChartView {
    const v = prefs.get<string>("effChartView", "scatter");
    return (EFF_CHART_VIEWS as readonly string[]).includes(v) ? (v as EffChartView) : "scatter";
  }

  function quantileJs(values: number[], q: number): number {
    const a = values.slice().sort((x, y) => x - y);
    if (!a.length) return 0;
    const pos = (a.length - 1) * q;
    const lo = Math.floor(pos);
    const hi = Math.ceil(pos);
    return a[lo] + (a[hi] - a[lo]) * (pos - lo);
  }

  function hideOutliers(): boolean {
    return prefs.get<boolean>("effHideOutliers", false);
  }

  // Optional outlier rejection (off by default): drop samples beyond a 1.5×IQR
  // fence on grade-normalized efficiency, computed per 10-mph speed bucket. Only
  // buckets with >=4 samples are fenced (the IQR is unreliable below that); smaller
  // buckets pass through untouched. Applied to the whole pool so the dots, the
  // median/IQR buckets, and the city/highway averages all exclude the same points.
  function rejectEffOutliers(pool: EffPoint[]): EffPoint[] {
    const groups: Record<number, number[]> = {};
    pool.forEach((p) => {
      const b = Math.floor(p.mph / 10);
      (groups[b] = groups[b] || []).push(p.effFlat);
    });
    const fence: Record<number, { lo: number; hi: number }> = {};
    Object.keys(groups).forEach((k) => {
      const arr = groups[Number(k)];
      if (arr.length < 4) return;
      const q1 = quantileJs(arr, 0.25);
      const q3 = quantileJs(arr, 0.75);
      const iqr = q3 - q1;
      fence[Number(k)] = { lo: q1 - 1.5 * iqr, hi: q3 + 1.5 * iqr };
    });
    return pool.filter((p) => {
      const f = fence[Math.floor(p.mph / 10)];
      return !f || (p.effFlat >= f.lo && p.effFlat <= f.hi);
    });
  }

  // Smooth a median series with a 3-point moving average so the curve view reads
  // as a trend rather than a zig-zag of noisy buckets.
  function smooth3(vals: number[]): number[] {
    return vals.map((v, i) => {
      const a = vals[Math.max(0, i - 1)];
      const c = vals[Math.min(vals.length - 1, i + 1)];
      return (a + v + c) / 3;
    });
  }

  const EFF_SVGNS = "http://www.w3.org/2000/svg";
  function effNode(tag: string, attrs: Record<string, string | number>) {
    return setSvgAttrs(document.createElementNS(EFF_SVGNS, tag), attrs);
  }

  // Per-sample dots (grade-normalized, single accent). Kept for the "scatter"
  // view; the grade is folded into effFlat so the third color dimension is gone.
  function renderScatterDots(
    svg: SVGElement,
    pool: EffPoint[],
    xOf: (mph: number) => number,
    yS: (e: number) => number,
    color: string
  ) {
    pool.forEach((p) => {
      svg.append(
        effNode("circle", {
          cx: xOf(p.mph).toFixed(1),
          cy: yS(p.effFlat).toFixed(1),
          r: 2.8,
          fill: color,
          "fill-opacity": 0.32
        })
      );
    });
  }

  function bucketTrendPath(
    buckets: EffBucket[],
    series: number[],
    xOf: (mph: number) => number,
    yS: (e: number) => number
  ): string {
    let d = "";
    buckets.forEach((b, i) => {
      d += `${i ? "L" : "M"}${xOf(b.mid).toFixed(1)} ${yS(series[i]).toFixed(1)} `;
    });
    return d;
  }

  function appendTrendLine(
    svg: SVGElement,
    buckets: EffBucket[],
    xOf: (mph: number) => number,
    yS: (e: number) => number,
    color: string
  ) {
    if (buckets.length < 2) return;
    svg.append(
      effNode("path", {
        d: bucketTrendPath(buckets, buckets.map((b) => b.med), xOf, yS),
        fill: "none",
        stroke: color,
        "stroke-width": 2.5,
        "stroke-linejoin": "round",
        "stroke-linecap": "round"
      })
    );
  }

  // An interquartile band (q1..q3 across buckets) drawn as a filled ribbon. CSS
  // vars / color-mix don't resolve inside SVG presentation attributes, so callers
  // pass a resolved literal color plus a separate fill-opacity.
  function appendIqrBand(
    svg: SVGElement,
    buckets: EffBucket[],
    q1s: number[],
    q3s: number[],
    xOf: (mph: number) => number,
    yS: (e: number) => number,
    fill: string,
    opacity: number
  ) {
    if (buckets.length < 2) return;
    let top = "";
    buckets.forEach((b, i) => {
      top += `${i ? "L" : "M"}${xOf(b.mid).toFixed(1)} ${yS(q3s[i]).toFixed(1)} `;
    });
    let bot = "";
    for (let i = buckets.length - 1; i >= 0; i -= 1) {
      bot += `L${xOf(buckets[i].mid).toFixed(1)} ${yS(q1s[i]).toFixed(1)} `;
    }
    svg.append(effNode("path", { d: `${top}${bot}Z`, fill, "fill-opacity": opacity, stroke: "none" }));
  }

  function appendPeakMarker(
    svg: SVGElement,
    peak: EffBucket,
    yVal: number,
    xOf: (mph: number) => number,
    yS: (e: number) => number,
    color: string,
    bg: string,
    label: string
  ) {
    svg.append(
      effNode("circle", {
        cx: xOf(peak.mid).toFixed(1),
        cy: yS(yVal).toFixed(1),
        r: 4,
        fill: color,
        stroke: bg,
        "stroke-width": 1.5
      })
    );
    if (label) {
      const node = effNode("text", {
        x: xOf(peak.mid).toFixed(1),
        y: (yS(yVal) - 8).toFixed(1),
        fill: color,
        "font-size": 10,
        "font-weight": 700,
        "text-anchor": "middle",
        "font-family": "system-ui,sans-serif"
      });
      node.textContent = label;
      svg.append(node);
    }
  }

  // Bars view: one bar per 10-mph bucket at its median efficiency, an IQR whisker
  // for spread, and the buckets within 5% of the best highlighted as the sweet
  // spot.
  function renderBuckets(
    svg: SVGElement,
    buckets: EffBucket[],
    peak: EffBucket | undefined,
    xOf: (mph: number) => number,
    yS: (e: number) => number,
    padT: number,
    colors: { accent: string; idleBar: string; idleWhisker: string; bg: string }
  ) {
    if (!peak) return;
    const thresh = peak.med * 0.95;
    const y0 = yS(0);
    const fullW = xOf(15) - xOf(5);
    const bw = fullW * 0.58;
    buckets.forEach((b) => {
      const cx = xOf(b.mid);
      const sweet = b.med >= thresh;
      if (sweet) {
        svg.append(
          effNode("rect", {
            x: (cx - fullW / 2).toFixed(1),
            y: padT,
            width: fullW.toFixed(1),
            height: (y0 - padT).toFixed(1),
            fill: colors.accent,
            "fill-opacity": 0.08
          })
        );
      }
      svg.append(
        effNode("rect", {
          x: (cx - bw / 2).toFixed(1),
          y: yS(b.med).toFixed(1),
          width: bw.toFixed(1),
          height: Math.max(0, y0 - yS(b.med)).toFixed(1),
          rx: 3,
          fill: sweet ? colors.accent : colors.idleBar
        })
      );
      svg.append(
        effNode("line", {
          x1: cx.toFixed(1),
          y1: yS(b.q1).toFixed(1),
          x2: cx.toFixed(1),
          y2: yS(b.q3).toFixed(1),
          stroke: sweet ? colors.accent : colors.idleWhisker,
          "stroke-width": 2
        })
      );
    });
    appendPeakMarker(svg, peak, peak.med, xOf, yS, colors.accent, colors.bg, "");
  }

  // Curve view: a smoothed median curve over a confidence band, with no per-sample
  // dots — the most glanceable style.
  function renderCurve(
    svg: SVGElement,
    buckets: EffBucket[],
    peak: EffBucket | undefined,
    xOf: (mph: number) => number,
    yS: (e: number) => number,
    accent: string,
    bg: string
  ) {
    if (buckets.length < 2 || !peak) return;
    const meds = smooth3(buckets.map((b) => b.med));
    const q1s = smooth3(buckets.map((b) => b.q1));
    const q3s = smooth3(buckets.map((b) => b.q3));
    appendIqrBand(svg, buckets, q1s, q3s, xOf, yS, accent, 0.14);
    svg.append(
      effNode("path", {
        d: bucketTrendPath(buckets, meds, xOf, yS),
        fill: "none",
        stroke: accent,
        "stroke-width": 3,
        "stroke-linejoin": "round",
        "stroke-linecap": "round"
      })
    );
    const peakIdx = buckets.findIndex((b) => b.mid === peak.mid);
    appendPeakMarker(svg, peak, peakIdx >= 0 ? meds[peakIdx] : peak.med, xOf, yS, accent, bg, "");
  }

  function renderInsightScatter() {
    const card = el("effScatterCard");
    const chart = el("effScatter");
    const head = el("effScatterHead");
    const statsEl = el("effScatterStats");
    if (!card || !chart) return;
    const routes =
      state.storage && Array.isArray(state.storage.recentRoutes)
        ? state.storage.recentRoutes
        : [];
    const pool: EffPoint[] = [];
    // "Drives" reports routes that actually contributed a plotted sample, not
    // every recentRoute: City/Highway averages pool only routes that clear
    // enrichRouteEff (>=2 points AND >=2 in-range power samples), so counting
    // routes.length beside them overstated the drive count.
    let contributingRoutes = 0;
    routes.forEach((route) => {
      enrichRouteEff(route);
      const pts = (route && route.points) || [];
      const poolBefore = pool.length;
      for (let i = 0; i < pts.length; i += 1) {
        // enrichRouteEff sets eff = null for all-regen/idle windows (no positive
        // drive sample in range) even when the point has real highway speed.
        // Number(null) === 0 slips past the isFinite guard, then whmi = 1000/0 =
        // Infinity clamps to a false 0.8 mi/kWh dot — drop nulls before coercing.
        if (pts[i].eff == null) continue;
        const eff = Number(pts[i].eff);
        if (!Number.isFinite(eff)) continue;
        // enrichRouteEff clamps efficiency to a 6.5 ceiling; those saturated
        // coasting/regen-tail samples otherwise pile into a solid false row along
        // the top gridline. Drop them from the scatter (and the stats/trend) so
        // the plot shows the real drive-efficiency spread, not a clamp artifact.
        if (eff >= 6.45) continue;
        const mph = pointMph(pts, i);
        // A NaN speed (pointMph can't resolve one) must be rejected, not kept:
        // `NaN < 10` is false, so the bare guard let it through and NaN then
        // poisoned fastest/axisMaxMph/xOf and blanked the whole scatter.
        if (!Number.isFinite(mph) || mph < 10) continue;
        // Grade over the SAME ±8-sample window the efficiency average spans (see
        // enrichRouteEff), not a single pts[i-1]→pts[i] segment: one segment's
        // GPS-altitude noise (a couple of metres over ~8 m of run) swings the raw
        // slope to the ±0.13 clamp and over-corrects on rolling terrain. Averaging
        // the altitude delta across the window's cumulative horizontal distance
        // matches the correction to the value it corrects.
        let grade = 0;
        const gStart = Math.max(0, i - 8);
        const gEnd = Math.min(pts.length - 1, i + 8);
        const altStart = numOrNaN(pts[gStart].altM);
        const altEnd = numOrNaN(pts[gEnd].altM);
        if (gEnd > gStart && Number.isFinite(altStart) && Number.isFinite(altEnd)) {
          let horiz = 0;
          for (let j = gStart + 1; j <= gEnd; j += 1) {
            const seg = haversineMetersJs(pts[j - 1].lat, pts[j - 1].lng, pts[j].lat, pts[j].lng);
            if (Number.isFinite(seg)) horiz += seg;
          }
          horiz = Math.max(8, horiz);
          grade = Math.max(-0.13, Math.min(0.13, (altEnd - altStart) / horiz));
        }
        // Grade-normalize: remove the gravity term from the observed Wh/mi so the
        // plotted value is a flat-equivalent efficiency. enrichRouteEff clamps eff
        // to [0.8, 6.5]; keep the flat value in [0.8, 7] (Wh/mi floored so a steep
        // descent can't divide to an absurd number).
        const whmi = 1000 / eff;
        const flatWhmi = Math.max(143, whmi - GRADE_WHMI_PER_UNIT * grade);
        const effFlat = Math.max(0.8, Math.min(7, 1000 / flatWhmi));
        pool.push({ mph, eff, effFlat, grade });
      }
      if (pool.length > poolBefore) contributingRoutes += 1;
    });
    // Card visibility tracks the raw pool so toggling outlier removal can't hide
    // the whole card; everything downstream plots the (optionally) filtered pool.
    if (pool.length < 6) {
      card.hidden = true;
      return;
    }
    card.hidden = false;
    const plotPool = hideOutliers() ? rejectEffOutliers(pool) : pool;
    // Units-aware axes (C2): the pool is always mph / (mi/kWh) internally (see
    // enrichRouteEff + pointMph), but a metric user must see km/h and km/kWh axes
    // so the chart never contradicts the headline (which already converts via the
    // units helper). Read the preference at render time — prefs.rerenderForUnits
    // re-runs this on a units toggle. Conversions mirror the units module:
    // km/h = mph * KM_PER_MILE, km/kWh = mi/kWh * KM_PER_MILE.
    const metric = units.system() === "metric";
    const speedUnitLabel = units.speedUnit(); // "km/h" | "mph"
    const effUnitLabel = units.efficiencyUnit(); // "km/kWh" | "mi/kWh"
    const speedToDisplay = (mph: number) => (metric ? mph * KM_PER_MILE : mph);
    const effToDisplay = (miPerKwh: number) => (metric ? miPerKwh * KM_PER_MILE : miPerKwh);
    // Theme-aware colors for the inline SVG. CSS variables don't cascade into
    // SVG presentation attributes (fill/stroke) here the way they do for CSS
    // properties, so read the resolved token values once and inject the
    // literals — this keeps the scatter (gridlines, axis labels, grade-coded
    // dots, trend line) legible in BOTH the dark and light themes instead of
    // hardcoding dark-only colors. Fallbacks mirror the dark token defaults.
    // Resolve on the chart host so --view-accent cascades in — the chart
    // speaks its own tab's color (purple on Insights) instead of borrowing
    // Drive orange. Blue stays as the secondary series color for dots.
    const tokens = getComputedStyle(chart);
    const token = (name: string, fallback: string) =>
      (tokens.getPropertyValue(name) || "").trim() || fallback;
    const lineColor = token("--line", "rgba(255,255,255,0.1)"); // gridlines
    const axisColor = token("--muted", "#aaaab4"); // axis tick labels
    const trendColor = token("--view-accent", token("--volt", "#ff7a45")); // best-fit trend path
    const evColor = token("--ev", "#b8e63b"); // bars/curve accent + headline
    const dotColor = token("--map-accent", "#4cc4ff"); // grade-normalized scatter dots
    const w = Math.max(300, chart.clientWidth || 360);
    const h = 280;
    const padL = 38;
    const padR = 12;
    const padT = 14;
    const padB = 28;
    // The x-axis grows with the data (a Volt reaches ~100 mph) so fast samples
    // never render outside the viewBox. The floor is the data max rounded up
    // to the next 15 mph (min 45) — the old fixed 75 left city-driving
    // datasets plotted in the left 40% of the frame.
    const fastest = plotPool.reduce((m, p) => Math.max(m, p.mph), 0);
    const axisMaxMph = Math.max(45, Math.ceil(Math.max(fastest, 1) / 15) * 15);
    const xOf = (mph: number) => padL + (mph / axisMaxMph) * (w - padL - padR);
    // Y-axis grows to the data (kept in [5,7]) instead of a fixed 0–7, so the
    // plot no longer leaves a dead empty band above the points now that the
    // saturated 6.5 pile is excluded.
    const maxEff = plotPool.reduce((m, p) => Math.max(m, p.effFlat), 0);
    const yMax = Math.min(7, Math.max(5, Math.ceil(maxEff)));
    const yS = (e: number) => padT + (1 - e / yMax) * (h - padT - padB);
    const svgNs = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(svgNs, "svg");
    svg.setAttribute("width", String(w));
    svg.setAttribute("height", String(h));
    svg.setAttribute("viewBox", `0 0 ${w} ${h}`);
    svg.setAttribute("role", "img");
    const appendLine = (attrs: Record<string, string | number>) => {
      svg.append(setSvgAttrs(document.createElementNS(svgNs, "line"), attrs));
    };
    const appendText = (text: string, attrs: Record<string, string | number>) => {
      const node = setSvgAttrs(document.createElementNS(svgNs, "text"), attrs);
      node.textContent = text;
      svg.append(node);
    };
    for (let gx = 0; gx <= axisMaxMph; gx += 15) {
      appendLine({
        x1: xOf(gx),
        y1: padT,
        x2: xOf(gx),
        y2: h - padB,
        stroke: lineColor
      });
      appendText(String(Math.round(speedToDisplay(gx))), {
        x: xOf(gx),
        y: h - padB + 15,
        fill: axisColor,
        "font-size": 10.5,
        "font-family": "ui-monospace,monospace",
        "text-anchor": "middle"
      });
    }
    for (let gy = 0; gy <= yMax; gy += 1) {
      appendLine({
        x1: padL,
        y1: yS(gy),
        x2: w - padR,
        y2: yS(gy),
        stroke: lineColor
      });
      appendText(String(Math.round(effToDisplay(gy))), {
        x: padL - 6,
        y: yS(gy) + 3,
        fill: axisColor,
        "font-size": 10.5,
        "font-family": "ui-monospace,monospace",
        "text-anchor": "end"
      });
    }
    // Aggregate the grade-normalized samples into 10-mph buckets; median + IQR per
    // bucket drive every view and the headline, so a few noisy samples can't swing
    // the story the way a per-bin mean did. Buckets with <3 samples are dropped.
    const grouped: Record<number, number[]> = {};
    plotPool.forEach((p) => {
      const b = Math.floor(p.mph / 10);
      (grouped[b] = grouped[b] || []).push(p.effFlat);
    });
    const buckets: EffBucket[] = Object.keys(grouped)
      .map((k) => {
        const arr = grouped[Number(k)];
        return {
          mid: Number(k) * 10 + 5,
          n: arr.length,
          med: quantileJs(arr, 0.5),
          q1: quantileJs(arr, 0.25),
          q3: quantileJs(arr, 0.75)
        };
      })
      .filter((b) => b.n >= 3)
      .sort((a, b) => a.mid - b.mid);
    const peak = buckets.reduce<EffBucket | undefined>(
      (m, b) => (m && m.med >= b.med ? m : b),
      undefined
    );
    // SVG presentation attributes can't read CSS vars, so resolve the extra
    // chrome tokens to literals here and pass them down.
    const idleBar = token("--fill-bold", "rgba(255,255,255,0.16)");
    const idleWhisker = token("--line-strong", "rgba(255,255,255,0.28)");
    const bgColor = token("--bg", "#07080c");
    const view = scatterView();
    if (view === "bars") {
      renderBuckets(svg, buckets, peak, xOf, yS, padT, {
        accent: evColor,
        idleBar,
        idleWhisker,
        bg: bgColor
      });
    } else if (view === "curve") {
      renderCurve(svg, buckets, peak, xOf, yS, evColor, bgColor);
    } else {
      renderScatterDots(svg, plotPool, xOf, yS, dotColor);
      appendTrendLine(svg, buckets, xOf, yS, trendColor);
    }
    const best = peak ? { e: peak.med, mph: peak.mid } : { e: 0, mph: 0 };
    appendText(`speed (${speedUnitLabel}) \u2192`, {
      x: w - padR,
      y: h - 4,
      fill: axisColor,
      "font-size": 10.5,
      "font-family": "ui-monospace,monospace",
      "text-anchor": "end"
    });
    // Y-axis unit annotation (efficiency), rotated to read up the left gutter so
    // a metric user knows the 0..7 grid is km/kWh, not mi/kWh.
    appendText(effUnitLabel, {
      x: 10,
      y: padT + (h - padT - padB) / 2,
      fill: axisColor,
      "font-size": 10.5,
      "font-family": "ui-monospace,monospace",
      "text-anchor": "middle",
      transform: `rotate(-90 10 ${(padT + (h - padT - padB) / 2).toFixed(1)})`
    });
    svg.setAttribute(
      "aria-label",
      best.e > 0
        ? `Efficiency versus speed chart; most efficient around ${units.speedText(best.mph * KM_PER_MILE)}`
        : "Efficiency versus speed chart across logged drives"
    );
    chart.replaceChildren(svg);
    if (head) {
      head.replaceChildren();
      if (best.e > 0) {
        const speed = document.createElement("b");
        speed.textContent = units.speedText(best.mph * KM_PER_MILE);
        speed.style.color = evColor;
        head.append(
          "Most efficient around ",
          speed,
          " — about " + units.efficiencyText(best.e) + "."
        );
      } else {
        head.textContent = "Log a few more drives to see your most efficient speed.";
      }
    }
    if (statsEl) {
      // Grade-normalized averages (effFlat). "Drives" counts the routes that
      // actually contributed a plotted sample (contributingRoutes) — far more
      // honest than the old per-sample "Samples", where one long trip dumped
      // hundreds of correlated points, and than routes.length, which counted
      // routes the averages never drew from. Downhill avg is gone: grade is
      // normalized out, so a city/highway split is what's left to compare.
      const city = plotPool.filter((p) => p.mph < 35).map((p) => p.effFlat);
      const hwy = plotPool.filter((p) => p.mph > 55).map((p) => p.effFlat);
      const avgText = (a: number[]) =>
        a.length ? units.efficiencyText(a.reduce((s, x) => s + x, 0) / a.length) : "--";
      statsEl.replaceChildren(
        insightStat("Drives", String(contributingRoutes)),
        insightStat("City avg", avgText(city)),
        insightStat("Highway avg", avgText(hwy))
      );
    }
  }

  function insightStat(label: string, value: string) {
    const item = document.createElement("div");
    const key = document.createElement("span");
    key.className = "kicker";
    key.textContent = label;
    const strong = document.createElement("strong");
    strong.textContent = value;
    item.append(key, strong);
    return item;
  }

  // Re-render the scatter on viewport resize (SVG sized in real pixels).
  let scatterResizeTimer: ReturnType<typeof setTimeout> | null = null;
  window.addEventListener("resize", () => {
    if (scatterResizeTimer) clearTimeout(scatterResizeTimer);
    scatterResizeTimer = setTimeout(() => {
      const card = el("effScatterCard");
      if (card && !card.hidden) renderInsightScatter();
    }, 160);
  });

  // Efficiency-chart view switcher (Bars / Scatter / Curve). The choice persists
  // via prefs and re-renders the card in place. Bound once; the segmented control
  // lives inside the (initially hidden) card, so the buttons exist at load.
  function syncEffViewButtons() {
    const active = scatterView();
    document.querySelectorAll<HTMLElement>("[data-eff-view]").forEach((btn) => {
      const on = btn.getAttribute("data-eff-view") === active;
      btn.classList.toggle("is-active", on);
      btn.setAttribute("aria-selected", String(on));
    });
  }

  (function bindEffViewSwitch() {
    const group = el("effViewSwitch");
    if (!group) return;
    group.addEventListener("click", (event) => {
      const target =
        event.target instanceof Element ? event.target.closest("[data-eff-view]") : null;
      if (!target) return;
      const v = target.getAttribute("data-eff-view") || "scatter";
      prefs.set("effChartView", v);
      syncEffViewButtons();
      const card = el("effScatterCard");
      if (card && !card.hidden) renderInsightScatter();
    });
    syncEffViewButtons();
  })();

  // "Hide outliers" toggle: a 1.5×IQR fence per speed bucket, off by default and
  // persisted via prefs. Re-renders the card in place.
  function syncEffOutlierToggle() {
    const btn = el("effOutlierToggle");
    if (!btn) return;
    const on = hideOutliers();
    btn.dataset.on = String(on);
    btn.setAttribute("aria-pressed", String(on));
  }

  (function bindEffOutlierToggle() {
    const btn = el("effOutlierToggle");
    if (!btn) return;
    btn.addEventListener("click", () => {
      prefs.set("effHideOutliers", !hideOutliers());
      syncEffOutlierToggle();
      const card = el("effScatterCard");
      if (card && !card.hidden) renderInsightScatter();
    });
    syncEffOutlierToggle();
  })();

  Object.assign(VD, {
    // loadTrips is kept purely as a data-loader: it populates state.trips (used by
    // Insights lifetime totals + map.ts) and surfaces read errors via the global
    // status. The Trips tab and all its rendering were removed.
    loadTrips,
    loadInsights,
    setTrips: applyTripsPayload,
    setInsights: applyInsightsPayload,
    forceLazyStorageRead: FORCE_LAZY_READ,
    renderInsightStats,
    renderDriveTrend,
    renderThisWeek,
    renderInsightsEmptyState,
    renderInsightScatter,
    enrichRouteEff
  });

  // Retry-cancel button in the error banner. Wired here instead of in
  // actions.js so the surgical addition stays inside the panels file the
  // related rendering code. The button visibility is driven by troubleshooter.js based
  // on the status state — this binding just forwards the click to the
  // bridge.
  (function bindRetryCancel() {
    const btn = el("errorBannerCancelRetry") as HTMLButtonElement | null;
    if (!btn) return;
    btn.addEventListener("click", () => {
      // Only enter the "Cancelling…" UI state when the bridge actually has a cancelRetry
      // method to call — otherwise the user sees a fake progress state for an action that
      // never happened.
      if (!(bridge && typeof bridge.cancelRetry === "function")) {
        return;
      }
      btn.disabled = true;
      btn.textContent = "Cancelling…";
      try {
        bridge.cancelRetry();
      } catch (err) {
        // Surface, but never throw from a click handler.
        if (typeof bridge.logClientError === "function") {
          bridge.logClientError("cancelRetry", err instanceof Error ? err.message : String(err));
        }
      }
      // Re-enable after a short window in case the engine keeps retrying
      // (e.g. flag-cleared race) so the user can try again.
      setTimeout(() => {
        btn.disabled = false;
        btn.textContent = "Cancel";
      }, 1500);
    });
  })();
})();

export {};
