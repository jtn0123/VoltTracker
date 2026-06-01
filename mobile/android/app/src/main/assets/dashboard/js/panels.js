(function () {
  "use strict";

  const VD = window.VoltDashboard;
  const state = VD.state;
  const bridge = VD.bridge;
  const el = VD.el;

  function isNativeError(payload) {
    return payload && typeof payload === "object" && payload.ok === false && payload.error;
  }

  function reportNativeReadError(payload, fallbackDetail) {
    const detail = payload.message || fallbackDetail || "Could not read local storage.";
    VD.setStatus({ state: "blocked", detail });
    if (bridge && typeof bridge.logClientError === "function") {
      bridge.logClientError(String(payload.error || "native_read_failed"), detail);
    }
  }

  function setStorage(payload) {
    const parsed = VD.parsePayload(payload, {});
    if (isNativeError(parsed)) {
      reportNativeReadError(parsed, "Could not read local storage summary.");
      state.storage = { error: parsed.error, message: parsed.message || "" };
      updateStorageUi();
      updateReviewUi();
      renderRealV2Ui();
      VD.renderMap();
      VD.updateValidationUi();
      return;
    }
    const newRoutes =
      parsed && Array.isArray(parsed.recentRoutes) ? parsed.recentRoutes : [];
    const allowSampleFallback = !bridge;
    // First-launch fallback: until the user has logged a real OBD drive,
    // populate the Map tab with the synthetic sample drive so the scrubber,
    // efficiency-colored route, drive picker, and Insights scatter all show
    // a populated UI instead of an empty state.
    //
    // The bridge re-pushes storage on every refresh, so we also protect the
    // loaded sample against subsequent empty pushes — otherwise the next
    // publishStorageSummary call wipes the sample back to empty. Real data
    // (newRoutes.length > 0) always wins and clears the flag.
    if (newRoutes.length > 0 || !allowSampleFallback) {
      state._mapSampleLoaded = false;
    } else if (state._mapSampleLoaded) {
      // We're already showing the sample, incoming payload has no real
      // routes — preserve the sample.
      return;
    }
    state.storage = parsed;
    if (allowSampleFallback && !state._mapSampleLoaded && newRoutes.length === 0 && typeof VD.loadSampleData === "function") {
      state._mapSampleLoaded = true;
      VD.loadSampleData();
      return;
    }
    updateStorageUi();
    updateReviewUi();
    renderRealV2Ui();
    VD.renderMap();
    VD.updateValidationUi();
    loadTrips();
    loadInsights();
  }

  function updateStorageUi() {
    const storage = state.storage || {};
    const sessions = Number(storage.sessionCount || 0);
    const samples = Number(storage.sampleCount || 0);
    const events = Number(storage.eventCount || 0);
    const pidRows = Number(storage.pidObservationCount || 0);
    const dtcRows = Number(storage.diagnosticCodeCount || 0);
    const locationRows = Number(storage.locationSampleCount || 0);
    const tripRows = Number(storage.tripSegmentCount || 0);
    const chargeRows = Number(storage.chargeSessionCount || 0);
    const batteryRows = Number(storage.batterySnapshotCount || 0);
    const cellRows = Number(storage.cellSnapshotCount || 0);
    const dbCard = el("dbCard");
    if (dbCard) dbCard.classList.toggle("is-empty", VD.dbRowCount(storage) === 0);
    VD.setText("dbSessionCount", sessions);
    VD.setText("dbSampleCount", samples);
    VD.setText("dbEventCount", events);
    VD.setText("dbPidCount", pidRows);
    VD.setText("dbDtcCount", dtcRows);
    VD.setText("dbLocationCount", locationRows);
    VD.setText("dbTripCount", tripRows);
    VD.setText("dbChargeCount", chargeRows);
    VD.setText("dbBatteryCount", batteryRows + cellRows);
    VD.setText("dbSize", VD.formatBytes(Number(storage.databaseBytes || 0)));
    VD.setText("dbRawTelemetryCount", Number(storage.rawTelemetryCount || samples || 0));
    VD.setText("dbEmptyTelemetryCount", Number(storage.emptyTelemetryCount || 0));
    VD.setText("dbState", VD.dbRowCount(storage) ? `${VD.dbRowCount(storage)} rows` : "ready");
    const last = storage.lastEventAtMs || storage.lastStartedAtMs;
    VD.setText("dbSummaryTitle", sessions ? `${samples} samples - ${VD.formatWhen(last)}` : "No stored sessions yet");
    const recent = Array.isArray(storage.recentSessions) ? storage.recentSessions : [];
    const list = el("dbSessionList");
    updateDiagnosticCodeUi();
    if (!recent.length) {
      list.replaceChildren(buildStatusCopy("Connect or scan to create local SQLite rows. Preview data stays isolated in the sandbox."));
      updateReviewUi();
      return;
    }
    list.replaceChildren(...recent.map(buildRecentSessionRow));
    updateReviewUi();
  }

  // ---- Row builders: prefer document.createElement + textContent over
  // innerHTML += template literals so storage strings can never be reinterpreted
  // as markup. Each builder returns a single root Element.

  function buildStatusCopy(text) {
    const p = document.createElement("p");
    p.className = "status-copy";
    p.textContent = text;
    return p;
  }

  function buildRecentSessionRow(session) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "history-row";
    const center = document.createElement("span");
    const strong = document.createElement("strong");
    strong.textContent = `${session.mode || "session"} - ${session.adapterName || "OBD adapter"}`;
    const small = document.createElement("small");
    small.textContent = `${VD.formatWhen(session.startedAtMs)} - ${session.status || "active"} - ${Number(session.usefulSampleCount ?? session.sampleCount ?? 0)} useful`;
    center.append(strong, small);
    const right = document.createElement("b");
    const empty = Number(session.emptySampleCount || 0);
    right.textContent = empty ? `${empty} empty` : `${Number(session.sampleCount || 0)}x`;
    button.append(center, right);
    return button;
  }

  function updateDiagnosticCodeUi() {
    const storage = state.storage || {};
    const codes = Array.isArray(storage.latestDiagnosticCodes) ? storage.latestDiagnosticCodes : [];
    const list = el("dtcList");
    const summaryCounts = storage.diagnosticCodeStatusCounts || {};
    const statusCounts = Object.keys(summaryCounts).length ? summaryCounts : codes.reduce((counts, code) => {
      const key = String(code.status || "stored").toLowerCase();
      counts[key] = (counts[key] || 0) + 1;
      return counts;
    }, {});
    const totalCodes = Number(storage.diagnosticCodeCount ?? codes.length);
    const storedOrCurrent = Number(statusCounts.stored || 0) + Number(statusCounts.current || 0);
    const latestSeen = codes.reduce((latest, code) => Math.max(latest, Number(code.lastSeenMs || 0)), 0);
    const needsDtcData = codes.length > 0 && typeof VD.dtcInfo !== "function";
    VD.setText("dtcTitle", totalCodes ? `${totalCodes} code${totalCodes === 1 ? "" : "s"} saved` : "No car-code scan yet");
    VD.setText("dtcReportBadge", needsDtcData ? "loading details" : totalCodes ? "evidence saved" : "ready");
    VD.setText("dtcTotalCount", totalCodes);
    VD.setText("dtcStoredCount", storedOrCurrent);
    VD.setText("dtcPendingCount", Number(statusCounts.pending || 0));
    VD.setText("dtcPermanentCount", Number(statusCounts.permanent || 0));
    VD.setText("dtcFreezeCount", Number(statusCounts["freeze-frame"] || 0));
    VD.setText("dtcLastSeen", latestSeen ? VD.formatWhen(latestSeen) : "--");
    if (!list) return;
    if (needsDtcData && typeof VD.ensureDtcData === "function") {
      VD.ensureDtcData()
        .then(() => {
          if (typeof VD.updateDiagnosticCodeUi === "function") VD.updateDiagnosticCodeUi();
        })
        .catch(() => {
          VD.setText("dtcReportBadge", "details unavailable");
        });
    }
    if (!codes.length) {
      list.replaceChildren(buildDtcEmptyState());
      return;
    }
    list.replaceChildren(...codes.map((c) => buildDtcItem(c, false)));
  }

  function buildDtcEmptyState() {
    const wrap = document.createElement("div");
    wrap.className = "dtc-empty-wrap";
    const article = document.createElement("article");
    article.className = "dtc-empty-state";
    const strong = document.createElement("strong");
    strong.textContent = "No saved code evidence";
    const small = document.createElement("small");
    small.textContent = "Current, pending, permanent, and freeze-frame results will appear here after a scan.";
    article.append(strong, small);
    wrap.append(article);

    const samples = Array.isArray(VD.dtcSampleCodes) ? VD.dtcSampleCodes : [];
    if (samples.length) {
      const header = document.createElement("div");
      header.className = "dtc-example-header";
      header.textContent = "Example - what a scan result looks like";
      wrap.append(header);
      samples.forEach((sample) => wrap.append(buildDtcItem(sample, true)));
    }
    return wrap;
  }

  function buildDtcItem(code, isExample) {
    const article = document.createElement("article");
    article.className = "dtc-item";
    article.dataset.status = String(code.status || "stored");
    if (isExample) article.dataset.example = "true";
    const _info = typeof VD.dtcInfo === "function" ? VD.dtcInfo(code.dtc) : null;
    if (_info && _info.severity) article.dataset.severity = _info.severity;

    const codeBlock = document.createElement("span");
    codeBlock.className = "dtc-code-block";
    const codeB = document.createElement("b");
    codeB.className = "dtc-code";
    codeB.textContent = code.dtc || "--";
    const codeSmall = document.createElement("small");
    codeSmall.textContent = code.statusLabel || code.status || "stored";
    codeBlock.append(codeB, codeSmall);

    const moduleBlock = document.createElement("span");
    moduleBlock.className = "dtc-module-block";
    const info = typeof VD.dtcInfo === "function" ? VD.dtcInfo(code.dtc) : null;
    const headline = document.createElement("strong");
    if (info && info.description) {
      headline.textContent = info.description;
    } else if (info && info.category) {
      headline.textContent = info.category;
    } else {
      headline.textContent = code.moduleName || "Unknown code - tap to look up";
    }
    moduleBlock.append(headline);

    if (info && info.description) {
      const small = document.createElement("small");
      const headerLabel = code.header ? `header ${code.header} - ` : "";
      small.textContent = `${code.moduleName || "generic OBD-II"} - ${headerLabel}first ${VD.formatWhen(code.firstSeenMs)}`;
      moduleBlock.append(small);
    } else {
      const small = document.createElement("small");
      const headerLabel = code.header ? `header ${code.header} - ` : "";
      const moduleLabel = info && info.category ? (code.moduleName || "generic OBD-II") + " - " : "";
      small.textContent = `${moduleLabel}${headerLabel}first ${VD.formatWhen(code.firstSeenMs)} - last ${VD.formatWhen(code.lastSeenMs)}`;
      moduleBlock.append(small);
    }

    if (info && Array.isArray(info.causes) && info.causes.length) {
      const causesWrap = document.createElement("div");
      causesWrap.className = "dtc-causes";
      const header = document.createElement("span");
      header.className = "dtc-causes-head";
      const tag = info.category ? ` - ${info.category}` : "";
      header.textContent = `Likely causes${tag}`;
      causesWrap.append(header);
      const list = document.createElement("ul");
      info.causes.slice(0, 5).forEach((cause) => {
        const li = document.createElement("li");
        li.textContent = cause;
        list.append(li);
      });
      causesWrap.append(list);
      moduleBlock.append(causesWrap);
    }

    const searchLink = document.createElement("a");
    searchLink.className = "dtc-search";
    searchLink.textContent = info && info.known ? "More on Google" : "Look up on Google";
    searchLink.href = "#";
    searchLink.dataset.dtcSearch = code.dtc || "";
    searchLink.setAttribute("role", "button");
    moduleBlock.append(searchLink);

    const repeatBlock = document.createElement("span");
    repeatBlock.className = "dtc-repeat-block";
    const repeatB = document.createElement("b");
    repeatB.textContent = `${Number(code.seenCount || 0)}x`;
    const repeatSmall = document.createElement("small");
    repeatSmall.textContent = "seen";
    repeatBlock.append(repeatB, repeatSmall);

    article.append(codeBlock, moduleBlock, repeatBlock);
    return article;
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
    const reviewCard = el("reviewCard");
    if (reviewCard) reviewCard.classList.toggle("has-session", hasSession);

    VD.setText("reviewTitle", hasSession
      ? `${session.mode || "session"} - ${session.adapterName || "OBD adapter"}`
      : "No real session yet");
    VD.setText("reviewMaxSpeed", maxSpeed ? `${Math.round(maxSpeed * 0.621371)} mph` : "--");
    VD.setText("reviewGpsCount", gpsCount ? `${gpsCount}` : "--");
    VD.setText("reviewPidParse", (parsed || unknown) ? `${parsed}/${parsed + unknown}` : "--");
    VD.setText("reviewInterval", interval ? VD.formatShortDuration(interval) : "--");
    VD.setText("reviewBackground", backgroundSamples ? `${backgroundSamples} samples` : "--");
    VD.setText("reviewGaps", sampleGaps ? `${sampleGaps}` : "0");
    VD.setText("reviewUsefulSamples", usefulSamples ? `${usefulSamples}` : "--");
    VD.setText("reviewEmptySamples", emptySamples ? `${emptySamples}` : "0");
    VD.setText("pidFrameTitle", frames.length ? `${frames.length} latest frames` : "Waiting for scan data");

    const warningList = el("reviewWarnings");
    if (warningList) {
      if (!hasSession) {
        warningList.replaceChildren(buildStatusCopy("Connect or scan once, then this becomes the post-test review."));
      } else if (!warnings.length) {
        warningList.replaceChildren(buildRealInsightItem(
          "No warnings in stored summary",
          "That only means the current checks did not flag GPS, parser, or speed-sentinel issues."
        ));
      } else {
        warningList.replaceChildren(...warnings.map(buildWarningItem));
      }
    }

    const insightList = el("realInsightList");
    if (insightList) {
      if (hasSession) {
        insightList.replaceChildren(
          ...buildRealInsights(review).map((item) => buildRealInsightItem(item.title, item.detail))
        );
      } else {
        insightList.replaceChildren();
      }
    }

    const timelineList = el("reviewTimeline");
    if (timelineList) {
      if (timeline.length) {
        timelineList.replaceChildren(...timeline.slice(-8).map(buildTimelineItem));
      } else if (hasSession) {
        timelineList.replaceChildren(buildStatusCopy("No stored event timeline for this session yet."));
      } else {
        timelineList.replaceChildren();
      }
    }

    const pidList = el("pidFrameList");
    if (pidList) {
      if (frames.length) {
        pidList.replaceChildren(...frames.map(buildPidFrameItem));
      } else {
        pidList.replaceChildren(buildStatusCopy("Run Scan or Connect to capture command/response frames."));
      }
    }
  }

  function buildRealInsightItem(title, detail) {
    const article = document.createElement("article");
    article.className = "real-insight-item";
    const strong = document.createElement("strong");
    strong.textContent = title;
    const small = document.createElement("small");
    small.textContent = detail;
    article.append(strong, small);
    return article;
  }

  function buildWarningItem(item) {
    const article = document.createElement("article");
    article.className = "warning-item";
    const strong = document.createElement("strong");
    strong.textContent = `${item.code || "warning"}${item.count ? ` - ${item.count}` : ""}`;
    const small = document.createElement("small");
    small.textContent = item.detail || "";
    article.append(strong, small);
    return article;
  }

  function buildTimelineItem(item) {
    const article = document.createElement("article");
    article.className = "timeline-item";
    const wrapper = document.createElement("span");
    const strong = document.createElement("strong");
    strong.textContent = item.detail || item.state || item.kind || "event";
    const small = document.createElement("small");
    small.textContent = `${item.kind || "event"} - ${VD.formatWhen(item.atMs)}`;
    wrapper.append(strong, small);
    article.append(wrapper);
    return article;
  }

  function buildPidFrameItem(frame) {
    const article = document.createElement("article");
    article.className = "pid-frame-item";
    const cmd = document.createElement("strong");
    cmd.textContent = frame.command || "--";
    const center = document.createElement("span");
    const name = document.createElement("strong");
    name.textContent = frame.name || "Unparsed response";
    const detail = document.createElement("small");
    detail.textContent = frame.valueText || frame.rawResponse || "stored raw frame";
    center.append(name, detail);
    const right = document.createElement("b");
    right.textContent = frame.parsed ? "parsed" : "raw";
    article.append(cmd, center, right);
    return article;
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
        title: hasChargeHint ? "Possible charging detected" : "No charging detected yet",
        detail: hasChargeHint ? "A possible charging event was saved for review." : "Connect the adapter while the car is charging to detect sessions."
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
    const route = VD.selectedMapRoute(storage);
    const hasRows = VD.dbRowCount(storage) > 0;
    const _hasRoute = Number(route.pointCount || 0) >= 2;
    const hasCharge = Number(charge.chargeSessionCount || charge.chargingHintCount || 0) > 0;
    const latest = battery.latestBatterySnapshot && Object.keys(battery.latestBatterySnapshot).length
      ? battery.latestBatterySnapshot
      : (battery.latestTelemetry || overview.latestTelemetry || {});
    toggleHidden("appEmptyState", hasRows);
    toggleHidden("chargeEmptyState", hasCharge);
    toggleHidden("insightsEmptyState", hasRows);
    const routeDistance = Number(route.distanceMeters || overview.distanceMeters || 0);
    VD.setText("overviewDistance", routeDistance ? VD.formatDistance(routeDistance) : "--");
    VD.setText("overviewDistanceSub", route.pointCount ? `${route.pointCount} GPS samples in latest route` : "waiting for route samples");
    VD.setText("overviewMaxSpeed", overview.maxSpeedKph ? `${Math.round(Number(overview.maxSpeedKph) * 0.621371)} mph` : "--");
    const soc = Number(latest.soc);
    const power = Number(latest.powerKw ?? latest.packPowerKw);
    VD.setText("overviewBattery", Number.isFinite(soc) && soc > 0 ? `${Math.round(soc)}%` : (Number.isFinite(power) && power ? `${power.toFixed(1)} kW` : "--"));
    VD.setText("overviewBatterySub", Number.isFinite(power) && power ? `${power.toFixed(1)} kW latest power` : "SOC/power once observed");
    VD.setText("overviewChargeHints", Number(charge.chargingHintCount || overview.chargingHints || 0));

    VD.setText("realChargeSessions", Number(charge.chargeSessionCount || 0));
    VD.setText("realChargeHints", Number(charge.chargingHintCount || 0));
    VD.setText("realChargePower", charge.maxPowerKw ? `${Number(charge.maxPowerKw).toFixed(1)} kW` : "--");
    VD.setText("realChargeStatus", charge.chargeSessionCount ? "recorded" : (charge.chargingHintCount ? "needs review" : "needs data"));

    const ring = el("realPackRing");
    const ringValue = el("realPackValue");
    if (Number.isFinite(soc) && soc > 0) {
      if (ring) ring.style.setProperty("--v", Math.max(0, Math.min(100, soc)));
      if (ringValue) ringValue.textContent = `${Math.round(soc)}%`;
      VD.setText("realPackTitle", "Latest battery reading captured.");
      VD.setText("realPackCopy", `${Number.isFinite(power) ? power.toFixed(1) + " kW · " : ""}${latest.vehicleState || "vehicle state unknown"} · accuracy improves as more drives are logged.`);
    } else {
      if (ring) ring.style.setProperty("--v", 0);
      if (ringValue) ringValue.textContent = "--";
      VD.setText("realPackTitle", "Waiting for battery readings.");
      VD.setText("realPackCopy", "Battery charge, power, and pack health appear here once the adapter has logged a few readings.");
    }

    const maintenance = el("maintenanceList");
    if (maintenance) {
      const rows = [
        ["Tire rotation", "Track manually until odometer PID is validated", "manual"],
        ["Battery coolant", "Needs service interval data before app reminders are trusted", "watch"],
        ["Engine oil", "Gas-engine runtime PID will make this useful", "pending"]
      ];
      maintenance.replaceChildren(...rows.map(([name, detail, tag]) => buildMaintenanceRow(name, detail, tag)));
    }

    renderVehicleUi();
  }

  function buildMaintenanceRow(name, detail, tag) {
    const article = document.createElement("article");
    article.className = "real-insight-item";
    const center = document.createElement("span");
    const strong = document.createElement("strong");
    strong.textContent = name;
    const small = document.createElement("small");
    small.textContent = detail;
    center.append(strong, small);
    const right = document.createElement("b");
    right.textContent = tag;
    article.append(center, right);
    return article;
  }

  // Vehicle identity card. Reads state.appState.vehicle once the OBD bridge can
  // supply it; every field degrades to "--" until its PID/source is validated.
  // Expected vehicle fields: name, vin, year, make, model, odometerMiles (or
  // odometerKm), evSharePct, batteryHealthPct.
  function renderVehicleUi() {
    const vehicle = (state.appState || {}).vehicle || {};
    const insights = state.insights || {};

    const year = vehicle.year || vehicle.modelYear || "";
    const identity = [year, vehicle.make || "", vehicle.model || ""].filter(Boolean).join(" ").trim();
    const name = vehicle.name || vehicle.nickname || "";
    const known = Boolean(name || identity || vehicle.vin);

    VD.setText("vehicleName", name || identity || "No vehicle identified yet");
    VD.setText("vehicleSummary", known
      ? "Read from your vehicle. Blank fields fill in as more readings come through."
      : "Your vehicle's details fill in automatically once the VIN and odometer are read.");
    VD.setText("vehicleVin", vehicle.vin || "--");
    VD.setText("vehicleYear", year || "--");

    const odoMiles = Number(vehicle.odometerMiles);
    const odoKm = Number(vehicle.odometerKm);
    let odometer = "--";
    if (Number.isFinite(odoMiles) && odoMiles > 0) {
      odometer = `${Math.round(odoMiles).toLocaleString()} mi`;
    } else if (Number.isFinite(odoKm) && odoKm > 0) {
      odometer = `${Math.round(odoKm * 0.621371).toLocaleString()} mi`;
    }
    VD.setText("vehicleOdometer", odometer);

    const loggedMeters = Number(insights.totalDistanceMeters || 0);
    VD.setText("vehicleLoggedDistance", loggedMeters > 0 ? VD.formatDistance(loggedMeters) : "--");

    const evMix = Number(vehicle.evSharePct != null ? vehicle.evSharePct : vehicle.electricSharePct);
    VD.setText("vehicleEvMix", Number.isFinite(evMix) ? `${Math.round(evMix)}% electric` : "--");

    const health = Number(vehicle.batteryHealthPct != null ? vehicle.batteryHealthPct : vehicle.packHealthPct);
    VD.setText("vehicleBatteryHealth", Number.isFinite(health) ? `${health.toFixed(1)}%` : "--");
  }

  function toggleHidden(id, hidden) {
    const node = el(id);
    if (node) node.hidden = Boolean(hidden);
  }

  function loadTrips() {
    if (bridge && typeof bridge.getTrips === "function") {
      const parsed = VD.parsePayload(bridge.getTrips(), []);
      if (isNativeError(parsed)) {
        reportNativeReadError(parsed, "Could not read logged trips.");
        state.trips = [];
      } else {
        state.trips = Array.isArray(parsed) ? parsed : [];
      }
    }
    renderRealTrips();
  }

  function renderRealTrips() {
    if (state.demoActive) return;
    const trips = Array.isArray(state.trips) ? state.trips : [];
    toggleHidden("realTripsCard", trips.length === 0);
    toggleHidden("tripsEmptyState", trips.length > 0);
    toggleHidden("realTripDetailGrid", trips.length === 0);
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

  function realTripsRenderKey(trips) {
    return trips.map((trip) => [
      trip.id,
      trip.hasRoute ? "route" : "samples",
      trip.pointCount || 0,
      trip.sampleCount || 0,
      trip.startedAtMs || 0,
      trip.distanceMeters || 0
    ].join(":")).join("|");
  }

  function needsMiniMapUpgrade(list) {
    if (!list) return false;
    return Array.prototype.some.call(
      list.querySelectorAll("[data-real-trip-map-role='mini']"),
      (slot) => !slot.querySelector(".leaflet-container")
    );
  }

  function renderTripRow(trip) {
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
    const route = trip.hasRoute ? routeForTrip(trip) : null;
    if (route) button.classList.add("has-route-preview");
    const center = document.createElement("span");
    center.className = "real-trip-chip-copy";
    const strong = document.createElement("strong");
    strong.textContent = whenLabel;
    const small = document.createElement("small");
    small.textContent = meta;
    center.append(strong, small);
    const right = document.createElement("b");
    right.className = "trip-route-state";
    right.textContent = trip.hasRoute ? `${Number(trip.pointCount || 0) || "GPS"} pts` : `${Number(trip.sampleCount || 0)}x`;
    if (route) {
      button.append(center, right, buildTripMapSlot(route, "mini", trip.id));
    } else {
      button.append(center, right);
    }
    return button;
  }

  function selectRealTrip(id) {
    state.selectedRealTripId = String(id || "");
    updateRealTripSelection();
    renderRealTripDetail();
    queueRenderRealTripMaps({ detailOnly: true });
  }

  function updateRealTripSelection() {
    document.querySelectorAll("[data-real-trip-id]").forEach((button) => {
      button.classList.toggle("is-active", String(button.dataset.realTripId || "") === String(state.selectedRealTripId || ""));
    });
  }

  function routeForTrip(trip) {
    const routes =
      state.storage && Array.isArray(state.storage.recentRoutes)
        ? state.storage.recentRoutes
        : [];
    const id = String(trip.id || "");
    return routes.find((route) => String((route.session || {}).id || "") === id) || null;
  }

  function buildTripRouteSpark(route) {
    const points = (route && route.points || [])
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
    const endPair = coords.split(" ").pop().split(",");
    const end = document.createElementNS(ns, "circle");
    end.setAttribute("cx", endPair[0]);
    end.setAttribute("cy", endPair[1]);
    end.setAttribute("r", "3");
    end.setAttribute("class", "trip-route-spark-end");
    svgNode.append(halo, line, start, end);
    return svgNode;
  }

  function buildTripMapSlot(route, role, tripId) {
    const slot = document.createElement("span");
    slot.className = role === "detail" ? "real-route-map" : "trip-route-map";
    slot.dataset.realTripMap = String(tripId || (route && route.session && route.session.id) || "");
    slot.dataset.realTripMapRole = role;
    slot.appendChild(role === "detail" ? buildTripRoutePreview(route) : buildTripRouteSpark(route));
    return slot;
  }

  function buildTripRoutePreview(route) {
    const points = (route && route.points || [])
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
    const endPair = coords.split(" ").pop().split(",");
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

  function buildEnergyRow(label, value, pct, color) {
    const row = document.createElement("div");
    const span = document.createElement("span");
    span.textContent = label;
    const bar = document.createElement("i");
    bar.style.width = Math.max(3, Math.min(100, Number(pct) || 0)) + "%";
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
    const route = trip.hasRoute ? routeForTrip(trip) : null;
    const distance = VD.formatDistance(Number(trip.distanceMeters || 0));
    const duration = Number(trip.durationMs) > 0 ? VD.formatDuration(Number(trip.durationMs)) : "--";
    const topMph = trip.maxSpeedKph ? Math.round(Number(trip.maxSpeedKph) * 0.621371) : 0;
    const avgMph = trip.avgMovingSpeedKph ? Math.round(Number(trip.avgMovingSpeedKph) * 0.621371) : 0;
    VD.setText("realTripRouteTitle", VD.formatWhen(trip.startedAtMs));
    VD.setText(
      "realTripRouteMeta",
      [distance !== "--" ? distance : null, duration !== "--" ? duration : null, topMph ? `top ${topMph} mph` : null]
        .filter(Boolean)
        .join(" - ") || "stored drive"
    );
    const mapBtn = el("realTripMapBtn");
    if (mapBtn) {
      mapBtn.dataset.tripMap = String(trip.id || "");
      mapBtn.disabled = !trip.hasRoute;
      mapBtn.textContent = trip.hasRoute ? "Open map" : "No route";
    }
    const routeBox = el("realTripRouteBox");
    if (routeBox) {
      const nextTripMap = trip.hasRoute ? String(trip.id || "") : "";
      const hasCurrentMap = routeBox.dataset.tripMap === nextTripMap &&
        routeBox.querySelector("[data-real-trip-map-role='detail']");
      routeBox.dataset.tripMap = nextTripMap;
      routeBox.setAttribute("role", trip.hasRoute ? "button" : "presentation");
      routeBox.setAttribute("aria-label", trip.hasRoute ? "Open selected trip on map" : "No route map available");
      if (!hasCurrentMap) routeBox.replaceChildren(buildTripMapSlot(route, "detail", trip.id));
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
      rows.replaceChildren(
        buildEnergyRow("Distance", distance, 100, "var(--volt)"),
        buildEnergyRow("Duration", duration, 68, "#a4b8ff"),
        buildEnergyRow("Samples", `${Number(trip.sampleCount || 0).toLocaleString()}x`, 52, "rgba(255, 255, 255, 0.32)"),
        buildEnergyRow("Efficiency", avgEff ? `${avgEff.toFixed(1)} mi/kWh` : "--", avgEff ? Math.min(100, avgEff * 16) : 8, "var(--ok)")
      );
    }
  }

  const realTripMaps = new Map();
  let realTripMapTimer = 0;

  function clearRealTripMaps() {
    realTripMaps.forEach((map) => {
      try { map.remove(); } catch (_err) {}
    });
    realTripMaps.clear();
  }

  function queueRenderRealTripMaps(options) {
    clearTimeout(realTripMapTimer);
    const detailOnly = Boolean(options && options.detailOnly);
    realTripMapTimer = setTimeout(() => renderRealTripLeafletMaps({ detailOnly }), 80);
  }

  function renderRealTripLeafletMaps(options) {
    if (typeof L === "undefined") return;
    const detailOnly = Boolean(options && options.detailOnly);
    if (!detailOnly) {
      clearRealTripMaps();
    } else {
      const currentDetailId = (el("realTripRouteBox") || {}).dataset?.tripMap || "";
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
    document.querySelectorAll("[data-real-trip-map]").forEach((slot) => {
      if (detailOnly && slot.dataset.realTripMapRole !== "detail") return;
      if (realTripMaps.has(slot)) {
        try { realTripMaps.get(slot).invalidateSize(false); } catch (_err) {}
        return;
      }
      if (slot.querySelector(".leaflet-container")) return;
      const id = slot.dataset.realTripMap;
      const role = slot.dataset.realTripMapRole || "mini";
      const route = routeForTrip({ id });
      const points = (route && route.points || [])
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
      if (state.mapRemoteTilesEnabled) {
        L.tileLayer("https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png", {
          subdomains: "abcd",
          maxZoom: 19
        }).addTo(map);
      }
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
      const parsed = VD.parsePayload(bridge.getInsights(), {});
      if (isNativeError(parsed)) {
        reportNativeReadError(parsed, "Could not read vehicle insights.");
        state.insights = {};
      } else {
        state.insights = parsed;
      }
    }
    renderInsightStats();
    renderInsightScatter();
  }

  function renderInsightStats() {
    const insights = state.insights || {};
    const trips = Number(insights.tripCount || 0);
    VD.setText("insightTripCount", trips || "--");
    VD.setText("insightTotalDistance", trips ? VD.formatDistance(Number(insights.totalDistanceMeters || 0)) : "--");
    VD.setText("insightDriveTime", Number(insights.totalDriveMs) > 0 ? VD.formatDuration(Number(insights.totalDriveMs)) : "--");
    VD.setText("insightTopSpeed", insights.maxSpeedKph ? `${Math.round(Number(insights.maxSpeedKph) * 0.621371)} mph` : "--");
    VD.setText("insightLongest", Number(insights.longestTripMeters) > 0 ? VD.formatDistance(Number(insights.longestTripMeters)) : "--");
    VD.setText("insightGpsTrips", trips ? `${Number(insights.gpsTripCount || 0)}/${trips}` : "--");
  }

  // ----- Efficiency vs Speed scatter (Insights tab) -------------------------
  // Pools per-point efficiency from every recent route. Efficiency is derived
  // by time-joining the route's `powerTrack` onto its points and computing
  // mi/kWh with a +/-8-sample window. The card stays hidden until enough
  // samples carry derived eff (which depends on the OBD loop having captured
  // battery current via the Volt 7E1 PIDs).

  const haversineMetersJsLocal = (lat1, lng1, lat2, lng2) => {
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

  function enrichRouteEff(route) {
    if (!route || route._effDone) return;
    route._effDone = true;
    const pts = route.points || [];
    const track = (route.powerTrack || []).filter((s) =>
      Number.isFinite(Number(s.powerKw))
    );
    if (pts.length < 2 || track.length < 2) return;
    const powerAt = (atMs) => {
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
    const pool = [];
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
    const xOf = (mph) => padL + (mph / 75) * (w - padL - padR);
    const yS = (e) => padT + (1 - e / 7) * (h - padT - padB);
    const gColor = (g) =>
      g <= -0.006 ? "#5cc8ff" : g >= 0.006 ? "#ff6b5f" : "#b8e63b";
    let inner = "";
    for (let gx = 0; gx <= 75; gx += 15) {
      inner +=
        `<line x1="${xOf(gx)}" y1="${padT}" x2="${xOf(gx)}" y2="${h - padB}" stroke="rgba(255,255,255,0.06)"/>` +
        `<text x="${xOf(gx)}" y="${h - padB + 15}" fill="#747582" font-size="9" font-family="ui-monospace,monospace" text-anchor="middle">${gx}</text>`;
    }
    for (let gy = 0; gy <= 7; gy += 1) {
      inner +=
        `<line x1="${padL}" y1="${yS(gy)}" x2="${w - padR}" y2="${yS(gy)}" stroke="rgba(255,255,255,0.06)"/>` +
        `<text x="${padL - 6}" y="${yS(gy) + 3}" fill="#747582" font-size="9" font-family="ui-monospace,monospace" text-anchor="end">${gy}</text>`;
    }
    const bins = [];
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
      `<text x="${w - padR}" y="${h - 4}" fill="#747582" font-size="9" font-family="ui-monospace,monospace" text-anchor="end">speed (mph) -></text>`;
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
      const avg = (a) =>
        a.length ? (a.reduce((s, x) => s + x, 0) / a.length).toFixed(1) : "--";
      statsEl.replaceChildren(
        insightStat("Samples", String(pool.length)),
        insightStat("Highway avg", avg(hwy) + " mi/kWh"),
        insightStat("Downhill avg", avg(down) + " mi/kWh")
      );
    }
  }

  function insightStat(label, value) {
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
  let scatterResizeTimer = null;
  window.addEventListener("resize", () => {
    clearTimeout(scatterResizeTimer);
    scatterResizeTimer = setTimeout(() => {
      const card = el("effScatterCard");
      if (card && !card.hidden) renderInsightScatter();
    }, 160);
  });

  Object.assign(VD, {
    setStorage,
    updateStorageUi,
    updateDiagnosticCodeUi,
    updateReviewUi,
    buildRealInsights,
    stateCountSummary,
    renderRealV2Ui,
    renderVehicleUi,
    toggleHidden,
    loadTrips,
    renderRealTrips,
    renderTripRow,
    selectRealTrip,
    renderRealTripDetail,
    renderRealTripLeafletMaps,
    loadInsights,
    renderInsightStats,
    renderInsightScatter,
    enrichRouteEff
  });

  // Retry-cancel button in the error banner. Wired here instead of in
  // actions.js so the surgical addition stays inside the panels file the
  // related rendering code. The button visibility is driven by troubleshooter.js based
  // on the status state — this binding just forwards the click to the
  // bridge.
  (function bindRetryCancel() {
    const btn = el("errorBannerCancelRetry");
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
          bridge.logClientError("cancelRetry", String(err && err.message || err));
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
