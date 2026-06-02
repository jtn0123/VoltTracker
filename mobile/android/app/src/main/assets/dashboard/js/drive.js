// @ts-check
/*
 * drive.js — Drive-tab live polish.
 *
 * Mirrors the Map-tab visual vocabulary (chip strip, scrub-chart, scrub-readout)
 * on the Drive screen so the live OBD experience feels like the same product:
 *
 *  - renderDriveNowChips(): top session chip strip (Idle / Recording / Demo)
 *    plus an optional "Last drive" chip that jumps to the Map tab.
 *  - drawLiveSpeedTrace(): canvas speed trace with a "now" cursor pinned to
 *    the right edge.
 *  - drawLivePowerBars(): +/- bars around zero for the last ~60s of power.
 *  - drawLiveSocTrace(): single-stroke SOC fall across the session.
 *
 * The render entry point is renderDriveLive(), called once per scheduled render
 * frame from telemetry.js. Charts re-render on resize.
 */
(function () {
  "use strict";

  const VD = window.VoltDashboard;
  const state = VD.state;
  const el = VD.el;

  // ----- shared SVG helpers -------------------------------------------------

  function paintEmpty(/** @type {any} */ target, /** @type {any} */ label) {
    if (!target) return;
    const empty = document.createElement("div");
    empty.className = "live-chart-empty";
    empty.textContent = label;
    target.dataset.chartState = "empty";
    target.replaceChildren(empty);
  }

  function domNode(/** @type {any} */ tag, /** @type {any} */ className) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    return node;
  }

  function targetWidth(/** @type {any} */ node) {
    if (!node) return 0;
    return Math.max(0, node.clientWidth || node.getBoundingClientRect().width);
  }

  // ----- session chip strip -------------------------------------------------

  function fmtDuration(/** @type {number} */ ms) {
    const s = Math.max(0, Math.round(Number(ms) / 1000));
    if (s < 60) return s + "s";
    const m = Math.floor(s / 60);
    if (m < 60) return m + "m " + String(s % 60).padStart(2, "0") + "s";
    const h = Math.floor(m / 60);
    return h + "h " + String(m % 60).padStart(2, "0") + "m";
  }

  function fmtChipDate(/** @type {number} */ ms) {
    const ts = Number(ms);
    if (!Number.isFinite(ts) || ts <= 0) return "saved";
    const d = new Date(ts);
    const now = new Date();
    const sameDay =
      d.getFullYear() === now.getFullYear() &&
      d.getMonth() === now.getMonth() &&
      d.getDate() === now.getDate();
    const fmtTime = (/** @type {any} */ date) =>
      date
        .toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })
        .toLowerCase();
    if (sameDay) return "today " + fmtTime(d);
    const y = new Date(now.getTime() - 86400000);
    if (
      d.getFullYear() === y.getFullYear() &&
      d.getMonth() === y.getMonth() &&
      d.getDate() === y.getDate()
    )
      return "yesterday";
    const diffDays = Math.round((now.getTime() - d.getTime()) / 86400000);
    if (diffDays < 7)
      return d.toLocaleDateString([], { weekday: "short" }).toLowerCase();
    return d.toLocaleDateString([], { month: "short", day: "numeric" });
  }

  function deriveLiveChip() {
    const app = state.appState || {};
    const session = app.session || {};
    const status = state.status || {};
    const adapter = app.adapter || {};
    const t = state.telemetry || {};
    const stateName = String(
      session.state || status.state || ""
    ).toLowerCase();
    const samples = Number(session.sampleCount || t.sampleCount || 0);
    const runtimeMs = Number(t.sessionMs || session.runtimeMs || 0);
    const distance = Number(state.sessionDistanceM || 0);

    if (state.demoActive) {
      // Show live demo metrics so the chip feels alive — same shape as a real
      // recording chip but with a "demo" tone and a Demo label.
      const meta = [];
      const demoSamples = samples || Number(t.sampleCount || 0);
      if (demoSamples) meta.push(demoSamples.toLocaleString() + " samples");
      const demoRuntime = runtimeMs || Number(t.sessionMs || 0);
      if (demoRuntime) meta.push(fmtDuration(demoRuntime));
      const soc = Number(t.soc);
      if (Number.isFinite(soc)) meta.push(Math.round(soc) + "% SOC");
      if (!meta.length) meta.push("preview data");
      return { tone: "demo", label: "Demo preview", meta: meta };
    }
    if (
      ["connected", "scanning", "scan-complete", "initializing"].includes(
        stateName
      ) ||
      adapter.connected
    ) {
      const meta = [];
      if (samples) meta.push(samples.toLocaleString() + " samples");
      if (runtimeMs) meta.push(fmtDuration(runtimeMs));
      if (distance) meta.push((distance / 1609.34).toFixed(1) + " mi");
      if (!meta.length) meta.push("awaiting first sample");
      return { tone: "live", label: "Recording", meta: meta };
    }
    if (stateName === "connecting") {
      return { tone: "live", label: "Connecting…", meta: ["adapter handshake"] };
    }
    if (adapter.remembered || (state.lastDevice || {}).address) {
      const name = adapter.name || (state.lastDevice || {}).name || "adapter";
      return {
        tone: "ok",
        label: "Idle · ready",
        meta: [name + " remembered"]
      };
    }
    return {
      tone: "idle",
      label: "Idle",
      meta: ["pick an adapter to log"]
    };
  }

  function deriveLastDriveChip() {
    const routes =
      (state.storage && state.storage.recentRoutes) ||
      (state.storage && state.storage.latestRoute && [state.storage.latestRoute]) ||
      [];
    const recorded = routes.filter((/** @type {any} */ r) => {
      const session = (r && r.session) || {};
      const id = String(session.id || (r && r.sessionId) || "");
      return id && !id.startsWith("__sample-");
    });
    if (!recorded.length) return null;
    const r = recorded[0];
    const session = r.session || r;
    const mi = Number(r.distanceMeters) / 1609.34;
    return {
      tone: "ok",
      label: "Last drive",
      meta: [
        fmtChipDate(session.endedAtMs || session.startedAtMs),
        Number.isFinite(mi) ? mi.toFixed(1) + " mi" : "--"
      ],
      isLink: true,
      liveStable: true
    };
  }

  // Build via DOM APIs (textContent) instead of innerHTML string-concat so the
  // user-controlled Bluetooth `adapter.name` (which lands in `c.meta` via
  // deriveLiveChip()) can never be reinterpreted as markup.
  function buildDriveNowChip(/** @type {any} */ c) {
    const root = document.createElement(c.isLink ? "button" : "div");
    root.className = "map-drive-chip drive-now-chip";
    if (c.isLink) {
      /** @type {HTMLButtonElement} */ (root).type = "button";
      root.dataset.navJump = "map";
    } else if (c.liveStable || c.tone === "idle" || c.tone === "ok") {
      root.setAttribute("role", "status");
      root.setAttribute("aria-live", "polite");
    } else {
      root.setAttribute("aria-hidden", "true");
    }
    root.dataset.tone = c.tone;

    const labelSpan = document.createElement("span");
    labelSpan.className = "dl";
    labelSpan.appendChild(document.createElement("u"));
    labelSpan.appendChild(document.createTextNode(c.label));

    const metaSpan = document.createElement("span");
    metaSpan.className = "dm";
    c.meta.forEach((/** @type {any} */ m, /** @type {number} */ i) => {
      if (i > 0) {
        const sep = document.createElement("span");
        sep.textContent = "·";
        metaSpan.appendChild(sep);
      }
      const cell = document.createElement(i === 0 ? "b" : "span");
      cell.textContent = String(m == null ? "" : m);
      metaSpan.appendChild(cell);
    });

    root.append(labelSpan, metaSpan);
    return root;
  }

  function renderDriveNowChips() {
    const host = el("driveNowChips");
    if (!host) return;
    const chips = [];
    chips.push(deriveLiveChip());
    const last = deriveLastDriveChip();
    if (last) chips.push(last);

    host.replaceChildren(...chips.map(buildDriveNowChip));
  }

  // ----- live speed trace ---------------------------------------------------

  function drawLiveSpeedTrace() {
    const host = el("liveTraceChart");
    const canvas = /** @type {HTMLCanvasElement | null} */ (el("liveTraceCanvas"));
    if (!host || !canvas) return;
    const w = targetWidth(host);
    if (!w) return;
    const h = 96;
    const padT = 16;
    const padB = 10;
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.max(1, Math.round(w * dpr));
    canvas.height = Math.max(1, Math.round(h * dpr));
    canvas.style.height = h + "px";
    const ctx = canvas.getContext && canvas.getContext("2d");
    const samples = state.speedHistory.map((/** @type {any} */ kph) => kph * 0.621371);
    host.dataset.traceState = samples.length >= 2 ? "ready" : "empty";
    host.dataset.traceLabel = samples.length >= 2
      ? Math.round(samples[samples.length - 1]) + " mph"
      : "waiting for samples";
    if (!ctx) return;

    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = "rgba(255, 255, 255, 0.012)";
    ctx.fillRect(0, 0, w, h);
    ctx.strokeStyle = "rgba(255, 255, 255, 0.06)";
    ctx.lineWidth = 1;
    for (let i = 1; i < 4; i += 1) {
      const y = padT + (i / 4) * (h - padT - padB);
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(w, y);
      ctx.stroke();
    }
    if (samples.length < 2) {
      ctx.fillStyle = "#5d5e69";
      ctx.font = "11px ui-monospace, monospace";
      ctx.textAlign = "center";
      ctx.fillText("waiting for samples", w / 2, h / 2);
      return;
    }

    const maxMph = Math.max(40, ...samples) * 1.12;
    const cap = Math.max(12, samples.length);
    const stride = w / Math.max(1, cap - 1);
    const offset = w - (samples.length - 1) * stride;
    const points = samples.map((/** @type {any} */ sample, /** @type {any} */ index) => ({
      x: offset + index * stride,
      y: padT + (1 - sample / maxMph) * (h - padT - padB)
    }));

    const gradient = ctx.createLinearGradient(0, padT, 0, h - padB);
    gradient.addColorStop(0, "rgba(255, 122, 69, 0.2)");
    gradient.addColorStop(1, "rgba(255, 122, 69, 0)");
    ctx.beginPath();
    points.forEach((/** @type {any} */ point, /** @type {any} */ index) => {
      if (index === 0) ctx.moveTo(point.x, point.y);
      else ctx.lineTo(point.x, point.y);
    });
    ctx.lineTo(points[points.length - 1].x, h - padB);
    ctx.lineTo(points[0].x, h - padB);
    ctx.closePath();
    ctx.fillStyle = gradient;
    ctx.fill();

    ctx.beginPath();
    points.forEach((/** @type {any} */ point, /** @type {any} */ index) => {
      if (index === 0) ctx.moveTo(point.x, point.y);
      else ctx.lineTo(point.x, point.y);
    });
    ctx.strokeStyle = "#ff7a45";
    ctx.lineWidth = 2.4;
    ctx.lineJoin = "round";
    ctx.lineCap = "round";
    ctx.shadowColor = "rgba(255, 122, 69, 0.38)";
    ctx.shadowBlur = 12;
    ctx.stroke();
    ctx.shadowBlur = 0;

    const latest = points[points.length - 1];
    ctx.beginPath();
    ctx.arc(latest.x, latest.y, 3.2, 0, Math.PI * 2);
    ctx.fillStyle = "#ffd0b8";
    ctx.fill();
  }

  // ----- power bars ---------------------------------------------------------

  function drawLivePowerBars() {
    const host = el("powerBarsChart");
    if (!host) return;
    const w = targetWidth(host);
    if (!w) return;
    const h = 86;
    const padT = 14;
    const padB = 10;
    const samples = state.powerHistory || [];
    if (!samples.length) {
      paintEmpty(host, "Waiting for power samples");
      return;
    }
    const ZERO_PCT = 0.55; // zero line a touch below center so regen has room.
    const zeroY = padT + ZERO_PCT * (h - padT - padB);
    const cap = Math.max(60, samples.length);
    const chart = domNode("div", "live-dom-chart live-power-chart");
    chart.style.height = h + "px";
    const zero = domNode("span", "live-power-zero");
    zero.style.top = zeroY.toFixed(1) + "px";
    chart.append(zero);

    // Range from observed |power|, with a floor so quiet idling reads cleanly.
    const abs = samples.map(Math.abs);
    const maxAbs = Math.max(8, ...abs) * 1.1;
    const stride = w / cap;
    const barW = Math.max(1, stride * 0.7);
    const offset = w - samples.length * stride;
    for (let i = 0; i < samples.length; i += 1) {
      const v = samples[i];
      const x = offset + i * stride;
      const top = padT;
      const bottom = h - padB;
      const bar = domNode(
        "span",
        "live-power-bar " + (v >= 0 ? "is-drive" : "is-regen")
      );
      let y;
      let bh;
      if (v >= 0) {
        // Drive — bar grows upward from zero line.
        const usable = zeroY - top;
        bh = Math.min(usable, (v / maxAbs) * usable);
        y = zeroY - Math.max(1, bh);
      } else {
        const usable = bottom - zeroY;
        bh = Math.min(usable, (-v / maxAbs) * usable);
        y = zeroY;
      }
      bar.style.left = x.toFixed(1) + "px";
      bar.style.top = y.toFixed(1) + "px";
      bar.style.width = barW.toFixed(1) + "px";
      bar.style.height = Math.max(1, bh).toFixed(1) + "px";
      chart.append(bar);
    }
    host.dataset.chartState = "ready";
    host.replaceChildren(chart);
  }

  // ----- SOC trace ----------------------------------------------------------

  // Typographic minus matches the +/- glyph advance width — same trick the Map
  // scrubber uses to keep the SOC delta chip from twitching as it crosses zero.
  function fmtSocDelta(/** @type {number} */ v) {
    const abs = Math.abs(v);
    if (abs < 0.05) return "+0.0";
    return (v < 0 ? "−" : "+") + abs.toFixed(1);
  }

  function drawLiveSocTrace() {
    const host = el("socTraceChart");
    if (!host) return;
    const w = targetWidth(host);
    if (!w) return;
    const h = 86;
    const padT = 14;
    const padB = 12;
    const samples = state.socHistory || [];
    // The live value chip lives in the panel header so the chart body can stay
    // dedicated to the trace or a single centered empty-state label.
    if (samples.length < 2) {
      paintEmpty(host, "Waiting for SOC samples");
      return;
    }
    // The SOC chart's biggest failure mode is auto-zoom on a 0.2% drift —
    // making a near-flat line read as dramatic. Clamp the range to a minimum
    // span so trivial drift renders trivially and a real drop reads in proportion.
    const MIN_RANGE = 6;
    const obsLo = Math.min(...samples);
    const obsHi = Math.max(...samples);
    const observed = obsHi - obsLo;
    let lo;
    let hi;
    if (observed < MIN_RANGE) {
      const center = (obsHi + obsLo) / 2;
      lo = Math.max(0, center - MIN_RANGE / 2);
      hi = Math.min(100, center + MIN_RANGE / 2);
      if (hi - lo < MIN_RANGE) {
        if (lo === 0) hi = Math.min(100, MIN_RANGE);
        if (hi === 100) lo = Math.max(0, 100 - MIN_RANGE);
      }
    } else {
      const pad = Math.max(1, observed * 0.18);
      lo = Math.max(0, obsLo - pad);
      hi = Math.min(100, obsHi + pad);
    }
    const cap = Math.max(48, samples.length);
    const stride = w / Math.max(1, cap - 1);
    const offset = w - (samples.length - 1) * stride;
    const points = samples.map((/** @type {any} */ sample, /** @type {any} */ index) => ({
      x: offset + index * stride,
      y: padT + (1 - (sample - lo) / (hi - lo)) * (h - padT - padB)
    }));
    const chart = domNode("div", "live-dom-chart live-soc-chart");
    chart.style.height = h + "px";
    const startSoc = samples[0];
    const baselineY =
      padT + (1 - (startSoc - lo) / (hi - lo)) * (h - padT - padB);
    const baseline = domNode("span", "live-soc-baseline");
    baseline.style.top = baselineY.toFixed(1) + "px";
    chart.append(baseline);

    points.forEach((/** @type {any} */ point, /** @type {any} */ index) => {
      const dot = domNode("span", "live-soc-dot");
      dot.style.left = point.x.toFixed(1) + "px";
      dot.style.top = point.y.toFixed(1) + "px";
      chart.append(dot);
      if (index === points.length - 1) return;
      const next = points[index + 1];
      const dx = next.x - point.x;
      const dy = next.y - point.y;
      const segment = domNode("span", "live-soc-segment");
      segment.style.left = point.x.toFixed(1) + "px";
      segment.style.top = point.y.toFixed(1) + "px";
      segment.style.width = Math.hypot(dx, dy).toFixed(1) + "px";
      segment.style.transform = "rotate(" + Math.atan2(dy, dx) + "rad)";
      chart.append(segment);
    });
    host.dataset.chartState = "ready";
    host.replaceChildren(chart);
  }

  // Update the SOC micro-card header with the current value and a delta-from-
  // session-start chip. Lives outside the SVG so we can use real text + CSS.
  function renderSocMicroHeader() {
    const tag = el("socMicroTag");
    if (!tag) return;
    const t = state.telemetry || {};
    const current = t.soc == null || t.soc === "" ? NaN : Number(t.soc);
    const start = Number(state.sessionStartSoc);
    if (!Number.isFinite(current)) {
      tag.textContent = "%";
      tag.dataset.tone = "idle";
      return;
    }
    if (!Number.isFinite(start)) {
      tag.textContent = current.toFixed(1) + "%";
      tag.dataset.tone = "idle";
      return;
    }
    const delta = current - start;
    tag.textContent = current.toFixed(1) + "% · Δ " + fmtSocDelta(delta) + "%";
    // Tone: meaningful drop = warn, gain (regen / charging) = ok, drift = idle.
    tag.dataset.tone = delta <= -0.5 ? "warn" : delta >= 0.5 ? "ok" : "idle";
  }

  // ----- top-level driver ---------------------------------------------------

  // Update the Power micro-card header with the current value, colored by
  // drive/regen/coast — mirrors the .power-state vocabulary above.
  function renderPowerMicroHeader() {
    const tag = el("powerMicroTag");
    if (!tag) return;
    const t = state.telemetry || {};
    const v = t.powerKw == null || t.powerKw === "" ? NaN : Number(t.powerKw);
    if (!Number.isFinite(v)) {
      tag.textContent = "kW";
      tag.dataset.tone = "idle";
      return;
    }
    // Match the existing thresholds in telemetry.js updateLiveUi() so the
    // tag tone is consistent with #powerDetail.
    const tone = v < -0.5 ? "regen" : v > 0.5 ? "drive" : "coast";
    const abs = Math.abs(v);
    const sign = v < -0.05 ? "−" : "+";
    tag.textContent = sign + abs.toFixed(1) + " kW";
    tag.dataset.tone = tone;
  }

  function renderDriveLive() {
    renderDriveNowChips();
    drawLiveSpeedTrace();
    drawLivePowerBars();
    renderPowerMicroHeader();
    drawLiveSocTrace();
    renderSocMicroHeader();
  }

  // Resize redraws — keep the rendered widths in sync with the container.
  let /** @type {any} */ resizeTimer = null;
  window.addEventListener("resize", () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(renderDriveLive, 160);
  });

  Object.assign(VD, {
    renderDriveLive,
    renderDriveNowChips,
    drawLiveSpeedTrace,
    drawLivePowerBars,
    drawLiveSocTrace
  });
})();
