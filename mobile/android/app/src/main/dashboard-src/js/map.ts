import {
  ASSUMED_VOLT_MI_PER_KWH,
  computeSavingsVsGas,
  formatSignedMoney,
  savingsPrefsReady
} from "./cost-model";
import {
  CURRENT_DRIVE_LABEL,
  LIVE_ROUTE_ID,
  appendLiveRoutePoint,
  haversineMetersJs,
  isValidRoutePoint,
  liveFollowShouldRecenter,
  liveSampleTimeMs,
  mapEffColor,
  numOrNaN,
  routeFitKey
} from "./map-route-utils";
import type { MapRoute, MapRoutePoint, MapRouteSession } from "./map-route-utils";
import {
  renderMapSessionListInto,
  routeIsLive,
  sessionForRoute
} from "./map-session-list";
import type { MapSessionFilter } from "./map-session-list";

  const VD = window.VoltDashboard;
  const state = VD.state;
  const bridge = VD.bridge;
  const el = VD.el;

  // Leaflet's stylesheet AND the Map-tab chrome (screens-map.css, split out of the
  // eager screens.css in G3) are not render-blocking in index.template.html — both
  // are only needed once a map renders. Inject them here, as this lazy map chunk
  // loads (well before the first renderMap()), so neither costs the Drive-first
  // startup path. `VD.mapStylesReady` resolves once screens-map.css has applied;
  // requestMapRender() awaits it so the map never paints unstyled (no FOUC).
  VD.mapStylesReady = (function ensureMapStyles(): Promise<void> {
    const sheets = ["lib/leaflet/leaflet.css", "css/screens-map.css"];
    const loads: Array<Promise<void>> = [];
    for (const href of sheets) {
      if (document.querySelector(`link[href="${href}"]`)) continue;
      const link = document.createElement("link");
      link.rel = "stylesheet";
      link.href = href;
      loads.push(
        new Promise<void>((resolve) => {
          // Resolve on load OR error — a missing/blocked sheet must not hang render.
          link.addEventListener("load", () => resolve(), { once: true });
          link.addEventListener("error", () => resolve(), { once: true });
        }),
      );
      document.head.appendChild(link);
    }
    return Promise.all(loads).then(() => undefined);
  })();

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
    flow: MutablePolylineLayer;
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
  const seededLiveRouteStartedAtMs = Number(state.liveRouteStartedAtMs);
  let liveRouteStartedAtMs =
    state.liveRouteStartedAtMs != null && Number.isFinite(seededLiveRouteStartedAtMs)
      ? seededLiveRouteStartedAtMs
      : null;
  let liveRoutePoints: MapRoutePoint[] = Array.isArray(state.liveRoutePoints)
    ? state.liveRoutePoints as MapRoutePoint[]
    : [];
  state.liveRouteStartedAtMs = liveRouteStartedAtMs;
  state.liveRoutePoints = liveRoutePoints;

  /** Store a demo/real GPS sample as the selectable Current route on the map. */
  function updateLivePosition(lat: unknown, lng: unknown) {
    const la = Number(lat);
    const ln = Number(lng);
    if (!Number.isFinite(la) || !Number.isFinite(ln)) return;
    const point = liveRoutePoint(la, ln);
    // Gate on isValidRoutePoint (not just finiteness) so a (0,0) null-island fix
    // never enters the live buffer. drawMapRoute filters the buffer through
    // isValidRoutePoint, but buildLiveRoute derives distance / point-count from the
    // raw buffer — an unfiltered (0,0) would add a ~13,000 km phantom leg to the
    // stats while the drawn route skips it. Reject it here so both stay in sync.
    if (!isValidRoutePoint(point)) return;
    const result = appendLiveRoutePoint(liveRoutePoints, point);
    if (result === "skipped") return;
    if (result === "first") {
      liveRouteStartedAtMs = point.atMs;
      state.liveRouteStartedAtMs = liveRouteStartedAtMs;
      state.selectedMapSessionId = LIVE_ROUTE_ID;
      mapFitKey = null;
      // A fresh drive re-arms follow so it tracks from the first fix, even if the
      // user had turned follow off while inspecting a previous/historical route.
      state.mapFollowLive = true;
    }
    state.liveRoutePoints = liveRoutePoints;
    if (state.selectedMapSessionId === LIVE_ROUTE_ID && state.view === "map") {
      renderMap();
    }
  }

  /** Remove the Current route (e.g. when demo stops or a new real session starts). */
  function clearLivePosition() {
    const wasSelected = String(state.selectedMapSessionId || "") === LIVE_ROUTE_ID;
    liveRouteStartedAtMs = null;
    liveRoutePoints = [];
    state.liveRouteStartedAtMs = null;
    state.liveRoutePoints = liveRoutePoints;
    if (wasSelected) state.selectedMapSessionId = null;
    mapFitKey = null;
    if (state.view === "map") renderMap();
  }

  /**
   * Replace the live-route buffer from an external seed — telemetry.ts calls this when it
   * recovers an in-progress GPS track after a mid-drive WebView teardown (hydrateLiveRouteIfActive).
   * Updates BOTH the module-local `liveRoutePoints` (which buildLiveRoute reads) and `state` so a
   * reassigned `state.liveRoutePoints` can't desync from this module's reference, which would leave
   * the recovered drive invisible on an already-loaded map.
   */
  function setLiveRoutePoints(points: unknown, startedAtMs?: unknown) {
    liveRoutePoints = Array.isArray(points) ? (points as MapRoutePoint[]) : [];
    state.liveRoutePoints = liveRoutePoints;
    const started = Number(startedAtMs);
    liveRouteStartedAtMs = Number.isFinite(started)
      ? started
      : (liveRoutePoints[0] ? Number(liveRoutePoints[0].atMs) : null);
    state.liveRouteStartedAtMs = liveRouteStartedAtMs;
    if (liveRoutePoints.length) state.selectedMapSessionId = LIVE_ROUTE_ID;
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
    // outage, regional restriction). Single tile misses are common on mobile networks, so only a
    // run of failures should surface as a user-visible map problem.
    let tileErrorCount = 0;
    let fallbackErrorCount = 0;
    let fallbackActivated = false;
    // In-flight requests on the removed primary layer can still settle after the
    // fallback takes over; ignore them so they cannot clear or re-raise the banner.
    tiles.on("tileload", () => {
      if (fallbackActivated) return;
      tileErrorCount = 0;
      setMapTileError(false);
    });
    tiles.on("tileerror", (event: LeafletTileErrorEvent) => {
      if (fallbackActivated) return;
      tileErrorCount += 1;
      const src = (event && event.tile && event.tile.src) || "unknown";
      if (tileErrorCount <= 2) {
        if (bridge && typeof bridge.logClientError === "function") {
          bridge.logClientError("map.tileerror", "Basemap tile failed: " + src);
        }
      }
      if (tileErrorCount >= MAP_TILE_WARNING_THRESHOLD) {
        setMapTileError(true, "Map tiles are not loading. Routes still work; retry when the network is back.");
      }
      if (tileErrorCount >= MAP_TILE_FALLBACK_THRESHOLD && !fallbackActivated) {
        fallbackActivated = true;
        try {
          map.removeLayer(tiles);
          const fallback = L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
            attribution: "© OpenStreetMap",
            maxZoom: 19
          });
          fallback.on("tileload", () => {
            fallbackErrorCount = 0;
            setMapTileError(false);
          });
          fallback.on("tileerror", () => {
            fallbackErrorCount += 1;
            if (fallbackErrorCount >= MAP_TILE_WARNING_THRESHOLD) {
              setMapTileError(true, "Backup map tiles are also unavailable. Routes still work without basemap tiles.");
            }
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
    // Always refresh the copy when showing, so a generic show can't surface a
    // stale message from an earlier, different failure (e.g. the fallback-tiles
    // text after the primary layer recovers and fails again).
    if (show) VD.setText("mapTileErrorCopy", detail || "Map tiles are not loading.");
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

  const MAP_TILE_WARNING_THRESHOLD = 3;
  const MAP_TILE_FALLBACK_THRESHOLD = 6;
  // Cap on stop markers drawn (and counted in the badge) so a long stop-and-go
  // drive can't flood the map; keep the badge count and the drawn markers in sync.
  const MAX_DRAWN_STOPS = 20;
  // Meters/second to miles/hour.
  const MPS_TO_MPH = 2.2369363;

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
    const map: LeafletMapInstance = L.map(container, { zoomControl: false, attributionControl: true });
    map.setView([39.5, -98.35], 4);
    // Keep the OSM/CARTO credit but drop Leaflet's default "Leaflet" prefix
    // (with flag glyph) — the stock chrome rendered at body size over the
    // legend. The pill styling lives in screens-map.css.
    if (map.attributionControl && typeof map.attributionControl.setPrefix === "function") {
      map.attributionControl.setPrefix("");
    }
    mapInstance = map;
    syncRemoteTiles();
    if (typeof VD.scrubberAttachMap === "function") VD.scrubberAttachMap(map);
    // Tap anywhere on the map → snap the scrubber to the closest route point.
    map.on("click", (e: { latlng?: LeafletLatLng }) => {
      if (e && e.latlng && typeof VD.scrubAtLatLng === "function") {
        VD.scrubAtLatLng(e.latlng.lat, e.latlng.lng);
      }
    });
    // The moment the user drags the map, stop auto-following the live drive so
    // they can inspect freely. `dragstart` only fires for user gestures —
    // programmatic fitBounds/setView never drag — so this can't fight our own
    // recenters. The Follow button re-arms it. No-op for historical routes.
    map.on("dragstart", () => {
      if (state.mapFollowLive && String(state.selectedMapSessionId || "") === LIVE_ROUTE_ID) {
        state.mapFollowLive = false;
        updateFollowButton();
      }
    });
    return map;
  }

  /** Reflect follow state + live-route visibility on the Follow button. */
  function updateFollowButton() {
    const btn = el("mapFollowBtn");
    if (!btn) return;
    const liveSelected = String(state.selectedMapSessionId || "") === LIVE_ROUTE_ID;
    btn.hidden = !liveSelected;
    const following = state.mapFollowLive !== false;
    btn.setAttribute("aria-pressed", following ? "true" : "false");
    btn.setAttribute("aria-label", following ? "Following the live drive" : "Follow the live drive");
  }

  /**
   * Toggle (or set) live-follow. Turning it on recenters immediately so the
   * button doubles as "recenter on the current drive". Called from the Follow
   * button in actions.ts.
   */
  function setMapFollowLive(on?: boolean) {
    const next = typeof on === "boolean" ? on : state.mapFollowLive === false;
    state.mapFollowLive = next;
    updateFollowButton();
    if (next) {
      // Force the next render to reframe the track (mapFitKey gate is bypassed
      // by the follow path, but clearing it keeps non-follow callers honest too).
      mapFitKey = null;
      if (state.view === "map") renderMap();
    }
  }

  /**
   * Keep the live drive framed while following. Recenters to the whole (capped,
   * so ~rolling-window) live track only when the newest point nears the viewport
   * edge — avoiding per-tick jitter. Returns true when it moved the view.
   */
  function fitLiveFollow(map: LeafletMapInstance, latlngs: LatLngTuple[]): boolean {
    const last = latlngs[latlngs.length - 1];
    if (!last) return false;
    let view = null;
    try {
      const bounds = map.getBounds && map.getBounds();
      if (bounds && typeof bounds.getNorth === "function") {
        view = {
          north: bounds.getNorth(),
          south: bounds.getSouth(),
          east: bounds.getEast(),
          west: bounds.getWest()
        };
      }
    } catch (_ignored) {
      view = null;
    }
    if (!liveFollowShouldRecenter(view, { lat: last[0], lng: last[1] })) return false;
    map.fitBounds(L.latLngBounds(latlngs), { padding: [40, 40] });
    return true;
  }

  // "Morning drive" / "Evening drive" from the trip's start hour — the human
  // label a person would use, instead of the adapter's model number.
  function daypartDriveLabel(startedAtMs: unknown): string {
    const ms = Number(startedAtMs);
    if (!Number.isFinite(ms) || ms <= 0) return "Logged drive";
    const hour = new Date(ms).getHours();
    const daypart = hour < 5 ? "Night" : hour < 12 ? "Morning" : hour < 17 ? "Afternoon" : hour < 21 ? "Evening" : "Night";
    return `${daypart} drive`;
  }

  function renderMap() {
    const storage = state.storage || {};
    const routes = mapRoutes(storage);
    const route = ensureRoutePoints(selectedMapRoute(storage, routes));
    const points = Array.isArray(route.points) ? route.points : [];
    const hasRoute = points.length >= 2;
    const isLiveRoute = routeIsLive(route);
    const hasMapContent = hasRoute || (isLiveRoute && points.some(isValidRoutePoint));
    const layer = isLiveRoute ? "routes" : state.mapLayer;
    const stops = hasRoute ? detectStops(points.filter(isValidRoutePoint)) : [];

    const frame = el("mapFrame");
    if (frame) frame.dataset.layer = layer;
    VD.setText("mapStopsCount", stops.length ? String(Math.min(MAX_DRAWN_STOPS, stops.length)) : "");
    const stopsCountEl = el("mapStopsCount");
    if (stopsCountEl) stopsCountEl.hidden = !stops.length;
    document.querySelectorAll("[data-map-layer]").forEach((node) => {
      const button = node as HTMLElement;
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
      fullBtn.setAttribute("aria-label", state.mapFull ? "Exit full map" : "Toggle full map");
    }

    VD.setText("mapPointBadge", `${points.length} ${points.length === 1 ? "pt" : "pts"}`);
    const routeSession = sessionForRoute(route);
    // Trip identity is the DRIVE, not the hardware: "Morning drive", never
    // "OBDLink MX+". The adapter model moves to the kicker line; GPS point
    // counts stay in the trip-detail sheet where engineers look for them.
    VD.setText(
      "mapTitle",
      hasMapContent
        ? (isLiveRoute
          // The live pseudo-session carries its human label ("Current demo" /
          // "Current drive") in adapterName — for live routes it IS the label.
          ? routeSession.adapterName || CURRENT_DRIVE_LABEL
          : daypartDriveLabel(routeSession.startedAtMs))
        : "No route recorded yet"
    );
    // For a live drive the title already reads "Current drive", so the kicker
    // carries the hardware model (deviceModel); stored routes keep adapterName.
    // For a live route the kicker carries ONLY the hardware model (deviceModel);
    // it must not fall back to adapterName, which for a live route IS the title
    // ("Current drive" / "Current demo") and would duplicate it into the kicker.
    const kickerModel = isLiveRoute
      ? String(routeSession.deviceModel || "")
      : routeSession.adapterName;
    VD.setText(
      "mapKicker",
      // Use the same absolute, skim-able format as the drive-picker chips (fmtChipDate) so a
      // single drive doesn't read "Today 2:51 AM" in its chip but "6h ago" in the kicker at once.
      hasMapContent
        ? [fmtChipDate(routeSession.startedAtMs), kickerModel].filter(Boolean).join(" · ")
        : "GPS map"
    );
    VD.setText("mapDistance", hasMapContent ? VD.formatDistance(route.distanceMeters || 0) : "--");
    const session = route.session || {};
    // Duration needs BOTH timestamps to be real. A stored session that crashed
    // before writing endedAtMs would otherwise read as time-since-the-drive
    // (and avg ≈ 0), and a missing startedAtMs yields an epoch-scale span —
    // show "--" instead, like every other missing value. The live route has no
    // endedAtMs yet by design; "now" is the honest end of the current drive.
    const startedAtMs = Number(session.startedAtMs);
    const endedAtMsRaw = Number(session.endedAtMs);
    const endedAtMs = Number.isFinite(endedAtMsRaw) && endedAtMsRaw > 0
      ? endedAtMsRaw
      : (isLiveRoute ? Date.now() : NaN);
    const hasTimestamps =
      Number.isFinite(startedAtMs) && startedAtMs > 0 &&
      Number.isFinite(endedAtMs) && endedAtMs > 0;
    const duration = hasTimestamps ? endedAtMs - startedAtMs : 0;
    VD.setText("mapDuration", hasMapContent && duration > 0 ? VD.formatDuration(duration) : "--");
    // Avg moving speed from GPS: distance / duration, ignoring stopped time at the
    // granularity of the route. More useful than GPS accuracy. Shown in the user's
    // chosen unit (the label reflects it too).
    const avgKph = hasMapContent && duration > 0
      ? (Number(route.distanceMeters || 0) / (duration / 1000)) * 3.6
      : 0;
    const avgSpeed = VD.units.speed(avgKph);
    // Gate on the rounded display value, not raw avgKph: a sub-0.5-unit average rounds to 0 and
    // should read "--", not a bare "0".
    VD.setText("mapAvgMph", avgSpeed.value > 0 ? avgSpeed.value : "--");
    VD.setText("mapAvgSpeedLabel", `Avg ${avgSpeed.unit}`);
    const empty = el("mapEmpty");
    if (empty) empty.hidden = hasMapContent;

    if (hasRoute && typeof VD.enrichRouteEff === "function") VD.enrichRouteEff(route);
    syncRemoteTiles();
    drawMapRoute(points, hasRoute, layer, routeSession);
    if (hasRoute && typeof VD.renderScrubber === "function") VD.renderScrubber(route);
    else if (typeof VD.hideScrubber === "function") VD.hideScrubber();
    renderMapListsIfChanged(routes);
    updateFollowButton();
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
    // `undefined` locale follows the device's runtime locale; explicit options
    // keep the compact "2:51 AM" / "Sat" / "Oct 15" shape across locales.
    const time = d.toLocaleTimeString(undefined, {
      hour: "numeric",
      minute: "2-digit"
    });
    if (drive === today) return "Today " + time;
    if (drive === yesterday) return "Yesterday " + time;
    const daysAgo = (now.getTime() - ms) / 86400000;
    if (daysAgo < 7) {
      return (
        d.toLocaleDateString(undefined, { weekday: "short" }) + " " + time
      );
    }
    return d.toLocaleDateString(undefined, { month: "short", day: "numeric" });
  }

  function mapRoutes(storage: VoltStorageSummary): MapRoute[] {
    const history = Array.isArray(storage.recentRoutes) ? storage.recentRoutes : [];
    const stored = history
      .concat(olderTripStubRoutes(history))
      .sort((a, b) => sessionStartMs(b) - sessionStartMs(a));
    stampTripLabels(stored);
    const live = buildLiveRoute();
    if (!live) return stored;
    return [
      live,
      ...stored.filter((route: MapRoute) =>
        String((route.session || {}).id || "") !== LIVE_ROUTE_ID
      )
    ];
  }

  function sessionStartMs(route: MapRoute) {
    const value = Number((route.session || {}).startedAtMs);
    return Number.isFinite(value) ? value : 0;
  }

  // Stamps each stored route's session with its user trip label (M4) and favorite flag (M4
  // favorites half) from state.trips, so the map session list shows both regardless of whether the
  // route came from the detailed recentRoutes window or an older trip stub. Both are keyed by
  // routeKey (trip.id); detailed routes whose id differs from the trip's clipped id are matched via
  // routeCoversTrip.
  function stampTripLabels(routes: MapRoute[]) {
    const trips = Array.isArray(state.trips) ? state.trips : [];
    const labelById = new Map<string, string>();
    const favoriteById = new Map<string, boolean>();
    for (const trip of trips) {
      const id = trip && trip.id != null ? String(trip.id) : "";
      if (!id) continue;
      const label = trip && typeof trip.label === "string" ? trip.label : "";
      if (label) labelById.set(id, label);
      if (trip && trip.favorite === true) favoriteById.set(id, true);
    }
    if (!labelById.size && !favoriteById.size) return;
    for (const route of routes) {
      const session = route.session || (route.session = {});
      const id = session.id == null ? "" : String(session.id);
      let label = labelById.get(id) || "";
      let favorite = favoriteById.get(id) === true;
      if (!label || !favorite) {
        const match = trips.find((trip) => routeCoversTrip(route, trip));
        if (match) {
          if (!label && typeof match.label === "string") label = match.label;
          if (!favorite && match.favorite === true) favorite = true;
        }
      }
      (session as MapRouteSession).label = label;
      (session as MapRouteSession).favorite = favorite;
    }
  }

  // The storage summary ships full point geometry for only the most recent few
  // drives (payload size), which used to silently cap the map's history at a
  // handful of days. The trips rollup goes back much further and shares the
  // same routeKey ids, so every older route-bearing trip joins the list as a
  // point-less stub; its geometry is fetched on demand via bridge.getTripRoute
  // when selected (see ensureRoutePoints).
  function olderTripStubRoutes(history: MapRoute[]): MapRoute[] {
    syncRouteCacheWithTrips();
    const trips = Array.isArray(state.trips) ? state.trips : [];
    const stubs: MapRoute[] = [];
    for (const trip of trips) {
      if (!trip || trip.hasRoute === false || Number(trip.pointCount || 0) < 2) continue;
      const id = trip.id == null ? "" : String(trip.id);
      if (!id || id === LIVE_ROUTE_ID) continue;
      if (history.some((route) => routeCoversTrip(route, trip))) continue;
      stubs.push(fetchedRouteCache.get(id) || tripStubRoute(trip, id));
    }
    return stubs;
  }

  // True when a detailed recentRoutes entry already represents this trip.
  // recentRoutes ids use the drive window's bounds while trip ids use the
  // point-clipped bounds, so the same drive can carry two different keys —
  // fall back to matching any time overlap within the same session.
  function routeCoversTrip(route: MapRoute, trip: VoltTrip) {
    const session = route.session || {};
    const routeId = session.id == null ? "" : String(session.id);
    const tripId = trip.id == null ? "" : String(trip.id);
    if (routeId && routeId === tripId) return true;
    const routeSessionId = session.sessionId == null ? routeId : String(session.sessionId);
    const tripSessionId = trip.sessionId == null ? "" : String(trip.sessionId);
    if (!routeSessionId || routeSessionId !== tripSessionId) return false;
    const routeStart = Number(session.startedAtMs);
    const tripStart = Number(trip.startedAtMs);
    if (!Number.isFinite(routeStart) || !Number.isFinite(tripStart)) return true;
    const routeEnd = Number.isFinite(Number(session.endedAtMs)) ? Number(session.endedAtMs) : Infinity;
    const tripEnd = Number.isFinite(Number(trip.endedAtMs)) ? Number(trip.endedAtMs) : Infinity;
    return tripStart <= routeEnd && tripEnd >= routeStart;
  }

  function tripStubRoute(trip: VoltTrip, id: string): MapRoute {
    return {
      session: {
        id,
        sessionId: trip.sessionId,
        adapterName: trip.adapterName,
        startedAtMs: trip.startedAtMs,
        endedAtMs: trip.endedAtMs,
        status: trip.status,
        sampleCount: trip.sampleCount,
        label: typeof trip.label === "string" ? trip.label : ""
      },
      points: [],
      pointCount: Number(trip.pointCount) || 0,
      distanceMeters: Number(trip.distanceMeters) || 0
    };
  }

  // Fetched full routes for trips beyond the storage summary's recentRoutes
  // window, keyed by routeKey. Cleared whenever the trips payload refreshes so
  // a restore/merge/hide can never serve stale geometry.
  const fetchedRouteCache = new Map<string, MapRoute>();
  const pendingRouteFetches = new Set<string>();
  let fetchedRouteCacheTripsRef: unknown = null;

  function syncRouteCacheWithTrips() {
    if (state.trips !== fetchedRouteCacheTripsRef) {
      fetchedRouteCacheTripsRef = state.trips;
      fetchedRouteCache.clear();
      pendingRouteFetches.clear();
    }
  }

  function applyTripRoutePayload(payload: unknown) {
    const wrapped = VD.parsePayload<Record<string, unknown>>(payload, {});
    const id = wrapped.routeKey == null ? "" : String(wrapped.routeKey);
    if (!id) return;
    pendingRouteFetches.delete(id);
    const parsed = VD.parsePayload<MapRoute>(wrapped.payload, {});
    const existing = fetchedRouteCache.get(id);
    const resolved =
      parsed && Array.isArray(parsed.points) && parsed.points.length >= 2
        ? parsed
        : existing || ({ session: { id }, points: [], pointCount: 0 } as MapRoute);
    fetchedRouteCache.set(id, resolved);
    VD.renderMapIfLoaded();
  }

  /** Resolve a stub route's full geometry via the native bridge, preferring the async request path
   *  and falling back to one synchronous read for older bridges. Failed lookups cache the stub so a
   *  render loop can't hammer the bridge. Detailed routes and the live route pass through untouched. */
  function ensureRoutePoints(route: MapRoute): MapRoute {
    const session = route.session || {};
    const id = session.id == null ? "" : String(session.id);
    if (!id || routeIsLive(route)) return route;
    if (Array.isArray(route.points) && route.points.length >= 2) return route;
    if (Number(route.pointCount || 0) < 2) return route;
    const cached = fetchedRouteCache.get(id);
    if (cached) return cached;
    if (
      bridge &&
      typeof bridge.requestTripRoute === "function" &&
      !pendingRouteFetches.has(id)
    ) {
      pendingRouteFetches.add(id);
      fetchedRouteCache.set(id, route);
      try {
        if (bridge.requestTripRoute(id)) {
          return route;
        }
      } catch (ignored) {
        // Fall back to the synchronous bridge path below.
      }
      pendingRouteFetches.delete(id);
      fetchedRouteCache.delete(id);
    }
    if (!bridge || typeof bridge.getTripRoute !== "function") return route;
    let fetched: MapRoute | null = null;
    try {
      fetched = VD.parsePayload<MapRoute>(bridge.getTripRoute(id), {});
    } catch (ignored) {
      fetched = null;
    }
    const resolved =
      fetched && Array.isArray(fetched.points) && fetched.points.length >= 2 ? fetched : route;
    fetchedRouteCache.set(id, resolved);
    return resolved;
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
    // The map title is the DRIVE ("Current drive"), not the hardware. Keep the
    // adapter/device model in a separate field so renderMap shows it only in the
    // kicker line — folding it into adapterName duplicated it into the title.
    const deviceModel = isDemo
      ? ""
      : String(sample.adapterName || (selectedDevice && selectedDevice.name) || "");
    const adapterName = isDemo ? "Current demo" : CURRENT_DRIVE_LABEL;
    // Keep the displayed distance and the duration (renderMap derives both from the values
    // below) on the SAME basis, or avg speed = distance/duration drifts once the rolling
    // 600-point GPS buffer trims its head on a long drive. When the full-session distance is
    // available (and larger than the buffered window), pair it with the original drive start;
    // otherwise use the buffered window's own span — anchored on its current first point, not a
    // first fix that has already rolled out of the buffer.
    const windowedDistanceMeters = routeDistanceMeters(points);
    const sessionDistanceMeters = Number(state.sessionDistanceM) || 0;
    const useSessionDistance = sessionDistanceMeters > windowedDistanceMeters;
    const distanceMeters = useSessionDistance ? sessionDistanceMeters : windowedDistanceMeters;
    const startedAtMs = useSessionDistance
      ? (liveRouteStartedAtMs || firstPoint.atMs)
      : firstPoint.atMs;
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
      distanceMeters,
      powerTrack,
      socTrack,
      session: {
        id: LIVE_ROUTE_ID,
        mode: isDemo ? "demo" : "drive",
        adapterName,
        deviceModel,
        startedAtMs,
        endedAtMs: Math.max(Date.now(), Number(lastPoint.atMs) || 0),
        status: "live",
        sampleCount: Number(sample.sampleCount) || points.length
      }
    };
  }

  // Rebuild the drive-chip strip and session list only when their inputs
  // change. A live GPS tick calls renderMap ~1×/s, and previously both lists
  // (dozens of buttons each) were torn down and rebuilt per tick even though
  // the route SET hadn't changed — the live polyline is already updated
  // incrementally by drawMapRoute. The signature folds in everything the two
  // lists render: the route set (id / favorite / label / geometry+power
  // hydration / eff enrichment), the live route's DISPLAYED distance (so the
  // live chip still ticks up at display precision, not per GPS point), the
  // selection, the search/sort/favorites filter, and a minute bucket so
  // relative-time copy stays fresh. Direct renderMapSessionList calls (search
  // input, favorite toggles) bypass this gate on purpose.
  let lastMapListsSig = "";
  function renderMapListsIfChanged(routes: MapRoute[]) {
    const filter = readMapSessionFilter();
    const sig = [
      routes.map((r) => {
        const s = sessionForRoute(r);
        const hydration = routeIsLive(r)
          ? "live:" + String(VD.units.distanceMeters(Number(r.distanceMeters || 0)).value)
          : (r.points || []).length + ":" +
            ((r as { powerTrack?: unknown[] }).powerTrack || []).length + ":" +
            ((r as { _effDone?: boolean })._effDone ? 1 : 0);
        return [s.id, s.favorite === true ? 1 : 0, s.label || "", hydration].join(",");
      }).join(";"),
      String(state.selectedMapSessionId || ""),
      filter.query,
      filter.sort,
      filter.favoritesOnly ? 1 : 0,
      Math.floor(Date.now() / 60000)
    ].join("|");
    if (sig === lastMapListsSig) return;
    lastMapListsSig = sig;
    renderMapDriveChips(routes);
    renderMapSessionList(routes);
  }

  // Horizontal drive-picker chips above the map. Each chip shows the drive's
  // start time, distance, and a color-coded average efficiency dot — same
  // pattern as the demo's drive picker. Click delegation flows through the
  // existing [data-map-session] handler in actions.ts.
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
      const distConv = VD.units.distanceMeters(Number(route.distanceMeters || 0));
      distance.textContent = live && Number(distConv.value) < 0.1 ? "current" : `${distConv.value} ${distConv.unit}`;
      meta.append(distance);
      if (live) {
        meta.append(document.createTextNode(" · "));
        const dot = document.createElement("u");
        dot.style.background = "var(--map-accent)";
        meta.append(dot, document.createTextNode(" live"));
      }
      // null eff = regen / no-data (enrichRouteEff); numOrNaN keeps those out of
      // the average instead of letting Number(null) === 0 drag the dot down a band.
      const effPts = (route.points || []).filter((p) => Number.isFinite(numOrNaN(p.eff)));
      const avgEff = effPts.length
        ? effPts.reduce((acc, p) => acc + numOrNaN(p.eff), 0) / effPts.length
        : 0;
      if (!live && avgEff > 0) {
        meta.append(document.createTextNode(" · "));
        const dot = document.createElement("u");
        dot.style.background = mapEffColor(avgEff);
        meta.append(dot, document.createTextNode(" " + VD.units.efficiencyText(avgEff)));
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
    if (isLiveRoute && layer === "routes" && updateLiveRouteLayer(drawable, map)) {
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
    // Direction-of-travel cue: a thin overlay of round dashes whose CSS animation
    // flows start -> end (chronological draw order = direction travelled). Subtle
    // (no extra weight, just a moving stipple) so it reads as "which way" without
    // shouting. The class drives the dash pattern + keyframes (screens.css).
    const flowColor = isLiveRoute ? "#dff4ff" : "#fff0e6";

    const outerRoute = L.polyline(latlngs, { color: routeColor, weight: 9, opacity: 0.16 }) as MutablePolylineLayer;
    const innerRoute = L.polyline(latlngs, { color: routeColor, weight: 3.5, opacity: 1 }) as MutablePolylineLayer;
    const flowRoute = L.polyline(latlngs, {
      color: flowColor,
      weight: 2.5,
      opacity: 0.9,
      lineCap: "round",
      className: "route-flow",
      interactive: false
    }) as MutablePolylineLayer;
    const startMarker = L.circleMarker(firstLatLng, { radius: 6, color: "#fff", weight: 2, fillColor: routeColor, fillOpacity: 1 }) as MutableMarkerLayer;
    const endMarker = L.circleMarker(lastLatLng, {
      radius: isLiveRoute ? 8 : 7,
      color: "#fff",
      weight: 2,
      fillColor: routeEndColor,
      fillOpacity: 1,
      // The live "head" gently pulses to mark the current position; historical
      // routes get a plain end cap.
      className: isLiveRoute ? "live-head-pulse" : undefined
    }) as MutableMarkerLayer;
    const routeGroup = L.layerGroup([outerRoute, innerRoute, flowRoute, startMarker, endMarker]);
    mapLayerGroups.routes = routeGroup;
    if (isLiveRoute && layer === "routes") {
      liveRouteLayerCache = {
        outer: outerRoute,
        inner: innerRoute,
        flow: flowRoute,
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
    const stops = detectStops(drawable).slice(0, MAX_DRAWN_STOPS);
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
      // Pass raw eff (mapEffColor is null-safe): null regen/no-data segments
      // render grey, not the worst-efficiency red band.
      const color = mapEffColor(point.eff);
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

    if (isLiveRoute && state.mapFollowLive !== false) {
      // Follow mode: keep the growing drive framed (see fitLiveFollow). The
      // fitKey gate is for one-shot historical fits and would pin the view to the
      // first point forever on a live route, so bypass it here.
      fitLiveFollow(map, latlngs);
      mapFitKey = liveRouteFitKey();
      return;
    }
    // A live route with follow OFF gates on drive identity (liveRouteFitKey) so a
    // buffer head-trim on a long drive can't refit and yank a panned view; historical
    // routes keep the coordinate-based key (a changed geometry is a different drive).
    const fitKey = isLiveRoute ? liveRouteFitKey() : routeFitKey(routeSession, drawable);
    if (fitKey !== mapFitKey) {
      map.fitBounds(L.latLngBounds(latlngs), { padding: [30, 30] });
      mapFitKey = fitKey;
    }
  }

  // Follow-OFF fit gate for the LIVE route: keyed on drive identity, NOT the first
  // drawn coordinate. routeFitKey folds the first fix into the live key, but
  // appendLiveRoutePoint trims the buffer head once it passes LIVE_ROUTE_MAX_POINTS
  // (~10 min of driving), so that first coordinate changes every tick. Keying the
  // follow-off fit on it would refit fitBounds every second and yank a manually
  // panned view back. `liveRouteStartedAtMs` is set once when the drive starts and
  // is stable across head-trims (unlike buildLiveRoute's session.startedAtMs, which
  // can fall back to the trimmed first point), so the initial fit still fires once
  // (mapFitKey starts null / carries a prior route's key) and then never re-fits
  // until a new drive starts (which resets mapFitKey) or the user picks another drive.
  function liveRouteFitKey(): string {
    return [LIVE_ROUTE_ID, String(liveRouteStartedAtMs || "")].join(":");
  }

  function updateLiveRouteLayer(
    drawable: MapRoutePoint[],
    map: LeafletMapInstance
  ) {
    const cache = liveRouteLayerCache;
    if (!cache || drawable.length < 2) return false;
    if (
      typeof cache.outer.setLatLngs !== "function" ||
      typeof cache.inner.setLatLngs !== "function" ||
      typeof cache.flow.setLatLngs !== "function" ||
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
    cache.flow.setLatLngs(latlngs);
    cache.start.setLatLng(first);
    cache.end.setLatLng(last);
    addActiveLayerGroup("routes", map);
    if (state.mapFollowLive !== false) {
      // Follow the head as new samples stream in (see fitLiveFollow). Keep
      // mapFitKey current so a later follow-off render doesn't snap-refit.
      fitLiveFollow(map, latlngs);
      mapFitKey = liveRouteFitKey();
      return true;
    }
    // Follow OFF: gate on drive identity (liveRouteFitKey), not the head-trimmed
    // first fix — otherwise a long drive's rolling-window trim refits (and yanks the
    // user's manual pan) every tick. The initial fit still runs once via the gate.
    const fitKey = liveRouteFitKey();
    if (fitKey !== mapFitKey) {
      map.fitBounds(L.latLngBounds(latlngs), { padding: [30, 30] });
      mapFitKey = fitKey;
    }
    return true;
  }

  // Read the M4 search / sort / favorites controls off the DOM. The controls are
  // the single source of truth (no extra state slot): the input value, the active
  // sort button, and the favorites toggle's data-on attribute.
  function readMapSessionFilter(): MapSessionFilter {
    const search = el("mapSessionSearch") as HTMLInputElement | null;
    const activeSort = document.querySelector("[data-map-sort].is-active") as HTMLElement | null;
    const favToggle = el("mapSessionFavoritesOnly");
    return {
      query: search ? String(search.value || "") : "",
      sort: activeSort && activeSort.dataset.mapSort === "distance" ? "distance" : "recent",
      favoritesOnly: Boolean(favToggle && favToggle.dataset.on === "true"),
    };
  }

  function renderMapSessionList(routes: MapRoute[]) {
    const list = el("mapSessionList");
    if (!list) return;
    renderMapSessionListInto(list, routes, {
      selectedSessionId: state.selectedMapSessionId,
      formatChipDate: (value) => fmtChipDate(value),
      formatDistance: (value) => VD.formatDistance(value),
      formatWhen: (value) => VD.formatWhen(value),
      filter: readMapSessionFilter(),
    });
  }

  // Re-render ONLY the trip list from current storage (M4). The search/sort/
  // favorites controls call this so a keystroke or toggle re-filters the list
  // without a full renderMap() — which would refit the Leaflet bounds and tug
  // the map view away from whatever route the user is inspecting.
  function refreshMapSessionList() {
    renderMapSessionList(mapRoutes(state.storage || {}));
  }

  // ---- Per-trip detail sheet (M7) -----------------------------------------
  // Opened from a map-session row's "Details" button. Resolves the one drive's
  // full geometry (reusing the on-demand ensureRoutePoints fetch + cache),
  // computes its headline stats from the route projection + trip rollup, and
  // renders an efficiency-vs-speed scatter scoped to JUST that drive — the
  // per-drive companion to the all-drives Insights scatter. XSS-safe throughout.

  // Find the route for a given route key from the current map routes, resolving
  // full geometry for an older trip stub on demand.
  function routeForKey(routeKey: string): MapRoute | null {
    const clean = String(routeKey || "").trim();
    if (!clean) return null;
    const routes = mapRoutes(state.storage || {});
    const match = routes.find((route) => String((route.session || {}).id || "") === clean);
    if (!match) return null;
    return ensureRoutePoints(match);
  }

  type TripDriveStats = {
    distanceMeters: number;
    durationMs: number;
    avgKph: number;
    maxKph: number;
    miPerKwh: number | null;
    pointCount: number;
    startedAtMs: number;
    endedAtMs: number;
  };

  // Derive a single drive's headline stats from its resolved route + session.
  // Max speed prefers per-point speedMps, falling back to GPS-segment speed.
  // Efficiency averages the per-point eff (mi/kWh) enrichRouteEff annotates.
  function tripDriveStats(route: MapRoute): TripDriveStats {
    const session = route.session || {};
    const points = Array.isArray(route.points) ? route.points : [];
    const distanceMeters = Number(route.distanceMeters || 0);
    const startedAtMs = Number(session.startedAtMs);
    const endedAtMsRaw = Number(session.endedAtMs);
    const endedAtMs = Number.isFinite(endedAtMsRaw) && endedAtMsRaw > 0 ? endedAtMsRaw : NaN;
    const durationMs =
      Number.isFinite(startedAtMs) && startedAtMs > 0 && Number.isFinite(endedAtMs) && endedAtMs > startedAtMs
        ? endedAtMs - startedAtMs
        : 0;
    const avgKph = durationMs > 0 ? (distanceMeters / (durationMs / 1000)) * 3.6 : 0;
    let maxMps = 0;
    // Filter through isValidRoutePoint (as drawMapRoute does) before the segment-speed
    // fallback: a single (0,0) null-island fix between real ones makes segmentSpeedMps
    // return an absurd value and blow out Max speed.
    const drivablePoints = points.filter(isValidRoutePoint);
    for (let i = 0; i < drivablePoints.length; i += 1) {
      const point = drivablePoints[i];
      if (!point) continue;
      let mps = Number(point.speedMps);
      if ((!Number.isFinite(mps) || mps < 0) && i > 0) {
        const previous = drivablePoints[i - 1];
        if (previous) mps = segmentSpeedMps(previous, point);
      }
      if (Number.isFinite(mps) && mps > maxMps) maxMps = mps;
    }
    if (typeof VD.enrichRouteEff === "function") VD.enrichRouteEff(route);
    let effSum = 0;
    let effCount = 0;
    for (const point of points) {
      // numOrNaN so null (regen / no-data) points are skipped, not averaged in as
      // 0 — Number(null) === 0 < 6.5 would otherwise deflate the headline mi/kWh.
      const eff = numOrNaN(point.eff);
      // Exclude only clamp-saturated samples (enrichRouteEff ceils eff at exactly 6.5);
      // averaging that pile would inflate the headline. Legitimate high-efficiency values
      // just below the ceiling are kept.
      if (Number.isFinite(eff) && eff < 6.5) {
        effSum += eff;
        effCount += 1;
      }
    }
    return {
      distanceMeters,
      durationMs,
      avgKph,
      maxKph: maxMps * 3.6,
      miPerKwh: effCount ? effSum / effCount : null,
      pointCount: Number(route.pointCount || points.length || 0),
      startedAtMs: Number.isFinite(startedAtMs) ? startedAtMs : NaN,
      endedAtMs: Number.isFinite(endedAtMs) ? endedAtMs : NaN,
    };
  }

  // Pool per-point { mph, eff, grade } for ONE drive (the per-drive analogue of
  // insights-panel.ts#renderInsightScatter). Returns the rendered SVG, or null
  // when too few samples carry derived efficiency.
  function buildTripScatter(route: MapRoute): SVGElement | null {
    if (typeof VD.enrichRouteEff === "function") VD.enrichRouteEff(route);
    const points = Array.isArray(route.points) ? route.points : [];
    const pool: Array<{ mph: number; eff: number; grade: number }> = [];
    for (let i = 0; i < points.length; i += 1) {
      const point = points[i];
      if (!point) continue;
      // numOrNaN so null (regen / no-data) points are skipped — Number(null) === 0
      // is finite and would plot a bogus 0 mi/kWh dot at the chart floor.
      const eff = numOrNaN(point.eff);
      if (!Number.isFinite(eff)) continue;
      let mps = Number(point.speedMps);
      if (!Number.isFinite(mps) || mps < 0) {
        const a = points[Math.max(0, i - 1)];
        const b = points[Math.min(points.length - 1, i + 1)];
        if (a && b) {
          const dt = Math.max(1, (Number(b.atMs) - Number(a.atMs)) / 1000);
          mps = haversineMetersJs(a.lat, a.lng, b.lat, b.lng) / dt;
        } else {
          mps = 0;
        }
      }
      const mph = Math.max(0, mps) * MPS_TO_MPH;
      if (mph < 10) continue;
      let grade = 0;
      const prev = points[i - 1];
      // numOrNaN, not Number(): a JSON null altitude is Number(null) === 0, which is
      // finite and would pass the guard and mis-color the grade dot as sea level.
      if (i > 0 && prev && Number.isFinite(numOrNaN(prev.altM)) && Number.isFinite(numOrNaN(point.altM))) {
        const horiz = Math.max(8, haversineMetersJs(prev.lat, prev.lng, point.lat, point.lng));
        grade = Math.max(-0.13, Math.min(0.13, (numOrNaN(point.altM) - numOrNaN(prev.altM)) / horiz));
      }
      pool.push({ mph, eff, grade });
    }
    if (pool.length < 4) return null;
    return drawTripScatterSvg(pool);
  }

  // mi → km factor, mirroring the units module (km/h = mph * KM_PER_MILE,
  // km/kWh = mi/kWh * KM_PER_MILE). Used to convert the scatter axes for metric.
  const KM_PER_MILE = 1.609344;

  function drawTripScatterSvg(pool: Array<{ mph: number; eff: number; grade: number }>): SVGElement {
    // Units-aware axes (C2): the pool is mph / (mi/kWh) internally, but a metric
    // user must see km/h and km/kWh axes so the chart matches the unit-aware
    // headline. Read the preference at render time (the trip-detail sheet is
    // rebuilt on open, after a units toggle takes effect).
    const metric = VD.units.system() === "metric";
    const speedUnitLabel = VD.units.speedUnit(); // "km/h" | "mph"
    const effUnitLabel = VD.units.efficiencyUnit(); // "km/kWh" | "mi/kWh"
    const speedToDisplay = (mph: number) => (metric ? mph * KM_PER_MILE : mph);
    const effToDisplay = (miPerKwh: number) => (metric ? miPerKwh * KM_PER_MILE : miPerKwh);
    // Theme-aware colors (CSS vars don't cascade into SVG fill/stroke).
    const tokens = getComputedStyle(document.documentElement);
    const token = (name: string, fallback: string) => (tokens.getPropertyValue(name) || "").trim() || fallback;
    const lineColor = token("--line", "rgba(255,255,255,0.1)");
    const axisColor = token("--muted", "#aaaab4");
    const evColor = token("--ev", "#b8e63b");
    const downColor = token("--map-accent", "#4cc4ff");
    const upColor = token("--bad", "#ff6b5f");
    const w = 320;
    const h = 220;
    const padL = 34;
    const padR = 10;
    const padT = 12;
    const padB = 26;
    const fastest = pool.reduce((m, p) => Math.max(m, p.mph), 0);
    const axisMaxMph = Math.max(75, Math.ceil(fastest / 5) * 5);
    const xOf = (mph: number) => padL + (mph / axisMaxMph) * (w - padL - padR);
    const yS = (e: number) => padT + (1 - e / 7) * (h - padT - padB);
    const gColor = (g: number) => (g <= -0.006 ? downColor : g >= 0.006 ? upColor : evColor);
    const ns = "http://www.w3.org/2000/svg";
    const setSvgAttrs = VD.setSvgAttrs;
    const svg = document.createElementNS(ns, "svg");
    svg.setAttribute("viewBox", `0 0 ${w} ${h}`);
    svg.setAttribute("width", String(w));
    svg.setAttribute("height", String(h));
    svg.setAttribute("class", "trip-detail-scatter-svg");
    svg.setAttribute("role", "img");
    svg.setAttribute(
      "aria-label",
      "Efficiency versus speed for this drive; each dot is colored by road grade — downhill, level, or uphill"
    );
    const appendLine = (attrs: Record<string, string | number>) =>
      svg.append(setSvgAttrs(document.createElementNS(ns, "line"), attrs));
    const appendText = (text: string, attrs: Record<string, string | number>) => {
      const node = setSvgAttrs(document.createElementNS(ns, "text"), attrs);
      node.textContent = text;
      svg.append(node);
    };
    for (let gx = 0; gx <= axisMaxMph; gx += 15) {
      appendLine({ x1: xOf(gx), y1: padT, x2: xOf(gx), y2: h - padB, stroke: lineColor });
      appendText(String(Math.round(speedToDisplay(gx))), { x: xOf(gx), y: h - padB + 14, fill: axisColor, "font-size": 9, "font-family": "ui-monospace,monospace", "text-anchor": "middle" });
    }
    for (let gy = 0; gy <= 7; gy += 1) {
      appendLine({ x1: padL, y1: yS(gy), x2: w - padR, y2: yS(gy), stroke: lineColor });
      appendText(String(Math.round(effToDisplay(gy))), { x: padL - 6, y: yS(gy) + 3, fill: axisColor, "font-size": 9, "font-family": "ui-monospace,monospace", "text-anchor": "end" });
    }
    pool.forEach((p) => {
      svg.append(setSvgAttrs(document.createElementNS(ns, "circle"), {
        cx: xOf(p.mph).toFixed(1),
        cy: yS(p.eff).toFixed(1),
        r: 3.2,
        fill: gColor(p.grade),
        "fill-opacity": 0.55,
      }));
    });
    appendText(`speed (${speedUnitLabel}) ->`, { x: w - padR, y: h - 4, fill: axisColor, "font-size": 9, "font-family": "ui-monospace,monospace", "text-anchor": "end" });
    // Y-axis (efficiency) unit annotation, rotated up the left gutter.
    appendText(effUnitLabel, { x: 9, y: padT + (h - padT - padB) / 2, fill: axisColor, "font-size": 9, "font-family": "ui-monospace,monospace", "text-anchor": "middle", transform: `rotate(-90 9 ${(padT + (h - padT - padB) / 2).toFixed(1)})` });
    return svg;
  }

  function renderTripDetailScatter(route: MapRoute) {
    const chart = el("tripDetailScatter");
    const empty = el("tripDetailScatterEmpty");
    const head = el("tripDetailScatterHead");
    if (!chart) return;
    const svg = buildTripScatter(route);
    if (!svg) {
      chart.replaceChildren();
      if (empty) empty.hidden = false;
      if (head) head.textContent = "Efficiency by speed for this drive.";
      return;
    }
    if (empty) empty.hidden = true;
    chart.replaceChildren(svg);
    if (head) head.textContent = "Grade-coded efficiency at each speed on this drive.";
  }

  // Elevation profile for ONE drive: GPS altitude vs cumulative route distance,
  // drawn as a filled line with a climb/descent headline. Returns null when the
  // drive carries too few altitude fixes (telemetry-fallback routes have none).
  function buildTripElevationProfile(route: MapRoute): { svg: SVGElement; climbM: number; descentM: number } | null {
    // Filter through isValidRoutePoint (as drawMapRoute does) so a (0,0) null-island
    // fix can't inject a ~13,000 km jump into the cumulative distance and squash the
    // whole elevation trace against one edge of the axis.
    const points = (Array.isArray(route.points) ? route.points : []).filter(isValidRoutePoint);
    const profile: Array<{ dist: number; alt: number }> = [];
    let dist = 0;
    let prev: (typeof points)[number] | null = null;
    for (const point of points) {
      if (!point) continue;
      if (prev) dist += haversineMetersJs(prev.lat, prev.lng, point.lat, point.lng);
      prev = point;
      // numOrNaN, not Number(): a JSON null altitude is Number(null) === 0, which is
      // finite and would spike the trace with a phantom sea-level point. Skip it.
      const alt = numOrNaN(point.altM);
      if (Number.isFinite(alt)) profile.push({ dist, alt });
    }
    if (profile.length < 4) return null;
    let altMin = Infinity;
    let altMax = -Infinity;
    let climbM = 0;
    let descentM = 0;
    for (let i = 0; i < profile.length; i += 1) {
      const p = profile[i] as { dist: number; alt: number };
      altMin = Math.min(altMin, p.alt);
      altMax = Math.max(altMax, p.alt);
      if (i > 0) {
        const delta = p.alt - (profile[i - 1] as { alt: number }).alt;
        // Ignore sub-meter jitter between fixes so GPS noise doesn't
        // accumulate into a fictional climb total.
        if (delta > 1) climbM += delta;
        else if (delta < -1) descentM += -delta;
      }
    }
    const totalDist = (profile[profile.length - 1] as { dist: number }).dist;
    if (!(totalDist > 0)) return null;
    // Pad a flat drive so the line doesn't sit on the frame edge.
    if (altMax - altMin < 4) {
      altMin -= 2;
      altMax += 2;
    }
    const metric = VD.units.system() === "metric";
    const altText = (m: number) => (metric ? `${Math.round(m)} m` : `${Math.round(m * 3.28084)} ft`);
    const tokens = getComputedStyle(document.documentElement);
    const token = (name: string, fallback: string) => (tokens.getPropertyValue(name) || "").trim() || fallback;
    const lineColor = token("--line", "rgba(255,255,255,0.1)");
    const axisColor = token("--muted", "#aaaab4");
    const traceColor = token("--map-accent", "#4cc4ff");
    const ns = "http://www.w3.org/2000/svg";
    const setSvgAttrs = VD.setSvgAttrs;
    const w = 320;
    const h = 140;
    const padL = 40;
    const padR = 10;
    const padT = 10;
    const padB = 22;
    const plotW = w - padL - padR;
    const plotH = h - padT - padB;
    const xOf = (d: number) => padL + (d / totalDist) * plotW;
    const yOf = (a: number) => padT + (1 - (a - altMin) / (altMax - altMin)) * plotH;
    const svg = setSvgAttrs(document.createElementNS(ns, "svg"), {
      viewBox: `0 0 ${w} ${h}`,
      class: "trip-detail-elevation-svg",
      role: "img",
      "aria-label": `Elevation profile for this drive: ${altText(climbM)} of climb, ${altText(descentM)} of descent`,
    });
    for (const alt of [altMax, (altMin + altMax) / 2, altMin]) {
      const y = yOf(alt);
      svg.append(setSvgAttrs(document.createElementNS(ns, "line"), {
        x1: padL, x2: w - padR, y1: y.toFixed(1), y2: y.toFixed(1), stroke: lineColor,
      }));
      const label = setSvgAttrs(document.createElementNS(ns, "text"), {
        x: padL - 5, y: (y + 3).toFixed(1), fill: axisColor,
        "font-size": 9, "font-family": "ui-monospace,monospace", "text-anchor": "end",
      });
      label.textContent = altText(alt);
      svg.append(label);
    }
    let d = "";
    for (const p of profile) {
      d += `${d ? "L" : "M"}${xOf(p.dist).toFixed(1)} ${yOf(p.alt).toFixed(1)} `;
    }
    const first = profile[0] as { dist: number };
    const last = profile[profile.length - 1] as { dist: number };
    const baseY = (padT + plotH).toFixed(1);
    svg.append(setSvgAttrs(document.createElementNS(ns, "path"), {
      d: `${d.trim()} L${xOf(last.dist).toFixed(1)} ${baseY} L${xOf(first.dist).toFixed(1)} ${baseY} Z`,
      fill: traceColor,
      "fill-opacity": 0.16,
      stroke: "none",
    }));
    svg.append(setSvgAttrs(document.createElementNS(ns, "path"), {
      d: d.trim(), fill: "none", stroke: traceColor, "stroke-width": 1.6,
    }));
    const distLabel = setSvgAttrs(document.createElementNS(ns, "text"), {
      x: w - padR, y: h - 4, fill: axisColor,
      "font-size": 9, "font-family": "ui-monospace,monospace", "text-anchor": "end",
    });
    distLabel.textContent = `distance (${VD.units.distanceUnit()}) ->`;
    svg.append(distLabel);
    return { svg, climbM, descentM };
  }

  function renderTripDetailElevation(route: MapRoute) {
    const card = el("tripDetailElevationCard");
    const chart = el("tripDetailElevation");
    if (!card || !chart) return;
    const built = buildTripElevationProfile(route);
    if (!built) {
      card.hidden = true;
      chart.replaceChildren();
      return;
    }
    card.hidden = false;
    const metric = VD.units.system() === "metric";
    const altText = (m: number) => (metric ? `${Math.round(m)} m` : `${Math.round(m * 3.28084)} ft`);
    VD.setText("tripDetailElevationHead", `↗ ${altText(built.climbM)} climb · ↘ ${altText(built.descentM)} descent`);
    chart.replaceChildren(built.svg);
  }

  // The trip-list row for a route key (state.trips is the source the list
  // renders from), carrying the rollup-only fields the route projection
  // doesn't: evShare and energyKwh.
  function tripRowForKey(routeKey: string): VoltTrip | null {
    const trips = Array.isArray(state.trips) ? (state.trips as VoltTrip[]) : [];
    return trips.find((trip) => String(trip.id) === String(routeKey)) || null;
  }

  // EV-vs-gas split + integrated HV energy rows in the trip-detail sheet, from
  // the trip rollup. Rows stay hidden when the drive logged no classified
  // driving / no pack power (older drives, GPS-only logging).
  function renderTripDetailEvSplit(routeKey: string): void {
    const evRow = el("tripDetailEvRow");
    const energyRow = el("tripDetailEnergyRow");
    const trip = tripRowForKey(routeKey);
    const evShare = trip && trip.evShare != null ? Number(trip.evShare) : NaN;
    const energyKwh = trip && trip.energyKwh != null ? Number(trip.energyKwh) : NaN;
    if (evRow) evRow.hidden = !Number.isFinite(evShare);
    VD.setText("tripDetailEvShare", Number.isFinite(evShare) ? `${Math.round(evShare * 100)}% electric` : "--");
    if (energyRow) energyRow.hidden = !Number.isFinite(energyKwh);
    VD.setText("tripDetailEnergy", Number.isFinite(energyKwh) ? `${energyKwh.toFixed(1)} kWh` : "--");
  }

  // Estimated electricity cost + savings-vs-gas for ONE drive, shown in the
  // trip-detail sheet. Reuses the SHARED cost model (cost-model.ts) so this
  // per-trip figure and the lifetime Insights figure (insights-panel.ts) use the
  // exact same math — only the distance differs (this drive vs every drive).
  // Mirrors renderSavingsVsGas's three states:
  //   • no logged distance        → cost/savings rows hidden, no note
  //   • distance but prefs unset   → rows hidden, a tap-through prompt to Settings
  //   • distance + all prefs set   → the estimated cost + savings figures + note
  // XSS-safe throughout (textContent / createElement only).
  function renderTripDetailCost(stats: TripDriveStats): void {
    const costRow = el("tripDetailCostRow");
    const savingsRow = el("tripDetailSavingsRow");
    const note = el("tripDetailCostNote");
    const meters = stats.distanceMeters;
    const prefs = VD.prefs;
    const mpg = prefs.get<number>("mpg", 0);
    const gasPrice = prefs.get<number>("gasPricePerGal", 0);
    const pricePerKwh = prefs.get<number>("pricePerKwh", 0);
    const setHidden = (node: HTMLElement | null, hidden: boolean) => {
      if (node) node.hidden = hidden;
    };
    if (!(meters > 0)) {
      // No distance to estimate against — keep the rows out entirely.
      setHidden(costRow, true);
      setHidden(savingsRow, true);
      VD.setText("tripDetailCost", "--");
      VD.setText("tripDetailSavings", "--");
      if (note) note.replaceChildren();
      return;
    }
    if (!savingsPrefsReady(mpg, gasPrice, pricePerKwh)) {
      // Distance exists but the comparison prefs are missing — surface the same
      // tap-through-to-Settings prompt the lifetime savings row uses, rather than
      // silently hiding cost/savings. Built with createElement/textContent only.
      setHidden(costRow, true);
      setHidden(savingsRow, true);
      VD.setText("tripDetailCost", "--");
      VD.setText("tripDetailSavings", "--");
      if (note) {
        const text = document.createTextNode(
          "Set your MPG, gas price, and rate in Settings to estimate this drive's cost. "
        );
        const link = document.createElement("button");
        link.type = "button";
        link.className = "link-btn";
        link.dataset.navJump = "settings";
        link.textContent = "Open Settings";
        note.replaceChildren(text, link);
      }
      return;
    }
    const { evCost, savings } = computeSavingsVsGas({
      meters,
      mpg,
      gasPricePerGal: gasPrice,
      pricePerKwh
    });
    setHidden(costRow, false);
    setHidden(savingsRow, false);
    VD.setText("tripDetailCost", formatSignedMoney(evCost));
    VD.setText("tripDetailSavings", formatSignedMoney(savings));
    if (note) {
      note.replaceChildren(
        document.createTextNode(
          `Estimated vs a ${Math.round(mpg)} mpg car at $${gasPrice.toFixed(2)}/gal · assumes ${ASSUMED_VOLT_MI_PER_KWH} mi/kWh`
        )
      );
    }
  }

  // Route key of the drive the detail sheet is showing; consumed by the
  // "Share card" action so it doesn't need to be threaded through the DOM.
  let tripDetailRouteKey = "";

  // Builds the shareable drive-card payload from the stat strings the sheet is
  // ALREADY showing (units/cost formatting stays single-sourced here in the
  // WebView) and hands it to native, which renders the PNG + share sheet.
  function shareTripCard(): boolean {
    if (!bridge || typeof bridge.shareTripCard !== "function") {
      VD.setStatus({ state: "idle", detail: "Card sharing is only available inside the Android app." });
      return false;
    }
    const routeKey = tripDetailRouteKey;
    if (!routeKey) return false;
    const textOf = (id: string) => {
      const node = el(id);
      const value = node ? String(node.textContent || "").trim() : "";
      return value === "--" ? "" : value;
    };
    const stats: Array<{ label: string; value: string }> = [];
    const push = (label: string, id: string) => {
      const value = textOf(id);
      if (value) stats.push({ label, value });
    };
    push("Distance", "tripDetailDistance");
    push("Duration", "tripDetailDuration");
    push("Avg speed", "tripDetailAvgSpeed");
    push("Efficiency", "tripDetailEfficiency");
    push("Electric", "tripDetailEvShare");
    push("Energy", "tripDetailEnergy");
    // Absolute date for the card (the sheet's relative "2d ago" would go stale
    // the moment the image leaves the phone).
    const route = routeForKey(routeKey);
    const startedAtMs = route ? Number(sessionForRoute(route).startedAtMs) : NaN;
    const subtitle =
      Number.isFinite(startedAtMs) && startedAtMs > 0
        ? new Date(startedAtMs).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" })
        : "";
    const payload = { routeKey, title: textOf("tripDetailTitle") || "Drive", subtitle, stats };
    try {
      // The bridge reports failures synchronously via its {ok:false} envelope,
      // not only via throws — surface those too instead of a silent no-op.
      const result = VD.parsePayload<{ ok?: boolean }>(bridge.shareTripCard(JSON.stringify(payload)), {});
      if (result && result.ok === false) {
        VD.setStatus({ state: "blocked", detail: "Could not share the drive card." });
        return false;
      }
    } catch (_err) {
      VD.setStatus({ state: "blocked", detail: "Could not share the drive card." });
      return false;
    }
    return true;
  }

  // Open the trip-detail sheet for a route key, filling stats + the per-drive
  // scatter. Returns true when a route was found (so actions.ts can decide
  // whether to activate the focus trap).
  function openTripDetail(routeKey: string): boolean {
    const sheet = el("tripDetailSheet");
    if (!sheet) return false;
    const route = routeForKey(routeKey);
    if (!route) return false;
    tripDetailRouteKey = routeKey;
    const session = sessionForRoute(route);
    const stats = tripDriveStats(route);
    const label = typeof session.label === "string" ? session.label.trim() : "";
    const fallback = `${session.mode || "Drive"} · ${session.adapterName || "OBD adapter"}`;
    VD.setText("tripDetailTitle", label || fallback);
    VD.setText("tripDetailSub", label ? fallback : "");
    VD.setText("tripDetailDistance", stats.distanceMeters > 0 ? VD.formatDistance(stats.distanceMeters) : "--");
    VD.setText("tripDetailDuration", stats.durationMs > 0 ? VD.formatDuration(stats.durationMs) : "--");
    const avg = VD.units.speed(stats.avgKph);
    VD.setText("tripDetailAvgLabel", `Avg ${avg.unit}`);
    VD.setText("tripDetailAvgSpeed", avg.value > 0 ? `${avg.value} ${avg.unit}` : "--");
    VD.setText("tripDetailMaxSpeed", stats.maxKph > 0 ? VD.units.speedText(stats.maxKph) : "--");
    VD.setText("tripDetailEfficiency", stats.miPerKwh != null && stats.miPerKwh > 0 ? VD.units.efficiencyText(stats.miPerKwh) : "--");
    renderTripDetailCost(stats);
    VD.setText("tripDetailPoints", stats.pointCount > 0 ? String(stats.pointCount) : "--");
    VD.setText("tripDetailStart", Number.isFinite(stats.startedAtMs) ? VD.formatWhen(stats.startedAtMs) : "--");
    VD.setText("tripDetailEnd", Number.isFinite(stats.endedAtMs) ? VD.formatWhen(stats.endedAtMs) : "--");
    renderTripDetailEvSplit(routeKey);
    renderTripDetailScatter(route);
    renderTripDetailElevation(route);
    // Reveal the sheet here so the function is self-contained; actions.ts then
    // layers the focus trap on top (it re-sets hidden=false too, harmlessly).
    sheet.hidden = false;
    return true;
  }

  function closeTripDetail() {
    const sheet = el("tripDetailSheet");
    if (sheet) sheet.hidden = true;
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

  // Fabricated Los Angeles-area preview geometry, kept as [secondsFromStart, lat, lng]
  // triples. Used only when no Android bridge is present so the dashboard can be reviewed
  // loaded; it is not captured device data and never runs inside the real app.
  const SAMPLE_ROUTE: Array<[number, number, number]> = [[0,34.11872,-118.30064],[25,34.11766,-118.29943],[53,34.11699,-118.29818],[78,34.11532,-118.29755],[105,34.11502,-118.29607],[130,34.11379,-118.29581],[156,34.11331,-118.29444],[185,34.11252,-118.29361],[214,34.11012,-118.29201],[240,34.10983,-118.29046],[267,34.10874,-118.28941],[294,34.10729,-118.28843],[321,34.10664,-118.28671],[349,34.10559,-118.28549],[377,34.10511,-118.28492],[404,34.1033,-118.28202],[429,34.1023,-118.28133],[457,34.1008,-118.27949],[483,34.09991,-118.27795],[508,34.09915,-118.27716],[536,34.09834,-118.27577],[561,34.09684,-118.27385],[590,34.09515,-118.27155],[616,34.09342,-118.271],[642,34.09256,-118.26922],[668,34.09135,-118.26858],[695,34.08982,-118.26784],[720,34.08844,-118.26674],[745,34.08693,-118.26578],[771,34.0843,-118.26341],[796,34.08263,-118.26337],[822,34.0816,-118.26159],[848,34.07927,-118.2616],[873,34.07817,-118.26081],[901,34.07642,-118.25998],[926,34.07477,-118.25933],[951,34.07184,-118.25716],[980,34.07051,-118.25643],[1006,34.06868,-118.25502],[1033,34.06792,-118.25419],[1058,34.06598,-118.25286],[1087,34.06515,-118.25179],[1112,34.06331,-118.25034],[1139,34.06027,-118.2478],[1165,34.05899,-118.24718],[1191,34.05694,-118.24539],[1217,34.05534,-118.24367],[1245,34.05383,-118.24281],[1270,34.05279,-118.24071],[1299,34.05117,-118.23964],[1328,34.04818,-118.23953],[1355,34.0477,-118.24092],[1384,34.04669,-118.24208],[1409,34.04499,-118.24255],[1437,34.04389,-118.24347],[1462,34.04302,-118.24425],[1489,34.04196,-118.24531],[1518,34.03951,-118.24858],[1544,34.03842,-118.25072],[1572,34.03737,-118.25368],[1600,34.03604,-118.25494],[1626,34.0352,-118.25727],[1654,34.03402,-118.25954],[1679,34.03298,-118.26183],[1707,34.03128,-118.26548],[1733,34.03023,-118.26819],[1758,34.02976,-118.2707],[1785,34.02839,-118.27292],[1811,34.02762,-118.2752],[1837,34.02678,-118.27692],[1866,34.02614,-118.27939],[1893,34.02432,-118.28434],[1918,34.02337,-118.28802],[1946,34.02334,-118.29035],[1973,34.022,-118.29332],[2002,34.02129,-118.29641],[2031,34.02046,-118.29924],[2057,34.01996,-118.30215],[2082,34.0191,-118.30761],[2107,34.0194,-118.31139],[2135,34.02004,-118.31402],[2161,34.01943,-118.31713],[2189,34.01987,-118.31994],[2215,34.02041,-118.32298],[2240,34.02081,-118.32606],[2266,34.02166,-118.33312],[2293,34.02148,-118.33598],[2322,34.02206,-118.33889],[2347,34.02288,-118.34169],[2373,34.02263,-118.34537],[2401,34.02381,-118.34841],[2427,34.02384,-118.35127],[2456,34.02433,-118.35766],[2485,34.0248,-118.36111],[2513,34.02565,-118.36468],[2539,34.02532,-118.36794],[2566,34.02551,-118.37083],[2595,34.02644,-118.37385],[2620,34.02654,-118.37771],[2648,34.0269,-118.3805]];

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

    // Elevation: descend from the Griffith Park hills toward flatter west-side
    // streets, then shift by opts.elevShift so each drive feels different.
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
      if (!a || !b || !point) continue;
      const dt = Math.max(1, (b.atMs - a.atMs) / 1000);
      const v = haversineMetersJs(a.lat, a.lng, b.lat, b.lng) / dt;
      const horiz = Math.max(8, haversineMetersJs(a.lat, a.lng, point.lat, point.lng) || 1);
      const dz = Number(point.altM) - Number(a.altM);
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
      distanceMeters, bounds: {}, socTrack, powerTrack
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
    const sampleVehicle = { year: 2017, make: "Chevrolet", model: "Volt", vin: "redacted-demo-vin", odometerMiles: 48213 };
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
        { command: "0105", name: "Coolant temp", valueText: "85°C", parsed: true },
        { command: "221154", name: "Engine oil temperature", valueText: "96°C", parsed: true },
        { command: "225B", name: "Hybrid battery SOC", valueText: "77%", parsed: true },
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
    VD.setDemoActive(true, "Demo / Testing sample drive loaded.");
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
  // insights) from the current state.storage. Shared by loadSampleData and the
  // scenario switcher so a mutated payload paints everywhere consistently.
  function renderDemoSurfaces() {
    VD.updateStorageUi();
    VD.renderRealV2Ui();
    renderMap();
    if (typeof VD.renderInsightStats === "function") VD.renderInsightStats();
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
      // Trips feed the map's stub rows, so the empty scenario must clear them
      // too or leftover real trips would repopulate the demo map.
      state.trips = [];
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
    renderMapLoaded: true,
    ensureMap,
    renderMap,
    drawMapRoute,
    setMapTileError,
    retryMapTiles,
    renderMapSessionList,
    refreshMapSessionList,
    openTripDetail,
    closeTripDetail,
    shareTripCard,
    selectedMapRoute,
    sessionForRoute,
    haversineMetersJs,
    routeDistanceMeters,
    segmentSpeedMps,
    detectStops,
    addStop,
    updateLivePosition,
    clearLivePosition,
    setLiveRoutePoints,
    setMapFollowLive,
    loadSampleData,
    loadDemoScenario,
    setTripRoute: applyTripRoutePayload
  });

export {};
