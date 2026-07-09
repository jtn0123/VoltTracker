import { el } from "./core";
import { haversineMetersJs } from "./map-route-utils";
import { units } from "./prefs";

  // Route scrubber for the Map tab. Drag through a logged drive to inspect
  // speed / elevation / grade / battery / efficiency at each point. Fed by
  // map.js renderScrubber(route); the marker rides the Leaflet map. Efficiency
  // lights up once route points carry a derived `eff` field (set by
  // VD.enrichRouteEff once telemetry_samples.power_kw is available).

  const VD = window.VoltDashboard;

  type ScrubPoint = {
    lat: number;
    lng: number;
    atMs: number;
    distM: number;
    mph: number;
    elevM: number;
    soc: number | null;
    eff: number | null;
    grade: number;
    frac: number;
    distMi: number;
    elevFt: number;
  };

  type ScrubRoutePoint = {
    lat?: unknown;
    lng?: unknown;
    atMs?: unknown;
    speedMps?: unknown;
    altM?: unknown;
    eff?: unknown;
  };

  type ScrubSocPoint = {
    atMs: number;
    soc: number;
  };

  type ScrubRoute = {
    points?: ScrubRoutePoint[];
    socTrack?: ScrubSocPoint[];
    [key: string]: unknown;
  };

  type ScrubSample = {
    lat: number;
    lng: number;
    mph: number;
    elevFt: number;
    grade: number;
    soc: number | null;
    eff: number | null;
    distMi: number;
  };

  type ScrubChipOptions = {
    dim?: boolean;
    color?: string | null;
  };

  type ScrubExtent = {
    lo: number;
    hi: number;
  };

  type ScrubTrack = [keyof ScrubPoint, string, string, string, boolean];

  type ScrubMapHandle = {
    removeLayer(layer: unknown): void;
  };

  type ScrubMarkerHandle = {
    setLatLng(latLng: [number, number]): void;
    setPopupContent(markup: string): void;
    addTo(map: ScrubMapHandle): ScrubMarkerHandle;
    bringToFront(): void;
  };

  let scrubMap: ScrubMapHandle | null = null;
  let scrubMarker: ScrubMarkerHandle | null = null;
  // Playback trail: the travelled portion of the route, drawn under the
  // scrub marker and updated as the cursor moves (v2).
  type ScrubTrailHandle = {
    addTo(target: unknown): unknown;
    setLatLngs(latlngs: LatLngTuple[]): unknown;
  };
  let scrubTrail: ScrubTrailHandle | null = null;
  // Trail vertices precomputed once per route (setScrubCursor runs per frame
  // during playback — rebuilding tuples there would be O(n) alloc each frame).
  let scrubTrailPts: LatLngTuple[] = [];

  // Index of the last scrubData sample at or before `frac` (scrubData is
  // ordered by ascending frac), by binary search. -1 when none.
  function scrubTravelledIdx(frac: number): number {
    let lo = 0;
    let hi = scrubData.length - 1;
    let best = -1;
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      if ((scrubData[mid] as ScrubPoint).frac <= frac) {
        best = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return best;
  }
  let scrubData: ScrubPoint[] = [];
  let scrubFrac = 0.5;
  let scrubExpanded = false;
  let scrubHasElev = false;
  let scrubHasSoc = false;
  let scrubHasEff = false;
  let scrubCursors: HTMLElement[] = [];

  // v2 design: the scrub speed tone is the softer #ff9d6e, not full volt orange.
  const SCRUB_SPEED = "#ff9d6e";
  const SCRUB_ELEV = "#8b94ad";
  const SCRUB_SOC = "#a48cff";
  const SCRUB_EFF = "#b8e63b";

  const scrubClamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v));

  // Number(null) is 0 (finite!), which would silently coerce missing samples
  // into real readings. Treat null/undefined as NaN BEFORE coercion so a
  // null-eff regen segment stays "missing" instead of becoming 0.0.
  const scrubNum = (v: unknown) => (v == null ? NaN : Number(v));

  function scrubberAttachMap(map: ScrubMapHandle) {
    scrubMap = map;
  }

  // ----- data ----------------------------------------------------------------

  // Nearest-by-time SOC from the session's socTrack ({atMs, soc} ascending).
  function scrubSocAt(track: ScrubSocPoint[], atMs: number) {
    if (!track.length) return null;
    const first = track[0];
    if (!first) return null;
    if (atMs <= first.atMs) return first.soc;
    const last = track[track.length - 1];
    if (!last) return null;
    if (atMs >= last.atMs) return last.soc;
    for (let i = 1; i < track.length; i += 1) {
      const b = track[i];
      if (b && b.atMs >= atMs) {
        const a = track[i - 1];
        if (!a) continue;
        const t = (atMs - a.atMs) / ((b.atMs - a.atMs) || 1);
        return a.soc + (b.soc - a.soc) * t;
      }
    }
    return last.soc;
  }

  function scrubWindowAvg(arr: number[], i: number, half: number) {
    let s = 0;
    let c = 0;
    for (let k = -half; k <= half; k += 1) {
      const v = arr[i + k];
      if (v !== undefined && Number.isFinite(v)) {
        s += v;
        c += 1;
      }
    }
    return c ? s / c : NaN;
  }

  function buildScrubData(route: ScrubRoute): ScrubPoint[] {
    const pts = ((route && route.points) || []).filter(isValidScrubPoint) as ScrubRoutePoint[];
    const n = pts.length;
    if (n < 2) return [];
    const d = pts.map((p) => ({
      lat: Number(p.lat),
      lng: Number(p.lng),
      atMs: Number(p.atMs),
      distM: 0,
      mph: 0,
      elevM: 0,
      soc: null,
      eff: null,
      grade: 0,
      frac: 0,
      distMi: 0,
      elevFt: 0
    })) as ScrubPoint[];
    const firstPoint = d[0];
    if (!firstPoint) return [];
    firstPoint.distM = 0;
    for (let i = 1; i < n; i += 1) {
      const previousPoint = d[i - 1];
      const point = d[i];
      if (!previousPoint || !point) continue;
      point.distM =
        previousPoint.distM +
        haversineMetersJs(previousPoint.lat, previousPoint.lng, point.lat, point.lng);
    }
    const lastPoint = d[n - 1];
    const total = (lastPoint && lastPoint.distM) || 1;

    // speed — prefer the GPS-reported value, derive from geometry if missing
    const rawMph = pts.map((p, i) => {
      let mps = Number(p.speedMps);
      if (!Number.isFinite(mps) || mps < 0) {
        const a = pts[Math.max(0, i - 1)];
        const b = pts[Math.min(n - 1, i + 1)];
        if (!a || !b) return 0;
        const dt = Math.max(1, (Number(b.atMs) - Number(a.atMs)) / 1000);
        mps =
          haversineMetersJs(Number(a.lat), Number(a.lng), Number(b.lat), Number(b.lng)) /
          dt;
      }
      return mps * 2.2369363;
    });

    // Use the null-safe scrubNum (not bare Number) so a null altitude stays NaN
    // instead of coercing to a real 0 m reading and faking a sea-level trace.
    scrubHasElev = pts.some((p) => Number.isFinite(scrubNum(p.altM)));
    const rawElev = pts.map((p) => scrubNum(p.altM));
    // Carry-forward seed for windowed elevation: the first finite altitude, so a
    // gap in altitude coverage holds the last known height rather than dropping
    // to a false 0 m dip. Falls back to 0 only when no point has altitude.
    let lastElevM = rawElev.find((v) => Number.isFinite(v));
    if (lastElevM === undefined) lastElevM = 0;
    scrubHasEff = pts.some((p) => Number.isFinite(scrubNum(p.eff)));
    const track = Array.isArray(route.socTrack) ? route.socTrack as ScrubSocPoint[] : [];
    scrubHasSoc = track.length >= 2;

    for (let i = 0; i < n; i += 1) {
      const point = d[i];
      const routePoint = pts[i];
      if (!point || !routePoint) continue;
      // A windowed average of exactly 0 (genuinely stopped) must not be discarded by `||`;
      // fall through to the raw sample only when the average is non-finite (no finite neighbors).
      const mphAvg = scrubWindowAvg(rawMph, i, 1);
      point.mph = Math.max(0, Number.isFinite(mphAvg) ? mphAvg : (rawMph[i] || 0));
      if (scrubHasElev) {
        // Hold the last finite windowed average across altitude gaps; a `|| 0`
        // here would turn a NaN (no finite neighbours) into a fake 0 m dip and
        // a phantom steep grade at each edge of the gap.
        const elevAvg = scrubWindowAvg(rawElev, i, 3);
        if (Number.isFinite(elevAvg)) lastElevM = elevAvg;
        point.elevM = lastElevM;
      } else {
        point.elevM = 0;
      }
      point.soc = scrubHasSoc ? scrubSocAt(track, point.atMs) : null;
      // Don't coerce null -> 0; missing samples must remain null so the chart
      // can skip them and the readout can show "soon" rather than "0.0".
      const eff = scrubNum(routePoint.eff);
      point.eff = scrubHasEff && Number.isFinite(eff) ? eff : null;
    }
    for (let i = 0; i < n; i += 1) {
      const point = d[i];
      if (!point) continue;
      if (i === 0 || !scrubHasElev) {
        point.grade = 0;
        continue;
      }
      const previousPoint = d[i - 1];
      if (!previousPoint) continue;
      const horiz = Math.max(8, point.distM - previousPoint.distM);
      point.grade = scrubClamp(
        (point.elevM - previousPoint.elevM) / horiz,
        -0.18,
        0.18
      );
    }
    for (let i = 0; i < n; i += 1) {
      const point = d[i];
      if (!point) continue;
      point.frac = point.distM / total;
      point.distMi = point.distM / 1609.34;
      point.elevFt = point.elevM * 3.28084;
    }
    return d;
  }

  function isValidScrubPoint(point: unknown): point is ScrubRoutePoint {
    const candidate = point as ScrubRoutePoint | null;
    const lat = Number(candidate && candidate.lat);
    const lng = Number(candidate && candidate.lng);
    // Reject exact (0,0) "null island" GPS sentinel — see isValidRoutePoint in map-route-utils.
    if (lat === 0 && lng === 0) return false;
    return Number.isFinite(lat) && Number.isFinite(lng) && Math.abs(lat) <= 90 && Math.abs(lng) <= 180;
  }

  function scrubSampleAt(frac: number): ScrubSample {
    const d = scrubData;
    const m = d.length;
    frac = scrubClamp(frac, 0, 1);
    let i = 0;
    while (i < m - 1) {
      const next = d[i + 1];
      if (!next || next.frac >= frac) break;
      i += 1;
    }
    const a = d[i];
    const b = d[Math.min(m - 1, i + 1)];
    if (!a || !b) {
      return { lat: 0, lng: 0, mph: 0, elevFt: 0, grade: 0, soc: null, eff: null, distMi: 0 };
    }
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

  function scrubYOf(v: number, lo: number, hi: number, h: number, padT: number, padB: number) {
    return padT + (1 - (v - lo) / ((hi - lo) || 1)) * (h - padT - padB);
  }

  // Build an SVG path that breaks on non-finite values, so the eff line
  // (which can have nulls where power data is missing) renders as separate
  // segments rather than zigzagging to 0.
  function scrubLine(key: keyof ScrubPoint, lo: number, hi: number, w: number, h: number, padT: number, padB: number) {
    let started = false;
    const parts: string[] = [];
    for (let i = 0; i < scrubData.length; i += 1) {
      const point = scrubData[i];
      if (!point) continue;
      const v = scrubNum(point[key]);
      if (!Number.isFinite(v)) {
        started = false;
        continue;
      }
      const cmd = started ? "L" : "M";
      started = true;
      parts.push(
        cmd +
          (point.frac * w).toFixed(1) +
          " " +
          scrubYOf(v, lo, hi, h, padT, padB).toFixed(1)
      );
    }
    return parts.join(" ");
  }

  function scrubExtent(key: keyof ScrubPoint): ScrubExtent {
    let lo = Infinity;
    let hi = -Infinity;
    scrubData.forEach((p) => {
      const v = scrubNum(p[key]);
      if (!Number.isFinite(v)) return;
      if (v < lo) lo = v;
      if (v > hi) hi = v;
    });
    if (!Number.isFinite(lo)) {
      lo = 0;
      hi = 1;
    }
    // Pad flat data SYMMETRICALLY: padding only the top pinned a constant
    // trace (e.g. steady SOC) to the bottom edge of its track, where it read
    // as "no data". Centered, a flat line is visibly a flat line.
    if (hi - lo < 1) {
      const mid = (hi + lo) / 2;
      lo = mid - 0.5;
      hi = mid + 0.5;
    }
    return { lo: lo, hi: hi };
  }

  function scrubSvg(w: number, h: number, inner: string) {
    // The xmlns is REQUIRED: parseSvg() parses this with DOMParser as
    // "image/svg+xml", and without a default namespace declaration every node
    // lands in the null namespace and renders nothing (the fill/stroke/d
    // attributes become inert). With it, the nodes are real SVG elements.
    return (
      '<svg xmlns="http://www.w3.org/2000/svg" width="' +
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
  function drawScrubCombo(w: number) {
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
  function drawScrubTrack(w: number, key: keyof ScrubPoint, color: string, fill: string, _label: string, terrain: boolean) {
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
      '" stroke-width="1.9" stroke-linejoin="round"/>';
    return scrubSvg(w, h, inner);
  }

  // ----- readout ------------------------------------------------------------

  // Grade is rendered with a typographic minus (U+2212) so the +/- glyphs have
  // identical widths — keeps the chip from twitching as the value crosses zero.
  function scrubGrade(g: number) {
    const pct = Math.abs(g * 100).toFixed(0);
    if (pct === "0") return "+0%";
    return (g < 0 ? "−" : "+") + pct + "%";
  }

  function scrubChip(k: string, v: string | number, opts: ScrubChipOptions = {}) {
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

  function fillScrubReadout(s: ScrubSample) {
    const socValue = s.soc;
    const effValue = s.eff;
    const socText =
      scrubHasSoc && socValue !== null && Number.isFinite(socValue)
        ? Math.round(socValue) + "%"
        : "--";
    const effText =
      scrubHasEff && effValue !== null && Number.isFinite(effValue)
        ? effValue.toFixed(1)
        : "soon";
    const node = el("scrubReadout");
    if (!node) return;
    const metricUnits = units.system() === "metric";
    const dist = units.distanceMiles(s.distMi);
    const speedVal = Math.round(metricUnits ? s.mph / 0.621371 : s.mph);
    const elevUnit = metricUnits ? "m" : "ft";
    // Value is the bare number; the unit lives in the label (like Speed) so a
    // large elevation like "1220 ft" isn't clipped by the nowrap readout cell.
    const elevText = scrubHasElev
      ? String(metricUnits ? Math.round(s.elevFt * 0.3048) : Math.round(s.elevFt))
      : "--";
    const effDisplay =
      scrubHasEff && effValue !== null && Number.isFinite(effValue)
        ? metricUnits
          ? (effValue / 0.621371).toFixed(1)
          : effValue.toFixed(1)
        : effText;
    node.replaceChildren(
      // Unit lives in the label (like Speed/Efficiency): baking it into the value
      // ("128.4 mi") clipped to "128.…" in the nowrap readout cell, losing the unit.
      scrubChip(`Distance ${dist.unit}`, String(dist.value)),
      // Unit lives in the label (like the efficiency chip below): "44 mph" was
      // one character too wide for the six-across readout cell and clipped to
      // "44 …" — the bare number always fits.
      scrubChip(`Speed ${units.speedUnit()}`, String(speedVal), { color: SCRUB_SPEED }),
      scrubChip(scrubHasElev ? `Elevation ${elevUnit}` : "Elevation", elevText, {
        color: scrubHasElev ? SCRUB_ELEV : null
      }),
      scrubChip("Grade", scrubHasElev ? scrubGrade(s.grade) : "--"),
      scrubChip(
        "Battery",
        socText,
        { color: scrubHasSoc ? SCRUB_SOC : null }
      ),
      // The label already carries the unit, so the value is just the number —
      // repeating the unit here overflowed the compact readout cell.
      scrubChip(
        units.efficiencyUnit(),
        effDisplay,
        { dim: !scrubHasEff, color: scrubHasEff ? SCRUB_EFF : null }
      )
    );
  }

  // ----- render + interaction -----------------------------------------------

  function paintScrub(target: HTMLElement, markup: string) {
    const old = target.querySelector("svg");
    if (old) old.remove();
    const next = parseSvg(markup);
    if (next) target.prepend(next);
  }

  function parseSvg(markup: string): SVGElement | null {
    const doc = new window.DOMParser().parseFromString(markup, "image/svg+xml");
    const node = doc.documentElement;
    if (!node || node.nodeName.toLowerCase() !== "svg") return null;
    return document.importNode(node, true) as unknown as SVGElement;
  }

  function renderScrubCharts() {
    const chart = el("scrubChart");
    if (!chart || !chart.clientWidth) return;
    paintScrub(chart, drawScrubCombo(chart.clientWidth));
    const stack = el("scrubStack");
    if (stack && scrubExpanded) {
      const w = chart.clientWidth;
      // Track values stay in source units (the trace autoscales, so only the
      // header label needs to reflect the user's unit preference).
      const metric = units.system() === "metric";
      const tracks: ScrubTrack[] = [
        ["mph", SCRUB_SPEED, "rgba(255,122,69,0.16)", `SPEED ${units.speedUnit().toUpperCase()}`, false]
      ];
      if (scrubHasElev) {
        tracks.push([
          "elevFt",
          SCRUB_ELEV,
          "rgba(139,148,173,0.18)",
          `ELEVATION ${metric ? "M" : "FT"}`,
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
          `EFFICIENCY ${units.efficiencyUnit().toUpperCase()}`,
          false
        ]);
      }
      stack.replaceChildren();
      tracks.forEach((t) => {
        const track = document.createElement("div");
        track.className = "scrub-track";
        const label = document.createElement("span");
        label.className = "scrub-track-label";
        label.textContent = t[3];
        track.appendChild(label);
        const svgNode = parseSvg(drawScrubTrack(w, t[0], t[1], t[2], t[3], t[4]));
        if (svgNode) track.appendChild(svgNode);
        const cursor = document.createElement("div");
        cursor.className = "scrub-cursor";
        track.appendChild(cursor);
        stack.appendChild(track);
      });
    }
    // Always-on elevation strip (v2): a compact terrain silhouette with its
    // own cursor, directly under the main chart. Hidden when the route never
    // logged altitude.
    const elevStrip = el("scrubElev");
    if (elevStrip) {
      elevStrip.hidden = !scrubHasElev;
      if (scrubHasElev) {
        paintScrub(
          elevStrip,
          drawScrubTrack(chart.clientWidth, "elevFt", "#a48cff", "rgba(164,140,255,0.18)", "", true)
        );
        bindScrubChart(elevStrip);
      }
    }
    scrubCursors = (
      [el("scrubCursor"), el("scrubElevCursor")]
        .concat(
          scrubExpanded
            ? Array.prototype.slice.call(
                document.querySelectorAll("#scrubStack .scrub-cursor")
              )
            : []
        )
        .filter((node): node is HTMLElement => Boolean(node))
    );
  }

  function setScrubCursor(frac: number) {
    if (!scrubData.length) return;
    scrubFrac = scrubClamp(frac, 0, 1);
    const s = scrubSampleAt(scrubFrac);
    scrubCursors.forEach((c) => {
      c.style.left = scrubFrac * 100 + "%";
    });
    // Keep the slider's assistive-tech position in sync with the cursor.
    const chart = el("scrubChart");
    if (chart) chart.setAttribute("aria-valuenow", String(Math.round(scrubFrac * 100)));
    fillScrubReadout(s);
    if (scrubMap && scrubTrail) {
      // Travelled-so-far trail: every sample at or before the cursor (found by
      // binary search over the precomputed vertices), plus the interpolated
      // cursor position as the leading vertex.
      const travelled = scrubTrailPts.slice(0, scrubTravelledIdx(scrubFrac) + 1);
      travelled.push([s.lat, s.lng]);
      scrubTrail.setLatLngs(travelled);
    }
    if (scrubMap && scrubMarker) {
      scrubMarker.setLatLng([s.lat, s.lng]);
      // Keep the marker's tap popup content live with the cursor, so tapping
      // it shows speed + efficiency at the current scrubbed position.
      const eff =
        scrubHasEff && Number.isFinite(s.eff)
          ? units.efficiencyText(Number(s.eff))
          : null;
      const grade = scrubHasElev ? scrubGrade(s.grade) : null;
      const popupMetric = units.system() === "metric";
      const popupSpeed = Math.round(popupMetric ? s.mph / 0.621371 : s.mph);
      const lines = [`${popupSpeed} ${units.speedUnit()}`];
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
  function bindScrubChart(elc: HTMLElement | null) {
    if (!elc || elc.dataset.scrubBound === "1") return;
    elc.dataset.scrubBound = "1";
    const move = (ev: PointerEvent) => {
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
    // Keyboard + screen-reader access: expose the main chart as a slider so
    // arrow keys step the scrub fraction (pointer users drag; both funnel
    // through setScrubCursor). Stack tracks stay plain draggable strips.
    if (elc.id === "scrubChart") {
      elc.setAttribute("role", "slider");
      elc.setAttribute("tabindex", "0");
      elc.setAttribute("aria-label", "Scrub through this drive");
      elc.setAttribute("aria-valuemin", "0");
      elc.setAttribute("aria-valuemax", "100");
      elc.setAttribute("aria-valuenow", String(Math.round(scrubFrac * 100)));
      elc.addEventListener("keydown", (ev: KeyboardEvent) => {
        // Step as an integer percent of the track, divided by 100. A `? 0.1 :`
        // ternary would minify to `?.1:` — a false hit on the Android-9 "no
        // optional chaining" bundle guard (script-order.test.js). Integer
        // branches (`?10:2`) sidestep it and esbuild can't fold them into `?.`.
        const step = (ev.shiftKey ? 10 : 2) / 100;
        let next = scrubFrac;
        if (ev.key === "ArrowLeft" || ev.key === "ArrowDown") next = scrubFrac - step;
        else if (ev.key === "ArrowRight" || ev.key === "ArrowUp") next = scrubFrac + step;
        else if (ev.key === "Home") next = 0;
        else if (ev.key === "End") next = 1;
        else return;
        ev.preventDefault();
        setScrubCursor(next);
      });
    }
  }

  // Re-attach drag handlers to every expanded stack track. renderScrubCharts()
  // rebuilds the .scrub-track nodes from scratch (no listeners), so every
  // re-render path — initial render, the Details toggle, AND window resize —
  // must re-run the binding or expanded tracks silently lose drag.
  function bindScrubTracks() {
    Array.prototype.slice
      .call(document.querySelectorAll("#scrubStack .scrub-track"))
      .forEach((node) => bindScrubChart(node as HTMLElement));
  }

  function hideScrubber() {
    // Stop any in-progress playback first, or its rAF loop keeps running against
    // cleared scrubData and the Play button stays stuck on "■ Stop".
    if (typeof stopScrubPlay === "function") stopScrubPlay();
    const node = el("scrubber");
    if (node) node.hidden = true;
    document.body.classList.remove("map-scrubber-active");
    if (scrubMarker && scrubMap) {
      scrubMap.removeLayer(scrubMarker);
      scrubMarker = null;
    }
    if (scrubTrail && scrubMap) {
      scrubMap.removeLayer(scrubTrail as unknown as ScrubMarkerHandle);
      scrubTrail = null;
    }
    scrubData = [];
    scrubTrailPts = [];
  }

  // Session id of the last render. The live route is re-rendered on every appended
  // GPS fix (~1/s), so keying off it lets a same-route update preserve the user's
  // scrub position and running playback instead of snapping back to 50% and
  // stopping the animation roughly once a second.
  let lastScrubRouteKey = "";

  // Called by map.js whenever the selected route changes.
  function renderScrubber(route: ScrubRoute) {
    // The route identity lives at route.session.id (MapRoute shape; the live route
    // is session:{ id: LIVE_ROUTE_ID }) — the same field routeIsLive() keys on. A
    // bare route.id is always undefined here, which would make sameRoute never match.
    const routeSession = (route as { session?: { id?: unknown } }).session || {};
    const routeKey = String(routeSession.id || "");
    const sameRoute = routeKey !== "" && routeKey === lastScrubRouteKey && scrubData.length >= 2;
    lastScrubRouteKey = routeKey;
    // Only a genuinely new route resets playback; a live append keeps it running.
    if (!sameRoute && typeof stopScrubPlay === "function") stopScrubPlay();
    scrubData = buildScrubData(route);
    scrubTrailPts = scrubData.map((point) => [point.lat, point.lng] as LatLngTuple);
    const node = el("scrubber");
    if (!node) return;
    if (scrubData.length < 2) {
      hideScrubber();
      return;
    }
    node.hidden = false;
    const mapView = el("view-map");
    document.body.classList.toggle(
      "map-scrubber-active",
      !!mapView && mapView.classList.contains("is-active"),
    );

    if (scrubMap) {
      if (!scrubTrail) {
        scrubTrail = L.polyline([], {
          color: "#e8f7ff",
          weight: 2.5,
          opacity: 0.95,
          interactive: false
        }) as ScrubTrailHandle;
      }
      scrubTrail.addTo(scrubMap);
      if (!scrubMarker) {
        const firstPoint = scrubData[0];
        if (!firstPoint) return;
        scrubMarker = L.circleMarker(
          [firstPoint.lat, firstPoint.lng],
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
      const marker = scrubMarker;
      if (marker) {
        marker.addTo(scrubMap);
        marker.bringToFront();
      }
    }
    renderScrubCharts();
    bindScrubChart(el("scrubChart"));
    bindScrubTracks();
    // Keep the user's position on a same-route update (live append); only a freshly
    // selected route jumps to the mid-route default. While playback is running on
    // the same route, let the animation loop drive the cursor instead of resetting it.
    if (!(sameRoute && scrubAnim)) {
      setScrubCursor(sameRoute ? scrubFrac : 0.5);
    }
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
        bindScrubTracks();
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
  let scrubAnim: number | null = null;
  const playBtn = el("scrubPlay");
  // v2 design copy: "Play route" / "Pause".
  const PLAY_LABEL = "▶ Play route";
  const STOP_LABEL = "❚❚ Pause";

  function prefersReducedMotion() {
    return Boolean(
      window.matchMedia &&
        window.matchMedia("(prefers-reduced-motion: reduce)").matches
    );
  }

  function setScrubAnimMode(on: boolean) {
    scrubCursors.forEach((c) => {
      if (!c) return;
      c.style.transition = on && !prefersReducedMotion() ? "left 90ms linear" : "";
    });
  }

  function stopScrubPlay() {
    if (scrubAnim) cancelAnimationFrame(scrubAnim);
    scrubAnim = null;
    setScrubAnimMode(false);
    el("scrubReadout")?.classList.remove("is-playing");
    if (playBtn) {
      playBtn.textContent = PLAY_LABEL;
      playBtn.setAttribute("aria-label", "Play route playback");
    }
  }
  if (playBtn) {
    playBtn.addEventListener("click", () => {
      if (!scrubData.length) return;
      if (scrubAnim) { stopScrubPlay(); return; }
      if (prefersReducedMotion()) {
        setScrubCursor(1);
        if (playBtn) playBtn.textContent = PLAY_LABEL;
        return;
      }
      playBtn.textContent = STOP_LABEL;
      playBtn.setAttribute("aria-label", "Stop route playback");
      setScrubAnimMode(true);
      // Readout cells glow while playing so the live numbers read as active.
      el("scrubReadout")?.classList.add("is-playing");
      const lastPoint = scrubData[scrubData.length - 1];
      const totalMi = (lastPoint && lastPoint.distMi) || 22;
      // ~1 second per mile, with a sane 8-22s floor/ceiling so very short or
      // very long drives still play in a watchable window.
      const dur = Math.min(22000, Math.max(8000, totalMi * 1000));
      const t0 = performance.now();
      const step = (now: number) => {
        const f = (now - t0) / dur;
        if (f >= 1) { setScrubCursor(1); stopScrubPlay(); return; }
        setScrubCursor(f);
        scrubAnim = requestAnimationFrame(step);
      };
      scrubAnim = requestAnimationFrame(step);
    });
  }

  let scrubResizeTimer: ReturnType<typeof setTimeout> | null = null;
  window.addEventListener("resize", () => {
    if (scrubResizeTimer) clearTimeout(scrubResizeTimer);
    scrubResizeTimer = setTimeout(() => {
      const node = el("scrubber");
      if (scrubData.length && node && !node.hidden) {
        renderScrubCharts();
        bindScrubTracks();
        setScrubCursor(scrubFrac);
      }
    }, 160);
  });

  // Snap the cursor to the route point closest to a lat/lng. Used by map.js
  // so tapping anywhere on the map jumps the scrubber to that point.
  function scrubAtLatLng(lat: number, lng: number) {
    if (!scrubData.length) return;
    let bestIdx = 0;
    let bestDist = Infinity;
    for (let i = 0; i < scrubData.length; i += 1) {
      const point = scrubData[i];
      if (!point) continue;
      const dLat = point.lat - lat;
      const dLng = point.lng - lng;
      const d = dLat * dLat + dLng * dLng;
      if (d < bestDist) { bestDist = d; bestIdx = i; }
    }
    const bestPoint = scrubData[bestIdx];
    if (bestPoint) setScrubCursor(bestPoint.frac);
  }

  Object.assign(VD, {
    renderScrubber,
    hideScrubber,
    scrubberAttachMap,
    scrubAtLatLng
  });

export {};
