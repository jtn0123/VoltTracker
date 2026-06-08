import {
  LIVE_ROUTE_ID,
  haversineMetersJs,
  isValidRoutePoint,
  liveSampleTimeMs,
  mapEffColor,
  routeFitKey
} from "./map-route-utils";
import type { MapRoute, MapRoutePoint, MapRouteSession } from "./map-route-utils";

  const VD = window.VoltDashboard;
  const state = VD.state;
  const bridge = VD.bridge;
  const el = VD.el;

  type MapStop = {
    lat: number;
    lng: number;
    durationMs: number;
  };

  type MutablePolylineLayer = LeafletLayer & {
    setLatLngs?: (latlngs: LatLngTuple[]) => void;
  };

  type MutableMarkerLayer = LeafletLayer & {
    setLatLng?: (latlng: LatLngTuple) => void;
  };

  type LiveRouteLayerCache = {
    outer: MutablePolylineLayer;
    inner: MutablePolylineLayer;
    start: MutableMarkerLayer;
    end: MutableMarkerLayer;
    group: LeafletLayer;
  };

  type DemoRouteOpts = {
    sessionId: number;
    startedAtMs: number;
    from: number;
    to: number;
    startSoc: number;
    socDrop: number;
    accW?: number;
    elevShift?: number;
    adapterName?: string;
  };

  // The synthetic demo route built by buildSampleRoute. Its session is fully
  // populated (unlike the open MapRouteSession), so name the concrete fields
  // loadSampleData reads back as required numbers/strings.
  type DemoRouteSession = MapRouteSession & {
    id: number;
    mode: string;
    adapterName: string;
    startedAtMs: number;
    endedAtMs: number;
    status: string;
    sampleCount: number;
  };

  type DemoRoute = MapRoute & {
    points: MapRoutePoint[];
    session: DemoRouteSession;
    distanceMeters: number;
  };

  let mapInstance: LeafletMapInstance | null = null;
  let remoteTileLayer: LeafletLayer | null = null;
  let liveRouteLayerCache: LiveRouteLayerCache | null = null;
  const mapLayerGroups: Record<string, LeafletLayer | null> = { routes: null, heat: null, stops: null, eff: null };
  let mapFitKey: string | null = null;

  // Add the layer group for the active layer (falling back to "routes") onto
  // the map. The groups are always populated before this runs, but the typed
  // record models them as nullable, so guard rather than assert.
  function addActiveLayerGroup(layer: string, map: LeafletMapInstance) {
    const group = mapLayerGroups[layer] || mapLayerGroups.routes;
    if (group) group.addTo(map);
  }
  const LIVE_ROUTE_MAX_POINTS = 600;
  let liveRouteStartedAtMs: number | null = null;
  let liveRoutePoints: MapRoutePoint[] = [];

  /** Store a demo/real GPS sample as the selectable Current route on the map. */
  function updateLivePosition(lat: unknown, lng: unknown) {
    const la = Number(lat);
    const ln = Number(lng);
    if (!Number.isFinite(la) || !Number.isFinite(ln)) return;
    const point = liveRoutePoint(la, ln);
    const previousPoint = liveRoutePoints[liveRoutePoints.length - 1];
    if (previousPoint) {
      const meters = haversineMetersJs(previousPoint.lat, previousPoint.lng, point.lat, point.lng);
      const ageMs = Math.abs(Number(point.atMs) - Number(previousPoint.atMs));
      if (meters < 1 && ageMs < 2000) return;
    } else {
      liveRouteStartedAtMs = point.atMs;
      state.selectedMapSessionId = LIVE_ROUTE_ID;
      mapFitKey = null;
    }
    liveRoutePoints.push(point);
    const overflow = liveRoutePoints.length - LIVE_ROUTE_MAX_POINTS;
    if (overflow > 0) liveRoutePoints.splice(0, overflow);
    if (state.selectedMapSessionId === LIVE_ROUTE_ID && state.view === "map") {
      renderMap();
    }
  }

  /** Remove the Current route (e.g. when demo stops or a new real session starts). */
  function clearLivePosition() {
    const wasSelected = String(state.selectedMapSessionId || "") === LIVE_ROUTE_ID;
    liveRouteStartedAtMs = null;
    liveRoutePoints = [];
    if (wasSelected) state.selectedMapSessionId = null;
    mapFitKey = null;
    if (state.view === "map") renderMap();
  }

  function liveRoutePoint(lat: number, lng: number): MapRoutePoint {
    const sample = state.telemetry || {};
    const updatedAt = liveSampleTimeMs(sample);
    const point: MapRoutePoint = { lat, lng, atMs: updatedAt };
    const speedKph = Number(sample.speedKph);
    if (Number.isFinite(speedKph)) {
      point.speedKph = speedKph;
      point.speedMps = speedKph / 3.6;
    }
    const soc = Number(sample.soc);
    if (Number.isFinite(soc)) point.soc = soc;
    const powerKw = Number(sample.powerKw);
    if (Number.isFinite(powerKw)) point.powerKw = powerKw;
    const altM = Number(sample.altM ?? sample.altitudeM ?? sample.altitudeMeters);
    if (Number.isFinite(altM)) point.altM = altM;
    return point;
  }

  function createRemoteTileLayer(map: LeafletMapInstance): LeafletLayer {
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
    tiles.on("tileerror", (event: LeafletTileErrorEvent) => {
      tileErrorCount += 1;
      const src = (event && event.tile && event.tile.src) || "unknown";
      setMapTileError(true, "Map tiles are not loading. Routes still work; retry when the network is back.");
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
          fallback.on("tileerror", () => {
            setMapTileError(true, "Backup map tiles are also unavailable. Routes still work without basemap tiles.");
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

  function setMapTileError(show: boolean, detail?: string) {
    const banner = el("mapTileError");
    if (!banner) return;
    banner.hidden = !show;
    if (detail) VD.setText("mapTileErrorCopy", detail);
  }

  function retryMapTiles() {
    const map = mapInstance;
    setMapTileError(false);
    if (!map) return;
    if (remoteTileLayer) {
      try {
        map.removeLayer(remoteTileLayer);
      } catch (ignored) {}
      remoteTileLayer = null;
    }
    syncRemoteTiles();
  }

  function syncRemoteTiles() {
    const map = mapInstance;
    if (!map || typeof L === "undefined") return;
    state.mapRemoteTilesEnabled = true;
    if (!remoteTileLayer) {
      remoteTileLayer = createRemoteTileLayer(map);
      remoteTileLayer.addTo(map);
    }
  }

  // Creates the Leaflet map once. Remote basemap tiles are always on so route
  // context stays consistent across Map and Trips.
  function ensureMap() {
    if (mapInstance) return mapInstance;
    if (typeof L === "undefined") return null;
    const container = el("mapLeaflet");
    if (!container) return null;
    const map: LeafletMapInstance = L.map(container, { zoomControl: false, attributionControl: true })
      .setView([39.5, -98.35], 4);
    mapInstance = map;
    syncRemoteTiles();
    if (typeof VD.scrubberAttachMap === "function") VD.scrubberAttachMap(map);
    // Tap anywhere on the map → snap the scrubber to the closest route point.
    map.on("click", (e: { latlng?: LeafletLatLng }) => {
      if (e && e.latlng && typeof VD.scrubAtLatLng === "function") {
        VD.scrubAtLatLng(e.latlng.lat, e.latlng.lng);
      }
    });
    return map;
  }

  function renderMap() {
    const storage = state.storage || {};
    const routes = mapRoutes(storage);
    if (routes.length) {
      const selectedExists = routes.some((route: MapRoute) =>
        String((route.session || {}).id || "") === String(state.selectedMapSessionId || "")
      );
      if (!selectedExists) {
        const firstId = (routes[0].session || {}).id;
        state.selectedMapSessionId = firstId == null ? null : String(firstId);
      }
    }
    const route = selectedMapRoute(storage, routes);
    const points = Array.isArray(route.points) ? route.points : [];
    const hasRoute = points.length >= 2;
    const isLiveRoute = routeIsLive(route);
    const hasMapContent = hasRoute || (isLiveRoute && points.some(isValidRoutePoint));
    const layer = isLiveRoute ? "routes" : state.mapLayer;
    const stops = hasRoute ? detectStops(points) : [];

    const frame = el("mapFrame");
    if (frame) frame.dataset.layer = layer;
    VD.setText("mapStopsCount", stops.length ? String(Math.min(20, stops.length)) : "0");
    document.querySelectorAll("[data-map-layer]").forEach((node) => {
      const button = node as HTMLElement;
      const active = button.dataset.mapLayer === layer;
      button.classList.toggle("is-active", active);
      button.setAttribute("aria-selected", active ? "true" : "false");
      button.setAttribute("aria-pressed", active ? "true" : "false");
    });
    const mapCard = el("mapCard");
    if (mapCard) mapCard.classList.toggle("is-fullscreen", state.mapFull);
    document.body.classList.toggle("map-full-active", state.mapFull);
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
      hasMapContent
        ? routeSession.adapterName || (isLiveRoute ? "Current drive" : routeSession.mode) || "Logged drive"
        : "No route recorded yet"
    );
    VD.setText(
      "mapKicker",
      // Use the same absolute, skim-able format as the drive-picker chips (fmtChipDate) so a
      // single drive doesn't read "Today 2:51 AM" in its chip but "6h ago" in the kicker at once.
      hasMapContent
        ? `${isLiveRoute ? "Current GPS" : "GPS map"} · ${fmtChipDate(routeSession.startedAtMs)}`
        : "GPS map"
    );
    VD.setText("mapDistance", hasMapContent ? VD.formatDistance(route.distanceMeters || 0) : "--");
    const session = route.session || {};
    const duration = Number(session.endedAtMs || Date.now()) - Number(session.startedAtMs || 0);
    VD.setText("mapDuration", hasMapContent && duration > 0 ? VD.formatDuration(duration) : "--");
    // Avg moving speed in mph from GPS: distance / duration, ignoring stopped
    // time at the granularity of the route. More useful than GPS accuracy.
    const avgMph = hasMapContent && duration > 0
      ? (Number(route.distanceMeters || 0) / (duration / 1000)) * 2.2369363
      : 0;
    VD.setText("mapAvgMph", avgMph > 0 ? Math.round(avgMph) : "--");
    const empty = el("mapEmpty");
    if (empty) empty.hidden = hasMapContent;

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
    const sessionsCard = document.querySelector("#view-map .map-layout") as HTMLElement | null;
    if (sessionsCard) sessionsCard.hidden = routes.length > 0;
  }

  // Chip date formatter: "Today 2:51 AM" / "Yesterday 6:54 PM" / "Sat 9:15 AM" /
  // "Oct 15". More skim-able than "6h ago / 30h ago / 4d ago". Accepts the
  // loosely-typed startedAtMs off a route session and coerces defensively.
  function fmtChipDate(value: unknown) {
    const ms = Number(value);
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

  function mapRoutes(storage: VoltStorageSummary): MapRoute[] {
    const history = Array.isArray(storage.recentRoutes) ? storage.recentRoutes : [];
    const live = buildLiveRoute();
    if (!live) return history;
    return [
      live,
      ...history.filter((route: MapRoute) =>
        String((route.session || {}).id || "") !== LIVE_ROUTE_ID
      )
    ];
  }

  function buildLiveRoute(): MapRoute | null {
    if (!liveRoutePoints.length) return null;
    const points = liveRoutePoints.slice();
    const firstPoint = points[0];
    const lastPoint = points[points.length - 1];
    if (!firstPoint || !lastPoint) return null;
    const selectedDevice = typeof VD.getSelectedDevice === "function" ? VD.getSelectedDevice() : null;
    const sample = state.telemetry || {};
    const source = String(sample.source || "").toLowerCase();
    const isDemo = state.demoActive || source.includes("demo");
    const adapterName = isDemo
      ? "Current demo"
      : String(
        sample.adapterName ||
        (selectedDevice && selectedDevice.name) ||
        "Current drive"
      );
    const startedAtMs = liveRouteStartedAtMs || firstPoint.atMs;
    const powerTrack = points
      .filter((point) => Number.isFinite(Number(point.powerKw)))
      .map((point) => ({ atMs: point.atMs, powerKw: Number(point.powerKw) }));
    const socTrack = points
      .filter((point) => Number.isFinite(Number(point.soc)))
      .map((point) => ({ atMs: point.atMs, soc: Number(point.soc) }));
    return {
      isLive: true,
      points,
      pointCount: points.length,
      distanceMeters: Math.max(routeDistanceMeters(points), Number(state.sessionDistanceM) || 0),
      powerTrack,
      socTrack,
      session: {
        id: LIVE_ROUTE_ID,
        mode: isDemo ? "demo" : "drive",
        adapterName,
        startedAtMs,
        endedAtMs: Math.max(Date.now(), Number(lastPoint.atMs) || 0),
        status: "live",
        sampleCount: Number(sample.sampleCount) || points.length
      }
    };
  }

  function routeIsLive(route: MapRoute) {
    return Boolean(route && (
      route.isLive ||
      String((route.session || {}).id || "") === LIVE_ROUTE_ID ||
      String((route.session || {}).status || "").toLowerCase() === "live"
    ));
  }

  // Horizontal drive-picker chips above the map. Each chip shows the drive's
  // start time, distance, and a color-coded average efficiency dot — same
  // pattern as the demo's drive picker. Click delegation flows through the
  // existing [data-map-session] handler in actions.js.
  function renderMapDriveChips(routes: MapRoute[]) {
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
      const live = routeIsLive(route);
      const button = document.createElement("button");
      button.type = "button";
      button.className = "map-drive-chip" + (live ? " is-live" : "") + (id === selId ? " is-active" : "");
      button.dataset.mapSession = id;
      const date = document.createElement("span");
      date.className = "dl";
      date.textContent = fmtChipDate(s.startedAtMs);
      const meta = document.createElement("span");
      meta.className = "dm";
      const distance = document.createElement("b");
      const distMi = (Number(route.distanceMeters || 0) / 1609.34).toFixed(1);
      distance.textContent = live && Number(distMi) < 0.1 ? "current" : distMi + " mi";
      meta.append(distance);
      if (live) {
        meta.append(document.createTextNode(" · "));
        const dot = document.createElement("u");
        dot.style.background = "#4cc4ff";
        meta.append(dot, document.createTextNode(" live"));
      }
      const effPts = (route.points || []).filter((p) => Number.isFinite(Number(p.eff)));
      const avgEff = effPts.length
        ? effPts.reduce((acc, p) => acc + Number(p.eff), 0) / effPts.length
        : 0;
      if (!live && avgEff > 0) {
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
  function drawMapRoute(points: VoltRoutePoint[], hasRoute: boolean, layer: string, routeSession: MapRouteSession) {
    const container = el("mapLeaflet");
    if (!container || !container.offsetWidth || !container.offsetHeight) return;
    const map = ensureMap();
    if (!map) return;
    map.invalidateSize(false);
    const drawable = points.filter(isValidRoutePoint);
    const isLiveRoute = String((routeSession || {}).id || "") === LIVE_ROUTE_ID;
    if (isLiveRoute && layer === "routes" && updateLiveRouteLayer(drawable, map, routeSession)) {
      return;
    }
    Object.keys(mapLayerGroups).forEach((key) => {
      if (mapLayerGroups[key]) {
        map.removeLayer(mapLayerGroups[key]);
        mapLayerGroups[key] = null;
      }
    });
    liveRouteLayerCache = null;
    if (!drawable.length || (!hasRoute && !isLiveRoute)) return;
    const latlngs = drawable.map((p) => [Number(p.lat), Number(p.lng)] as LatLngTuple);
    const firstLatLng = latlngs[0];
    const lastLatLng = latlngs[latlngs.length - 1];
    if (!firstLatLng || !lastLatLng) return;
    if (drawable.length === 1) {
      const onlyMarker = () => L.circleMarker(firstLatLng, {
        radius: 8,
        color: "#fff",
        weight: 2,
        fillColor: isLiveRoute ? "#4cc4ff" : "#ff7a45",
        fillOpacity: 1
      });
      mapLayerGroups.routes = L.layerGroup([onlyMarker()]);
      mapLayerGroups.heat = L.layerGroup([onlyMarker()]);
      mapLayerGroups.stops = L.layerGroup([onlyMarker()]);
      mapLayerGroups.eff = L.layerGroup([onlyMarker()]);
      addActiveLayerGroup(layer, map);
      const fitKey = routeFitKey(routeSession, drawable);
      if (fitKey !== mapFitKey) {
        map.setView(firstLatLng, 15);
        mapFitKey = fitKey;
      }
      return;
    }
    const routeColor = isLiveRoute ? "#4cc4ff" : "#ff7a45";
    const routeEndColor = isLiveRoute ? "#4cc4ff" : "#ff7141";

    const outerRoute = L.polyline(latlngs, { color: routeColor, weight: 9, opacity: 0.16 }) as MutablePolylineLayer;
    const innerRoute = L.polyline(latlngs, { color: routeColor, weight: 3.5, opacity: 1 }) as MutablePolylineLayer;
    const startMarker = L.circleMarker(firstLatLng, { radius: 6, color: "#fff", weight: 2, fillColor: routeColor, fillOpacity: 1 }) as MutableMarkerLayer;
    const endMarker = L.circleMarker(lastLatLng, { radius: isLiveRoute ? 8 : 7, color: "#fff", weight: 2, fillColor: routeEndColor, fillOpacity: 1 }) as MutableMarkerLayer;
    const routeGroup = L.layerGroup([outerRoute, innerRoute, startMarker, endMarker]);
    mapLayerGroups.routes = routeGroup;
    if (isLiveRoute && layer === "routes") {
      liveRouteLayerCache = {
        outer: outerRoute,
        inner: innerRoute,
        start: startMarker,
        end: endMarker,
        group: routeGroup
      };
    }

    const bands: Record<string, LatLngSegment[]> = { "#ff6b4a": [], "#ffd23f": [], "#7ee06a": [] };
    for (let i = 1; i < drawable.length; i += 1) {
      const previousPoint = drawable[i - 1];
      const point = drawable[i];
      const previousLatLng = latlngs[i - 1];
      const latLng = latlngs[i];
      if (!previousPoint || !point || !previousLatLng || !latLng) continue;
      const speed = segmentSpeedMps(previousPoint, point);
      const color = speed < 8 ? "#ff6b4a" : (speed < 18 ? "#ffd23f" : "#7ee06a");
      const bucket = bands[color];
      if (bucket) bucket.push([previousLatLng, latLng]);
    }
    mapLayerGroups.heat = L.layerGroup();
    Object.entries(bands).forEach(([color, segments]) => {
      if (segments.length) {
        L.polyline(segments, { color, weight: 5, opacity: 0.95 }).addTo(mapLayerGroups.heat);
      }
    });

    mapLayerGroups.stops = L.layerGroup([
      L.polyline(latlngs, { color: routeColor, weight: 2.5, opacity: 0.4 })
    ]);
    const stops = detectStops(drawable).slice(0, 20);
    stops.forEach((stop) => {
      const radius = Math.min(13, 7 + stop.durationMs / 120000);
      L.circleMarker([stop.lat, stop.lng], {
        radius, color: "#ffd7b0", weight: 3, fillColor: "#ff8a3d", fillOpacity: 0.38
      }).bindTooltip(`Stop · ${VD.formatDuration(stop.durationMs)}`).addTo(mapLayerGroups.stops);
    });

    // V3 efficiency layer — per-segment polylines bucketed by mi/kWh.
    // Segments with no power data (eff null) render grey so the user
    // can tell which portions of the drive lack derived efficiency.
    const effBands: Record<string, LatLngSegment[]> = {};
    for (let i = 1; i < drawable.length; i += 1) {
      const point = drawable[i];
      const previousLatLng = latlngs[i - 1];
      const latLng = latlngs[i];
      if (!point || !previousLatLng || !latLng) continue;
      const color = mapEffColor(Number(point.eff));
      (effBands[color] = effBands[color] || []).push([previousLatLng, latLng]);
    }
    mapLayerGroups.eff = L.layerGroup();
    // Soft white halo underneath the colored segments so the route reads
    // clearly against busy basemap areas (urban grid, dense streets).
    L.polyline(latlngs, {
      color: "#ffffff", weight: 11, opacity: 0.09, interactive: false
    }).addTo(mapLayerGroups.eff);
    Object.entries(effBands).forEach(([color, segments]) => {
      L.polyline(segments, { color, weight: 5, opacity: 0.95 }).addTo(mapLayerGroups.eff);
    });
    L.circleMarker(firstLatLng, { radius: 6, color: "#fff", weight: 2, fillColor: "#b8e63b", fillOpacity: 1 }).addTo(mapLayerGroups.eff);
    L.circleMarker(lastLatLng, { radius: 7, color: "#fff", weight: 2, fillColor: "#ff6b5f", fillOpacity: 1 }).addTo(mapLayerGroups.eff);

    addActiveLayerGroup(layer, map);

    const fitKey = routeFitKey(routeSession, drawable);
    if (fitKey !== mapFitKey) {
      map.fitBounds(L.latLngBounds(latlngs), { padding: [30, 30] });
      mapFitKey = fitKey;
    }
  }

  function updateLiveRouteLayer(
    drawable: MapRoutePoint[],
    map: LeafletMapInstance,
    routeSession: MapRouteSession
  ) {
    const cache = liveRouteLayerCache;
    if (!cache || drawable.length < 2) return false;
    if (
      typeof cache.outer.setLatLngs !== "function" ||
      typeof cache.inner.setLatLngs !== "function" ||
      typeof cache.start.setLatLng !== "function" ||
      typeof cache.end.setLatLng !== "function"
    ) {
      return false;
    }
    const latlngs = drawable.map((p) => [Number(p.lat), Number(p.lng)] as LatLngTuple);
    const first = latlngs[0];
    const last = latlngs[latlngs.length - 1];
    if (!first || !last) return false;
    cache.outer.setLatLngs(latlngs);
    cache.inner.setLatLngs(latlngs);
    cache.start.setLatLng(first);
    cache.end.setLatLng(last);
    addActiveLayerGroup("routes", map);
    const fitKey = routeFitKey(routeSession, drawable);
    if (fitKey !== mapFitKey) {
      map.fitBounds(L.latLngBounds(latlngs), { padding: [30, 30] });
      mapFitKey = fitKey;
    }
    return true;
  }

  function renderMapSessionList(routes: MapRoute[]) {
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

  function buildMapSessionRow(r: MapRoute) {
    const s = sessionForRoute(r);
    const active = String(s.id || "") === String(state.selectedMapSessionId || "");
    const live = routeIsLive(r);
    const button = document.createElement("button");
    button.type = "button";
    button.className = "history-row" + (active ? " is-active" : "");
    button.dataset.mapSession = String(s.id || "");
    const center = document.createElement("span");
    const strong = document.createElement("strong");
    strong.textContent = live
      ? `${s.adapterName || "Current drive"} · current`
      : `${s.mode || "session"} · ${s.adapterName || "OBD adapter"}`;
    const small = document.createElement("small");
    small.textContent = live
      ? `${fmtChipDate(s.startedAtMs)} · ${VD.formatDistance(Number(r.distanceMeters || 0))} · ${Number(r.pointCount || 0)} pts`
      : `${VD.formatWhen(s.startedAtMs)} · ${VD.formatDistance(Number(r.distanceMeters || 0))} · ${Number(r.pointCount || 0)} pts`;
    center.append(strong, small);
    const right = document.createElement("b");
    right.textContent = live ? "live" : s.status || "stored";
    button.append(center, right);
    return button;
  }

  function selectedMapRoute(storage: VoltStorageSummary, availableRoutes?: MapRoute[]): MapRoute {
    const routes = availableRoutes || mapRoutes(storage);
    if (routes.length) {
      const selected = routes.find((route: MapRoute) => String((route.session || {}).id || "") === String(state.selectedMapSessionId || ""));
      if (selected) return selected;
      const firstId = (routes[0].session || {}).id;
      state.selectedMapSessionId = firstId == null ? null : String(firstId);
      return routes[0];
    }
    return storage.latestRoute || {};
  }

  function sessionForRoute(route: MapRoute): MapRouteSession {
    // VoltRoute.session is the open `{ id?; [k]: unknown }` payload; narrow it
    // to the named fields map.ts reads. Field reads stay defensively coerced
    // (String()/Number()) at each use, so the narrowing is presentational only.
    return (route && route.session ? route.session : {}) as MapRouteSession;
  }

  function routeDistanceMeters(points: VoltRoutePoint[]) {
    let total = 0;
    for (let i = 1; i < points.length; i += 1) {
      const previousPoint = points[i - 1];
      const point = points[i];
      if (!previousPoint || !point) continue;
      total += haversineMetersJs(previousPoint.lat, previousPoint.lng, point.lat, point.lng);
    }
    return total;
  }

  function segmentSpeedMps(a: VoltRoutePoint, b: VoltRoutePoint) {
    const seconds = Math.max(1, (Number(b.atMs) - Number(a.atMs)) / 1000);
    return haversineMetersJs(a.lat, a.lng, b.lat, b.lng) / seconds;
  }

  // A stop is a sustained run (>= 45 s) of near-zero movement between GPS points.
  function detectStops(points: VoltRoutePoint[]): MapStop[] {
    const stops: MapStop[] = [];
    let runStart: number | null = null;
    for (let i = 1; i < points.length; i += 1) {
      const previousPoint = points[i - 1];
      const point = points[i];
      if (!previousPoint || !point) continue;
      const slow = segmentSpeedMps(previousPoint, point) < 1.5;
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

  function addStop(stops: MapStop[], points: VoltRoutePoint[], startIdx: number, endIdx: number) {
    const startPoint = points[startIdx];
    const endPoint = points[endIdx];
    if (!startPoint || !endPoint) return;
    const durationMs = Number(endPoint.atMs) - Number(startPoint.atMs);
    if (durationMs < 45000) return;
    const mid = points[Math.floor((startIdx + endIdx) / 2)];
    if (!mid) return;
    stops.push({ lat: mid.lat, lng: mid.lng, durationMs });
  }

  // A real ~23 mi logged drive (session 2 of the test database), kept as [secondsFromStart,
  // lat, lng] triples. Used only as preview content when no Android bridge is present, so
  // the dashboard can be reviewed loaded; it never runs inside the real app.
  const _SAMPLE_ROUTE_START_MS = 1779281066443;
  const SAMPLE_ROUTE: Array<[number, number, number]> = [[0,32.80131,-116.9513],[29,32.80324,-116.95098],[58,32.80344,-116.95173],[86,32.80321,-116.95898],[112,32.80314,-116.96924],[139,32.79793,-116.97686],[167,32.78853,-116.97791],[194,32.78066,-116.9825],[220,32.77918,-116.99186],[247,32.77833,-117.00258],[273,32.77462,-117.01211],[299,32.77102,-117.02164],[326,32.77389,-117.03147],[352,32.77268,-117.04105],[378,32.77609,-117.0498],[405,32.77837,-117.05984],[431,32.77939,-117.06921],[457,32.7801,-117.07931],[483,32.78089,-117.0893],[511,32.77943,-117.09954],[537,32.77872,-117.10943],[563,32.77788,-117.1194],[590,32.77325,-117.12789],[616,32.77062,-117.13732],[642,32.76709,-117.14734],[669,32.7644,-117.15821],[695,32.76095,-117.16827],[721,32.75943,-117.17893],[748,32.76013,-117.18982],[774,32.76021,-117.19997],[800,32.75575,-117.20492],[826,32.75121,-117.20511],[852,32.74743,-117.2089],[878,32.74532,-117.21175],[905,32.74297,-117.21367],[934,32.73984,-117.21632],[961,32.73651,-117.21916],[989,32.73247,-117.22261],[1017,32.72903,-117.22553],[1045,32.7292,-117.22551],[1074,32.72893,-117.22561],[1103,32.72679,-117.22744],[1130,32.72613,-117.228],[1158,32.72348,-117.23026],[1188,32.72314,-117.23053],[1217,32.72154,-117.23188],[1245,32.7204,-117.23277],[1273,32.71904,-117.23456],[1301,32.71875,-117.23497],[1329,32.71858,-117.2352],[1358,32.71839,-117.23547],[1386,32.71828,-117.23569],[1415,32.71818,-117.2357],[1444,32.71791,-117.23587],[1473,32.71751,-117.23595],[1501,32.71704,-117.23612],[1530,32.71623,-117.23621],[1557,32.71601,-117.23646],[1585,32.71559,-117.23663],[1613,32.71527,-117.23675],[1640,32.71482,-117.23691],[1667,32.71444,-117.23707],[1693,32.71383,-117.23737],[1721,32.71325,-117.23766],[1747,32.71302,-117.23772],[1773,32.71254,-117.23785],[1801,32.71167,-117.2381],[1829,32.71131,-117.23819],[1857,32.71064,-117.23836],[1885,32.71034,-117.23846],[1913,32.70995,-117.23854],[1941,32.70949,-117.23866],[1971,32.70894,-117.2388],[1999,32.70801,-117.23906],[2027,32.70724,-117.23926],[2056,32.70754,-117.23883],[2085,32.70661,-117.23928],[2112,32.70621,-117.23918],[2140,32.70603,-117.2382],[2168,32.70531,-117.23915],[2194,32.705,-117.23913],[2221,32.70452,-117.23904],[2249,32.70137,-117.23966],[2276,32.69781,-117.24032],[2303,32.69418,-117.24076],[2331,32.69063,-117.23976],[2360,32.68805,-117.23964],[2389,32.68648,-117.23939],[2417,32.6851,-117.23849],[2445,32.68422,-117.23824],[2474,32.68409,-117.23972],[2503,32.68402,-117.23975],[2529,32.68412,-117.2397]];

  // Build one synthetic route from a slice of SAMPLE_ROUTE. Each route gets its
  // own session id, start time, altitude profile, socTrack, and powerTrack so
  // the chip strip, Eff layer, scrubber, and Insights scatter all populate
  // with varied content rather than a single drive.
  function buildSampleRoute(opts: DemoRouteOpts): DemoRoute {
    const slice = SAMPLE_ROUTE.slice(opts.from, opts.to);
    const firstSlicePoint = slice[0];
    if (!firstSlicePoint) {
      throw new Error("Sample route slice is empty");
    }
    const baseT = firstSlicePoint[0];
    const points: MapRoutePoint[] = slice.map(([t, lat, lng]) => ({
      atMs: opts.startedAtMs + (t - baseT) * 1000, lat, lng
    }));
    const firstPoint = points[0];
    const lastPoint = points[points.length - 1];
    if (!firstPoint || !lastPoint) {
      throw new Error("Sample route has no drawable points");
    }
    const startMs = firstPoint.atMs;
    const endMs = lastPoint.atMs;
    const distanceMeters = routeDistanceMeters(points);

    // Elevation: descend from El Cajon (~150 m) to the coast with a mid-route
    // hill, then shift by opts.elevShift so each drive feels different.
    let cumM = 0;
    for (let i = 0; i < points.length; i += 1) {
      const point = points[i];
      if (!point) continue;
      if (i > 0) {
        const previousPoint = points[i - 1];
        if (!previousPoint) continue;
        cumM += haversineMetersJs(
          previousPoint.lat, previousPoint.lng, point.lat, point.lng
        );
      }
      const f = cumM / (distanceMeters || 1);
      point.altM =
        150 - 142 * f +
        70 * Math.exp(-Math.pow((f - 0.46) / 0.085, 2)) +
        7 * Math.sin(f * 19) +
        (opts.elevShift || 0);
    }

    const socTrack: Array<{ atMs: number; soc: number }> = [];
    const socEnd = Math.max(15, opts.startSoc - opts.socDrop);
    for (let i = 0; i <= 24; i += 1) {
      const f = i / 24;
      socTrack.push({
        atMs: startMs + f * (endMs - startMs),
        soc: opts.startSoc - opts.socDrop * f
      });
    }

    const powerTrack: Array<{ atMs: number; powerKw: number }> = [];
    const mass = 1720, g = 9.81, Crr = 0.011, kAero = 0.5 * 1.2 * 0.28 * 2.2;
    let prevV = 0;
    for (let i = 0; i < points.length; i += 1) {
      const a = points[Math.max(0, i - 1)];
      const b = points[Math.min(points.length - 1, i + 1)];
      const point = points[i];
      const previousPoint = points[Math.max(0, i - 1)];
      if (!a || !b || !point || !previousPoint) continue;
      const dt = Math.max(1, (b.atMs - a.atMs) / 1000);
      const v = haversineMetersJs(a.lat, a.lng, b.lat, b.lng) / dt;
      const horiz = Math.max(8, haversineMetersJs(a.lat, a.lng, point.lat, point.lng) || 1);
      const dz = Number(point.altM) - Number(previousPoint.altM || point.altM);
      const grade = Math.max(-0.13, Math.min(0.13, dz / horiz));
      const accel = i > 0 ? (v - prevV) / dt : 0;
      prevV = v;
      const force = Crr * mass * g + kAero * v * v + mass * g * grade + mass * accel;
      let watts = force * v;
      watts = watts > 0 ? watts / 0.86 : watts * 0.55;
      watts += opts.accW || 320;
      const kW = Math.max(-28, Math.min(72, watts / 1000));
      powerTrack.push({ atMs: point.atMs, powerKw: kW });
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

    // Demo charge history, a battery snapshot, and enhanced-signal evidence so the
    // browser preview / demo exercises every feature surface — Charge session list,
    // Insights HV-pack detail, and the Signals workspace — not just map + trips.
    // This only mutates the dashboard's in-memory preview state, so it never
    // touches persisted real-device data.
    const sampleCharges = [
      // Newest is in-progress (no end time) so the active-charge state renders.
      { id: 4, startedAtMs: now - 38 * 60 * 1000, endedAtMs: null, chargerType: "level2", startSoc: 54, endSoc: 71, powerKw: 7.1, energyKwh: 3.0 },
      { id: 3, startedAtMs: now - 24 * hour, endedAtMs: now - 24 * hour + Math.round(3.4 * hour), chargerType: "level2", startSoc: 24, endSoc: 91, powerKw: 7.2, energyKwh: 11.8 },
      { id: 2, startedAtMs: now - 48 * hour, endedAtMs: now - 48 * hour + Math.round(3.0 * hour), chargerType: "level2", startSoc: 36, endSoc: 90, powerKw: 7.0, energyKwh: 9.6 },
      { id: 1, startedAtMs: now - 96 * hour, endedAtMs: now - 96 * hour + Math.round(4.6 * hour), chargerType: "level1", startSoc: 58, endSoc: 88, powerKw: 1.3, energyKwh: 5.2 }
    ];
    // SOC kept close to the live browser-demo stream (~77%) so Drive's live tile
    // and the Insights HV-pack ring tell the same story in the demo.
    const sampleBattery = { id: 1, capturedAtMs: now - 6 * hour, soc: 77, capacityAh: 42.1, sohPct: 91, packVoltage: 364, packCurrentA: -5.8, packPowerKw: -2.1, batteryTempC: 23 };
    const sampleVehicle = { year: 2017, make: "Chevrolet", model: "Volt", vin: "1G1RC6S52HU123456", odometerMiles: 48213 };
    const sampleDtcs = [
      { dtc: "P0420", status: "stored", statusLabel: "stored", moduleName: "Powertrain", header: "7E8", firstSeenMs: now - 72 * hour, lastSeenMs: now - 24 * hour, seenCount: 4 },
      { dtc: "P0011", status: "pending", statusLabel: "pending", moduleName: "Powertrain", header: "7E8", firstSeenMs: now - 12 * hour, lastSeenMs: now - 2 * hour, seenCount: 1 }
    ];
    // A post-drive review (last-session card) + recent PID frames so those
    // surfaces render instead of staying on "Waiting for scan data".
    const sampleReview = {
      session: { id: today.session.id, mode: "drive", adapterName: today.session.adapterName },
      maxSpeedKph: 105,
      locationSampleCount: today.points.length,
      parsedPidCount: 1840,
      unknownPidCount: 71,
      avgSampleIntervalMs: 1000,
      usefulTelemetryCount: today.session.sampleCount,
      emptyTelemetryCount: 12,
      recentPidFrames: [
        { command: "010D", name: "Vehicle speed", valueText: "34 mph", parsed: true },
        { command: "0105", name: "Coolant temp", valueText: "85 °C", parsed: true },
        { command: "221154", name: "Engine oil temperature", valueText: "96 °C", parsed: true },
        { command: "225B", name: "Hybrid battery SOC", valueText: "77 %", parsed: true },
        { command: "22415B", name: "Unparsed response", rawResponse: "7F 22 31", parsed: false }
      ]
    };
    const sampleSignalCatalog = [
      { key: "batt.soc", category: "battery", header: "ATSH7E4", command: "225B", pid: "5B", name: "hybrid battery state of charge", unit: "%", pollLane: "fast", scanStage: "low-risk", risk: "low", validationStatus: "confirmed", source: "Volt community PID sheet" },
      { key: "maint.oil", category: "maintenance", header: "ATSH7E0", command: "221154", pid: "1154", name: "engine oil temperature", unit: "C", pollLane: "thermal", scanStage: "low-risk", risk: "low", validationStatus: "confirmed", source: "Volt community PID sheet" },
      { key: "tpms.fl", category: "tpms", header: "ATSH760", command: "224050", pid: "4050", name: "tire pressure front-left", unit: "kPa", pollLane: "slow", scanStage: "tires", risk: "medium", validationStatus: "candidate", source: "TPMS probe" },
      { key: "tpms.fr", category: "tpms", header: "ATSH760", command: "224051", pid: "4051", name: "tire pressure front-right", unit: "kPa", pollLane: "slow", scanStage: "tires", risk: "medium", validationStatus: "candidate", source: "TPMS probe" },
      { key: "odo", category: "odometer", header: "CAN:120", command: "CAN:120", pid: "CAN:120", name: "odometer", unit: "km", pollLane: "passive", scanStage: "passive", risk: "safe", validationStatus: "candidate", source: "GM Volt wiki" },
      { key: "batt.coolant", category: "battery", header: "ATSH7E4", command: "22F00A", pid: "F00A", name: "battery coolant pump RPM", unit: "rpm", pollLane: "diagnostic_only", scanStage: "experimental", risk: "medium", validationStatus: "candidate", source: "research candidate" }
    ];
    const sampleCapabilities = [
      { header: "ATSH7E4", id: 11, command: "225B", pid: "5B", name: "hybrid battery state of charge", supported: true, responseCount: 120, lastSeenMs: now - 6 * hour, sample: { pollLane: "fast", scanStage: "low-risk", risk: "low", validationStatus: "confirmed", rawResponse: "62 5B 63", category: "battery" } },
      { header: "ATSH7E0", id: 10, command: "221154", pid: "1154", name: "engine oil temperature", supported: true, responseCount: 42, lastSeenMs: now - 6 * hour, sample: { pollLane: "thermal", scanStage: "low-risk", risk: "low", validationStatus: "confirmed", rawResponse: "62 11 54 60", category: "maintenance" } },
      { header: "ATSH760", id: 12, command: "224050", pid: "4050", name: "tire pressure front-left", supported: true, responseCount: 3, lastSeenMs: now - 6 * hour, sample: { pollLane: "slow", scanStage: "tires", risk: "medium", validationStatus: "confirmed", rawResponse: "62 40 50 D2", category: "tpms" } },
      { header: "ATSH760", id: 13, command: "224051", pid: "4051", name: "tire pressure front-right", supported: false, responseCount: 0, lastSeenMs: now - 7 * hour, sample: { pollLane: "slow", scanStage: "tires", risk: "medium", validationStatus: "candidate", rawResponse: "NO DATA", category: "tpms" } }
    ];

    state.storage = {
      database: "volttracker_obd_poc.db",
      databaseBytes: 21086208,
      sessionCount: routes.length,
      sampleCount: routes.reduce((s, r) => s + r.session.sampleCount, 0),
      rawTelemetryCount: routes.reduce((s, r) => s + r.session.sampleCount, 0),
      locationSampleCount: routes.reduce((s, r) => s + r.points.length, 0),
      tripSegmentCount: routes.length,
      eventCount: 7,
      pidObservationCount: 1911,
      lastEventAtMs: today.session.endedAtMs,
      chargeSessionCount: sampleCharges.length,
      batterySnapshotCount: 1,
      fieldCapabilityCount: sampleCapabilities.length,
      diagnosticCodeCount: sampleDtcs.length,
      diagnosticCodeStatusCounts: { stored: 1, pending: 1 },
      latestDiagnosticCodes: sampleDtcs,
      latestReview: sampleReview,
      recentRoutes: routes,
      latestRoute: today,
      recentSessions: routes.map((r) => ({
        id: r.session.id,
        mode: "drive",
        adapterName: r.session.adapterName,
        startedAtMs: r.session.startedAtMs,
        status: "complete",
        sampleCount: r.session.sampleCount,
        usefulSampleCount: r.session.sampleCount,
        emptySampleCount: 0
      })),
      overview: {
        distanceMeters: totalDistance,
        maxSpeedKph: 105,
        chargingHints: 6,
        latestTelemetry: { soc: sampleBattery.soc, powerKw: sampleBattery.packPowerKw, vehicleState: "idle" }
      },
      chargeSummary: {
        chargeSessionCount: sampleCharges.length,
        chargingHintCount: 6,
        maxPowerKw: 7.2,
        latest: sampleCharges[0],
        recentSessions: sampleCharges
      },
      batterySummary: { snapshotCount: 1, cellSnapshotCount: 0, latestBatterySnapshot: sampleBattery },
      detailedSignalCatalog: sampleSignalCatalog,
      enhancedCapabilities: sampleCapabilities
    };
    state.insights = {
      tripCount: routes.length,
      totalDistanceMeters: totalDistance,
      totalDriveMs: totalDrive,
      longestTripMeters: Math.max.apply(null, routes.map((r) => r.distanceMeters)),
      maxSpeedKph: 105,
      gpsTripCount: routes.length
    };
    state.appState = Object.assign({}, state.appState, { vehicle: sampleVehicle });
    state.demoScenario = "typical";
    captureDemoPreview();
    VD.setDemoActive(true, "Demo preview sample drive loaded.");
    renderDemoSurfaces();
  }

  function captureDemoPreview() {
    // Snapshot the live payloads as the demo preview (cross-module demo invariant:
    // the read-side panels swap to these while demo is active).
    VD.setState({
      demoPreviewStorage: state.storage,
      demoPreviewTrips: state.trips,
      demoPreviewInsights: state.insights,
      demoPreviewAppState: state.appState
    });
  }

  // Re-renders every demo-backed surface (DB summary, Signals, DTC, vehicle, map,
  // trips, insights) from the current state.storage. Shared by loadSampleData and
  // the scenario switcher so a mutated payload paints everywhere consistently.
  function renderDemoSurfaces() {
    VD.updateStorageUi();
    VD.renderRealV2Ui();
    renderMap();
    VD.renderRealTrips();
    VD.renderInsightStats();
  }

  function loadDemoScenario(name: string) {
    const scenario = String(name || "typical");
    if (scenario === "empty") {
      state.storage = {
        database: "volttracker_obd_poc.db",
        databaseBytes: 4096,
        sessionCount: 0, sampleCount: 0, rawTelemetryCount: 0,
        recentRoutes: [], recentSessions: [],
        chargeSummary: { chargeSessionCount: 0, chargingHintCount: 0 },
        batterySummary: {}, detailedSignalCatalog: [], enhancedCapabilities: [],
        latestDiagnosticCodes: [], diagnosticCodeCount: 0
      };
      state.insights = { tripCount: 0 };
      state.appState = Object.assign({}, state.appState, { vehicle: null });
      state.demoScenario = "empty";
      captureDemoPreview();
      VD.setDemoActive(true, "Demo scenario: empty (no logged data yet).");
      renderDemoSurfaces();
      return;
    }

    // Every other scenario builds on the rich typical dataset.
    loadSampleData();
    const now = Date.now();
    const hour = 3600 * 1000;
    const s = state.storage;
    if (scenario === "power-user") {
      const charges = [];
      for (let i = 0; i < 14; i++) {
        charges.push({
          id: 100 - i, startedAtMs: now - (i * 26 + 4) * hour, endedAtMs: now - (i * 26 + 4) * hour + Math.round((2.4 + (i % 4) * 0.6) * hour),
          chargerType: i % 5 === 0 ? "level1" : "level2",
          startSoc: 22 + (i % 6) * 6, endSoc: 88 + (i % 3) * 3, powerKw: i % 5 === 0 ? 1.3 : 7.0 + (i % 3) * 0.2, energyKwh: 8 + (i % 7)
        });
      }
      // loadSampleData() above always seeds chargeSummary; keep a non-null
      // local so the demo writes below typecheck against the optional slot.
      const chargeSummary = (s.chargeSummary = s.chargeSummary || {});
      chargeSummary.recentSessions = charges;
      chargeSummary.chargeSessionCount = charges.length;
      s.chargeSessionCount = charges.length;
      s.sampleCount = 184213; s.rawTelemetryCount = 184213; s.pidObservationCount = 184213;
      s.eventCount = 312; s.sessionCount = 96;
      VD.setStatus({ state: "demo", detail: "Demo scenario: power user (months of data)." });
    } else if (scenario === "fault") {
      s.latestDiagnosticCodes = [
        { dtc: "P0420", status: "current", statusLabel: "current", moduleName: "Powertrain", header: "7E8", firstSeenMs: now - 72 * hour, lastSeenMs: now - hour, seenCount: 9 },
        { dtc: "P0301", status: "permanent", statusLabel: "permanent", moduleName: "Powertrain", header: "7E8", firstSeenMs: now - 50 * hour, lastSeenMs: now - 2 * hour, seenCount: 5 },
        { dtc: "P0128", status: "freeze-frame", statusLabel: "freeze-frame", moduleName: "Powertrain", header: "7E8", firstSeenMs: now - 30 * hour, lastSeenMs: now - 3 * hour, seenCount: 2 },
        { dtc: "P0011", status: "pending", statusLabel: "pending", moduleName: "Powertrain", header: "7E8", firstSeenMs: now - 12 * hour, lastSeenMs: now - hour, seenCount: 1 }
      ];
      s.diagnosticCodeCount = 4;
      s.diagnosticCodeStatusCounts = { current: 1, permanent: 1, "freeze-frame": 1, pending: 1 };
      state.demoScenario = "fault";
      captureDemoPreview();
      renderDemoSurfaces();
      VD.setStatus({ state: "blocked", detail: "Adapter handshake failed after 3 retries — check the OBD dongle is seated." });
      return;
    } else if (scenario === "extreme") {
      state.appState = Object.assign({}, state.appState, {
        vehicle: { year: 2017, make: "Chevrolet", model: "Volt Premier Long-Range Special Edition", vin: "1G1RC6S52HU1234567", odometerMiles: 1234567 }
      });
      s.sampleCount = 9999999; s.rawTelemetryCount = 9999999; s.pidObservationCount = 9999999; s.sessionCount = 4096;
      if (s.chargeSummary && Array.isArray(s.chargeSummary.recentSessions) && s.chargeSummary.recentSessions[1]) {
        s.chargeSummary.recentSessions[1].chargerType = "DC fast (CCS, 150 kW pedestal)";
        s.chargeSummary.recentSessions[1].energyKwh = 53.219;
      }
      s.overview = Object.assign({}, s.overview, { maxSpeedKph: 257 });
    }
    state.demoScenario = scenario;
    captureDemoPreview();
    renderDemoSurfaces();
    VD.setStatus({ state: "demo", detail: `Demo scenario: ${scenario}.` });
  }

  const mapTileRetry = el("mapTileRetryBtn");
  if (mapTileRetry) {
    mapTileRetry.addEventListener("click", retryMapTiles);
  }

  Object.assign(VD, {
    ensureMap,
    renderMap,
    drawMapRoute,
    setMapTileError,
    retryMapTiles,
    renderMapSessionList,
    selectedMapRoute,
    sessionForRoute,
    haversineMetersJs,
    routeDistanceMeters,
    segmentSpeedMps,
    detectStops,
    addStop,
    updateLivePosition,
    clearLivePosition,
    loadSampleData,
    loadDemoScenario
  });

export {};
