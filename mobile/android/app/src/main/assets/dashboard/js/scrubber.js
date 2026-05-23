(function () {
  "use strict";

  // Route scrubber for the Map tab. Drag through a logged drive to inspect
  // speed / elevation / grade / battery / efficiency at each point. Fed by
  // map.js renderScrubber(route); the marker rides the Leaflet map. Efficiency
  // lights up once route points carry a derived `eff` field (set by
  // VD.enrichRouteEff once telemetry_samples.power_kw is available).

  const VD = window.VoltDashboard;
  const el = VD.el;
  const haversineMetersJs = VD.haversineMetersJs;

  let scrubMap = null;
  let scrubMarker = null;
  let scrubData = [];
  let scrubFrac = 0.5;
  let scrubExpanded = false;
  let scrubHasElev = false;
  let scrubHasSoc = false;
  let scrubHasEff = false;
  let scrubCursors = [];

  const SCRUB_SPEED = "#ff7a45";
  const SCRUB_ELEV = "#8b94ad";
  const SCRUB_SOC = "#a48cff";
  const SCRUB_EFF = "#b8e63b";

  const scrubClamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));

  function scrubberAttachMap(map) {
    scrubMap = map;
  }

  // ----- data ----------------------------------------------------------------

  // Nearest-by-time SOC from the session's socTrack ({atMs, soc} ascending).
  function scrubSocAt(track, atMs) {
    if (!track.length) return null;
    if (atMs <= track[0].atMs) return track[0].soc;
    const last = track[track.length - 1];
    if (atMs >= last.atMs) return last.soc;
    for (let i = 1; i < track.length; i += 1) {
      if (track[i].atMs >= atMs) {
        const a = track[i - 1];
        const b = track[i];
        const t = (atMs - a.atMs) / ((b.atMs - a.atMs) || 1);
        return a.soc + (b.soc - a.soc) * t;
      }
    }
    return last.soc;
  }

  function scrubWindowAvg(arr, i, half) {
    let s = 0;
    let c = 0;
    for (let k = -half; k <= half; k += 1) {
      const v = arr[i + k];
      if (Number.isFinite(v)) {
        s += v;
        c += 1;
      }
    }
    return c ? s / c : NaN;
  }

  function buildScrubData(route) {
    const pts = (route && route.points) || [];
    const n = pts.length;
    if (n < 2) return [];
    const d = pts.map((p) => ({
      lat: Number(p.lat),
      lng: Number(p.lng),
      atMs: Number(p.atMs)
    }));
    d[0].distM = 0;
    for (let i = 1; i < n; i += 1) {
      d[i].distM =
        d[i - 1].distM +
        haversineMetersJs(d[i - 1].lat, d[i - 1].lng, d[i].lat, d[i].lng);
    }
    const total = d[n - 1].distM || 1;

    // speed — prefer the GPS-reported value, derive from geometry if missing
    const rawMph = pts.map((p, i) => {
      let mps = Number(p.speedMps);
      if (!Number.isFinite(mps) || mps < 0) {
        const a = pts[Math.max(0, i - 1)];
        const b = pts[Math.min(n - 1, i + 1)];
        const dt = Math.max(1, (Number(b.atMs) - Number(a.atMs)) / 1000);
        mps = haversineMetersJs(a.lat, a.lng, b.lat, b.lng) / dt;
      }
      return mps * 2.2369363;
    });

    scrubHasElev = pts.some((p) => Number.isFinite(Number(p.altM)));
    const rawElev = pts.map((p) => Number(p.altM));
    scrubHasEff = pts.some((p) => Number.isFinite(Number(p.eff)));
    const track = (route && route.socTrack) || [];
    scrubHasSoc = track.length >= 2;

    for (let i = 0; i < n; i += 1) {
      d[i].mph = Math.max(0, scrubWindowAvg(rawMph, i, 1) || rawMph[i]);
      d[i].elevM = scrubHasElev ? scrubWindowAvg(rawElev, i, 3) || 0 : 0;
      d[i].soc = scrubHasSoc ? scrubSocAt(track, d[i].atMs) : null;
      // Don't coerce null -> 0; missing samples must remain null so the chart
      // can skip them and the readout can show "soon" rather than "0.0".
      const eff = Number(pts[i].eff);
      d[i].eff = scrubHasEff && Number.isFinite(eff) ? eff : null;
    }
    for (let i = 0; i < n; i += 1) {
      if (i === 0 || !scrubHasElev) {
        d[i].grade = 0;
        continue;
      }
      const horiz = Math.max(8, d[i].distM - d[i - 1].distM);
      d[i].grade = scrubClamp(
        (d[i].elevM - d[i - 1].elevM) / horiz,
        -0.18,
        0.18
      );
    }
    for (let i = 0; i < n; i += 1) {
      d[i].frac = d[i].distM / total;
      d[i].distMi = d[i].distM / 1609.34;
      d[i].elevFt = d[i].elevM * 3.28084;
    }
    return d;
  }

  function scrubSampleAt(frac) {
    const d = scrubData;
    const m = d.length;
    frac = scrubClamp(frac, 0, 1);
    let i = 0;
    while (i < m - 1 && d[i + 1].frac < frac) i += 1;
    const a = d[i];
    const b = d[Math.min(m - 1, i + 1)];
    const span = (b.frac - a.frac) || 1;
    const t = scrubClamp((frac - a.frac) / span, 0, 1);
    const near = t < 0.5 ? a : b;
    return {
      lat: a.lat + (b.lat - a.lat) * t,
      lng: a.lng + (b.lng - a.lng) * t,
      mph: near.mph,
      elevFt: near.elevFt,
      grade: near.grade,
      soc: near.soc,
      eff: near.eff,
      distMi: a.distMi + (b.distMi - a.distMi) * t
    };
  }

  // ----- SVG chart helpers --------------------------------------------------

  function scrubYOf(v, lo, hi, h, padT, padB) {
    return padT + (1 - (v - lo) / ((hi - lo) || 1)) * (h - padT - padB);
  }

  // Build an SVG path that breaks on non-finite values, so the eff line
  // (which can have nulls where power data is missing) renders as separate
  // segments rather than zigzagging to 0.
  function scrubLine(key, lo, hi, w, h, padT, padB) {
    let started = false;
    const parts = [];
    for (let i = 0; i < scrubData.length; i += 1) {
      const v = Number(scrubData[i][key]);
      if (!Number.isFinite(v)) {
        started = false;
        continue;
      }
      const cmd = started ? "L" : "M";
      started = true;
      parts.push(
        cmd +
          (scrubData[i].frac * w).toFixed(1) +
          " " +
          scrubYOf(v, lo, hi, h, padT, padB).toFixed(1)
      );
    }
    return parts.join(" ");
  }

  function scrubExtent(key) {
    let lo = Infinity;
    let hi = -Infinity;
    scrubData.forEach((p) => {
      const v = Number(p[key]);
      if (!Number.isFinite(v)) return;
      if (v < lo) lo = v;
      if (v > hi) hi = v;
    });
    if (!Number.isFinite(lo)) {
      lo = 0;
      hi = 1;
    }
    if (hi - lo < 1) hi = lo + 1;
    return { lo: lo, hi: hi };
  }

  function scrubSvg(w, h, inner) {
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

  // V1 combo strip — terrain silhouette as the ground, speed + efficiency on top.
  function drawScrubCombo(w) {
    const h = 116;
    const padT = 12;
    const padB = 9;
    const sp = scrubExtent("mph");
    let inner = "";
    if (scrubHasElev) {
      const e = scrubExtent("elevFt");
      const pad = (e.hi - e.lo) * 0.15 + 6;
      const lo = e.lo - pad;
      const hi = e.hi + (e.hi - e.lo) * 1.5 + pad;
      const tLine = scrubLine("elevFt", lo, hi, w, h, padT, padB);
      inner +=
        '<path d="' +
        tLine +
        " L" +
        w +
        " " +
        (h - padB) +
        " L0 " +
        (h - padB) +
        ' Z" fill="rgba(139,148,173,0.16)"/>' +
        '<path d="' +
        tLine +
        '" fill="none" stroke="rgba(139,148,173,0.5)" stroke-width="1.3"/>';
    }
    inner +=
      '<path d="' +
      scrubLine("mph", 0, sp.hi * 1.12, w, h, padT, padB) +
      '" fill="none" stroke="' +
      SCRUB_SPEED +
      '" stroke-width="2.2" stroke-linejoin="round"/>';
    if (scrubHasEff) {
      inner +=
        '<path d="' +
        scrubLine("eff", 0, 7, w, h, padT, padB) +
        '" fill="none" stroke="' +
        SCRUB_EFF +
        '" stroke-width="2.2" stroke-linejoin="round"/>';
    }
    return scrubSvg(w, h, inner);
  }

  // V2 instrument stack — one thin track per signal, shared cursor.
  function drawScrubTrack(w, key, color, fill, label, terrain) {
    const h = 52;
    const padT = 14;
    const padB = 8;
    const ext = scrubExtent(key);
    let lo = ext.lo;
    let hi = ext.hi;
    if (terrain) {
      const r = hi - lo;
      lo -= r * 0.12 + 3;
      hi += r * 0.12 + 3;
    } else if (key === "mph") {
      lo = 0;
      hi = ext.hi * 1.12;
    }
    const line = scrubLine(key, lo, hi, w, h, padT, padB);
    let inner =
      '<path d="' +
      line +
      " L" +
      w +
      " " +
      (h - padB) +
      " L0 " +
      (h - padB) +
      ' Z" fill="' +
      fill +
      '"/>' +
      '<path d="' +
      line +
      '" fill="none" stroke="' +
      color +
      '" stroke-width="1.9" stroke-linejoin="round"/>' +
      '<text x="8" y="13" fill="#8b8c98" font-size="9" font-weight="700" ' +
      'font-family="ui-monospace,monospace" letter-spacing="0.08em">' +
      label +
      "</text>";
    return scrubSvg(w, h, inner);
  }

  // ----- readout ------------------------------------------------------------

  // Grade is rendered with a typographic minus (U+2212) so the +/- glyphs have
  // identical widths — keeps the chip from twitching as the value crosses zero.
  function scrubGrade(g) {
    const pct = Math.abs(g * 100).toFixed(0);
    if (pct === "0") return "+0%";
    return (g < 0 ? "−" : "+") + pct + "%";
  }

  function scrubChip(k, v, opts) {
    opts = opts || {};
    const kStyle = opts.color ? ' style="color:' + opts.color + '"' : "";
    return (
      '<div class="' +
      (opts.dim ? "scrub-dim" : "") +
      '"><span class="kicker"' +
      kStyle +
      ">" +
      k +
      "</span><strong>" +
      v +
      "</strong></div>"
    );
  }

  function fillScrubReadout(s) {
    const node = el("scrubReadout");
    if (!node) return;
    node.innerHTML =
      scrubChip("Dist mi", s.distMi.toFixed(1)) +
      scrubChip("Speed", Math.round(s.mph), { color: SCRUB_SPEED }) +
      scrubChip("Elev ft", scrubHasElev ? Math.round(s.elevFt) : "--", {
        color: scrubHasElev ? SCRUB_ELEV : null
      }) +
      scrubChip("Grade", scrubHasElev ? scrubGrade(s.grade) : "--") +
      scrubChip(
        "Battery",
        scrubHasSoc && Number.isFinite(s.soc) ? Math.round(s.soc) + "%" : "--",
        { color: scrubHasSoc ? SCRUB_SOC : null }
      ) +
      scrubChip(
        "mi/kWh",
        scrubHasEff && Number.isFinite(s.eff) ? s.eff.toFixed(1) : "soon",
        { dim: !scrubHasEff, color: scrubHasEff ? SCRUB_EFF : null }
      );
  }

  // ----- render + interaction -----------------------------------------------

  function paintScrub(target, markup) {
    const old = target.querySelector("svg");
    if (old) old.remove();
    target.insertAdjacentHTML("afterbegin", markup);
  }

  function renderScrubCharts() {
    const chart = el("scrubChart");
    if (!chart || !chart.clientWidth) return;
    paintScrub(chart, drawScrubCombo(chart.clientWidth));
    const stack = el("scrubStack");
    if (stack && scrubExpanded) {
      const w = chart.clientWidth;
      let html = "";
      const tracks = [
        ["mph", SCRUB_SPEED, "rgba(255,122,69,0.16)", "SPEED MPH", false]
      ];
      if (scrubHasElev) {
        tracks.push([
          "elevFt",
          SCRUB_ELEV,
          "rgba(139,148,173,0.18)",
          "ELEVATION FT",
          true
        ]);
      }
      if (scrubHasSoc) {
        tracks.push([
          "soc",
          SCRUB_SOC,
          "rgba(164,140,255,0.16)",
          "BATTERY %",
          false
        ]);
      }
      if (scrubHasEff) {
        tracks.push([
          "eff",
          SCRUB_EFF,
          "rgba(184,230,59,0.16)",
          "EFFICIENCY MI/KWH",
          false
        ]);
      }
      tracks.forEach((t) => {
        html +=
          '<div class="scrub-track">' +
          drawScrubTrack(w, t[0], t[1], t[2], t[3], t[4]) +
          '<div class="scrub-cursor"></div></div>';
      });
      stack.innerHTML = html;
    }
    scrubCursors = [el("scrubCursor")]
      .concat(
        scrubExpanded
          ? Array.prototype.slice.call(
              document.querySelectorAll("#scrubStack .scrub-cursor")
            )
          : []
      )
      .filter(Boolean);
  }

  function setScrubCursor(frac) {
    if (!scrubData.length) return;
    scrubFrac = scrubClamp(frac, 0, 1);
    const s = scrubSampleAt(scrubFrac);
    scrubCursors.forEach((c) => {
      c.style.left = scrubFrac * 100 + "%";
    });
    fillScrubReadout(s);
    if (scrubMap && scrubMarker) {
      scrubMarker.setLatLng([s.lat, s.lng]);
      // Keep the marker's tap popup content live with the cursor, so tapping
      // it shows speed + efficiency at the current scrubbed position.
      const eff =
        scrubHasEff && Number.isFinite(s.eff)
          ? s.eff.toFixed(1) + " mi/kWh"
          : null;
      const grade = scrubHasElev ? scrubGrade(s.grade) : null;
      const lines = [Math.round(s.mph) + " mph"];
      if (eff) lines.push(eff);
      if (grade) lines.push("grade " + grade);
      scrubMarker.setPopupContent(
        '<div class="scrub-popup-body">' +
          '<div class="scrub-popup-big">' + lines[0] + '</div>' +
          (lines.length > 1
            ? '<div class="scrub-popup-sub">' + lines.slice(1).join(" · ") + '</div>'
            : "") +
          "</div>"
      );
    }
  }

  // Idempotent — won't double-attach handlers across re-renders.
  function bindScrubChart(elc) {
    if (!elc || elc.dataset.scrubBound === "1") return;
    elc.dataset.scrubBound = "1";
    const move = (ev) => {
      const r = elc.getBoundingClientRect();
      if (r.width) setScrubCursor((ev.clientX - r.left) / r.width);
    };
    elc.addEventListener("pointerdown", (ev) => {
      elc.setPointerCapture(ev.pointerId);
      move(ev);
    });
    elc.addEventListener("pointermove", (ev) => {
      if (ev.buttons) move(ev);
    });
  }

  function hideScrubber() {
    const node = el("scrubber");
    if (node) node.hidden = true;
    if (scrubMarker && scrubMap) {
      scrubMap.removeLayer(scrubMarker);
      scrubMarker = null;
    }
    scrubData = [];
  }

  // Called by map.js whenever the selected route changes.
  function renderScrubber(route) {
    if (typeof stopScrubPlay === "function") stopScrubPlay();
    scrubData = buildScrubData(route);
    const node = el("scrubber");
    if (!node) return;
    if (scrubData.length < 2) {
      hideScrubber();
      return;
    }
    node.hidden = false;

    if (scrubMap) {
      if (!scrubMarker) {
        scrubMarker = L.circleMarker(
          [scrubData[0].lat, scrubData[0].lng],
          {
            radius: 8,
            color: "#fff",
            weight: 3,
            fillColor: SCRUB_SPEED,
            fillOpacity: 1
          }
        ).bindPopup("", {
          closeButton: false,
          autoPan: false,
          className: "scrub-popup",
          offset: [0, -6]
        });
      }
      scrubMarker.addTo(scrubMap);
      scrubMarker.bringToFront();
    }
    renderScrubCharts();
    [el("scrubChart")]
      .concat(
        Array.prototype.slice.call(
          document.querySelectorAll("#scrubStack .scrub-track")
        )
      )
      .filter(Boolean)
      .forEach(bindScrubChart);
    setScrubCursor(0.5);
  }

  // Bind toggle once on first script load.
  const toggle = el("scrubToggle");
  if (toggle) {
    toggle.addEventListener("click", () => {
      scrubExpanded = !scrubExpanded;
      toggle.setAttribute("aria-expanded", scrubExpanded ? "true" : "false");
      toggle.textContent = scrubExpanded ? "Hide details" : "Details";
      const stack = el("scrubStack");
      if (stack) stack.hidden = !scrubExpanded;
      if (scrubData.length) {
        renderScrubCharts();
        Array.prototype.slice
          .call(document.querySelectorAll("#scrubStack .scrub-track"))
          .forEach(bindScrubChart);
        setScrubCursor(scrubFrac);
      }
    });
  }

  // Play button — animates the cursor from start to finish. Tap again to stop.
  // The marker rides the route as you go.
  //
  // Duration scales to drive length so a 22 mi commute doesn't whip past in
  // the same time as a 6 mi errand. Clamped to a sensible 8 - 22 s range.
  // A brief CSS transition on the cursors smooths the inter-frame motion
  // (toggled on only while playing so manual drags stay responsive).
  let scrubAnim = null;
  const playBtn = el("scrubPlay");
  const PLAY_LABEL = "▶ Play";
  const STOP_LABEL = "■ Stop";

  function setScrubAnimMode(on) {
    scrubCursors.forEach((c) => {
      if (!c) return;
      c.style.transition = on ? "left 90ms linear" : "";
    });
  }

  function stopScrubPlay() {
    if (scrubAnim) cancelAnimationFrame(scrubAnim);
    scrubAnim = null;
    setScrubAnimMode(false);
    if (playBtn) playBtn.textContent = PLAY_LABEL;
  }
  if (playBtn) {
    playBtn.addEventListener("click", () => {
      if (!scrubData.length) return;
      if (scrubAnim) { stopScrubPlay(); return; }
      playBtn.textContent = STOP_LABEL;
      setScrubAnimMode(true);
      const totalMi = scrubData[scrubData.length - 1].distMi || 22;
      // ~1 second per mile, with a sane 8-22s floor/ceiling so very short or
      // very long drives still play in a watchable window.
      const dur = Math.min(22000, Math.max(8000, totalMi * 1000));
      const t0 = performance.now();
      const step = (now) => {
        const f = (now - t0) / dur;
        if (f >= 1) { setScrubCursor(1); stopScrubPlay(); return; }
        setScrubCursor(f);
        scrubAnim = requestAnimationFrame(step);
      };
      scrubAnim = requestAnimationFrame(step);
    });
  }

  let scrubResizeTimer = null;
  window.addEventListener("resize", () => {
    clearTimeout(scrubResizeTimer);
    scrubResizeTimer = setTimeout(() => {
      const node = el("scrubber");
      if (scrubData.length && node && !node.hidden) {
        renderScrubCharts();
        setScrubCursor(scrubFrac);
      }
    }, 160);
  });

  // Snap the cursor to the route point closest to a lat/lng. Used by map.js
  // so tapping anywhere on the map jumps the scrubber to that point.
  function scrubAtLatLng(lat, lng) {
    if (!scrubData.length) return;
    let bestIdx = 0;
    let bestDist = Infinity;
    for (let i = 0; i < scrubData.length; i += 1) {
      const dLat = scrubData[i].lat - lat;
      const dLng = scrubData[i].lng - lng;
      const d = dLat * dLat + dLng * dLng;
      if (d < bestDist) { bestDist = d; bestIdx = i; }
    }
    setScrubCursor(scrubData[bestIdx].frac);
  }

  Object.assign(VD, {
    renderScrubber,
    hideScrubber,
    scrubberAttachMap,
    scrubAtLatLng
  });
})();
