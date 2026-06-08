// insights-panel.ts — the Trips tab (logged-drive list, selection, per-trip
// route preview + Leaflet mini-maps) and the Insights tab stats + the
// efficiency-vs-speed scatter.
//
// Split out of the old panels.ts god-module (C2). Render entry points are
// attached to the shared VD global exactly as before. The native-read-error
// helpers (isNativeError / reportNativeReadError) and the toggleHidden helper
// are owned by storage-status.ts and read off VD here.
(function () {
  "use strict";

  const VD = window.VoltDashboard;
  const state = VD.state;
  const bridge = VD.bridge;
  const el = VD.el;

  function loadTrips() {
    if (bridge && typeof bridge.getTrips === "function") {
      const parsed = VD.parsePayload<VoltTrip[]>(bridge.getTrips(), []);
      if (VD.isNativeError(parsed)) {
        const err = parsed as VoltNativeError;
        VD.reportNativeReadError(parsed, "Could not read logged trips.");
        state.tripsReadError = err.message || "Could not read logged trips.";
        state.trips = [];
      } else if (state.demoActive && Array.isArray(state.demoPreviewTrips)) {
        // Park real trips behind the demo preview (cross-module demo invariant).
        VD.setState({ realTrips: Array.isArray(parsed) ? parsed : [] });
      } else {
        state.tripsReadError = null;
        state.trips = Array.isArray(parsed) ? parsed : [];
      }
    }
    renderRealTrips();
  }

  function renderRealTrips() {
    // No demo guard: demo only simulates live numbers, so the Trips tab keeps rendering the user's
    // real logged drives (or the empty state) exactly as it would outside demo.
    const trips = Array.isArray(state.trips) ? state.trips : [];
    renderTripsEmptyState();
    VD.toggleHidden("realTripsCard", trips.length === 0);
    VD.toggleHidden("tripsEmptyState", trips.length > 0);
    VD.toggleHidden("realTripDetailGrid", trips.length === 0);
    if (trips.length && !trips.some((trip) => String(trip.id) === String(state.selectedRealTripId || ""))) {
      const withRoute = trips.find((trip) => trip.hasRoute);
      state.selectedRealTripId = String((withRoute || trips[0]).id || "");
    }
    const list = el("realTripsList");
    const renderKey = realTripsRenderKey(trips);
    const listChanged = !list || list.dataset.renderKey !== renderKey;
    if (listChanged && list) {
      list.dataset.renderKey = renderKey;
      list.replaceChildren(...trips.map(renderTripRow));
    } else {
      updateRealTripSelection();
    }
    renderRealTripDetail();
    queueRenderRealTripMaps({ detailOnly: !(listChanged || needsMiniMapUpgrade(list)) });
    VD.setText("realTripsTitle", trips.length
      ? `${trips.length} logged ${trips.length === 1 ? "drive" : "drives"}`
      : "Your trips");
  }

  function renderTripsEmptyState() {
    const empty = el("tripsEmptyState");
    if (!empty) return;
    const title = empty.querySelector("h2");
    const copy = empty.querySelector("p");
    const existingRetry = empty.querySelector("[data-retry-trips]");
    if (!state.tripsReadError) {
      if (title) title.textContent = "No real trips stored yet.";
      if (copy) {
        copy.textContent =
          "Trip capture will come after the OBD bridge is stable. This view will stay empty until real sessions are materialized from stored GPS and PID samples.";
      }
      existingRetry?.remove();
      return;
    }
    if (title) title.textContent = "Trips could not load.";
    if (copy) copy.textContent = state.tripsReadError;
    if (!existingRetry) {
      const retry = document.createElement("button");
      retry.type = "button";
      retry.className = "link-btn";
      retry.dataset.retryTrips = "true";
      retry.textContent = "Retry";
      retry.addEventListener("click", () => loadTrips());
      empty.append(retry);
    }
  }

  function realTripsRenderKey(trips: VoltTrip[]) {
    return trips.map((trip) => [
      trip.id,
      trip.hasRoute ? "route" : "samples",
      trip.pointCount || 0,
      trip.sampleCount || 0,
      trip.startedAtMs || 0,
      trip.endedAtMs || 0,
      trip.durationMs || 0,
      trip.distanceMeters || 0,
      trip.avgMovingSpeedKph || 0,
      trip.status || "",
      trip.adapterName || ""
    ].join(":")).join("|");
  }

  function needsMiniMapUpgrade(list: HTMLElement | null) {
    if (!list) return false;
    return Array.prototype.some.call(
      list.querySelectorAll("[data-real-trip-map-role='mini']"),
      (slot: Element) => !slot.querySelector(".leaflet-container")
    );
  }

  function renderTripRow(trip: VoltTrip) {
    const distance = VD.formatDistance(Number(trip.distanceMeters || 0));
    const duration = Number(trip.durationMs) > 0 ? VD.formatDuration(Number(trip.durationMs)) : null;
    const topMph = trip.maxSpeedKph ? Math.round(Number(trip.maxSpeedKph) * 0.621371) : 0;
    const meta = [distance !== "--" ? distance : null, duration, topMph ? `top ${topMph} mph` : null]
      .filter(Boolean).join(" · ") || "no movement logged";
    const whenLabel = VD.formatWhen(trip.startedAtMs);
    const button = document.createElement("button");
    button.type = "button";
    button.className = "map-drive-chip real-trip-chip";
    button.dataset.realTripId = String(trip.id);
    button.setAttribute("aria-label", `Open trip from ${whenLabel} — ${meta}`);
    button.classList.toggle("is-active", String(trip.id) === String(state.selectedRealTripId || ""));
    if (trip.hasRoute) button.classList.add("has-route-preview");
    const center = document.createElement("span");
    center.className = "real-trip-chip-copy";
    const strong = document.createElement("strong");
    strong.textContent = whenLabel;
    const small = document.createElement("small");
    small.textContent = meta;
    center.append(strong, small);
    const right = document.createElement("b");
    right.className = "trip-route-state";
    // Clear, labelled badge instead of the cryptic "660x": GPS trips show their point count,
    // routeless trips show the raw sample count. The route itself renders in the large preview
    // below, so the chip stays compact (no empty in-chip mini-map).
    right.textContent = trip.hasRoute
      ? `${Number(trip.pointCount || 0) || "—"} pts`
      : `${Number(trip.sampleCount || 0).toLocaleString()} samples`;
    button.append(center, right);
    // Wrap in a listitem so #realTripsList (role="list") has valid listitem
    // children without overriding the chip's native button role. The wrapper is
    // display:contents (components.css) so layout is unchanged; click delegation
    // uses closest("[data-real-trip-id]") which still resolves to the button.
    const item = document.createElement("div");
    item.className = "list-row-item";
    item.setAttribute("role", "listitem");
    item.appendChild(button);
    return item;
  }

  function selectRealTrip(id: string | number) {
    state.selectedRealTripId = String(id || "");
    updateRealTripSelection();
    renderRealTripDetail();
    queueRenderRealTripMaps({ detailOnly: true });
  }

  function updateRealTripSelection() {
    document.querySelectorAll<HTMLElement>("[data-real-trip-id]").forEach((button) => {
      button.classList.toggle("is-active", String(button.dataset.realTripId || "") === String(state.selectedRealTripId || ""));
    });
  }

  // Routes loaded on demand (per selected trip) for drives outside the storage summary's
  // recent-routes window. Successful routes are cached; misses are retried on future renders so a
  // route that arrives after a storage refresh is not hidden forever.
  const onDemandRoutes = new Map<string, VoltRoute>();

  function routeForTrip(trip: VoltTrip): VoltRoute | null {
    const routes =
      state.storage && Array.isArray(state.storage.recentRoutes)
        ? state.storage.recentRoutes
        : [];
    const id = tripRouteKey(trip);
    const fromRecent = routes.find(
      (route) => String((route.session || {}).id || "") === id
    );
    if (fromRecent) return fromRecent;
    return onDemandRoutes.get(id) || null;
  }

  function tripRouteKey(trip: VoltTrip | null) {
    return String((trip && (trip.routeId || trip.id || trip.sessionId)) || "");
  }

  // Ensures the selected trip's route geometry is available, fetching it from the native bridge
  // the first time a drive outside the recent-routes window is opened. The storage summary only
  // ships the most recent few routes for payload size; this lets ANY logged drive (e.g. one folded
  // in from a merged backup) preview its route. Returns the route payload or null.
  function ensureRouteForTrip(trip: VoltTrip): VoltRoute | null {
    if (!trip || !trip.hasRoute) return null;
    const id = tripRouteKey(trip);
    const cached = routeForTrip(trip);
    if (cached) return cached;
    if (!(bridge && typeof bridge.getTripRoute === "function")) return null;
    let route: VoltRoute | null = null;
    try {
      const payload = VD.parsePayload<VoltRoute>(bridge.getTripRoute(id), null);
      if (payload && Array.isArray(payload.points) && payload.points.length >= 2) {
        route = payload;
        if (route.session && !route.session.id) route.session.id = id;
      }
    } catch (_err) {
      route = null;
    }
    if (route) onDemandRoutes.set(id, route);
    return route;
  }

  function buildTripRouteSpark(route: VoltRoute) {
    const points = ((route && route.points) || [])
      .map((point) => ({ lat: Number(point.lat), lng: Number(point.lng) }))
      .filter((point) => Number.isFinite(point.lat) && Number.isFinite(point.lng));
    if (points.length < 2) return document.createTextNode("");
    const ns = "http://www.w3.org/2000/svg";
    const svgNode = document.createElementNS(ns, "svg");
    svgNode.setAttribute("class", "trip-route-spark");
    svgNode.setAttribute("viewBox", "0 0 72 38");
    svgNode.setAttribute("aria-hidden", "true");
    const minLat = Math.min.apply(null, points.map((point) => point.lat));
    const maxLat = Math.max.apply(null, points.map((point) => point.lat));
    const minLng = Math.min.apply(null, points.map((point) => point.lng));
    const maxLng = Math.max.apply(null, points.map((point) => point.lng));
    const spanLat = maxLat - minLat || 1;
    const spanLng = maxLng - minLng || 1;
    const coords = points.map((point) => {
      const x = 6 + ((point.lng - minLng) / spanLng) * 60;
      const y = 6 + (1 - (point.lat - minLat) / spanLat) * 26;
      return x.toFixed(1) + "," + y.toFixed(1);
    }).join(" ");
    const halo = document.createElementNS(ns, "polyline");
    halo.setAttribute("points", coords);
    halo.setAttribute("class", "trip-route-spark-halo");
    const line = document.createElementNS(ns, "polyline");
    line.setAttribute("points", coords);
    line.setAttribute("class", "trip-route-spark-line");
    const start = document.createElementNS(ns, "circle");
    start.setAttribute("cx", coords.split(" ")[0].split(",")[0]);
    start.setAttribute("cy", coords.split(" ")[0].split(",")[1]);
    start.setAttribute("r", "2.6");
    start.setAttribute("class", "trip-route-spark-start");
    const endPair = (coords.split(" ").pop() || "").split(",");
    const end = document.createElementNS(ns, "circle");
    end.setAttribute("cx", endPair[0]);
    end.setAttribute("cy", endPair[1]);
    end.setAttribute("r", "3");
    end.setAttribute("class", "trip-route-spark-end");
    svgNode.append(halo, line, start, end);
    return svgNode;
  }

  // Context-aware empty state for the route preview. A drive that recorded OBD samples but has no
  // GPS track almost always means location was off during the drive — so say that plainly and, if
  // the permission is still off, offer to enable it so future drives map.
  function buildRouteEmptyState(trip: VoltTrip) {
    const box = document.createElement("div");
    box.className = "real-route-empty route-empty-rich";
    const sampleCount = Number((trip && trip.sampleCount) || 0);
    const locationGranted = !!(((state.appState || {}).permissions || {}).location);
    const title = document.createElement("strong");
    title.className = "route-empty-title";
    const sub = document.createElement("span");
    sub.className = "route-empty-sub";
    if (sampleCount > 0) {
      title.textContent = "No GPS recorded for this drive";
      sub.textContent = locationGranted
        ? "This drive logged OBD data but never got a GPS fix."
        : "Location was off, so no route could be mapped.";
    } else {
      title.textContent = "No route shape stored";
      sub.textContent = "This drive has no stored GPS track.";
    }
    box.append(title, sub);
    if (!locationGranted && bridge && typeof bridge.requestPermissions === "function") {
      const cta = document.createElement("button");
      cta.type = "button";
      cta.className = "route-empty-cta";
      cta.textContent = "Enable location";
      cta.addEventListener("click", () => {
        try {
          bridge.requestPermissions();
        } catch (_err) {
          /* bridge may be unavailable outside the app */
        }
      });
      box.append(cta);
    }
    return box;
  }

  function buildTripMapSlot(route: VoltRoute, role: string, tripId: string | number | undefined) {
    const slot = document.createElement("span");
    slot.className = role === "detail" ? "real-route-map" : "trip-route-map";
    slot.dataset.realTripMap = String(tripId || (route && route.session && route.session.id) || "");
    slot.dataset.realTripMapRole = role;
    slot.appendChild(role === "detail" ? buildTripRoutePreview(route) : buildTripRouteSpark(route));
    return slot;
  }

  function buildTripRoutePreview(route: VoltRoute) {
    const points = ((route && route.points) || [])
      .map((point) => ({ lat: Number(point.lat), lng: Number(point.lng) }))
      .filter((point) => Number.isFinite(point.lat) && Number.isFinite(point.lng));
    const box = document.createElement("div");
    box.className = "real-route-empty";
    if (points.length < 2) {
      box.textContent = "No route shape stored";
      return box;
    }
    const ns = "http://www.w3.org/2000/svg";
    const svgNode = document.createElementNS(ns, "svg");
    svgNode.setAttribute("class", "real-route-svg");
    svgNode.setAttribute("viewBox", "0 0 320 150");
    svgNode.setAttribute("aria-hidden", "true");
    const minLat = Math.min.apply(null, points.map((point) => point.lat));
    const maxLat = Math.max.apply(null, points.map((point) => point.lat));
    const minLng = Math.min.apply(null, points.map((point) => point.lng));
    const maxLng = Math.max.apply(null, points.map((point) => point.lng));
    const spanLat = maxLat - minLat || 1;
    const spanLng = maxLng - minLng || 1;
    const coords = points.map((point) => {
      const x = 22 + ((point.lng - minLng) / spanLng) * 276;
      const y = 18 + (1 - (point.lat - minLat) / spanLat) * 112;
      return x.toFixed(1) + "," + y.toFixed(1);
    }).join(" ");
    const halo = document.createElementNS(ns, "polyline");
    halo.setAttribute("points", coords);
    halo.setAttribute("class", "real-route-halo");
    const line = document.createElementNS(ns, "polyline");
    line.setAttribute("points", coords);
    line.setAttribute("class", "real-route-line");
    const startPair = coords.split(" ")[0].split(",");
    const endPair = (coords.split(" ").pop() || "").split(",");
    const start = document.createElementNS(ns, "circle");
    start.setAttribute("cx", startPair[0]);
    start.setAttribute("cy", startPair[1]);
    start.setAttribute("r", "5");
    start.setAttribute("class", "real-route-start");
    const end = document.createElementNS(ns, "circle");
    end.setAttribute("cx", endPair[0]);
    end.setAttribute("cy", endPair[1]);
    end.setAttribute("r", "5.5");
    end.setAttribute("class", "real-route-end");
    svgNode.append(halo, line, start, end);
    return svgNode;
  }

  function buildEnergyRow(label: string, value: string, pct: number, color: string) {
    const row = document.createElement("div");
    const span = document.createElement("span");
    span.textContent = label;
    const bar = document.createElement("i");
    // 0% means "no value" — render an empty track, not a sliver, so a "--" metric reads as blank.
    bar.style.width = Math.max(0, Math.min(100, Number(pct) || 0)) + "%";
    if (color) bar.style.background = color;
    const strong = document.createElement("b");
    strong.textContent = value;
    row.append(span, bar, strong);
    return row;
  }

  function renderRealTripDetail() {
    const trips = Array.isArray(state.trips) ? state.trips : [];
    const trip =
      trips.find((item) => String(item.id) === String(state.selectedRealTripId || "")) ||
      trips[0];
    const detail = el("realTripDetailGrid");
    if (!detail || !trip) return;
    detail.hidden = false;
    const route = ensureRouteForTrip(trip);
    const distance = VD.formatDistance(Number(trip.distanceMeters || 0));
    const duration = Number(trip.durationMs) > 0 ? VD.formatDuration(Number(trip.durationMs)) : "--";
    const topMph = trip.maxSpeedKph ? Math.round(Number(trip.maxSpeedKph) * 0.621371) : 0;
    const avgMph = trip.avgMovingSpeedKph ? Math.round(Number(trip.avgMovingSpeedKph) * 0.621371) : 0;
    VD.setText("realTripRouteTitle", VD.formatWhen(trip.startedAtMs));
    VD.setText(
      "realTripRouteMeta",
      [distance !== "--" ? distance : null, duration !== "--" ? duration : null, topMph ? `top ${topMph} mph` : null]
        .filter(Boolean)
        .join(" · ") || "stored drive"
    );
    // Use the resolved route (after the on-demand fetch), not just trip.hasRoute — a drive can
    // claim a route in its rollup yet have no geometry available.
    const hasRouteGeometry = !!(route && Array.isArray(route.points) && route.points.length >= 2);
    const mapBtn = el("realTripMapBtn") as HTMLButtonElement | null;
    if (mapBtn) {
      mapBtn.dataset.tripMap = String(trip.id || "");
      mapBtn.disabled = !hasRouteGeometry;
      mapBtn.textContent = hasRouteGeometry ? "Open map" : "No route";
    }
    const routeBox = el("realTripRouteBox");
    if (routeBox) {
      const nextTripMap = hasRouteGeometry ? String(trip.id || "") : "";
      const hasCurrentMap = routeBox.dataset.tripMap === nextTripMap &&
        routeBox.querySelector("[data-real-trip-map-role='detail']");
      routeBox.dataset.tripMap = nextTripMap;
      routeBox.setAttribute("role", hasRouteGeometry ? "button" : "presentation");
      routeBox.setAttribute("aria-label", hasRouteGeometry ? "Open selected trip on map" : "No route map available");
      if (hasRouteGeometry && route) {
        if (!hasCurrentMap) routeBox.replaceChildren(buildTripMapSlot(route, "detail", trip.id));
      } else {
        // Explain WHY there's no route (and point at the fix) instead of a bare "no route".
        routeBox.replaceChildren(buildRouteEmptyState(trip));
      }
    }
    const effPts = route && Array.isArray(route.points)
      ? route.points.map((point) => Number(point.eff)).filter(Number.isFinite)
      : [];
    const avgEff = effPts.length
      ? effPts.reduce((sum, value) => sum + value, 0) / effPts.length
      : 0;
    VD.setText("realTripEnergyTitle", avgEff ? `${avgEff.toFixed(1)} mi/kWh` : (avgMph ? `${avgMph} mph avg` : "Stored drive"));
    const rows = el("realTripEnergyRows");
    if (rows) {
      // Bars are proportional to real values, scaled against the user's other logged drives so the
      // longest/busiest trip reads as full. A metric with no value renders no bar (width 0) rather
      // than a misleading full bar next to a "--".
      const allTrips = Array.isArray(state.trips) ? state.trips : [];
      const maxOf = (key: keyof VoltTrip) =>
        allTrips.reduce((m, t) => Math.max(m, Number(t[key]) || 0), 0);
      const pctOf = (value: number, max: number) =>
        max > 0 && Number(value) > 0 ? Math.round((Number(value) / max) * 100) : 0;
      const distMeters = Number(trip.distanceMeters || 0);
      const durMs = Number(trip.durationMs || 0);
      const sampleCount = Number(trip.sampleCount || 0);
      rows.replaceChildren(
        buildEnergyRow("Distance", distance, pctOf(distMeters, maxOf("distanceMeters")), "var(--volt)"),
        buildEnergyRow("Duration", duration, pctOf(durMs, maxOf("durationMs")), "#a4b8ff"),
        buildEnergyRow("Samples", sampleCount.toLocaleString(), pctOf(sampleCount, maxOf("sampleCount")), "rgba(255, 255, 255, 0.32)"),
        // Efficiency scaled against a fixed 5.0 mi/kWh ceiling (a strong EV result), so the bar is
        // comparable run-to-run rather than relative to a single trip.
        buildEnergyRow("Efficiency", avgEff ? `${avgEff.toFixed(1)} mi/kWh` : "--", avgEff ? Math.min(100, Math.round((avgEff / 5) * 100)) : 0, "var(--ok)")
      );
    }
  }

  const realTripMaps = new Map<HTMLElement, LeafletMap>();
  let realTripMapTimer = 0;

  function clearRealTripMaps() {
    realTripMaps.forEach((map) => {
      try { map.remove(); } catch (_err) {}
    });
    realTripMaps.clear();
  }

  function queueRenderRealTripMaps(options: { detailOnly?: boolean }) {
    clearTimeout(realTripMapTimer);
    const detailOnly = Boolean(options && options.detailOnly);
    realTripMapTimer = setTimeout(() => renderRealTripLeafletMaps({ detailOnly }), 80);
  }

  function renderRealTripLeafletMaps(options: { detailOnly?: boolean }) {
    if (typeof L === "undefined") return;
    const detailOnly = Boolean(options && options.detailOnly);
    if (!detailOnly) {
      clearRealTripMaps();
    } else {
      const currentDetailId = (el("realTripRouteBox") as HTMLElement | null)?.dataset.tripMap || "";
      realTripMaps.forEach((map, slot) => {
        if (
          slot.dataset.realTripMapRole === "detail" &&
          (!slot.isConnected || String(slot.dataset.realTripMap || "") !== String(currentDetailId))
        ) {
          try { map.remove(); } catch (_err) {}
          realTripMaps.delete(slot);
        }
      });
    }
    document.querySelectorAll<HTMLElement>("[data-real-trip-map]").forEach((slot) => {
      if (detailOnly && slot.dataset.realTripMapRole !== "detail") return;
      if (realTripMaps.has(slot)) {
        try { realTripMaps.get(slot)!.invalidateSize(false); } catch (_err) {}
        return;
      }
      if (slot.querySelector(".leaflet-container")) return;
      const id = slot.dataset.realTripMap;
      const role = slot.dataset.realTripMapRole || "mini";
      const route = routeForTrip({ id });
      const points = ((route && route.points) || [])
        .map((point) => [Number(point.lat), Number(point.lng)])
        .filter((pair) => Number.isFinite(pair[0]) && Number.isFinite(pair[1]));
      const rect = slot.getBoundingClientRect();
      if (points.length < 2 || rect.width < 24 || rect.height < 24) return;
      slot.replaceChildren();
      const map = L.map(slot, {
        attributionControl: false,
        boxZoom: false,
        dragging: false,
        doubleClickZoom: false,
        keyboard: false,
        scrollWheelZoom: false,
        tap: false,
        touchZoom: false,
        zoomControl: false
      });
      L.tileLayer("https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png", {
        subdomains: "abcd",
        maxZoom: 19
      }).addTo(map);
      L.polyline(points, {
        color: "rgba(255, 255, 255, 0.64)",
        weight: role === "detail" ? 9 : 6,
        opacity: 0.64,
        lineCap: "round",
        lineJoin: "round"
      }).addTo(map);
      L.polyline(points, {
        color: "#ff7a45",
        weight: role === "detail" ? 5 : 3,
        opacity: 0.95,
        lineCap: "round",
        lineJoin: "round"
      }).addTo(map);
      L.circleMarker(points[0], {
        radius: role === "detail" ? 5.5 : 3.6,
        color: "#fff",
        weight: role === "detail" ? 2 : 1.4,
        fillColor: "#ff7a45",
        fillOpacity: 1
      }).addTo(map);
      L.circleMarker(points[points.length - 1], {
        radius: role === "detail" ? 6 : 3.8,
        color: "#fff",
        weight: role === "detail" ? 2 : 1.4,
        fillColor: "#b8e63b",
        fillOpacity: 1
      }).addTo(map);
      map.fitBounds(L.latLngBounds(points), {
        animate: false,
        padding: role === "detail" ? [18, 18] : [8, 8]
      });
      setTimeout(() => map.invalidateSize(false), 40);
      realTripMaps.set(slot, map);
    });
  }

  function loadInsights() {
    if (bridge && typeof bridge.getInsights === "function") {
      const parsed = VD.parsePayload<VoltInsights>(bridge.getInsights(), {});
      if (VD.isNativeError(parsed)) {
        const err = parsed as VoltNativeError;
        VD.reportNativeReadError(parsed, "Could not read vehicle insights.");
        state.insightsReadError = err.message || "Could not read vehicle insights.";
        state.insights = {};
      } else if (state.demoActive && state.demoPreviewInsights) {
        // Park real insights behind the demo preview (cross-module demo invariant).
        VD.setState({ realInsights: parsed });
      } else {
        state.insightsReadError = null;
        state.insights = parsed;
      }
    }
    renderInsightStats();
    renderInsightScatter();
  }

  function renderInsightStats() {
    const insights = state.insights || {};
    const trips = Number(insights.tripCount || 0);
    renderInsightsEmptyState();
    VD.setText("insightTripCount", trips || "--");
    VD.setText("insightTotalDistance", trips ? VD.formatDistance(Number(insights.totalDistanceMeters || 0)) : "--");
    VD.setText("insightDriveTime", Number(insights.totalDriveMs) > 0 ? VD.formatDuration(Number(insights.totalDriveMs)) : "--");
    VD.setText("insightTopSpeed", insights.maxSpeedKph ? `${Math.round(Number(insights.maxSpeedKph) * 0.621371)} mph` : "--");
    VD.setText("insightLongest", Number(insights.longestTripMeters) > 0 ? VD.formatDistance(Number(insights.longestTripMeters)) : "--");
    VD.setText("insightGpsTrips", trips ? `${Number(insights.gpsTripCount || 0)}/${trips}` : "--");
  }

  function renderInsightsEmptyState() {
    const empty = el("insightsEmptyState");
    if (!empty) return;
    const title = empty.querySelector("h2");
    const copy = empty.querySelector("p");
    const hints = empty.querySelector(".empty-hints") as HTMLElement | null;
    const existingRetry = empty.querySelector("[data-retry-insights]");
    if (!state.insightsReadError) {
      if (title) title.textContent = "Not enough data yet.";
      if (copy) {
        copy.textContent =
          "Pack health, savings, and monthly insights appear here once you've logged a few drives with the adapter connected.";
      }
      if (hints) hints.hidden = false;
      existingRetry?.remove();
      return;
    }
    if (title) title.textContent = "Insights could not load.";
    if (copy) copy.textContent = state.insightsReadError;
    if (hints) hints.hidden = true;
    if (!existingRetry) {
      const retry = document.createElement("button");
      retry.type = "button";
      retry.className = "link-btn";
      retry.dataset.retryInsights = "true";
      retry.textContent = "Retry";
      retry.addEventListener("click", () => loadInsights());
      empty.append(retry);
    }
  }

  // ----- Efficiency vs Speed scatter (Insights tab) -------------------------
  // Pools per-point efficiency from every recent route. Efficiency is derived
  // by time-joining the route's `powerTrack` onto its points and computing
  // mi/kWh with a +/-8-sample window. The card stays hidden until enough
  // samples carry derived eff (which depends on the OBD loop having captured
  // battery current via the Volt 7E1 PIDs).

  const haversineMetersJsLocal = (lat1: number, lng1: number, lat2: number, lng2: number) => {
    const r = 6371000;
    const dLat = ((lat2 - lat1) * Math.PI) / 180;
    const dLng = ((lng2 - lng1) * Math.PI) / 180;
    const a =
      Math.sin(dLat / 2) ** 2 +
      Math.cos((lat1 * Math.PI) / 180) *
        Math.cos((lat2 * Math.PI) / 180) *
        Math.sin(dLng / 2) ** 2;
    return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  };

  function enrichRouteEff(route: VoltRoute) {
    if (!route || route._effDone) return;
    const pts = route.points || [];
    const track = (route.powerTrack || []).filter((s: PowerTrackSample) =>
      Number.isFinite(Number(s.powerKw))
    );
    if (pts.length < 2 || track.length < 2) return;
    route._effDone = true;
    const powerAt = (atMs: number) => {
      if (atMs <= track[0].atMs) return Number(track[0].powerKw);
      const last = track[track.length - 1];
      if (atMs >= last.atMs) return Number(last.powerKw);
      for (let i = 1; i < track.length; i += 1) {
        if (track[i].atMs >= atMs) {
          const a = track[i - 1];
          const b = track[i];
          const t = (atMs - a.atMs) / ((b.atMs - a.atMs) || 1);
          return Number(a.powerKw) + (Number(b.powerKw) - Number(a.powerKw)) * t;
        }
      }
      return Number(last.powerKw);
    };
    const mphArr = pts.map((p, i) => {
      let mps = Number(p.speedMps);
      if (!Number.isFinite(mps) || mps < 0) {
        const a = pts[Math.max(0, i - 1)];
        const b = pts[Math.min(pts.length - 1, i + 1)];
        const dt = Math.max(1, (Number(b.atMs) - Number(a.atMs)) / 1000);
        mps = haversineMetersJsLocal(a.lat, a.lng, b.lat, b.lng) / dt;
      }
      return Math.max(0, mps) * 2.2369363;
    });
    // Drop regen samples (kW < 0) from the per-point efficiency average. Including them with
    // the old `Math.max(60, s/c)` clamp folded every regen-dominant segment into the same
    // upper-bound green color band as a high-efficiency cruise — visually identical and
    // misleading. Tagging them as null instead leaves the regen segments grey (the
    // mapEffColor `eff == null` branch in map.js), making downhill / regen runs visibly
    // distinct from drive efficiency.
    const whmiInst = pts.map((p, i) => {
      if (mphArr[i] <= 4) return NaN;
      const kW = powerAt(Number(p.atMs));
      if (!Number.isFinite(kW)) return NaN;
      if (kW <= 0) return NaN;
      return (kW * 1000) / mphArr[i];
    });
    for (let i = 0; i < pts.length; i += 1) {
      let s = 0;
      let c = 0;
      for (let k = -8; k <= 8; k += 1) {
        const j = i + k;
        if (j >= 0 && j < pts.length && Number.isFinite(whmiInst[j])) {
          s += whmiInst[j];
          c += 1;
        }
      }
      if (!c) {
        pts[i].eff = null;
        continue;
      }
      const whmi = s / c;
      pts[i].eff = Math.max(0.8, Math.min(6.5, 1000 / whmi));
    }
  }

  function renderInsightScatter() {
    const card = el("effScatterCard");
    const chart = el("effScatter");
    const head = el("effScatterHead");
    const statsEl = el("effScatterStats");
    if (!card || !chart) return;
    const routes =
      state.storage && Array.isArray(state.storage.recentRoutes)
        ? state.storage.recentRoutes
        : [];
    const pool: Array<{ mph: number; eff: number; grade: number }> = [];
    routes.forEach((route) => {
      enrichRouteEff(route);
      const pts = (route && route.points) || [];
      for (let i = 0; i < pts.length; i += 1) {
        const eff = Number(pts[i].eff);
        if (!Number.isFinite(eff)) continue;
        let mps = Number(pts[i].speedMps);
        if (!Number.isFinite(mps) || mps < 0) {
          const a = pts[Math.max(0, i - 1)];
          const b = pts[Math.min(pts.length - 1, i + 1)];
          const dt = Math.max(1, (Number(b.atMs) - Number(a.atMs)) / 1000);
          mps = haversineMetersJsLocal(a.lat, a.lng, b.lat, b.lng) / dt;
        }
        const mph = Math.max(0, mps) * 2.2369363;
        if (mph < 10) continue;
        let grade = 0;
        if (
          i > 0 &&
          Number.isFinite(Number(pts[i - 1].altM)) &&
          Number.isFinite(Number(pts[i].altM))
        ) {
          const horiz = Math.max(
            8,
            haversineMetersJsLocal(
              pts[i - 1].lat,
              pts[i - 1].lng,
              pts[i].lat,
              pts[i].lng
            )
          );
          grade = Math.max(
            -0.13,
            Math.min(0.13, (Number(pts[i].altM) - Number(pts[i - 1].altM)) / horiz)
          );
        }
        pool.push({ mph, eff, grade });
      }
    });
    if (pool.length < 6) {
      card.hidden = true;
      return;
    }
    card.hidden = false;
    const w = Math.max(300, chart.clientWidth || 360);
    const h = 280;
    const padL = 38;
    const padR = 12;
    const padT = 14;
    const padB = 28;
    const xOf = (mph: number) => padL + (mph / 75) * (w - padL - padR);
    const yS = (e: number) => padT + (1 - e / 7) * (h - padT - padB);
    const gColor = (g: number) =>
      g <= -0.006 ? "#5cc8ff" : g >= 0.006 ? "#ff6b5f" : "#b8e63b";
    let inner = "";
    for (let gx = 0; gx <= 75; gx += 15) {
      inner +=
        `<line x1="${xOf(gx)}" y1="${padT}" x2="${xOf(gx)}" y2="${h - padB}" stroke="rgba(255,255,255,0.06)"/>` +
        `<text x="${xOf(gx)}" y="${h - padB + 15}" fill="#8b8c99" font-size="9" font-family="ui-monospace,monospace" text-anchor="middle">${gx}</text>`;
    }
    for (let gy = 0; gy <= 7; gy += 1) {
      inner +=
        `<line x1="${padL}" y1="${yS(gy)}" x2="${w - padR}" y2="${yS(gy)}" stroke="rgba(255,255,255,0.06)"/>` +
        `<text x="${padL - 6}" y="${yS(gy) + 3}" fill="#8b8c99" font-size="9" font-family="ui-monospace,monospace" text-anchor="end">${gy}</text>`;
    }
    const bins: number[][] = [];
    pool.forEach((p) => {
      inner += `<circle cx="${xOf(p.mph).toFixed(1)}" cy="${yS(p.eff).toFixed(1)}" r="3.2" fill="${gColor(p.grade)}" fill-opacity="0.5"/>`;
      const b = Math.floor(p.mph / 10);
      (bins[b] = bins[b] || []).push(p.eff);
    });
    let trend = "";
    let best = { e: 0, mph: 0 };
    let started = false;
    bins.forEach((arr, b) => {
      if (!arr || arr.length < 3) return;
      const mph = b * 10 + 5;
      const e = arr.reduce((s, x) => s + x, 0) / arr.length;
      trend += `${started ? "L" : "M"}${xOf(mph).toFixed(1)} ${yS(e).toFixed(1)} `;
      started = true;
      if (e > best.e) best = { e: e, mph: mph };
    });
    inner +=
      `<path d="${trend}" fill="none" stroke="#ff7a45" stroke-width="2.5" stroke-linejoin="round"/>` +
      `<text x="${w - padR}" y="${h - 4}" fill="#8b8c99" font-size="9" font-family="ui-monospace,monospace" text-anchor="end">speed (mph) -></text>`;
    // SAFE SINK: `inner` is composed exclusively from computed numbers (chart
    // geometry via xOf/yS/.toFixed, loop integers, and the fixed gColor palette) —
    // never from telemetry strings or any user/bridge input, so no markup can be
    // injected. This is one of two innerHTML sinks allowlisted in
    // dashboard-tests/dom-sinks.test.js; keep it geometry-only. If you ever need to
    // render a label from data, build it with createElementNS, not string interp.
    chart.innerHTML = `<svg width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">${inner}</svg>`;
    if (head) {
      head.replaceChildren();
      if (best.e > 0) {
        const speed = document.createElement("b");
        speed.textContent = Math.round(best.mph) + " mph";
        speed.style.color = "#b8e63b";
        head.append(
          "Most efficient around ",
          speed,
          " - about " + best.e.toFixed(1) + " mi/kWh."
        );
      } else {
        head.textContent = "Pooling samples across every logged drive.";
      }
    }
    if (statsEl) {
      const hwy = pool.filter((p) => p.mph > 55).map((p) => p.eff);
      const down = pool.filter((p) => p.grade <= -0.012).map((p) => p.eff);
      const avg = (a: number[]) =>
        a.length ? (a.reduce((s, x) => s + x, 0) / a.length).toFixed(1) : "--";
      statsEl.replaceChildren(
        insightStat("Samples", String(pool.length)),
        insightStat("Highway avg", avg(hwy) + " mi/kWh"),
        insightStat("Downhill avg", avg(down) + " mi/kWh")
      );
    }
  }

  function insightStat(label: string, value: string) {
    const item = document.createElement("div");
    const key = document.createElement("span");
    key.className = "kicker";
    key.textContent = label;
    const strong = document.createElement("strong");
    strong.textContent = value;
    item.append(key, strong);
    return item;
  }

  // Re-render the scatter on viewport resize (SVG sized in real pixels).
  let scatterResizeTimer: ReturnType<typeof setTimeout> | null = null;
  window.addEventListener("resize", () => {
    if (scatterResizeTimer) clearTimeout(scatterResizeTimer);
    scatterResizeTimer = setTimeout(() => {
      const card = el("effScatterCard");
      if (card && !card.hidden) renderInsightScatter();
    }, 160);
  });

  Object.assign(VD, {
    loadTrips,
    renderRealTrips,
    renderTripsEmptyState,
    renderTripRow,
    selectRealTrip,
    ensureRouteForTrip,
    renderRealTripDetail,
    renderRealTripLeafletMaps,
    loadInsights,
    renderInsightStats,
    renderInsightsEmptyState,
    renderInsightScatter,
    enrichRouteEff
  });

  // Retry-cancel button in the error banner. Wired here instead of in
  // actions.js so the surgical addition stays inside the panels file the
  // related rendering code. The button visibility is driven by troubleshooter.js based
  // on the status state — this binding just forwards the click to the
  // bridge.
  (function bindRetryCancel() {
    const btn = el("errorBannerCancelRetry") as HTMLButtonElement | null;
    if (!btn) return;
    btn.addEventListener("click", () => {
      // Only enter the "Cancelling…" UI state when the bridge actually has a cancelRetry
      // method to call — otherwise the user sees a fake progress state for an action that
      // never happened.
      if (!(bridge && typeof bridge.cancelRetry === "function")) {
        return;
      }
      btn.disabled = true;
      btn.textContent = "Cancelling…";
      try {
        bridge.cancelRetry();
      } catch (err) {
        // Surface, but never throw from a click handler.
        if (typeof bridge.logClientError === "function") {
          bridge.logClientError("cancelRetry", err instanceof Error ? err.message : String(err));
        }
      }
      // Re-enable after a short window in case the engine keeps retrying
      // (e.g. flag-cleared race) so the user can try again.
      setTimeout(() => {
        btn.disabled = false;
        btn.textContent = "Cancel";
      }, 1500);
    });
  })();
})();

export {};
