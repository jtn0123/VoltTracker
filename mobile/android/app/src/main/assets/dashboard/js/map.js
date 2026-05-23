(function () {
  "use strict";

  const VD = window.VoltDashboard;
  const state = VD.state;
  const bridge = VD.bridge;
  const el = VD.el;

  let mapInstance = null;
  const mapLayerGroups = { routes: null, heat: null, stops: null };
  let mapFitKey = null;

  // Creates the Leaflet map once, with CARTO dark basemap tiles. Tiles need network;
  // the route and all data are drawn from on-device state and work offline regardless.
  function ensureMap() {
    if (mapInstance) return mapInstance;
    if (typeof L === "undefined") return null;
    const container = el("mapLeaflet");
    if (!container) return null;
    mapInstance = L.map(container, { zoomControl: false, attributionControl: true })
      .setView([39.5, -98.35], 4);
    const tiles = L.tileLayer("https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png", {
      subdomains: "abcd",
      maxZoom: 19,
      attribution: "&copy; OpenStreetMap, &copy; CARTO"
    });
    let tileErrorLogged = false;
    tiles.on("tileerror", (event) => {
      if (tileErrorLogged) return;
      tileErrorLogged = true;
      const src = (event && event.tile && event.tile.src) || "unknown";
      if (bridge && typeof bridge.logClientError === "function") {
        bridge.logClientError("map.tileerror", "Basemap tile failed: " + src);
      }
    });
    tiles.addTo(mapInstance);
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
    const fullBtn = el("mapFullBtn");
    if (fullBtn) {
      fullBtn.setAttribute("aria-pressed", state.mapFull ? "true" : "false");
      fullBtn.textContent = state.mapFull ? "Exit map" : "Full map";
    }

    VD.setText("mapPointBadge", `${points.length} pts`);
    const routeSession = sessionForRoute(route);
    VD.setText("mapTitle", hasRoute ? `${VD.formatDistance(route.distanceMeters || 0)} route` : "No route recorded yet");
    VD.setText("mapKicker", hasRoute ? `GPS map - ${VD.formatWhen(routeSession.startedAtMs)}` : "GPS map");
    VD.setText("mapDistance", hasRoute ? VD.formatDistance(route.distanceMeters || 0) : "--");
    const session = route.session || {};
    const duration = Number(session.endedAtMs || Date.now()) - Number(session.startedAtMs || 0);
    VD.setText("mapDuration", hasRoute && duration > 0 ? VD.formatDuration(duration) : "--");
    const avgAccuracy = VD.average(points.map((p) => Number(p.accuracyM)).filter(Number.isFinite));
    VD.setText("mapAccuracy", avgAccuracy ? `~${Math.round(avgAccuracy)}m GPS` : "--");
    const empty = el("mapEmpty");
    if (empty) empty.hidden = hasRoute;

    drawMapRoute(points, hasRoute, layer, routeSession);
    renderMapSessionList(routes);
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
  const SAMPLE_ROUTE_START_MS = 1779281066443;
  const SAMPLE_ROUTE = [[0,32.80131,-116.9513],[29,32.80324,-116.95098],[58,32.80344,-116.95173],[86,32.80321,-116.95898],[112,32.80314,-116.96924],[139,32.79793,-116.97686],[167,32.78853,-116.97791],[194,32.78066,-116.9825],[220,32.77918,-116.99186],[247,32.77833,-117.00258],[273,32.77462,-117.01211],[299,32.77102,-117.02164],[326,32.77389,-117.03147],[352,32.77268,-117.04105],[378,32.77609,-117.0498],[405,32.77837,-117.05984],[431,32.77939,-117.06921],[457,32.7801,-117.07931],[483,32.78089,-117.0893],[511,32.77943,-117.09954],[537,32.77872,-117.10943],[563,32.77788,-117.1194],[590,32.77325,-117.12789],[616,32.77062,-117.13732],[642,32.76709,-117.14734],[669,32.7644,-117.15821],[695,32.76095,-117.16827],[721,32.75943,-117.17893],[748,32.76013,-117.18982],[774,32.76021,-117.19997],[800,32.75575,-117.20492],[826,32.75121,-117.20511],[852,32.74743,-117.2089],[878,32.74532,-117.21175],[905,32.74297,-117.21367],[934,32.73984,-117.21632],[961,32.73651,-117.21916],[989,32.73247,-117.22261],[1017,32.72903,-117.22553],[1045,32.7292,-117.22551],[1074,32.72893,-117.22561],[1103,32.72679,-117.22744],[1130,32.72613,-117.228],[1158,32.72348,-117.23026],[1188,32.72314,-117.23053],[1217,32.72154,-117.23188],[1245,32.7204,-117.23277],[1273,32.71904,-117.23456],[1301,32.71875,-117.23497],[1329,32.71858,-117.2352],[1358,32.71839,-117.23547],[1386,32.71828,-117.23569],[1415,32.71818,-117.2357],[1444,32.71791,-117.23587],[1473,32.71751,-117.23595],[1501,32.71704,-117.23612],[1530,32.71623,-117.23621],[1557,32.71601,-117.23646],[1585,32.71559,-117.23663],[1613,32.71527,-117.23675],[1640,32.71482,-117.23691],[1667,32.71444,-117.23707],[1693,32.71383,-117.23737],[1721,32.71325,-117.23766],[1747,32.71302,-117.23772],[1773,32.71254,-117.23785],[1801,32.71167,-117.2381],[1829,32.71131,-117.23819],[1857,32.71064,-117.23836],[1885,32.71034,-117.23846],[1913,32.70995,-117.23854],[1941,32.70949,-117.23866],[1971,32.70894,-117.2388],[1999,32.70801,-117.23906],[2027,32.70724,-117.23926],[2056,32.70754,-117.23883],[2085,32.70661,-117.23928],[2112,32.70621,-117.23918],[2140,32.70603,-117.2382],[2168,32.70531,-117.23915],[2194,32.705,-117.23913],[2221,32.70452,-117.23904],[2249,32.70137,-117.23966],[2276,32.69781,-117.24032],[2303,32.69418,-117.24076],[2331,32.69063,-117.23976],[2360,32.68805,-117.23964],[2389,32.68648,-117.23939],[2417,32.6851,-117.23849],[2445,32.68422,-117.23824],[2474,32.68409,-117.23972],[2503,32.68402,-117.23975],[2529,32.68412,-117.2397]];

  function loadSampleData() {
    VD.setDemoActive(false);
    const points = SAMPLE_ROUTE.map(([t, lat, lng]) => ({
      atMs: SAMPLE_ROUTE_START_MS + t * 1000, lat, lng
    }));
    const startMs = points[0].atMs;
    const endMs = points[points.length - 1].atMs;
    const distanceMeters = routeDistanceMeters(points);
    const session = {
      id: 2, mode: "obd", adapterName: "OBDLink MX+",
      startedAtMs: startMs, endedAtMs: endMs, status: "complete", sampleCount: 1933
    };
    const route = { session, points, pointCount: points.length, distanceMeters, bounds: {} };
    const day = 86400000;
    state.trips = [
      { id: 2, startedAtMs: startMs, endedAtMs: endMs, durationMs: endMs - startMs,
        distanceMeters, maxSpeedKph: 105, avgMovingSpeedKph: 47, sampleCount: 1933,
        pointCount: points.length, hasRoute: true, adapterName: "OBDLink MX+", status: "complete" },
      { id: 12, startedAtMs: startMs - day, endedAtMs: startMs - day + 1320000, durationMs: 1320000,
        distanceMeters: 11200, maxSpeedKph: 78, avgMovingSpeedKph: 34, sampleCount: 742,
        pointCount: 0, hasRoute: false, adapterName: "OBDLink MX+", status: "complete" },
      { id: 9, startedAtMs: startMs - 3 * day, endedAtMs: startMs - 3 * day + 660000, durationMs: 660000,
        distanceMeters: 6100, maxSpeedKph: 64, avgMovingSpeedKph: 29, sampleCount: 388,
        pointCount: 0, hasRoute: false, adapterName: "OBDLink MX+", status: "complete" }
    ];
    state.storage = {
      database: "volttracker_obd_poc.db", databaseBytes: 21086208, sessionCount: 3,
      sampleCount: 3063, rawTelemetryCount: 3063, locationSampleCount: points.length,
      recentRoutes: [route], latestRoute: route, recentSessions: []
    };
    state.insights = {
      tripCount: 3,
      totalDistanceMeters: distanceMeters + 11200 + 6100,
      totalDriveMs: (endMs - startMs) + 1320000 + 660000,
      longestTripMeters: distanceMeters,
      maxSpeedKph: 105,
      gpsTripCount: 1
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
