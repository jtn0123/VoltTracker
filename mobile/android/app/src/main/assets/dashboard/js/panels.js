  function setStorage(payload) {
    state.storage = parsePayload(payload, {});
    updateStorageUi();
    updateReviewUi();
    renderRealV2Ui();
    renderMap();
    updateValidationUi();
    loadTrips();
    loadInsights();
  }

  function updateStorageUi() {
    const storage = state.storage || {};
    const sessions = Number(storage.sessionCount || 0);
    const samples = Number(storage.sampleCount || 0);
    const events = Number(storage.eventCount || 0);
    const pidRows = Number(storage.pidObservationCount || 0);
    const locationRows = Number(storage.locationSampleCount || 0);
    const tripRows = Number(storage.tripSegmentCount || 0);
    const chargeRows = Number(storage.chargeSessionCount || 0);
    const batteryRows = Number(storage.batterySnapshotCount || 0);
    const cellRows = Number(storage.cellSnapshotCount || 0);
    setText("dbSessionCount", sessions);
    setText("dbSampleCount", samples);
    setText("dbEventCount", events);
    setText("dbPidCount", pidRows);
    setText("dbLocationCount", locationRows);
    setText("dbTripCount", tripRows);
    setText("dbChargeCount", chargeRows);
    setText("dbBatteryCount", batteryRows + cellRows);
    setText("dbSize", formatBytes(Number(storage.databaseBytes || 0)));
    setText("dbRawTelemetryCount", Number(storage.rawTelemetryCount || samples || 0));
    setText("dbEmptyTelemetryCount", Number(storage.emptyTelemetryCount || 0));
    setText("dbState", dbRowCount(storage) ? `${dbRowCount(storage)} rows` : "ready");
    const last = storage.lastEventAtMs || storage.lastStartedAtMs;
    setText("dbSummaryTitle", sessions ? `${samples} samples - ${formatWhen(last)}` : "No stored sessions yet");
    const recent = Array.isArray(storage.recentSessions) ? storage.recentSessions : [];
    const list = el("dbSessionList");
    if (!recent.length) {
      list.innerHTML = '<p class="status-copy">Connect or scan to create local SQLite rows. Preview data stays isolated in the sandbox.</p>';
      updateReviewUi();
      return;
    }
    list.innerHTML = recent.map((session) => `
      <button type="button" class="history-row">
        <span>
          <strong>${session.mode || "session"} - ${session.adapterName || "OBD adapter"}</strong>
          <small>${formatWhen(session.startedAtMs)} - ${session.status || "active"} - ${Number(session.usefulSampleCount ?? session.sampleCount ?? 0)} useful</small>
        </span>
        <b>${Number(session.emptySampleCount || 0) ? `${Number(session.emptySampleCount || 0)} empty` : `${Number(session.sampleCount || 0)}x`}</b>
      </button>
    `).join("");
    updateReviewUi();
  }

  function updateReviewUi() {
    const review = (state.storage || {}).latestReview || {};
    const session = review.session || {};
    const hasSession = Boolean(session.id);
    const warnings = Array.isArray(review.warnings) ? review.warnings : [];
    const timeline = Array.isArray(review.timeline) ? review.timeline : [];
    const frames = Array.isArray(review.recentPidFrames) ? review.recentPidFrames : [];
    const maxSpeed = Number(review.maxSpeedKph || 0);
    const gpsCount = Number(review.locationSampleCount || 0);
    const parsed = Number(review.parsedPidCount || 0);
    const unknown = Number(review.unknownPidCount || 0);
    const interval = Number(review.avgSampleIntervalMs || 0);
    const backgroundSamples = Number(review.backgroundSampleCount || (review.latestHealth || {}).backgroundSampleCount || 0);
    const sampleGaps = Number(review.sampleGapEventCount || (review.latestHealth || {}).sampleGapCount || 0);
    const usefulSamples = Number(review.usefulTelemetryCount || 0);
    const emptySamples = Number(review.emptyTelemetryCount || 0);

    setText("reviewTitle", hasSession
      ? `${session.mode || "session"} - ${session.adapterName || "OBD adapter"}`
      : "No real session yet");
    setText("reviewMaxSpeed", maxSpeed ? `${Math.round(maxSpeed * 0.621371)} mph` : "--");
    setText("reviewGpsCount", gpsCount ? `${gpsCount}` : "--");
    setText("reviewPidParse", (parsed || unknown) ? `${parsed}/${parsed + unknown}` : "--");
    setText("reviewInterval", interval ? formatShortDuration(interval) : "--");
    setText("reviewBackground", backgroundSamples ? `${backgroundSamples} samples` : "--");
    setText("reviewGaps", sampleGaps ? `${sampleGaps}` : "0");
    setText("reviewUsefulSamples", usefulSamples ? `${usefulSamples}` : "--");
    setText("reviewEmptySamples", emptySamples ? `${emptySamples}` : "0");
    setText("pidFrameTitle", frames.length ? `${frames.length} latest frames` : "Waiting for scan data");

    const warningList = el("reviewWarnings");
    if (warningList) {
      if (!hasSession) {
        warningList.innerHTML = '<p class="status-copy">Connect or scan once, then this becomes the post-test review.</p>';
      } else if (!warnings.length) {
        warningList.innerHTML = '<article class="real-insight-item"><strong>No warnings in stored summary</strong><small>That only means the current checks did not flag GPS, parser, or speed-sentinel issues.</small></article>';
      } else {
        warningList.innerHTML = warnings.map((item) => `
          <article class="warning-item">
            <strong>${escapeHtml(item.code || "warning")}${item.count ? ` - ${escapeHtml(item.count)}` : ""}</strong>
            <small>${escapeHtml(item.detail || "")}</small>
          </article>
        `).join("");
      }
    }

    const insightList = el("realInsightList");
    if (insightList) {
      insightList.innerHTML = hasSession
        ? buildRealInsights(review).map((item) => `
            <article class="real-insight-item">
              <strong>${escapeHtml(item.title)}</strong>
              <small>${escapeHtml(item.detail)}</small>
            </article>
          `).join("")
        : "";
    }

    const timelineList = el("reviewTimeline");
    if (timelineList) {
      timelineList.innerHTML = timeline.length
        ? timeline.slice(-8).map((item) => `
            <article class="timeline-item">
              <span>
                <strong>${escapeHtml(item.detail || item.state || item.kind || "event")}</strong>
                <small>${escapeHtml(item.kind || "event")} - ${formatWhen(item.atMs)}</small>
              </span>
            </article>
          `).join("")
        : (hasSession ? '<p class="status-copy">No stored event timeline for this session yet.</p>' : "");
    }

    const pidList = el("pidFrameList");
    if (pidList) {
      pidList.innerHTML = frames.length
        ? frames.map((frame) => `
            <article class="pid-frame-item">
              <strong>${escapeHtml(frame.command || "--")}</strong>
              <span>
                <strong>${escapeHtml(frame.name || "Unparsed response")}</strong>
                <small>${escapeHtml(frame.valueText || frame.rawResponse || "stored raw frame")}</small>
              </span>
              <b>${frame.parsed ? "parsed" : "raw"}</b>
            </article>
          `).join("")
        : '<p class="status-copy">Run Scan or Connect to capture command/response frames.</p>';
    }
  }

  function buildRealInsights(review) {
    const warnings = Array.isArray(review.warnings) ? review.warnings : [];
    const stateCounts = review.stateCounts || {};
    const parsed = Number(review.parsedPidCount || 0);
    const unknown = Number(review.unknownPidCount || 0);
    const gps = Number(review.locationSampleCount || 0);
    const maxSpeed = Number(review.maxSpeedKph || 0);
    const backgroundSamples = Number(review.backgroundSampleCount || (review.latestHealth || {}).backgroundSampleCount || 0);
    const gapCount = Number(review.sampleGapEventCount || (review.latestHealth || {}).sampleGapCount || 0);
    const hasChargeHint = warnings.some((item) => String(item.code || "") === "charge-speed-hint");
    const hasDriving = Object.keys(stateCounts).some((key) => key.includes("driving"));
    return [
      {
        title: maxSpeed ? `Max speed ${Math.round(maxSpeed * 0.621371)} mph` : "No speed peak yet",
        detail: maxSpeed ? "Computed from accepted OBD speed samples for the latest session." : "Speed stays blank until accepted OBD speed samples are stored."
      },
      {
        title: gps ? `${gps} GPS samples stored` : "No GPS route stored",
        detail: gps ? "Route plotting has enough location samples to start review work." : "Check location permission and background behavior on the next drive."
      },
      {
        title: parsed || unknown ? `${parsed} parsed PID frames, ${unknown} raw` : "No PID parse counts yet",
        detail: "Raw frames are retained even when the parser does not understand them yet."
      },
      {
        title: hasChargeHint ? "Possible charging transition detected" : "No charging clue detected",
        detail: hasChargeHint ? "A rejected 255 km/h frame was stored as a Volt-specific clue to validate." : "Charging detection still needs more real plugged-in samples."
      },
      {
        title: hasDriving ? "Possible driving state detected" : "No driving state detected",
        detail: hasDriving ? stateCountSummary(stateCounts) : "Driving classifiers will become useful after a connected drive session."
      },
      {
        title: backgroundSamples ? `${backgroundSamples} samples captured in background` : "Background logging not proven yet",
        detail: backgroundSamples ? "The foreground service kept writing samples while the app was minimized." : "Minimize the app during the next drive to prove background collection."
      },
      {
        title: gapCount ? `${gapCount} sample gaps detected` : "No sample gaps flagged",
        detail: gapCount ? "Review these gaps against Android background behavior and adapter reconnects." : "No long active-session sample gaps are stored for the latest session."
      }
    ];
  }

  function stateCountSummary(counts) {
    return Object.keys(counts || {})
      .filter((key) => key && key !== "unknown")
      .map((key) => `${key}: ${counts[key]}`)
      .join(", ") || "Only unknown state samples stored.";
  }

  function renderRealV2Ui() {
    const storage = state.storage || {};
    const overview = storage.overview || {};
    const battery = storage.batterySummary || {};
    const charge = storage.chargeSummary || {};
    const route = selectedMapRoute(storage);
    const hasRows = dbRowCount(storage) > 0;
    const hasRoute = Number(route.pointCount || 0) >= 2;
    const hasCharge = Number(charge.chargeSessionCount || charge.chargingHintCount || 0) > 0;
    const latest = battery.latestBatterySnapshot && Object.keys(battery.latestBatterySnapshot).length
      ? battery.latestBatterySnapshot
      : (battery.latestTelemetry || overview.latestTelemetry || {});
    toggleHidden("appEmptyState", hasRows);
    toggleHidden("chargeEmptyState", hasCharge);
    toggleHidden("insightsEmptyState", hasRows);
    const routeDistance = Number(route.distanceMeters || overview.distanceMeters || 0);
    setText("overviewDistance", routeDistance ? formatDistance(routeDistance) : "--");
    setText("overviewDistanceSub", route.pointCount ? `${route.pointCount} GPS samples in latest route` : "waiting for route samples");
    setText("overviewMaxSpeed", overview.maxSpeedKph ? `${Math.round(Number(overview.maxSpeedKph) * 0.621371)} mph` : "--");
    const soc = Number(latest.soc);
    const power = Number(latest.powerKw ?? latest.packPowerKw);
    setText("overviewBattery", Number.isFinite(soc) && soc > 0 ? `${Math.round(soc)}%` : (Number.isFinite(power) && power ? `${power.toFixed(1)} kW` : "--"));
    setText("overviewBatterySub", Number.isFinite(power) && power ? `${power.toFixed(1)} kW latest power` : "SOC/power once observed");
    setText("overviewChargeHints", Number(charge.chargingHintCount || overview.chargingHints || 0));

    setText("realChargeSessions", Number(charge.chargeSessionCount || 0));
    setText("realChargeHints", Number(charge.chargingHintCount || 0));
    setText("realChargePower", charge.maxPowerKw ? `${Number(charge.maxPowerKw).toFixed(1)} kW` : "--");
    setText("realChargeStatus", charge.chargeSessionCount ? "recorded" : (charge.chargingHintCount ? "needs review" : "needs data"));

    const ring = el("realPackRing");
    const ringValue = el("realPackValue");
    if (Number.isFinite(soc) && soc > 0) {
      if (ring) ring.style.setProperty("--v", Math.max(0, Math.min(100, soc)));
      if (ringValue) ringValue.textContent = `${Math.round(soc)}%`;
      setText("realPackTitle", "Latest pack signal captured.");
      setText("realPackCopy", `${Number.isFinite(power) ? power.toFixed(1) + " kW · " : ""}${latest.vehicleState || "vehicle state unknown"} · confidence grows as Volt-specific PIDs are validated.`);
    } else {
      if (ring) ring.style.setProperty("--v", 0);
      if (ringValue) ringValue.textContent = "--";
      setText("realPackTitle", "Waiting for battery samples.");
      setText("realPackCopy", "SOC, power, and pack health will stay unknown until those PIDs are validated and stored.");
    }

    const maintenance = el("maintenanceList");
    if (maintenance) {
      maintenance.innerHTML = [
        ["Tire rotation", "Track manually until odometer PID is validated", "manual"],
        ["Battery coolant", "Needs service interval data before app reminders are trusted", "watch"],
        ["Engine oil", "Gas-engine runtime PID will make this useful", "pending"]
      ].map(([name, detail, tag]) => `
        <article class="real-insight-item">
          <span><strong>${name}</strong><small>${detail}</small></span>
          <b>${tag}</b>
        </article>`).join("");
    }
  }

  function toggleHidden(id, hidden) {
    const node = el(id);
    if (node) node.hidden = Boolean(hidden);
  }

  function loadTrips() {
    if (bridge && typeof bridge.getTrips === "function") {
      const parsed = parsePayload(bridge.getTrips(), []);
      state.trips = Array.isArray(parsed) ? parsed : [];
    }
    renderRealTrips();
  }

  function renderRealTrips() {
    if (state.demoActive) return;
    const trips = Array.isArray(state.trips) ? state.trips : [];
    toggleHidden("realTripsCard", trips.length === 0);
    toggleHidden("tripsEmptyState", trips.length > 0);
    const list = el("realTripsList");
    if (list) list.innerHTML = trips.map(renderTripRow).join("");
    setText("realTripsTitle", trips.length
      ? `${trips.length} logged ${trips.length === 1 ? "drive" : "drives"}`
      : "Your trips");
  }

  function renderTripRow(trip) {
    const distance = formatDistance(Number(trip.distanceMeters || 0));
    const duration = Number(trip.durationMs) > 0 ? formatDuration(Number(trip.durationMs)) : null;
    const topMph = trip.maxSpeedKph ? Math.round(Number(trip.maxSpeedKph) * 0.621371) : 0;
    const meta = [distance !== "--" ? distance : null, duration, topMph ? `top ${topMph} mph` : null]
      .filter(Boolean).join(" · ") || "no movement logged";
    return `
      <button type="button" class="history-row" data-trip-map="${escapeHtml(String(trip.id))}">
        <span>
          <strong>${escapeHtml(formatWhen(trip.startedAtMs))}</strong>
          <small>${escapeHtml(meta)}</small>
        </span>
        <b>${trip.hasRoute ? "route" : `${Number(trip.sampleCount || 0)}x`}</b>
      </button>`;
  }

  function loadInsights() {
    if (bridge && typeof bridge.getInsights === "function") {
      state.insights = parsePayload(bridge.getInsights(), {});
    }
    renderInsightStats();
  }

  function renderInsightStats() {
    const insights = state.insights || {};
    const trips = Number(insights.tripCount || 0);
    setText("insightTripCount", trips || "--");
    setText("insightTotalDistance", trips ? formatDistance(Number(insights.totalDistanceMeters || 0)) : "--");
    setText("insightDriveTime", Number(insights.totalDriveMs) > 0 ? formatDuration(Number(insights.totalDriveMs)) : "--");
    setText("insightTopSpeed", insights.maxSpeedKph ? `${Math.round(Number(insights.maxSpeedKph) * 0.621371)} mph` : "--");
    setText("insightLongest", Number(insights.longestTripMeters) > 0 ? formatDistance(Number(insights.longestTripMeters)) : "--");
    setText("insightGpsTrips", trips ? `${Number(insights.gpsTripCount || 0)}/${trips}` : "--");
  }
