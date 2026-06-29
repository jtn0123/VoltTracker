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
import { haversineMetersJs } from "./map-route-utils";
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
    VD.setText("insightTripCount", trips || "--");
    VD.setText("insightTotalDistance", trips ? VD.formatDistance(Number(insights.totalDistanceMeters || 0)) : "--");
    VD.setText("insightDriveTime", Number(insights.totalDriveMs) > 0 ? VD.formatDuration(Number(insights.totalDriveMs)) : "--");
    VD.setText("insightTopSpeed", Number(insights.maxSpeedKph) > 0 ? units.speedText(Number(insights.maxSpeedKph)) : "--");
    VD.setText("insightLongest", Number(insights.longestTripMeters) > 0 ? VD.formatDistance(Number(insights.longestTripMeters)) : "--");
    VD.setText("insightGpsTrips", trips ? `${Number(insights.gpsTripCount || 0)}/${trips}` : "--");
    renderSavingsVsGas();
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
    routes.forEach((route) => {
      enrichRouteEff(route);
      const pts = (route && route.points) || [];
      for (let i = 0; i < pts.length; i += 1) {
        const eff = Number(pts[i].eff);
        if (!Number.isFinite(eff)) continue;
        // enrichRouteEff clamps efficiency to a 6.5 ceiling; those saturated
        // coasting/regen-tail samples otherwise pile into a solid false row along
        // the top gridline. Drop them from the scatter (and the stats/trend) so
        // the plot shows the real drive-efficiency spread, not a clamp artifact.
        if (eff >= 6.45) continue;
        const mph = pointMph(pts, i);
        if (mph < 10) continue;
        let grade = 0;
        if (
          i > 0 &&
          Number.isFinite(Number(pts[i - 1].altM)) &&
          Number.isFinite(Number(pts[i].altM))
        ) {
          const horiz = Math.max(
            8,
            haversineMetersJs(
              pts[i - 1].lat,
              pts[i - 1].lng,
              pts[i].lat,
              pts[i].lng
            )
          );
          grade = Math.max(
            -0.13,
            Math.min(0.13, (Number(pts[i].altM) - Number(pts[i - 1].altM)) / horiz)
          );
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
    const tokens = getComputedStyle(document.documentElement);
    const token = (name: string, fallback: string) =>
      (tokens.getPropertyValue(name) || "").trim() || fallback;
    const lineColor = token("--line", "rgba(255,255,255,0.1)"); // gridlines
    const axisColor = token("--muted", "#aaaab4"); // axis tick labels
    const trendColor = token("--volt", "#ff7a45"); // best-fit trend path
    const evColor = token("--ev", "#b8e63b"); // bars/curve accent + headline
    const dotColor = token("--map-accent", "#4cc4ff"); // grade-normalized scatter dots
    const w = Math.max(300, chart.clientWidth || 360);
    const h = 280;
    const padL = 38;
    const padR = 12;
    const padT = 14;
    const padB = 28;
    // The x-axis grows with the data (a Volt reaches ~100 mph) so fast samples
    // never render outside the viewBox; 75 mph stays the floor so sparse city
    // drives keep a familiar scale. Rounded up to the next 5 mph.
    const fastest = plotPool.reduce((m, p) => Math.max(m, p.mph), 0);
    const axisMaxMph = Math.max(75, Math.ceil(fastest / 5) * 5);
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
        "font-size": 9,
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
        "font-size": 9,
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
    appendText(`speed (${speedUnitLabel}) ->`, {
      x: w - padR,
      y: h - 4,
      fill: axisColor,
      "font-size": 9,
      "font-family": "ui-monospace,monospace",
      "text-anchor": "end"
    });
    // Y-axis unit annotation (efficiency), rotated to read up the left gutter so
    // a metric user knows the 0..7 grid is km/kWh, not mi/kWh.
    appendText(effUnitLabel, {
      x: 10,
      y: padT + (h - padT - padB) / 2,
      fill: axisColor,
      "font-size": 9,
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
          " - about " + units.efficiencyText(best.e) + "."
        );
      } else {
        head.textContent = "Log a few more drives to see your most efficient speed.";
      }
    }
    if (statsEl) {
      // Grade-normalized averages (effFlat). "Drives" counts logged routes — far
      // more honest than the old per-sample "Samples", where one long trip dumped
      // hundreds of correlated points. Downhill avg is gone: grade is normalized
      // out, so a city/highway split is what's left to compare.
      const city = plotPool.filter((p) => p.mph < 35).map((p) => p.effFlat);
      const hwy = plotPool.filter((p) => p.mph > 55).map((p) => p.effFlat);
      const avgText = (a: number[]) =>
        a.length ? units.efficiencyText(a.reduce((s, x) => s + x, 0) / a.length) : "--";
      statsEl.replaceChildren(
        insightStat("Drives", String(routes.length)),
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
