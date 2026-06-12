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
import { haversineMetersJs } from "./map-route-utils";

(function () {
  "use strict";

  const VD = window.VoltDashboard;
  const state = VD.state;
  const bridge = VD.bridge;
  const el = VD.el;

  function loadTrips() {
    if (bridge && typeof bridge.getTrips === "function") {
      const parsed = VD.parsePayload<VoltTrip[]>(bridge.getTrips(), []);
      if (VD.isNativeError(parsed)) {
        const err = parsed as VoltNativeError;
        VD.reportNativeReadError(parsed, "Could not read logged trips.");
        state.tripsReadError = err.message || "Could not read logged trips.";
        state.trips = [];
      } else if (state.demoActive && Array.isArray(state.demoPreviewTrips)) {
        // Park real trips behind the demo preview (cross-module demo invariant).
        VD.setState({ realTrips: Array.isArray(parsed) ? parsed : [] });
      } else {
        state.tripsReadError = null;
        state.trips = Array.isArray(parsed) ? parsed : [];
      }
    }
  }

  function loadInsights() {
    if (bridge && typeof bridge.getInsights === "function") {
      const parsed = VD.parsePayload<VoltInsights>(bridge.getInsights(), {});
      if (VD.isNativeError(parsed)) {
        const err = parsed as VoltNativeError;
        VD.reportNativeReadError(parsed, "Could not read vehicle insights.");
        state.insightsReadError = err.message || "Could not read vehicle insights.";
        state.insights = {};
      } else if (state.demoActive && state.demoPreviewInsights) {
        // Park real insights behind the demo preview (cross-module demo invariant).
        VD.setState({ realInsights: parsed });
      } else {
        state.insightsReadError = null;
        state.insights = parsed;
      }
    }
    renderInsightStats();
    renderInsightScatter();
  }

  function renderInsightStats() {
    const insights = state.insights || {};
    const trips = Number(insights.tripCount || 0);
    renderInsightsEmptyState();
    VD.setText("insightTripCount", trips || "--");
    VD.setText("insightTotalDistance", trips ? VD.formatDistance(Number(insights.totalDistanceMeters || 0)) : "--");
    VD.setText("insightDriveTime", Number(insights.totalDriveMs) > 0 ? VD.formatDuration(Number(insights.totalDriveMs)) : "--");
    VD.setText("insightTopSpeed", insights.maxSpeedKph ? VD.units.speedText(Number(insights.maxSpeedKph)) : "--");
    VD.setText("insightLongest", Number(insights.longestTripMeters) > 0 ? VD.formatDistance(Number(insights.longestTripMeters)) : "--");
    VD.setText("insightGpsTrips", trips ? `${Number(insights.gpsTripCount || 0)}/${trips}` : "--");
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
          "Pack health, savings, and monthly insights appear here once you've logged a few drives with the adapter connected.";
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
    const mphArr = pts.map((p, i) => {
      let mps = Number(p.speedMps);
      if (!Number.isFinite(mps) || mps < 0) {
        const a = pts[Math.max(0, i - 1)];
        const b = pts[Math.min(pts.length - 1, i + 1)];
        const dt = Math.max(1, (Number(b.atMs) - Number(a.atMs)) / 1000);
        mps = haversineMetersJs(a.lat, a.lng, b.lat, b.lng) / dt;
      }
      return Math.max(0, mps) * 2.2369363;
    });
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
    const pool: Array<{ mph: number; eff: number; grade: number }> = [];
    routes.forEach((route) => {
      enrichRouteEff(route);
      const pts = (route && route.points) || [];
      for (let i = 0; i < pts.length; i += 1) {
        const eff = Number(pts[i].eff);
        if (!Number.isFinite(eff)) continue;
        let mps = Number(pts[i].speedMps);
        if (!Number.isFinite(mps) || mps < 0) {
          const a = pts[Math.max(0, i - 1)];
          const b = pts[Math.min(pts.length - 1, i + 1)];
          const dt = Math.max(1, (Number(b.atMs) - Number(a.atMs)) / 1000);
          mps = haversineMetersJs(a.lat, a.lng, b.lat, b.lng) / dt;
        }
        const mph = Math.max(0, mps) * 2.2369363;
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
        pool.push({ mph, eff, grade });
      }
    });
    if (pool.length < 6) {
      card.hidden = true;
      return;
    }
    card.hidden = false;
    const w = Math.max(300, chart.clientWidth || 360);
    const h = 280;
    const padL = 38;
    const padR = 12;
    const padT = 14;
    const padB = 28;
    // The x-axis grows with the data (a Volt reaches ~100 mph) so fast samples
    // never render outside the viewBox; 75 mph stays the floor so sparse city
    // drives keep a familiar scale. Rounded up to the next 5 mph.
    const fastest = pool.reduce((m, p) => Math.max(m, p.mph), 0);
    const axisMaxMph = Math.max(75, Math.ceil(fastest / 5) * 5);
    const xOf = (mph: number) => padL + (mph / axisMaxMph) * (w - padL - padR);
    const yS = (e: number) => padT + (1 - e / 7) * (h - padT - padB);
    const gColor = (g: number) =>
      g <= -0.006 ? "#5cc8ff" : g >= 0.006 ? "#ff6b5f" : "#b8e63b";
    const svgNs = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(svgNs, "svg");
    svg.setAttribute("width", String(w));
    svg.setAttribute("height", String(h));
    svg.setAttribute("viewBox", `0 0 ${w} ${h}`);
    const setSvgAttrs = (node: SVGElement, attrs: Record<string, string | number>) => {
      Object.entries(attrs).forEach(([key, value]) => node.setAttribute(key, String(value)));
      return node;
    };
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
        stroke: "rgba(255,255,255,0.06)"
      });
      appendText(String(gx), {
        x: xOf(gx),
        y: h - padB + 15,
        fill: "#8b8c99",
        "font-size": 9,
        "font-family": "ui-monospace,monospace",
        "text-anchor": "middle"
      });
    }
    for (let gy = 0; gy <= 7; gy += 1) {
      appendLine({
        x1: padL,
        y1: yS(gy),
        x2: w - padR,
        y2: yS(gy),
        stroke: "rgba(255,255,255,0.06)"
      });
      appendText(String(gy), {
        x: padL - 6,
        y: yS(gy) + 3,
        fill: "#8b8c99",
        "font-size": 9,
        "font-family": "ui-monospace,monospace",
        "text-anchor": "end"
      });
    }
    const bins: number[][] = [];
    pool.forEach((p) => {
      svg.append(
        setSvgAttrs(document.createElementNS(svgNs, "circle"), {
          cx: xOf(p.mph).toFixed(1),
          cy: yS(p.eff).toFixed(1),
          r: 3.2,
          fill: gColor(p.grade),
          "fill-opacity": 0.5
        })
      );
      const b = Math.floor(p.mph / 10);
      (bins[b] = bins[b] || []).push(p.eff);
    });
    let trend = "";
    let best = { e: 0, mph: 0 };
    let started = false;
    bins.forEach((arr, b) => {
      if (!arr || arr.length < 3) return;
      const mph = b * 10 + 5;
      const e = arr.reduce((s, x) => s + x, 0) / arr.length;
      trend += `${started ? "L" : "M"}${xOf(mph).toFixed(1)} ${yS(e).toFixed(1)} `;
      started = true;
      if (e > best.e) best = { e: e, mph: mph };
    });
    svg.append(
      setSvgAttrs(document.createElementNS(svgNs, "path"), {
        d: trend,
        fill: "none",
        stroke: "#ff7a45",
        "stroke-width": 2.5,
        "stroke-linejoin": "round"
      })
    );
    appendText("speed (mph) ->", {
      x: w - padR,
      y: h - 4,
      fill: "#8b8c99",
      "font-size": 9,
      "font-family": "ui-monospace,monospace",
      "text-anchor": "end"
    });
    chart.replaceChildren(svg);
    if (head) {
      head.replaceChildren();
      if (best.e > 0) {
        const speed = document.createElement("b");
        speed.textContent = VD.units.speedText(best.mph / 0.621371);
        speed.style.color = "#b8e63b";
        head.append(
          "Most efficient around ",
          speed,
          " - about " + VD.units.efficiencyText(best.e) + "."
        );
      } else {
        head.textContent = "Pooling samples across every logged drive.";
      }
    }
    if (statsEl) {
      const hwy = pool.filter((p) => p.mph > 55).map((p) => p.eff);
      const down = pool.filter((p) => p.grade <= -0.012).map((p) => p.eff);
      // Pool eff is always mi/kWh (see enrichRouteEff); efficiencyText does the
      // single metric conversion, matching the headline above.
      const avgText = (a: number[]) =>
        a.length ? VD.units.efficiencyText(a.reduce((s, x) => s + x, 0) / a.length) : "--";
      statsEl.replaceChildren(
        insightStat("Samples", String(pool.length)),
        insightStat("Highway avg", avgText(hwy)),
        insightStat("Downhill avg", avgText(down))
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

  Object.assign(VD, {
    // loadTrips is kept purely as a data-loader: it populates state.trips (used by
    // Insights lifetime totals + map.ts) and surfaces read errors via the global
    // status. The Trips tab and all its rendering were removed.
    loadTrips,
    loadInsights,
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
