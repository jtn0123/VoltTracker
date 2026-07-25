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
import { el, setText, state } from "./core";
import { setDataTone } from "./dataset-state";
import type { DataToneValue } from "./dataset-state";
import { dbRowCount, formatDuration } from "./telemetry";
import { units } from "./prefs";
import { t } from "./i18n";
import { numberSeriesSignature } from "./render-signatures";

import { VD } from "./vd-registry";

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

  // Waiting charts render as skeleton shimmer bars + a small caption (v2
  // design) instead of a bare text box, so the empty state reads as "chart
  // loading here" rather than a broken tile. The shimmer animation lives in
  // CSS (.chart-skel-bar) and is disabled under prefers-reduced-motion.
  function paintEmpty(target: HTMLElement | null, label: string) {
    if (!target) return;
    const empty = document.createElement("div");
    empty.className = "live-chart-empty";
    for (const width of ["62%", "90%", "44%"]) {
      const bar = document.createElement("span");
      bar.className = "chart-skel-bar";
      bar.style.width = width;
      empty.appendChild(bar);
    }
    const caption = document.createElement("small");
    caption.textContent = label;
    empty.appendChild(caption);
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
    const n = parseInt(m[1]!, 16);
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
    const tm = state.telemetry || {};
    const statusName = String(status.state || "").toLowerCase();
    const sessionName = String(session.state || "").toLowerCase();
    const stateName = statusName || sessionName;
    const samples = Number(session.sampleCount || tm.sampleCount || 0);
    // `session.sessionMs`, not `session.runtimeMs`: AppStatePayload#sessionJson emits
    // sessionMs, and no native builder has ever emitted runtimeMs — so this fallback was
    // dead and the duration read 0 whenever telemetry had no sessionMs of its own. Found by
    // native-payload-contract.test.js; the fixtures had been seeding runtimeMs, which is why
    // the tests covered a shape production cannot produce.
    const runtimeMs = Number(tm.sessionMs || session.sessionMs || 0);
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
      if (samples) meta.push(samples.toLocaleString() + (samples === 1 ? " sample" : " samples"));
      if (runtimeMs) meta.push(fmtDuration(runtimeMs));
      if (distance) meta.push(units.distanceText(distance / 1000));
      // hasLiveEvidence can already be true from lastSampleAt / a populated
      // history buffer before any counted sample/runtime/distance exists, leaving
      // meta empty. renderDriveNowChips would then setText("driveRecordingMeta",
      // "") and setText coerces "" to "--" — "Recording · --". Emit a real
      // placeholder instead so the live line reads "Recording · live".
      if (!meta.length) meta.push("live");
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
    const tm = state.telemetry || {};
    const session = (state.appState || {}).session || {};
    const meta: string[] = [];
    // SOC only — the sample count lives in the session footnote, and two meta
    // fragments overflow the header into "60 samples · …" on phone widths.
    // Number(null) is 0 and 0% is a lie the hero contradicts; only a present,
    // finite reading earns a header chip.
    const soc = tm.soc == null ? NaN : Number(tm.soc);
    if (Number.isFinite(soc) && soc > 0) meta.push(Math.round(soc) + "% SOC");
    else if (Number(session.sampleCount || tm.sampleCount || 0)) meta.push("live sample data");
    setText("topDemoMeta", meta.length ? meta.join(" · ") : "sample data");
    line.hidden = false;
  }

  function renderDriveNowChips() {
    renderTopDemoInfo();
    const host = el("driveNowChips");
    const rec = el("driveRecording");
    // During a demo the topbar carries the Demo / Testing line + purple pill, so
    // neither the strip nor the Settings recording footer repeats it.
    if (state.demoActive) {
      if (host) host.replaceChildren();
      if (rec) rec.hidden = true;
      return;
    }
    // A live session (Recording / Connecting / Waiting) consolidates into the
    // slim #driveRecording line at the bottom of Settings — not the crowded
    // topbar or a full-width strip above the Drive hero. Idle / "ready ·
    // remembered" is already the top-bar pill + last-connected line's job, so
    // nothing shows then.
    const live = deriveLiveChip();
    const showLive = Boolean(live && live.tone !== "idle" && live.tone !== "ok");
    if (rec) {
      if (showLive && live) {
        setText("driveRecordingLabel", live.label);
        setText("driveRecordingMeta", live.meta.join(" · "));
        setDataTone(rec, live.tone);
        rec.hidden = false;
      } else {
        rec.hidden = true;
      }
    }
    // The strip is retired as a live-status surface; keep it empty so the
    // :empty rule collapses it (no stray grid gap above the hero).
    if (host) host.replaceChildren();
  }

  // ----- data-provenance badge + first-sample reveal ------------------------

  // True once any live data has been observed (counted sample or a populated
  // history buffer). Mirrors telemetry.ts#hasLiveSamples without importing it
  // (telemetry.ts already imports drive renders, so the reverse would cycle).
  function driveHasLiveSamples() {
    const tm = state.telemetry || {};
    const session = (state.appState && state.appState.session) || {};
    const sampleCount = Number(session.sampleCount || tm.sampleCount || 0);
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
      dbRowCount(storage) > 0 ||
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

    // The topbar carries the stable live state on every tab: the "demo" pill +
    // Demo / Testing line during a preview, or the adapter/"connected" pill while
    // connected. Settings adds the fast-changing #driveRecording progress at its
    // bottom. A full-width source banner still repeats that connection truth, so
    // hide both badges for demo AND live. Offline/empty (not connected) keep the
    // banner as their per-tab provenance marker.
    const headerCarriesState = src.kind === "demo" || src.kind === "live";
    const driveBadge = el("driveSourceBadge");
    if (driveBadge) driveBadge.hidden = headerCarriesState;
    const chargeBadge = el("chargeSourceBadge");
    if (chargeBadge) chargeBadge.hidden = headerCarriesState;

    // First-run consolidation: with no history at all ("empty"), the Drive tab
    // collapses to badge + onboarding card + hero skeleton. The session/health/
    // overview cards would each vocalize their own "--"/"waiting" placeholder
    // for data that cannot exist yet — one page-level state replaces them
    // (see .view.is-prelive rules in screens.css).
    const driveView = document.querySelector('.view[data-view="drive"]');
    if (driveView) {
      // Live samples lift the collapse even when the source still derives as
      // "empty" (e.g. telemetry arriving before the adapter flags connected).
      driveView.classList.toggle("is-prelive", src.kind === "empty" && !driveHasLiveSamples());
    }

    const hero = document.querySelector(".view[data-view=\"drive\"] .hero");
    if (hero && !firstSampleRevealed && driveHasLiveSamples()) {
      firstSampleRevealed = true;
      hero.classList.add("is-first-sample");
    }
  }

  // ----- live speed trace ---------------------------------------------------

  // Memoized like drawLivePowerBars/drawLiveSocTrace: renderDriveLive runs on
  // every app-state broadcast and natives re-deliver the last sample verbatim,
  // so skip the canvas bitmap realloc + full re-stroke when nothing changed. The
  // resolved theme tokens are folded into the key so a light/dark or high-contrast
  // flip still repaints (the canvas resolves its colors via getComputedStyle,
  // which a naive length+value memo would leave stale).
  let lastSpeedTraceSig = "";
  function drawLiveSpeedTrace() {
    const host = el("liveTraceChart");
    const canvas = el("liveTraceCanvas") as HTMLCanvasElement | null;
    if (!host || !canvas) return;
    const w = targetWidth(host);
    if (!w) return;
    const h = 56; // v2 design trace height
    const padT = 8;
    const padB = 4;
    const metric = units.system() === "metric";
    const samples = (state.speedHistory || []).map((kph: unknown) =>
      metric ? Number(kph) : Number(kph) * 0.621371,
    );

    // Theme-aware colors: a <canvas> can't read CSS vars, so resolve the relevant
    // tokens once per render (renderDriveLive re-runs each frame, so a theme flip
    // is picked up). Mirrors the insights-panel.ts / map.ts scatter pattern. The
    // gradient/glow/wash are rgba fades derived from the resolved --volt / --text
    // channels so they track the theme instead of being dark-only literals.
    // v2 design: the trace is always volt orange (the design's fixed #ff7a45),
    // resolved from the theme token so light mode keeps its darker orange.
    const tokens = getComputedStyle(el("liveHeroCard") || document.documentElement);
    const token = (name: string, fallback: string) =>
      (tokens.getPropertyValue(name) || "").trim() || fallback;
    const mutedColor = token("--muted", "#5d5e69"); // empty-state label
    const voltColor = token("--volt", "#ff7a45"); // trace stroke
    const voltRgb = rgbChannels(voltColor, "255, 122, 69"); // fill base

    // Sign over the full sample window (not just first+last): at the cap the
    // window scrolls, and a shifted series can keep the same length + boundary
    // values while its shape changes, which a first/last key would wrongly skip.
    // ~48 numbers joined is negligible. Plus the metric flag and resolved colors.
    const sig = [
      samples.length,
      samples.join(","),
      w,
      metric ? 1 : 0,
      voltColor,
      mutedColor,
    ].join(":");
    if (sig === lastSpeedTraceSig) return;
    lastSpeedTraceSig = sig;

    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.max(1, Math.round(w * dpr));
    canvas.height = Math.max(1, Math.round(h * dpr));
    canvas.style.height = h + "px";
    const ctx = canvas.getContext && canvas.getContext("2d");
    host.dataset.traceState = samples.length >= 2 ? "ready" : "empty";
    const latestSample = samples[samples.length - 1];
    host.dataset.traceLabel = samples.length >= 2
      ? `${Math.round(latestSample || 0)} ${units.speedUnit()}`
      : t("drive.trace.waitingForSamples");
    if (!ctx) return;

    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    // v2 design: a bare full-bleed sparkline — no gridlines or background wash.
    if (samples.length < 2) {
      ctx.fillStyle = mutedColor;
      ctx.font = "11px ui-monospace, monospace";
      ctx.textAlign = "center";
      ctx.fillText(t("drive.trace.waitingForSamples"), w / 2, h / 2);
      return;
    }

    // v2 design sparkline scaling: normalize to the visible window's min..max
    // (not 0..axis) so the wave fills the band and speed changes read large.
    // A floor on the span keeps a steady cruise from rendering as noise.
    const lo = Math.min(...samples);
    const hi = Math.max(...samples);
    const minSpan = metric ? 16 : 10; // don't amplify jitter below ~10 mph of range
    const span = Math.max(hi - lo, minSpan);
    const base = lo - (span - (hi - lo)) / 2;
    const cap = Math.max(12, samples.length);
    const stride = w / Math.max(1, cap - 1);
    const offset = w - (samples.length - 1) * stride;
    const points: ChartPoint[] = samples.map((sample: number, index: number) => ({
      x: offset + index * stride,
      y: padT + (1 - (sample - base) / span) * (h - padT - padB)
    }));
    const firstPoint = points[0];
    const latest = points[points.length - 1];
    if (!firstPoint || !latest) return;

    // v2 design sparkline: a flat translucent fill down to the canvas base and
    // a plain 2px stroke — no glow, no now-dot (the trace itself is the story).
    ctx.beginPath();
    points.forEach((point, index) => {
      if (index === 0) ctx.moveTo(point.x, point.y);
      else ctx.lineTo(point.x, point.y);
    });
    ctx.lineTo(latest.x, h);
    ctx.lineTo(firstPoint.x, h);
    ctx.closePath();
    ctx.fillStyle = `rgba(${voltRgb}, 0.14)`;
    ctx.fill();

    ctx.beginPath();
    points.forEach((point, index) => {
      if (index === 0) ctx.moveTo(point.x, point.y);
      else ctx.lineTo(point.x, point.y);
    });
    ctx.strokeStyle = voltColor;
    ctx.lineWidth = 2;
    ctx.lineJoin = "round";
    ctx.lineCap = "round";
    ctx.stroke();
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
    const h = 58; // v2 design microchart height
    const padT = 14;
    const padB = 10;
    const samples = state.powerHistory || [];
    // Include the OLDEST sample: at the 60-sample cap the window scrolls while
    // length + newest value can stay identical (natives re-deliver a repeated
    // newest reading), so keying on those alone leaves the bars stale by one.
    const sig = numberSeriesSignature(samples, w);
    if (sig === lastPowerBarsSig) return;
    lastPowerBarsSig = sig;
    if (!samples.length) {
      paintEmpty(host, t("drive.power.waitingForSamples"));
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
      if (v == null) continue;
      const x = offset + i * stride;
      const top = padT;
      const bottom = h - padB;
      const bar = domNode(
        "span",
        "live-power-bar " + (v >= 0 ? "is-drive" : "is-regen")
      );
      // Signed chart: both halves share ONE per-kW pixel scale so equal |power|
      // renders equal height up and down. The drive half has more room than the
      // regen half (zero line sits below center), so scaling each side by its own
      // usable height made +40kW taller than −40kW — misreading the sign. Use the
      // smaller half's pixels-per-kW for both, still clamping each bar to its own
      // side so a spike can't overflow the zero line.
      const driveUsable = zeroY - top;
      const regenUsable = bottom - zeroY;
      const unit = Math.min(driveUsable, regenUsable) / maxAbs;
      let y;
      let bh;
      if (v >= 0) {
        // Drive — bar grows upward from zero line.
        bh = Math.min(driveUsable, v * unit);
        y = zeroY - Math.max(1, bh);
      } else {
        bh = Math.min(regenUsable, -v * unit);
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

  // Same broadcast-churn memo as drawLivePowerBars — the SOC trace is ~95 spans.
  let lastSocTraceSig = "";
  function drawLiveSocTrace() {
    const host = el("socTraceChart");
    if (!host) return;
    const w = targetWidth(host);
    if (!w) return;
    const h = 58; // v2 design microchart height
    const padT = 14;
    const padB = 12;
    const samples = state.socHistory || [];
    // Include the OLDEST sample: at the 240-sample cap the window scrolls while
    // length + newest value can stay identical (a repeated newest reading), so
    // keying on those alone leaves the trace stale by one position.
    const sig = numberSeriesSignature(samples, w);
    if (sig === lastSocTraceSig) return;
    lastSocTraceSig = sig;
    // The live value chip lives in the panel header so the chart body can stay
    // dedicated to the trace or a single centered empty-state label.
    if (samples.length < 2) {
      paintEmpty(host, t("drive.soc.waitingForSamples"));
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
    const startSoc = samples[0]!;
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

  // ----- top-level driver ---------------------------------------------------

  // v2 design: the micro-card corner tags are static unit labels ("kW" / "%")
  // baked into the markup — the old live value/delta chips were app additions
  // the design doesn't have, and the values already live in the hero above.

  function renderDriveLive() {
    renderDriveNowChips();
    renderDriveSourceBadge();
    drawLiveSpeedTrace();
    drawLivePowerBars();
    drawLiveSocTrace();
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
