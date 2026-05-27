(function () {
  "use strict";

  const VD = window.VoltDashboard;
  const state = VD.state;
  const bridge = VD.bridge;
  const el = VD.el;

  let mapInstance = null;
  let remoteTileLayer = null;
  const mapLayerGroups = { routes: null, heat: null, stops: null, eff: null };
  let mapFitKey = null;

  // Per-segment efficiency bucket color for the V3 "Eff" layer. Grey for
  // segments without power data yet, so the user can see at a glance which
  // parts of the drive lack derived efficiency.
  function mapEffColor(eff) {
    if (!Number.isFinite(eff)) return "#6a6a72";
    if (eff >= 4) return "#b8e63b";
    if (eff >= 2.7) return "#ffb84a";
    return "#ff6b5f";
  }

  function createRemoteTileLayer(map) {
    const tiles = L.tileLayer("https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png", {
      subdomains: "abcd",
      maxZoom: 19,
      attribution: "&copy; OpenStreetMap, &copy; CARTO"
    });
    // Track tile errors and swap to the plain OSM basemap if CARTO is unreachable (DNS block, CDN
    // outage, regional restriction). Log only the first couple to avoid spamming when the whole
    // basemap is down.
    let tileErrorCount = 0;
    let fallbackActivated = false;
    tiles.on("tileerror", (event) => {
      tileErrorCount += 1;
      const src = (event && event.tile && event.tile.src) || "unknown";
      if (tileErrorCount <= 2) {
        if (bridge && typeof bridge.logClientError === "function") {
          bridge.logClientError("map.tileerror", "Basemap tile failed: " + src);
        }
      }
      if (tileErrorCount > 5 && !fallbackActivated) {
        fallbackActivated = true;
        try {
          map.removeLayer(tiles);
          const fallback = L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
            attribution: "© OpenStreetMap",
            maxZoom: 19
          });
          fallback.addTo(map);
          remoteTileLayer = fallback;
          if (bridge && typeof bridge.logClientError === "function") {
            bridge.logClientError("map.fallback", "Switched to OSM basemap after tile errors");
          }
        } catch (err) {
          if (bridge && typeof bridge.logClientError === "function") {
            bridge.logClientError("map.fallback_failed", String(err));
          }
        }
      }
    });
    return tiles;
  }

  function syncRemoteTiles() {
    if (!mapInstance || typeof L === "undefined") return;
    if (state.mapRemoteTilesEnabled && !remoteTileLayer) {
      remoteTileLayer = createRemoteTileLayer(mapInstance);
      remoteTileLayer.addTo(mapInstance);
      return;
    }
    if (!state.mapRemoteTilesEnabled && remoteTileLayer) {
      mapInstance.removeLayer(remoteTileLayer);
      remoteTileLayer = null;
    }
  }

  // Creates the Leaflet map once. Remote basemap tiles are opt-in; the route and all data are drawn
  // from on-device state and work against the blank offline canvas regardless.
  function ensureMap() {
    if (mapInstance) return mapInstance;
    if (typeof L === "undefined") return null;
    const container = el("mapLeaflet");
    if (!container) return null;
    mapInstance = L.map(container, { zoomControl: false, attributionControl: true })
      .setView([39.5, -98.35], 4);
    syncRemoteTiles();
    if (typeof VD.scrubberAttachMap === "function") VD.scrubberAttachMap(mapInstance);
    // Tap anywhere on the map → snap the scrubber to the closest route point.
    mapInstance.on("click", (e) => {
      if (e && e.latlng && typeof VD.scrubAtLatLng === "function") {
        VD.scrubAtLatLng(e.latlng.lat, e.latlng.lng);
      }
    });
    return mapInstance;
  }

  function renderMap() {
    const storage = state.storage || {};
    const routes = Array.isArray(storage.recentRoutes) ? storage.recentRoutes : [];
    if (!state.selectedMapSessionId && routes.length) {
      state.selectedMapSessionId = (routes[0].session || {}).id || null;
    }
    const route = selectedMapRoute(storage);
    const points = Array.isArray(route.points) ? route.points : [];
    const hasRoute = points.length >= 2;
    const layer = state.mapLayer;

    const frame = el("mapFrame");
    if (frame) frame.dataset.layer = layer;
    document.querySelectorAll("[data-map-layer]").forEach((button) => {
      const active = button.dataset.mapLayer === layer;
      button.classList.toggle("is-active", active);
      button.setAttribute("aria-selected", active ? "true" : "false");
    });
    const mapCard = el("mapCard");
    if (mapCard) mapCard.classList.toggle("is-fullscreen", state.mapFull);
    document.body.classList.toggle("map-full-active", state.mapFull);
    const tilesBtn = el("mapTilesBtn");
    if (tilesBtn) {
      tilesBtn.setAttribute("aria-pressed", state.mapRemoteTilesEnabled ? "true" : "false");
      tilesBtn.setAttribute(
        "aria-label",
        state.mapRemoteTilesEnabled ? "Disable remote map tiles" : "Enable remote map tiles"
      );
    }
    const fullBtn = el("mapFullBtn");
    if (fullBtn) {
      fullBtn.setAttribute("aria-pressed", state.mapFull ? "true" : "false");
      fullBtn.setAttribute("aria-label", state.mapFull ? "Exit full map" : "Toggle full map");
    }

    VD.setText("mapPointBadge", `${points.length} pts`);
    const routeSession = sessionForRoute(route);
    // Title is the human label; the stats row carries the raw distance, so we
    // don't repeat "X mi" here.
    VD.setText(
      "mapTitle",
      hasRoute
        ? routeSession.adapterName || routeSession.mode || "Logged drive"
        : "No route recorded yet"
    );
    VD.setText(
      "mapKicker",
      hasRoute ? `GPS map - ${VD.formatWhen(routeSession.startedAtMs)}` : "GPS map"
    );
    VD.setText("mapDistance", hasRoute ? VD.formatDistance(route.distanceMeters || 0) : "--");
    const session = route.session || {};
    const duration = Number(session.endedAtMs || Date.now()) - Number(session.startedAtMs || 0);
    VD.setText("mapDuration", hasRoute && duration > 0 ? VD.formatDuration(duration) : "--");
    // Avg moving speed in mph from GPS: distance / duration, ignoring stopped
    // time at the granularity of the route. More useful than GPS accuracy.
    const avgMph = hasRoute && duration > 0
      ? (Number(route.distanceMeters || 0) / (duration / 1000)) * 2.2369363
      : 0;
    VD.setText("mapAvgMph", avgMph > 0 ? Math.round(avgMph) : "--");
    const empty = el("mapEmpty");
    if (empty) empty.hidden = hasRoute;

    if (hasRoute && typeof VD.enrichRouteEff === "function") VD.enrichRouteEff(route);
    syncRemoteTiles();
    drawMapRoute(points, hasRoute, layer, routeSession);
    if (hasRoute && typeof VD.renderScrubber === "function") VD.renderScrubber(route);
    else if (typeof VD.hideScrubber === "function") VD.hideScrubber();
    renderMapDriveChips(routes);
    renderMapSessionList(routes);
    // Hide the Recorded Sessions card when the chip strip already covers
    // every drive. It re-appears with its empty-state message when there are
    // no logged drives yet (so the user sees the "no route" prompt).
    const sessionsCard = document.querySelector("#view-map .map-layout");
    if (sessionsCard) sessionsCard.hidden = routes.length > 0;
  }

  // Chip date formatter: "Today 2:51 AM" / "Yesterday 6:54 PM" / "Sat 9:15 AM" /
  // "Oct 15". More skim-able than "6h ago / 30h ago / 4d ago".
  function fmtChipDate(ms) {
    if (!Number.isFinite(ms)) return "session";
    const now = new Date();
    const d = new Date(ms);
    const today = now.toDateString();
    const drive = d.toDateString();
    const yesterday = new Date(now.getTime() - 86400000).toDateString();
    const time = d.toLocaleTimeString("en-US", {
      hour: "numeric",
      minute: "2-digit"
    });
    if (drive === today) return "Today " + time;
    if (drive === yesterday) return "Yesterday " + time;
    const daysAgo = (now.getTime() - ms) / 86400000;
    if (daysAgo < 7) {
      return (
        d.toLocaleDateString("en-US", { weekday: "short" }) + " " + time
      );
    }
    return d.toLocaleDateString("en-US", { month: "short", day: "numeric" });
  }

  // Horizontal drive-picker chips above the map. Each chip shows the drive's
  // start time, distance, and a color-coded average efficiency dot — same
  // pattern as the demo's drive picker. Click delegation flows through the
  // existing [data-map-session] handler in actions.js.
  function renderMapDriveChips(routes) {
    const wrap = el("mapDriveChips");
    if (!wrap) return;
    if (!routes.length) {
      wrap.replaceChildren();
      wrap.hidden = true;
      return;
    }
    wrap.hidden = false;
    const selId = String(state.selectedMapSessionId || "");
    const chips = routes.map((route) => {
      if (typeof VD.enrichRouteEff === "function") VD.enrichRouteEff(route);
      const s = sessionForRoute(route);
      const id = String(s.id || "");
      const button = document.createElement("button");
      button.type = "button";
      button.className = "map-drive-chip" + (id === selId ? " is-active" : "");
      button.dataset.mapSession = id;
      const date = document.createElement("span");
      date.className = "dl";
      date.textContent = fmtChipDate(s.startedAtMs);
      const meta = document.createElement("span");
      meta.className = "dm";
      const distance = document.createElement("b");
      const distMi = (Number(route.distanceMeters || 0) / 1609.34).toFixed(1);
      distance.textContent = distMi + " mi";
      meta.append(distance);
      const effPts = (route.points || []).filter((p) => Number.isFinite(Number(p.eff)));
      const avgEff = effPts.length
        ? effPts.reduce((acc, p) => acc + Number(p.eff), 0) / effPts.length
        : 0;
      if (avgEff > 0) {
        meta.append(document.createTextNode(" · "));
        const dot = document.createElement("u");
        dot.style.background = mapEffColor(avgEff);
        meta.append(dot, document.createTextNode(" " + avgEff.toFixed(1) + " mi/kWh"));
      }
      button.append(date, meta);
      return button;
    });
    wrap.replaceChildren(...chips);
    // Keep the active chip visible after a selection change (or on first render
    // when the active chip might not be the first one).
    const active = wrap.querySelector(".map-drive-chip.is-active");
    if (active && typeof active.scrollIntoView === "function") {
      active.scrollIntoView({ inline: "nearest", block: "nearest" });
    }
  }

  // Draws the selected route on Leaflet as routes / heat / stops layer groups.
  function drawMapRoute(points, hasRoute, layer, routeSession) {
    const container = el("mapLeaflet");
    if (!container || !container.offsetWidth || !container.offsetHeight) return;
    const map = ensureMap();
    if (!map) return;
    map.invalidateSize(false);
    Object.keys(mapLayerGroups).forEach((key) => {
      if (mapLayerGroups[key]) {
        map.removeLayer(mapLayerGroups[key]);
        mapLayerGroups[key] = null;
      }
    });
    if (!hasRoute) return;
    const latlngs = points.map((p) => [Number(p.lat), Number(p.lng)]);

    mapLayerGroups.routes = L.layerGroup([
      L.polyline(latlngs, { color: "#ff7a45", weight: 9, opacity: 0.16 }),
      L.polyline(latlngs, { color: "#ff7a45", weight: 3.5, opacity: 1 }),
      L.circleMarker(latlngs[0], { radius: 6, color: "#fff", weight: 2, fillColor: "#ff7a45", fillOpacity: 1 }),
      L.circleMarker(latlngs[latlngs.length - 1], { radius: 7, color: "#fff", weight: 2, fillColor: "#ff7141", fillOpacity: 1 })
    ]);

    const bands = { "#ff6b4a": [], "#ffd23f": [], "#7ee06a": [] };
    for (let i = 1; i < points.length; i += 1) {
      const speed = segmentSpeedMps(points[i - 1], points[i]);
      const color = speed < 8 ? "#ff6b4a" : (speed < 18 ? "#ffd23f" : "#7ee06a");
      bands[color].push([latlngs[i - 1], latlngs[i]]);
    }
    mapLayerGroups.heat = L.layerGroup();
    Object.keys(bands).forEach((color) => {
      if (bands[color].length) {
        L.polyline(bands[color], { color, weight: 5, opacity: 0.95 }).addTo(mapLayerGroups.heat);
      }
    });

    mapLayerGroups.stops = L.layerGroup([
      L.polyline(latlngs, { color: "#ff7a45", weight: 2.5, opacity: 0.4 })
    ]);
    detectStops(points).slice(0, 20).forEach((stop) => {
      const radius = Math.min(13, 7 + stop.durationMs / 120000);
      L.circleMarker([stop.lat, stop.lng], {
        radius, color: "#ffa84c", weight: 2, fillColor: "#ffa84c", fillOpacity: 0.25
      }).bindTooltip(`Stop · ${VD.formatDuration(stop.durationMs)}`).addTo(mapLayerGroups.stops);
    });

    // V3 efficiency layer — per-segment polylines bucketed by mi/kWh.
    // Segments with no power data (eff null) render grey so the user
    // can tell which portions of the drive lack derived efficiency.
    const effBands = {};
    for (let i = 1; i < points.length; i += 1) {
      const color = mapEffColor(Number(points[i].eff));
      (effBands[color] = effBands[color] || []).push([latlngs[i - 1], latlngs[i]]);
    }
    mapLayerGroups.eff = L.layerGroup();
    // Soft white halo underneath the colored segments so the route reads
    // clearly against busy basemap areas (urban grid, dense streets).
    L.polyline(latlngs, {
      color: "#ffffff", weight: 11, opacity: 0.09, interactive: false
    }).addTo(mapLayerGroups.eff);
    Object.keys(effBands).forEach((color) => {
      L.polyline(effBands[color], { color, weight: 5, opacity: 0.95 }).addTo(mapLayerGroups.eff);
    });
    L.circleMarker(latlngs[0], { radius: 6, color: "#fff", weight: 2, fillColor: "#b8e63b", fillOpacity: 1 }).addTo(mapLayerGroups.eff);
    L.circleMarker(latlngs[latlngs.length - 1], { radius: 7, color: "#fff", weight: 2, fillColor: "#ff6b5f", fillOpacity: 1 }).addTo(mapLayerGroups.eff);

    (mapLayerGroups[layer] || mapLayerGroups.routes).addTo(map);

    const fitKey = `${(routeSession || {}).id || ""}:${points.length}`;
    if (fitKey !== mapFitKey) {
      map.fitBounds(L.latLngBounds(latlngs), { padding: [30, 30] });
      mapFitKey = fitKey;
    }
  }

  function renderMapSessionList(routes) {
    const list = el("mapSessionList");
    if (!list) return;
    if (!routes.length) {
      const p = document.createElement("p");
      p.className = "status-copy";
      p.textContent = "No route-bearing SQLite sessions yet. Start a real logged drive with GPS permission and the route will render here.";
      list.replaceChildren(p);
      return;
    }
    list.replaceChildren(...routes.map(buildMapSessionRow));
  }

  function buildMapSessionRow(r) {
    const s = sessionForRoute(r);
    const active = String(s.id || "") === String(state.selectedMapSessionId || "");
    const button = document.createElement("button");
    button.type = "button";
    button.className = "history-row" + (active ? " is-active" : "");
    button.dataset.mapSession = String(s.id || "");
    const center = document.createElement("span");
    const strong = document.createElement("strong");
    strong.textContent = `${s.mode || "session"} - ${s.adapterName || "OBD adapter"}`;
    const small = document.createElement("small");
    small.textContent = `${VD.formatWhen(s.startedAtMs)} - ${VD.formatDistance(Number(r.distanceMeters || 0))} - ${Number(r.pointCount || 0)} pts`;
    center.append(strong, small);
    const right = document.createElement("b");
    right.textContent = s.status || "stored";
    button.append(center, right);
    return button;
  }

  function selectedMapRoute(storage) {
    const routes = Array.isArray(storage.recentRoutes) ? storage.recentRoutes : [];
    if (routes.length) {
      return routes.find((route) => String((route.session || {}).id || "") === String(state.selectedMapSessionId || "")) || routes[0];
    }
    return storage.latestRoute || {};
  }

  function sessionForRoute(route) {
    return route && route.session ? route.session : {};
  }

  function haversineMetersJs(lat1, lng1, lat2, lng2) {
    const r = 6371000;
    const dLat = (lat2 - lat1) * Math.PI / 180;
    const dLng = (lng2 - lng1) * Math.PI / 180;
    const a = Math.sin(dLat / 2) ** 2
      + Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180)
      * Math.sin(dLng / 2) ** 2;
    return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }

  function routeDistanceMeters(points) {
    let total = 0;
    for (let i = 1; i < points.length; i += 1) {
      total += haversineMetersJs(points[i - 1].lat, points[i - 1].lng, points[i].lat, points[i].lng);
    }
    return total;
  }

  function segmentSpeedMps(a, b) {
    const seconds = Math.max(1, (Number(b.atMs) - Number(a.atMs)) / 1000);
    return haversineMetersJs(a.lat, a.lng, b.lat, b.lng) / seconds;
  }

  // A stop is a sustained run (>= 45 s) of near-zero movement between GPS points.
  function detectStops(points) {
    const stops = [];
    let runStart = null;
    for (let i = 1; i < points.length; i += 1) {
      const slow = segmentSpeedMps(points[i - 1], points[i]) < 1.5;
      if (slow) {
        if (runStart === null) runStart = i - 1;
      } else if (runStart !== null) {
        addStop(stops, points, runStart, i - 1);
        runStart = null;
      }
    }
    if (runStart !== null) addStop(stops, points, runStart, points.length - 1);
    return stops;
  }

  function addStop(stops, points, startIdx, endIdx) {
    const durationMs = Number(points[endIdx].atMs) - Number(points[startIdx].atMs);
    if (durationMs < 45000) return;
    const mid = points[Math.floor((startIdx + endIdx) / 2)];
    stops.push({ lat: mid.lat, lng: mid.lng, durationMs });
  }

  // A real ~23 mi logged drive (session 2 of the test database), kept as [secondsFromStart,
  // lat, lng] triples. Used only as preview content when no Android bridge is present, so
  // the dashboard can be reviewed loaded; it never runs inside the real app.
  const _SAMPLE_ROUTE_START_MS = 1779281066443;
  const SAMPLE_ROUTE = [[0,32.80131,-116.9513],[29,32.80324,-116.95098],[58,32.80344,-116.95173],[86,32.80321,-116.95898],[112,32.80314,-116.96924],[139,32.79793,-116.97686],[167,32.78853,-116.97791],[194,32.78066,-116.9825],[220,32.77918,-116.99186],[247,32.77833,-117.00258],[273,32.77462,-117.01211],[299,32.77102,-117.02164],[326,32.77389,-117.03147],[352,32.77268,-117.04105],[378,32.77609,-117.0498],[405,32.77837,-117.05984],[431,32.77939,-117.06921],[457,32.7801,-117.07931],[483,32.78089,-117.0893],[511,32.77943,-117.09954],[537,32.77872,-117.10943],[563,32.77788,-117.1194],[590,32.77325,-117.12789],[616,32.77062,-117.13732],[642,32.76709,-117.14734],[669,32.7644,-117.15821],[695,32.76095,-117.16827],[721,32.75943,-117.17893],[748,32.76013,-117.18982],[774,32.76021,-117.19997],[800,32.75575,-117.20492],[826,32.75121,-117.20511],[852,32.74743,-117.2089],[878,32.74532,-117.21175],[905,32.74297,-117.21367],[934,32.73984,-117.21632],[961,32.73651,-117.21916],[989,32.73247,-117.22261],[1017,32.72903,-117.22553],[1045,32.7292,-117.22551],[1074,32.72893,-117.22561],[1103,32.72679,-117.22744],[1130,32.72613,-117.228],[1158,32.72348,-117.23026],[1188,32.72314,-117.23053],[1217,32.72154,-117.23188],[1245,32.7204,-117.23277],[1273,32.71904,-117.23456],[1301,32.71875,-117.23497],[1329,32.71858,-117.2352],[1358,32.71839,-117.23547],[1386,32.71828,-117.23569],[1415,32.71818,-117.2357],[1444,32.71791,-117.23587],[1473,32.71751,-117.23595],[1501,32.71704,-117.23612],[1530,32.71623,-117.23621],[1557,32.71601,-117.23646],[1585,32.71559,-117.23663],[1613,32.71527,-117.23675],[1640,32.71482,-117.23691],[1667,32.71444,-117.23707],[1693,32.71383,-117.23737],[1721,32.71325,-117.23766],[1747,32.71302,-117.23772],[1773,32.71254,-117.23785],[1801,32.71167,-117.2381],[1829,32.71131,-117.23819],[1857,32.71064,-117.23836],[1885,32.71034,-117.23846],[1913,32.70995,-117.23854],[1941,32.70949,-117.23866],[1971,32.70894,-117.2388],[1999,32.70801,-117.23906],[2027,32.70724,-117.23926],[2056,32.70754,-117.23883],[2085,32.70661,-117.23928],[2112,32.70621,-117.23918],[2140,32.70603,-117.2382],[2168,32.70531,-117.23915],[2194,32.705,-117.23913],[2221,32.70452,-117.23904],[2249,32.70137,-117.23966],[2276,32.69781,-117.24032],[2303,32.69418,-117.24076],[2331,32.69063,-117.23976],[2360,32.68805,-117.23964],[2389,32.68648,-117.23939],[2417,32.6851,-117.23849],[2445,32.68422,-117.23824],[2474,32.68409,-117.23972],[2503,32.68402,-117.23975],[2529,32.68412,-117.2397]];

  // Build one synthetic route from a slice of SAMPLE_ROUTE. Each route gets its
  // own session id, start time, altitude profile, socTrack, and powerTrack so
  // the chip strip, Eff layer, scrubber, and Insights scatter all populate
  // with varied content rather than a single drive.
  function buildSampleRoute(opts) {
    const slice = SAMPLE_ROUTE.slice(opts.from, opts.to);
    const baseT = slice[0][0];
    const points = slice.map(([t, lat, lng]) => ({
      atMs: opts.startedAtMs + (t - baseT) * 1000, lat, lng
    }));
    const startMs = points[0].atMs;
    const endMs = points[points.length - 1].atMs;
    const distanceMeters = routeDistanceMeters(points);

    // Elevation: descend from El Cajon (~150 m) to the coast with a mid-route
    // hill, then shift by opts.elevShift so each drive feels different.
    let cumM = 0;
    for (let i = 0; i < points.length; i += 1) {
      if (i > 0) {
        cumM += haversineMetersJs(
          points[i - 1].lat, points[i - 1].lng, points[i].lat, points[i].lng
        );
      }
      const f = cumM / (distanceMeters || 1);
      points[i].altM =
        150 - 142 * f +
        70 * Math.exp(-Math.pow((f - 0.46) / 0.085, 2)) +
        7 * Math.sin(f * 19) +
        (opts.elevShift || 0);
    }

    const socTrack = [];
    const socEnd = Math.max(15, opts.startSoc - opts.socDrop);
    for (let i = 0; i <= 24; i += 1) {
      const f = i / 24;
      socTrack.push({
        atMs: startMs + f * (endMs - startMs),
        soc: opts.startSoc - opts.socDrop * f
      });
    }

    const powerTrack = [];
    const mass = 1720, g = 9.81, Crr = 0.011, kAero = 0.5 * 1.2 * 0.28 * 2.2;
    let prevV = 0;
    for (let i = 0; i < points.length; i += 1) {
      const a = points[Math.max(0, i - 1)];
      const b = points[Math.min(points.length - 1, i + 1)];
      const dt = Math.max(1, (b.atMs - a.atMs) / 1000);
      const v = haversineMetersJs(a.lat, a.lng, b.lat, b.lng) / dt;
      const horiz = Math.max(8, haversineMetersJs(a.lat, a.lng, points[i].lat, points[i].lng) || 1);
      const dz = Number(points[i].altM) - Number(points[Math.max(0, i - 1)].altM || points[i].altM);
      const grade = Math.max(-0.13, Math.min(0.13, dz / horiz));
      const accel = i > 0 ? (v - prevV) / dt : 0;
      prevV = v;
      const force = Crr * mass * g + kAero * v * v + mass * g * grade + mass * accel;
      let watts = force * v;
      watts = watts > 0 ? watts / 0.86 : watts * 0.55;
      watts += opts.accW || 320;
      const kW = Math.max(-28, Math.min(72, watts / 1000));
      powerTrack.push({ atMs: points[i].atMs, powerKw: kW });
    }

    const session = {
      id: opts.sessionId,
      mode: "obd",
      adapterName: opts.adapterName || "OBDLink MX+",
      startedAtMs: startMs,
      endedAtMs: endMs,
      status: "complete",
      sampleCount: points.length * 21
    };
    return {
      session, points, pointCount: points.length,
      distanceMeters, bounds: {}, socTrack, powerTrack,
      socEnd: socEnd
    };
  }

  function loadSampleData() {
    VD.setDemoActive(false);
    // Three synthetic drives so the session picker has work to do and the
    // scatter actually pools data. Times are relative to "now" so chips read
    // "today / yesterday / Sat" rather than a hardcoded 3d-ago.
    const now = Date.now();
    const hour = 3600 * 1000;
    const today = buildSampleRoute({
      sessionId: 2001,
      startedAtMs: now - 6 * hour,
      from: 0, to: 91,
      startSoc: 88, socDrop: 42, accW: 340
    });
    const yesterday = buildSampleRoute({
      sessionId: 2002,
      startedAtMs: now - 30 * hour,
      from: 18, to: 60,
      startSoc: 36, socDrop: 22, accW: 720,  // hot day, AC on, low start
      elevShift: -8
    });
    const earlier = buildSampleRoute({
      sessionId: 2003,
      startedAtMs: now - 4 * 24 * hour,
      from: 5, to: 45,
      startSoc: 73, socDrop: 18, accW: 380,  // morning descent
      elevShift: 4
    });
    const routes = [today, yesterday, earlier];

    state.trips = routes.map((r) => ({
      id: r.session.id,
      startedAtMs: r.session.startedAtMs,
      endedAtMs: r.session.endedAtMs,
      durationMs: r.session.endedAtMs - r.session.startedAtMs,
      distanceMeters: r.distanceMeters,
      maxSpeedKph: 105,
      avgMovingSpeedKph: 47,
      sampleCount: r.session.sampleCount,
      pointCount: r.points.length,
      hasRoute: true,
      adapterName: r.session.adapterName,
      status: "complete"
    }));

    const totalDistance = routes.reduce((s, r) => s + r.distanceMeters, 0);
    const totalDrive = routes.reduce(
      (s, r) => s + (r.session.endedAtMs - r.session.startedAtMs),
      0
    );

    state.storage = {
      database: "volttracker_obd_poc.db",
      databaseBytes: 21086208,
      sessionCount: routes.length,
      sampleCount: routes.reduce((s, r) => s + r.session.sampleCount, 0),
      rawTelemetryCount: routes.reduce((s, r) => s + r.session.sampleCount, 0),
      locationSampleCount: routes.reduce((s, r) => s + r.points.length, 0),
      recentRoutes: routes,
      latestRoute: today,
      recentSessions: []
    };
    state.insights = {
      tripCount: routes.length,
      totalDistanceMeters: totalDistance,
      totalDriveMs: totalDrive,
      longestTripMeters: Math.max.apply(null, routes.map((r) => r.distanceMeters)),
      maxSpeedKph: 105,
      gpsTripCount: routes.length
    };
    VD.renderRealV2Ui();
    renderMap();
    VD.renderRealTrips();
    VD.renderInsightStats();
    VD.setStatus({ state: "ready", detail: "Preview sample drive loaded (real logged route)." });
  }

  Object.assign(VD, {
    ensureMap,
    renderMap,
    drawMapRoute,
    renderMapSessionList,
    selectedMapRoute,
    sessionForRoute,
    haversineMetersJs,
    routeDistanceMeters,
    segmentSpeedMps,
    detectStops,
    addStop,
    loadSampleData
  });
})();
