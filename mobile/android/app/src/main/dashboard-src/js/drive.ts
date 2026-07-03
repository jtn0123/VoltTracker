/*
 * drive.ts — Drive-tab live polish.
 *
 * Mirrors the Map-tab visual vocabulary (chip strip, scrub-chart, scrub-readout)
 * on the Drive screen so the live OBD experience feels like the same product:
 *
 *  - renderDriveNowChips(): top session chip strip, shown only while a session is
 *    live (Recording / Connecting / Demo). Idle state and past drives are not
 *    repeated here — the top-bar pill covers status and Trips/Map cover history.
 *  - drawLiveSpeedTrace(): canvas speed trace with a "now" cursor pinned to
 *    the right edge.
 *  - drawLivePowerBars(): +/- bars around zero for the last ~60s of power.
 *  - drawLiveSocTrace(): single-stroke SOC fall across the session.
 *
 * The render entry point is renderDriveLive(), called once per scheduled render
 * frame from telemetry.js. Charts re-render on resize.
 */
import { el } from "./core";
import { setDataTone } from "./dataset-state";
import type { DataToneValue } from "./dataset-state";
import { formatDuration } from "./telemetry";
import { units } from "./prefs";

const VD = window.VoltDashboard;
const state = VD.state;

type DriveChip = {
  tone: DataToneValue;
  label: string;
  meta: Array<string | number>;
  isLink?: boolean;
  liveStable?: boolean;
};

type ChartPoint = {
  x: number;
  y: number;
};

  // ----- shared SVG helpers -------------------------------------------------

  function paintEmpty(target: HTMLElement | null, label: string) {
    if (!target) return;
    const empty = document.createElement("div");
    empty.className = "live-chart-empty";
    empty.textContent = label;
    target.dataset.chartState = "empty";
    target.replaceChildren(empty);
  }

  function domNode(tag: string, className: string) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    return node;
  }

  // Chart hosts are measured on every render frame (~1Hz telemetry), and each
  // clientWidth / getBoundingClientRect read forces a synchronous layout.
  // Cache the last measured width per host and invalidate on resize (the
  // existing handler at the bottom of this file). A zero width — host hidden
  // or not laid out yet — is never cached, so a tab that becomes visible later
  // re-measures until it has real layout.
  const measuredWidths = new Map<HTMLElement, number>();

  function invalidateMeasuredWidths() {
    measuredWidths.clear();
  }

  function targetWidth(node: HTMLElement | null) {
    if (!node) return 0;
    const cached = measuredWidths.get(node);
    if (cached) return cached;
    const width = Math.max(0, node.clientWidth || node.getBoundingClientRect().width);
    if (width > 0) measuredWidths.set(node, width);
    return width;
  }

  // Parse a resolved CSS color token to "r, g, b" so canvas code can build rgba()
  // fades/shadows from a theme token instead of a hardcoded literal. Handles the
  // "#rrggbb" form the volt tokens use; a non-hex value (rare) falls back to the
  // caller's default. Returns just the channels so callers append the alpha.
  function rgbChannels(color: string, fallback: string): string {
    const hex = color.trim();
    const m = /^#([0-9a-fA-F]{6})$/.exec(hex);
    if (!m) return fallback;
    const n = parseInt(m[1], 16);
    return `${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}`;
  }

  // ----- session chip strip -------------------------------------------------

  // Shared with every other elapsed-span readout (telemetry.formatDuration) so
  // the session chips can't drift from the rest of the dashboard.
  const fmtDuration = formatDuration;

  function deriveLiveChip(): DriveChip {
    const app = state.appState || {};
    const session = app.session || {};
    const status = state.status || {};
    const adapter = app.adapter || {};
    const t = state.telemetry || {};
    const statusName = String(status.state || "").toLowerCase();
    const sessionName = String(session.state || "").toLowerCase();
    const stateName = statusName || sessionName;
    const samples = Number(session.sampleCount || t.sampleCount || 0);
    const runtimeMs = Number(t.sessionMs || session.runtimeMs || 0);
    const distance = Number(state.sessionDistanceM || 0);
    const hasLiveEvidence = Boolean(
      samples ||
      runtimeMs ||
      distance ||
      Number(state.lastSampleAt || 0) > 0 ||
      (state.speedHistory || []).length ||
      (state.powerHistory || []).length ||
      (state.socHistory || []).length
    );

    if (stateName === "connecting" || stateName === "initializing") {
      return { tone: "live", label: "Connecting…", meta: ["adapter handshake"] };
    }
    if (["connected", "scanning", "scan-complete"].includes(stateName)) {
      if (!hasLiveEvidence) {
        return { tone: "live", label: "Waiting for data", meta: ["adapter connected"] };
      }
      const meta: string[] = [];
      if (samples) meta.push(samples.toLocaleString() + " samples");
      if (runtimeMs) meta.push(fmtDuration(runtimeMs));
      if (distance) meta.push(units.distanceText(distance / 1000));
      return { tone: "live", label: "Recording", meta: meta };
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

  // Build via DOM APIs (textContent) instead of innerHTML string-concat so the
  // user-controlled Bluetooth `adapter.name` (which lands in `c.meta` via
  // deriveLiveChip()) can never be reinterpreted as markup.
  function buildDriveNowChip(c: DriveChip) {
    const root = document.createElement(c.isLink ? "button" : "div");
    root.className = "map-drive-chip drive-now-chip";
    if (c.isLink) {
      (root as HTMLButtonElement).type = "button";
      root.dataset.navJump = "map";
    } else if (c.liveStable || c.tone === "idle" || c.tone === "ok") {
      root.setAttribute("role", "status");
      root.setAttribute("aria-live", "polite");
    } else {
      root.setAttribute("aria-hidden", "true");
    }
    setDataTone(root, c.tone);

    const labelSpan = document.createElement("span");
    labelSpan.className = "dl";
    labelSpan.appendChild(document.createElement("u"));
    labelSpan.appendChild(document.createTextNode(c.label));

    const metaSpan = document.createElement("span");
    metaSpan.className = "dm";
    c.meta.forEach((m, i) => {
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

  // Demo / Testing header line (topbar .top-status): the single demo marker.
  // While a demo runs, the purple state pill already says "demo" right next to
  // this line, so the strip chip that used to repeat it on Drive is gone — the
  // label + live sample/SOC meta live here instead, on every tab.
  function renderTopDemoInfo() {
    const line = el("topDemoInfo") as HTMLButtonElement | null;
    if (!line) return;
    if (!state.demoActive) {
      line.hidden = true;
      return;
    }
    const t = state.telemetry || {};
    const session = (state.appState || {}).session || {};
    const meta: string[] = [];
    const samples = Number(session.sampleCount || t.sampleCount || 0);
    if (samples) meta.push(samples.toLocaleString() + " samples");
    const soc = Number(t.soc);
    if (Number.isFinite(soc)) meta.push(Math.round(soc) + "% SOC");
    VD.setText("topDemoMeta", meta.length ? meta.join(" · ") : "sample data");
    line.hidden = false;
  }

  function renderDriveNowChips() {
    renderTopDemoInfo();
    const host = el("driveNowChips");
    if (!host) return;
    // During a demo the topbar carries the Demo / Testing line + purple pill —
    // a strip chip here would repeat the same state a third time.
    if (state.demoActive) {
      host.replaceChildren();
      return;
    }
    const chips: DriveChip[] = [];
    // The Drive page is for the live drive. Idle / "ready · remembered" is already shown by the
    // top-bar pill + slim last-connected line, and past drives live on Trips/Map — so the now-chips
    // strip only surfaces the live chip while something is actually happening (Recording /
    // Connecting). When idle it stays empty.
    const live = deriveLiveChip();
    if (live && live.tone !== "idle" && live.tone !== "ok") chips.push(live);

    host.replaceChildren(...chips.map(buildDriveNowChip));
  }

  // ----- data-provenance badge + first-sample reveal ------------------------

  // True once any live data has been observed (counted sample or a populated
  // history buffer). Mirrors telemetry.ts#hasLiveSamples without importing it
  // (telemetry.ts already imports drive renders, so the reverse would cycle).
  function driveHasLiveSamples() {
    const t = state.telemetry || {};
    const session = (state.appState && state.appState.session) || {};
    const sampleCount = Number(session.sampleCount || t.sampleCount || 0);
    return (
      Number(state.lastSampleAt || 0) > 0 ||
      sampleCount > 0 ||
      (state.speedHistory || []).length > 0 ||
      (state.powerHistory || []).length > 0 ||
      (state.socHistory || []).length > 0
    );
  }

  type SourceKind = "demo" | "live" | "offline" | "empty";

  function deriveSource(): { kind: SourceKind; label: string; sub: string } {
    const app = state.appState || {};
    const adapter = app.adapter || {};
    const status = state.status || {};
    const stateName = String(status.state || (app.session || {}).state || "").toLowerCase();
    const connecting = ["connecting", "initializing"].includes(stateName);
    const connected =
      adapter.connected === true ||
      ["connected", "scanning", "scan-complete"].includes(stateName);
    const live = driveHasLiveSamples();

    if (state.demoActive) {
      return { kind: "demo", label: "Demo data", sub: "Isolated from your real history" };
    }
    if (connecting && !connected) {
      return { kind: "live", label: "Live car data", sub: "Connecting to your OBD adapter" };
    }
    if (connected) {
      return live
        ? { kind: "live", label: "Live car data", sub: "Streaming from your OBD adapter" }
        : { kind: "live", label: "Live car data", sub: "Adapter linked — waiting for first sample" };
    }
    // Not connected: distinguish "have saved history" (offline) from "nothing yet".
    const storage = state.storage || {};
    const hasStored =
      (typeof VD.dbRowCount === "function" && VD.dbRowCount(storage) > 0) ||
      (state.trips || []).length > 0;
    if (hasStored) {
      return { kind: "offline", label: "Offline · stored data", sub: "Showing saved history — connect for live" };
    }
    return { kind: "empty", label: "No data yet", sub: "Connect an adapter to start logging" };
  }

  // Add a one-time class the first time live data appears so CSS can play a
  // single reveal of the hero meters/trace (reduced-motion disables it). Never
  // removed mid-session — a stale gap shouldn't replay the reveal.
  let firstSampleRevealed = false;

  // Renders the Drive badge and mirrors the same truth onto the Charge tab's
  // #chargeSourceBadge — the Charge KPIs/session list show demo values during
  // a preview too, so both live tabs carry the same provenance marker.
  function renderDriveSourceBadge() {
    const src = deriveSource();
    const apply = (badgeId: string, labelId: string, subId: string) => {
      const badge = el(badgeId);
      if (!badge) return;
      badge.dataset.source = src.kind;
      const label = el(labelId);
      if (label) label.textContent = src.label;
      const sub = el(subId);
      if (sub) sub.textContent = src.sub;
    };
    apply("driveSourceBadge", "driveSourceLabel", "driveSourceSub");
    apply("chargeSourceBadge", "chargeSourceLabel", "chargeSourceSub");

    // During a demo the topbar carries the Demo / Testing line + the purple
    // pill on EVERY tab (renderTopDemoInfo), so a demo banner under the header
    // reads as a duplicate on Drive and Charge alike — hide both badges and
    // let the header carry the state. For live/offline/empty they stay the
    // per-tab provenance markers.
    const driveBadge = el("driveSourceBadge");
    if (driveBadge) driveBadge.hidden = src.kind === "demo";
    const chargeBadge = el("chargeSourceBadge");
    if (chargeBadge) chargeBadge.hidden = src.kind === "demo";

    const hero = document.querySelector(".view[data-view=\"drive\"] .hero");
    if (hero && !firstSampleRevealed && driveHasLiveSamples()) {
      firstSampleRevealed = true;
      hero.classList.add("is-first-sample");
    }
  }

  // ----- live speed trace ---------------------------------------------------

  function drawLiveSpeedTrace() {
    const host = el("liveTraceChart");
    const canvas = el("liveTraceCanvas") as HTMLCanvasElement | null;
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
    const metric = units.system() === "metric";
    const samples = (state.speedHistory || []).map((kph: unknown) =>
      metric ? Number(kph) : Number(kph) * 0.621371,
    );
    host.dataset.traceState = samples.length >= 2 ? "ready" : "empty";
    const latestSample = samples[samples.length - 1];
    host.dataset.traceLabel = samples.length >= 2
      ? `${Math.round(latestSample || 0)} ${units.speedUnit()}`
      : "waiting for samples";
    if (!ctx) return;

    // Theme-aware colors: a <canvas> can't read CSS vars, so resolve the relevant
    // tokens once per render (renderDriveLive re-runs each frame, so a theme flip
    // is picked up). Mirrors the insights-panel.ts / map.ts scatter pattern. The
    // gradient/glow/wash are rgba fades derived from the resolved --volt / --text
    // channels so they track the theme instead of being dark-only literals.
    const tokens = getComputedStyle(document.documentElement);
    const token = (name: string, fallback: string) =>
      (tokens.getPropertyValue(name) || "").trim() || fallback;
    const lineColor = token("--line", "rgba(255, 255, 255, 0.06)"); // gridlines
    const mutedColor = token("--muted", "#5d5e69"); // empty-state label
    const voltColor = token("--volt", "#ff7a45"); // trace stroke
    const dotColor = token("--volt-soft", "#ffd0b8"); // now-cursor dot
    const voltRgb = rgbChannels(voltColor, "255, 122, 69"); // gradient + glow base
    const textRgb = rgbChannels(token("--text", "#ffffff"), "255, 255, 255"); // bg wash base

    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = `rgba(${textRgb}, 0.012)`;
    ctx.fillRect(0, 0, w, h);
    ctx.strokeStyle = lineColor;
    ctx.lineWidth = 1;
    for (let i = 1; i < 4; i += 1) {
      const y = padT + (i / 4) * (h - padT - padB);
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(w, y);
      ctx.stroke();
    }
    if (samples.length < 2) {
      ctx.fillStyle = mutedColor;
      ctx.font = "11px ui-monospace, monospace";
      ctx.textAlign = "center";
      ctx.fillText("waiting for samples", w / 2, h / 2);
      return;
    }

    // Autoscale floor must match the unit of `samples` (km/h in metric, mph in imperial)
    // so the trace scales consistently across unit systems. 64 km/h ≈ 40 mph.
    const axisFloor = metric ? 64 : 40;
    const maxAxis = Math.max(axisFloor, ...samples) * 1.12;
    const cap = Math.max(12, samples.length);
    const stride = w / Math.max(1, cap - 1);
    const offset = w - (samples.length - 1) * stride;
    const points: ChartPoint[] = samples.map((sample: number, index: number) => ({
      x: offset + index * stride,
      y: padT + (1 - sample / maxAxis) * (h - padT - padB)
    }));
    const firstPoint = points[0];
    const latest = points[points.length - 1];
    if (!firstPoint || !latest) return;

    const gradient = ctx.createLinearGradient(0, padT, 0, h - padB);
    gradient.addColorStop(0, `rgba(${voltRgb}, 0.2)`);
    gradient.addColorStop(1, `rgba(${voltRgb}, 0)`);
    ctx.beginPath();
    points.forEach((point, index) => {
      if (index === 0) ctx.moveTo(point.x, point.y);
      else ctx.lineTo(point.x, point.y);
    });
    ctx.lineTo(latest.x, h - padB);
    ctx.lineTo(firstPoint.x, h - padB);
    ctx.closePath();
    ctx.fillStyle = gradient;
    ctx.fill();

    ctx.beginPath();
    points.forEach((point, index) => {
      if (index === 0) ctx.moveTo(point.x, point.y);
      else ctx.lineTo(point.x, point.y);
    });
    ctx.strokeStyle = voltColor;
    ctx.lineWidth = 2.4;
    ctx.lineJoin = "round";
    ctx.lineCap = "round";
    ctx.shadowColor = `rgba(${voltRgb}, 0.38)`;
    ctx.shadowBlur = 12;
    ctx.stroke();
    ctx.shadowBlur = 0;

    ctx.beginPath();
    ctx.arc(latest.x, latest.y, 3.2, 0, Math.PI * 2);
    ctx.fillStyle = dotColor;
    ctx.fill();
  }

  // ----- power bars ---------------------------------------------------------

  // Memoized: renderDriveLive runs on every app-state broadcast, and natives
  // re-deliver the last sample verbatim — rebuilding ~60 bar spans when the
  // history hasn't moved is pure DOM churn. Keyed on history length + last
  // value + measured width so a genuinely new sample (or resize) still paints.
  let lastPowerBarsSig = "";
  function drawLivePowerBars() {
    const host = el("powerBarsChart");
    if (!host) return;
    const w = targetWidth(host);
    if (!w) return;
    const h = 86;
    const padT = 14;
    const padB = 10;
    const samples = state.powerHistory || [];
    const sig = samples.length + ":" + (samples[samples.length - 1] ?? "") + ":" + w;
    if (sig === lastPowerBarsSig) return;
    lastPowerBarsSig = sig;
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
  function fmtSocDelta(v: number) {
    const abs = Math.abs(v);
    if (abs < 0.05) return "+0.0";
    return (v < 0 ? "−" : "+") + abs.toFixed(1);
  }

  // Same broadcast-churn memo as drawLivePowerBars — the SOC trace is ~95 spans.
  let lastSocTraceSig = "";
  function drawLiveSocTrace() {
    const host = el("socTraceChart");
    if (!host) return;
    const w = targetWidth(host);
    if (!w) return;
    const h = 86;
    const padT = 14;
    const padB = 12;
    const samples = state.socHistory || [];
    const sig = samples.length + ":" + (samples[samples.length - 1] ?? "") + ":" + w;
    if (sig === lastSocTraceSig) return;
    lastSocTraceSig = sig;
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
    let lo: number;
    let hi: number;
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
    const points: ChartPoint[] = samples.map((sample: number, index: number) => ({
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

    points.forEach((point, index) => {
      const dot = domNode("span", "live-soc-dot");
      dot.style.left = point.x.toFixed(1) + "px";
      dot.style.top = point.y.toFixed(1) + "px";
      chart.append(dot);
      if (index === points.length - 1) return;
      const next = points[index + 1];
      if (!next) return;
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
      setDataTone(tag, "idle");
      return;
    }
    if (!Number.isFinite(start)) {
      tag.textContent = Math.round(current) + "%";
      setDataTone(tag, "idle");
      return;
    }
    const delta = current - start;
    tag.textContent = Math.round(current) + "% · Δ " + fmtSocDelta(delta) + "%";
    // Tone: meaningful drop = warn, gain (regen / charging) = ok, drift = idle.
    setDataTone(tag, delta <= -0.5 ? "warn" : delta >= 0.5 ? "ok" : "idle");
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
      setDataTone(tag, "idle");
      return;
    }
    // Match the existing thresholds in telemetry.js updateLiveUi() so the
    // tag tone is consistent with #powerDetail.
    const tone = v < -0.5 ? "regen" : v > 0.5 ? "drive" : "coast";
    const abs = Math.abs(v);
    const sign = v < -0.05 ? "−" : "+";
    tag.textContent = sign + abs.toFixed(1) + " kW";
    setDataTone(tag, tone);
  }

  function renderDriveLive() {
    renderDriveNowChips();
    renderDriveSourceBadge();
    drawLiveSpeedTrace();
    drawLivePowerBars();
    renderPowerMicroHeader();
    drawLiveSocTrace();
    renderSocMicroHeader();
  }

  // Resize redraws — drop the cached host widths first so the redraw measures
  // the new container size instead of re-painting at the stale one.
  let resizeTimer: ReturnType<typeof setTimeout> | null = null;
  window.addEventListener("resize", () => {
    if (resizeTimer) clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => {
      invalidateMeasuredWidths();
      renderDriveLive();
    }, 160);
  });

  Object.assign(VD, {
    renderDriveLive,
    renderDriveNowChips,
    renderDriveSourceBadge,
    drawLiveSpeedTrace,
    drawLivePowerBars,
    drawLiveSocTrace
  });

export {};
