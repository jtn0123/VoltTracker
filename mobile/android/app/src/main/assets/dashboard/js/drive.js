/*
 * drive.js — Drive-tab live polish.
 *
 * Mirrors the Map-tab visual vocabulary (chip strip, scrub-chart, scrub-readout)
 * on the Drive screen so the live OBD experience feels like the same product:
 *
 *  - renderDriveNowChips(): top session chip strip (Idle / Recording / Demo)
 *    plus an optional "Last drive" chip that jumps to the Map tab.
 *  - drawLiveSpeedTrace(): SVG speed trace replacing the old canvas; same look
 *    as the Map scrubber tracks, with a "now" cursor pinned to the right edge.
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

  function svg(w, h, inner) {
    return (
      '<svg width="' +
      w +
      '" height="' +
      h +
      '" viewBox="0 0 ' +
      w +
      " " +
      h +
      '">' +
      inner +
      "</svg>"
    );
  }

  function paint(target, markup) {
    if (!target) return;
    const old = target.querySelector("svg");
    if (old) old.remove();
    target.insertAdjacentHTML("afterbegin", markup);
  }

  function targetWidth(node) {
    if (!node) return 0;
    return Math.max(0, node.clientWidth || node.getBoundingClientRect().width);
  }

  // ----- session chip strip -------------------------------------------------

  function fmtDuration(ms) {
    const s = Math.max(0, Math.round(Number(ms) / 1000));
    if (s < 60) return s + "s";
    const m = Math.floor(s / 60);
    if (m < 60) return m + "m " + String(s % 60).padStart(2, "0") + "s";
    const h = Math.floor(m / 60);
    return h + "h " + String(m % 60).padStart(2, "0") + "m";
  }

  function fmtChipDate(ms) {
    const ts = Number(ms);
    if (!Number.isFinite(ts) || ts <= 0) return "saved";
    const d = new Date(ts);
    const now = new Date();
    const sameDay =
      d.getFullYear() === now.getFullYear() &&
      d.getMonth() === now.getMonth() &&
      d.getDate() === now.getDate();
    const fmtTime = (date) =>
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
    const diffDays = Math.round((now - d) / 86400000);
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
    const recorded = routes.filter((r) => {
      const id = String((r && r.sessionId) || "");
      return id && !id.startsWith("__sample-");
    });
    if (!recorded.length) return null;
    const r = recorded[0];
    const mi = Number(r.distanceMeters) / 1609.34;
    return {
      tone: "ok",
      label: "Last drive",
      meta: [
        fmtChipDate(r.endedAtMs || r.startedAtMs),
        Number.isFinite(mi) ? mi.toFixed(1) + " mi" : "--"
      ],
      isLink: true
    };
  }

  // Build via DOM APIs (textContent) instead of innerHTML string-concat so the
  // user-controlled Bluetooth `adapter.name` (which lands in `c.meta` via
  // deriveLiveChip()) can never be reinterpreted as markup.
  function buildDriveNowChip(c) {
    const root = document.createElement(c.isLink ? "button" : "div");
    root.className = "map-drive-chip drive-now-chip";
    if (c.isLink) {
      root.type = "button";
      root.dataset.navJump = "map";
    } else {
      root.setAttribute("role", "status");
      root.setAttribute("aria-live", "polite");
    }
    root.dataset.tone = c.tone;

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

  // Build a path that flows leftward — newest sample at x=w, oldest at x=0.
  // We pad missing samples on the left so a fresh session doesn't squish the
  // last few samples into the rightmost pixel.
  function speedLinePath(samples, w, h, padT, padB) {
    if (!samples.length) return { d: "", maxMph: 0 };
    const maxMph = Math.max(40, ...samples) * 1.1;
    const cap = Math.max(48, samples.length);
    const stride = w / Math.max(1, cap - 1);
    const offset = w - (samples.length - 1) * stride;
    let d = "";
    for (let i = 0; i < samples.length; i += 1) {
      const x = offset + i * stride;
      const y = padT + (1 - samples[i] / maxMph) * (h - padT - padB);
      d += (i === 0 ? "M" : "L") + x.toFixed(1) + " " + y.toFixed(1) + " ";
    }
    return { d: d.trim(), maxMph: maxMph };
  }

  function drawLiveSpeedTrace() {
    const host = el("liveTraceChart");
    if (!host) return;
    const w = targetWidth(host);
    if (!w) return;
    const h = 96;
    const padT = 16;
    const padB = 10;
    // Convert kph history → mph for the visual.
    const samples = state.speedHistory.map((kph) => kph * 0.621371);
    let inner =
      '<text x="8" y="13" fill="#8b8c98" font-size="9" font-weight="700" ' +
      'font-family="ui-monospace,monospace" letter-spacing="0.08em">SPEED MPH</text>';
    // Grid lines.
    for (let i = 1; i < 4; i += 1) {
      const y = padT + (i / 4) * (h - padT - padB);
      inner +=
        '<line x1="0" y1="' +
        y +
        '" x2="' +
        w +
        '" y2="' +
        y +
        '" stroke="rgba(255,255,255,0.06)" stroke-width="1"/>';
    }
    if (samples.length >= 2) {
      const built = speedLinePath(samples, w, h, padT, padB);
      // Soft area fill under the trace.
      inner +=
        '<path d="' +
        built.d +
        " L" +
        w +
        " " +
        (h - padB) +
        " L0 " +
        (h - padB) +
        ' Z" fill="rgba(255,122,69,0.16)"/>' +
        '<path d="' +
        built.d +
        '" fill="none" stroke="#ff7a45" stroke-width="2.2" ' +
        'stroke-linejoin="round" stroke-linecap="round"/>';
      // Y-axis hint top-right showing the current cap.
      inner +=
        '<text x="' +
        (w - 6) +
        '" y="13" text-anchor="end" fill="#8b8c98" font-size="9" font-weight="700" ' +
        'font-family="ui-monospace,monospace">' +
        Math.round(built.maxMph) +
        "</text>";
    } else {
      inner +=
        '<text x="' +
        w / 2 +
        '" y="' +
        h / 2 +
        '" text-anchor="middle" fill="#5d5e69" font-size="11" ' +
        'font-family="ui-monospace,monospace">waiting for samples…</text>';
    }
    paint(host, svg(w, h, inner));
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
    const ZERO_PCT = 0.55; // zero line a touch below center so regen has room.
    const zeroY = padT + ZERO_PCT * (h - padT - padB);
    const cap = Math.max(60, samples.length);
    let inner =
      '<text x="8" y="13" fill="#8b8c98" font-size="9" font-weight="700" ' +
      'font-family="ui-monospace,monospace" letter-spacing="0.08em">DRIVE / REGEN</text>' +
      '<line x1="0" y1="' +
      zeroY +
      '" x2="' +
      w +
      '" y2="' +
      zeroY +
      '" stroke="rgba(255,255,255,0.18)" stroke-dasharray="2 3"/>';
    if (samples.length) {
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
        if (v >= 0) {
          // Drive — bar grows upward from zero line.
          const usable = zeroY - top;
          const bh = Math.min(usable, (v / maxAbs) * usable);
          inner +=
            '<rect x="' +
            x.toFixed(1) +
            '" y="' +
            (zeroY - bh).toFixed(1) +
            '" width="' +
            barW.toFixed(1) +
            '" height="' +
            bh.toFixed(1) +
            '" fill="#ff7a45" opacity="0.85"/>';
        } else {
          const usable = bottom - zeroY;
          const bh = Math.min(usable, (-v / maxAbs) * usable);
          inner +=
            '<rect x="' +
            x.toFixed(1) +
            '" y="' +
            zeroY.toFixed(1) +
            '" width="' +
            barW.toFixed(1) +
            '" height="' +
            bh.toFixed(1) +
            '" fill="#b8e63b" opacity="0.85"/>';
        }
      }
    } else {
      inner +=
        '<text x="' +
        w / 2 +
        '" y="' +
        h / 2 +
        '" text-anchor="middle" fill="#5d5e69" font-size="11" ' +
        'font-family="ui-monospace,monospace">no power samples yet</text>';
    }
    paint(host, svg(w, h, inner));
  }

  // ----- SOC trace ----------------------------------------------------------

  // Typographic minus matches the +/- glyph advance width — same trick the Map
  // scrubber uses to keep the SOC delta chip from twitching as it crosses zero.
  function fmtSocDelta(v) {
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
    // Header label kept in the SVG; the live value chip lives in the panel
    // header (rendered from renderSocMicroHeader) so it can show a colored Δ.
    let inner =
      '<text x="8" y="13" fill="#8b8c98" font-size="9" font-weight="700" ' +
      'font-family="ui-monospace,monospace" letter-spacing="0.08em">BATTERY %</text>';
    if (samples.length >= 2) {
      // The SOC chart's biggest failure mode is auto-zoom on a 0.2% drift —
      // making a near-flat line read as dramatic. Clamp the range to a
      // minimum span (MIN_RANGE) so trivial drift renders trivially and a
      // real drop reads in proportion. Then add a typical floor pad so the
      // line never glues to the chart top or bottom.
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
        // Re-clamp to keep total span ~= MIN_RANGE near 0% or 100%.
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
      let d = "";
      for (let i = 0; i < samples.length; i += 1) {
        const x = offset + i * stride;
        const y =
          padT + (1 - (samples[i] - lo) / (hi - lo)) * (h - padT - padB);
        d += (i === 0 ? "M" : "L") + x.toFixed(1) + " " + y.toFixed(1) + " ";
      }
      // Faint "starting %" guide line so the eye anchors to the session
      // baseline rather than to the chart's bottom edge.
      const startSoc = samples[0];
      const baselineY =
        padT + (1 - (startSoc - lo) / (hi - lo)) * (h - padT - padB);
      inner +=
        '<line x1="0" y1="' +
        baselineY.toFixed(1) +
        '" x2="' +
        w +
        '" y2="' +
        baselineY.toFixed(1) +
        '" stroke="rgba(255,255,255,0.08)" stroke-dasharray="2 3"/>';
      inner +=
        '<path d="' +
        d.trim() +
        " L" +
        w +
        " " +
        (h - padB) +
        " L0 " +
        (h - padB) +
        ' Z" fill="rgba(164,140,255,0.16)"/>' +
        '<path d="' +
        d.trim() +
        '" fill="none" stroke="#a48cff" stroke-width="2.2" ' +
        'stroke-linejoin="round" stroke-linecap="round"/>';
    } else {
      inner +=
        '<text x="' +
        w / 2 +
        '" y="' +
        h / 2 +
        '" text-anchor="middle" fill="#5d5e69" font-size="11" ' +
        'font-family="ui-monospace,monospace">no SOC samples yet</text>';
    }
    paint(host, svg(w, h, inner));
  }

  // Update the SOC micro-card header with the current value and a delta-from-
  // session-start chip. Lives outside the SVG so we can use real text + CSS.
  function renderSocMicroHeader() {
    const tag = el("socMicroTag");
    if (!tag) return;
    const t = state.telemetry || {};
    const current = Number(t.soc);
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
    const v = Number(t.powerKw);
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
  let resizeTimer = null;
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
