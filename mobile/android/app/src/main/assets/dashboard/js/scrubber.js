// @ts-check
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

  /**
   * One derived scrubber sample. Built by buildScrubData from a route's GPS
   * points; carries the per-point distance/speed/elevation/grade/soc/eff used
   * by the readout and the mini-charts.
   * @typedef {{
   *   lat: number, lng: number, atMs: number, distM?: number, mph?: number,
   *   elevM?: number, soc?: number | null, eff?: number | null, grade?: number,
   *   frac?: number, distMi?: number, elevFt?: number,
   * }} ScrubPoint
   */

  /** @type {any} Leaflet map handle, set via scrubberAttachMap. */
  let scrubMap = null;
  /** @type {any} Leaflet marker that rides the route as you scrub. */
  let scrubMarker = null;
  /** @type {ScrubPoint[]} */
  let scrubData = [];
  let scrubFrac = 0.5;
  let scrubExpanded = false;
  let scrubHasElev = false;
  let scrubHasSoc = false;
  let scrubHasEff = false;
  /** @type {HTMLElement[]} */
  let scrubCursors = [];

  const SCRUB_SPEED = "#ff7a45";
  const SCRUB_ELEV = "#8b94ad";
  const SCRUB_SOC = "#a48cff";
  const SCRUB_EFF = "#b8e63b";

  const scrubClamp = (/** @type {number} */ v, /** @type {number} */ lo, /** @type {number} */ hi) => Math.max(lo, Math.min(hi, v));

  function scrubberAttachMap(/** @type {any} */ map) {
    scrubMap = map;
  }

  // ----- data ----------------------------------------------------------------

  // Nearest-by-time SOC from the session's socTrack ({atMs, soc} ascending).
  function scrubSocAt(/** @type {any} */ track, /** @type {number} */ atMs) {
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

  function scrubWindowAvg(/** @type {any} */ arr, /** @type {number} */ i, /** @type {number} */ half) {
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

  function buildScrubData(/** @type {any} */ route) {
    const pts = ((route && route.points) || []).filter(isValidScrubPoint);
    const n = pts.length;
    if (n < 2) return [];
    const d = pts.map((/** @type {any} */ p) => ({
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
    const rawMph = pts.map((/** @type {any} */ p, /** @type {number} */ i) => {
      let mps = Number(p.speedMps);
      if (!Number.isFinite(mps) || mps < 0) {
        const a = pts[Math.max(0, i - 1)];
        const b = pts[Math.min(n - 1, i + 1)];
        const dt = Math.max(1, (Number(b.atMs) - Number(a.atMs)) / 1000);
        mps = haversineMetersJs(a.lat, a.lng, b.lat, b.lng) / dt;
      }
      return mps * 2.2369363;
    });

    scrubHasElev = pts.some((/** @type {any} */ p) => Number.isFinite(Number(p.altM)));
    const rawElev = pts.map((/** @type {any} */ p) => Number(p.altM));
    scrubHasEff = pts.some((/** @type {any} */ p) => Number.isFinite(Number(p.eff)));
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

  function isValidScrubPoint(/** @type {any} */ point) {
    const lat = Number(point && point.lat);
    const lng = Number(point && point.lng);
    return Number.isFinite(lat) && Number.isFinite(lng) && Math.abs(lat) <= 90 && Math.abs(lng) <= 180;
  }

  function scrubSampleAt(/** @type {number} */ frac) {
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

  function scrubYOf(/** @type {number} */ v, /** @type {number} */ lo, /** @type {number} */ hi, /** @type {number} */ h, /** @type {number} */ padT, /** @type {number} */ padB) {
    return padT + (1 - (v - lo) / ((hi - lo) || 1)) * (h - padT - padB);
  }

  // Build an SVG path that breaks on non-finite values, so the eff line
  // (which can have nulls where power data is missing) renders as separate
  // segments rather than zigzagging to 0.
  function scrubLine(/** @type {keyof ScrubPoint} */ key, /** @type {number} */ lo, /** @type {number} */ hi, /** @type {number} */ w, /** @type {number} */ h, /** @type {number} */ padT, /** @type {number} */ padB) {
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

  function scrubExtent(/** @type {keyof ScrubPoint} */ key) {
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

  function scrubSvg(/** @type {number} */ w, /** @type {number} */ h, /** @type {string} */ inner) {
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
  function drawScrubCombo(/** @type {number} */ w) {
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
  function drawScrubTrack(/** @type {number} */ w, /** @type {keyof ScrubPoint} */ key, /** @type {string} */ color, /** @type {string} */ fill, /** @type {string} */ label, /** @type {boolean} */ terrain) {
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
  function scrubGrade(/** @type {number} */ g) {
    const pct = Math.abs(g * 100).toFixed(0);
    if (pct === "0") return "+0%";
    return (g < 0 ? "−" : "+") + pct + "%";
  }

  function scrubChip(/** @type {string} */ k, /** @type {string | number} */ v, /** @type {any} */ opts) {
    opts = opts || {};
    const chip = document.createElement("div");
    if (opts.dim) chip.className = "scrub-dim";
    const label = document.createElement("span");
    label.className = "kicker";
    if (opts.color) label.style.color = opts.color;
    label.textContent = k;
    const value = document.createElement("strong");
    value.textContent = String(v);
    chip.append(label, value);
    return chip;
  }

  function fillScrubReadout(/** @type {any} */ s) {
    const node = el("scrubReadout");
    if (!node) return;
    node.replaceChildren(
      scrubChip("Dist mi", s.distMi.toFixed(1)),
      scrubChip("Speed mph", Math.round(s.mph), { color: SCRUB_SPEED }),
      scrubChip("Elev ft", scrubHasElev ? Math.round(s.elevFt) : "--", {
        color: scrubHasElev ? SCRUB_ELEV : null
      }),
      scrubChip("Grade", scrubHasElev ? scrubGrade(s.grade) : "--"),
      scrubChip(
        "Battery",
        scrubHasSoc && Number.isFinite(s.soc) ? Math.round(s.soc) + "%" : "--",
        { color: scrubHasSoc ? SCRUB_SOC : null }
      ),
      scrubChip(
        "mi/kWh",
        scrubHasEff && Number.isFinite(s.eff) ? s.eff.toFixed(1) : "soon",
        { dim: !scrubHasEff, color: scrubHasEff ? SCRUB_EFF : null }
      )
    );
  }

  // ----- render + interaction -----------------------------------------------

  function paintScrub(/** @type {any} */ target, /** @type {any} */ markup) {
    const old = target.querySelector("svg");
    if (old) old.remove();
    const next = parseSvg(markup);
    if (next) target.prepend(next);
  }

  function parseSvg(/** @type {any} */ markup) {
    const doc = new window.DOMParser().parseFromString(markup, "image/svg+xml");
    const node = doc.documentElement;
    if (!node || node.nodeName.toLowerCase() !== "svg") return null;
    return document.importNode(node, true);
  }

  function renderScrubCharts() {
    const chart = el("scrubChart");
    if (!chart || !chart.clientWidth) return;
    paintScrub(chart, drawScrubCombo(chart.clientWidth));
    const stack = el("scrubStack");
    if (stack && scrubExpanded) {
      const w = chart.clientWidth;
      /** @type {Array<[keyof ScrubPoint, string, string, string, boolean]>} */
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
      stack.replaceChildren();
      tracks.forEach((t) => {
        const track = document.createElement("div");
        track.className = "scrub-track";
        const svgNode = parseSvg(drawScrubTrack(w, t[0], t[1], t[2], t[3], t[4]));
        if (svgNode) track.appendChild(svgNode);
        const cursor = document.createElement("div");
        cursor.className = "scrub-cursor";
        track.appendChild(cursor);
        stack.appendChild(track);
      });
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

  function setScrubCursor(/** @type {number} */ frac) {
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
  function bindScrubChart(/** @type {any} */ elc) {
    if (!elc || elc.dataset.scrubBound === "1") return;
    elc.dataset.scrubBound = "1";
    const move = (/** @type {any} */ ev) => {
      const r = elc.getBoundingClientRect();
      if (r.width) setScrubCursor((ev.clientX - r.left) / r.width);
    };
    elc.addEventListener("pointerdown", (/** @type {any} */ ev) => {
      elc.setPointerCapture(ev.pointerId);
      move(ev);
    });
    elc.addEventListener("pointermove", (/** @type {any} */ ev) => {
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
  function renderScrubber(/** @type {any} */ route) {
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
  let /** @type {any} */ scrubAnim = null;
  const playBtn = el("scrubPlay");
  const PLAY_LABEL = "▶ Play";
  const STOP_LABEL = "■ Stop";

  function setScrubAnimMode(/** @type {any} */ on) {
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
      const step = (/** @type {number} */ now) => {
        const f = (now - t0) / dur;
        if (f >= 1) { setScrubCursor(1); stopScrubPlay(); return; }
        setScrubCursor(f);
        scrubAnim = requestAnimationFrame(step);
      };
      scrubAnim = requestAnimationFrame(step);
    });
  }

  let /** @type {any} */ scrubResizeTimer = null;
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
  function scrubAtLatLng(/** @type {number} */ lat, /** @type {number} */ lng) {
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
