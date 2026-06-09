// storage-status.ts — Settings/Database storage summary, the post-session
// review block, the DTC (diagnostic trouble code) report, the Insights "real
// v2" hero (battery/charge/vehicle) and the Charge tab history.
//
// Split out of the old panels.ts god-module (C2). Cross-module render entry
// points are attached to the shared VD global exactly as before; the few helpers
// the sibling panels (signals-panel.ts, insights-panel.ts) reach for —
// isNativeError, reportNativeReadError, buildStatusCopy, toggleHidden — are
// published on VD here so this module stays the single owner of them.
(function () {
  "use strict";

  const VD = window.VoltDashboard;
  const state = VD.state;
  const bridge = VD.bridge;
  const el = VD.el;

  // Plain boolean (not a type predicate): the parsed payloads it screens —
  // VoltStorageSummary, VoltTrip[], VoltInsights — structurally overlap
  // VoltNativeError, so a `payload is VoltNativeError` guard would narrow the
  // happy-path value to `never`. Callers read the error fields off the same
  // payload after the check.
  function isNativeError(payload: unknown): boolean {
    const candidate = payload as VoltNativeError | null;
    return (
      candidate != null &&
      typeof candidate === "object" &&
      candidate.ok === false &&
      Boolean(candidate.error)
    );
  }

  function reportNativeReadError(payload: unknown, fallbackDetail: string) {
    const err = (payload || {}) as VoltNativeError;
    const detail = err.message || fallbackDetail || "Could not read local storage.";
    VD.setStatus({ state: "blocked", detail });
    if (bridge && typeof bridge.logClientError === "function") {
      bridge.logClientError(String(err.error || "native_read_failed"), detail);
    }
  }

  function setStorage(payload: unknown) {
    const parsed = VD.parsePayload<VoltStorageSummary>(payload, {});
    if (isNativeError(parsed)) {
      const err = parsed as VoltNativeError;
      reportNativeReadError(parsed, "Could not read local storage summary.");
      const storageError: VoltStorageSummary = { message: err.message || "" };
      if (err.error) storageError.error = err.error;
      state.storage = storageError;
      updateStorageUi();
      updateReviewUi();
      renderRealV2Ui();
      VD.renderMap();
      VD.updateValidationUi();
      return;
    }
    const newRoutes =
      parsed && Array.isArray(parsed.recentRoutes) ? parsed.recentRoutes : [];
    if (state.demoActive && state.demoPreviewStorage) {
      // Park the real summary while demo preview owns the screen (cross-module
      // invariant: restored when demo stops).
      VD.setState({ realStorage: parsed });
      return;
    }
    if (newRoutes.length > 0) {
      state._mapSampleLoaded = false;
    }
    state.storage = parsed;
    updateStorageUi();
    updateReviewUi();
    renderRealV2Ui();
    VD.renderMap();
    VD.updateValidationUi();
    VD.loadTrips();
    VD.loadInsights();
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
    VD.setText("dbSummaryTitle", sessions ? (last ? `${samples} samples · ${VD.formatWhen(last)}` : `${samples} samples`) : "No stored sessions yet");
    const recent = Array.isArray(storage.recentSessions) ? storage.recentSessions : [];
    const list = el("dbSessionList");
    updateDiagnosticCodeUi();
    VD.updateEnhancedCapabilityUi();
    if (!recent.length) {
      list?.replaceChildren(buildStatusCopy("Connect or scan to create local SQLite rows. Preview data stays isolated in the sandbox."));
      updateReviewUi();
      return;
    }
    list?.replaceChildren(...recent.map(buildRecentSessionRow));
    updateReviewUi();
  }

  // ---- Row builders: prefer document.createElement + textContent over
  // innerHTML += template literals so storage strings can never be reinterpreted
  // as markup. Each builder returns a single root Element.

  function buildStatusCopy(text: string) {
    const p = document.createElement("p");
    p.className = "status-copy";
    p.textContent = text;
    return p;
  }

  function buildRecentSessionRow(session: VoltRecentSession) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "history-row";
    const center = document.createElement("span");
    const strong = document.createElement("strong");
    strong.textContent = `${session.mode || "session"} · ${session.adapterName || "OBD adapter"}`;
    const small = document.createElement("small");
    small.textContent = `${VD.formatWhen(session.startedAtMs)} · ${session.status || "active"} · ${Number(session.usefulSampleCount ?? session.sampleCount ?? 0)} useful`;
    center.append(strong, small);
    const right = document.createElement("b");
    const empty = Number(session.emptySampleCount || 0);
    // The subtitle already states the useful-sample count, so the badge calls
    // out data quality instead of repeating it: empties if any, else "clean".
    right.textContent = empty ? `${empty} empty` : "clean";
    button.append(center, right);
    return button;
  }

  function updateDiagnosticCodeUi() {
    const storage = state.storage || {};
    const codes = Array.isArray(storage.latestDiagnosticCodes) ? storage.latestDiagnosticCodes : [];
    const list = el("dtcList");
    const summaryCounts = storage.diagnosticCodeStatusCounts || {};
    const statusCounts: Record<string, number> = Object.keys(summaryCounts).length ? summaryCounts : codes.reduce((counts: Record<string, number>, code) => {
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

  function buildDtcItem(code: VoltDtcRow, isExample: boolean) {
    const article = document.createElement("article");
    article.className = "dtc-item";
    article.dataset.status = String(code.status || "stored");
    if (isExample) article.dataset.example = "true";
    const _info = typeof VD.dtcInfo === "function" ? VD.dtcInfo(String(code.dtc || "")) : null;
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
    const info = typeof VD.dtcInfo === "function" ? VD.dtcInfo(String(code.dtc || "")) : null;
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
      const headerLabel = code.header ? `header ${code.header} · ` : "";
      small.textContent = `${code.moduleName || "generic OBD-II"} · ${headerLabel}first ${VD.formatWhen(code.firstSeenMs)}`;
      moduleBlock.append(small);
    } else {
      const small = document.createElement("small");
      const headerLabel = code.header ? `header ${code.header} · ` : "";
      const moduleLabel = info && info.category ? (code.moduleName || "generic OBD-II") + " · " : "";
      small.textContent = `${moduleLabel}${headerLabel}first ${VD.formatWhen(code.firstSeenMs)} · last ${VD.formatWhen(code.lastSeenMs)}`;
      moduleBlock.append(small);
    }

    if (info && Array.isArray(info.causes) && info.causes.length) {
      const causesWrap = document.createElement("div");
      causesWrap.className = "dtc-causes";
      const header = document.createElement("span");
      header.className = "dtc-causes-head";
      const tag = info.category ? ` · ${info.category}` : "";
      header.textContent = `Likely causes${tag}`;
      causesWrap.append(header);
      const list = document.createElement("ul");
      info.causes.slice(0, 5).forEach((cause: string) => {
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

    // Occurrence count, only when we actually have one. A stored/pending code
    // seen 0 times is contradictory, so suppress the badge rather than print
    // "0x seen".
    const seenCount = Number(code.seenCount || 0);
    if (seenCount > 0) {
      const repeatBlock = document.createElement("span");
      repeatBlock.className = "dtc-repeat-block";
      const repeatB = document.createElement("b");
      repeatB.textContent = `${seenCount}x`;
      const repeatSmall = document.createElement("small");
      repeatSmall.textContent = "seen";
      repeatBlock.append(repeatB, repeatSmall);
      article.append(codeBlock, moduleBlock, repeatBlock);
    } else {
      article.append(codeBlock, moduleBlock);
    }
    return article;
  }

  function updateReviewUi() {
    const review: VoltSessionReview = (state.storage || {}).latestReview || {};
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
      ? `${session.mode || "session"} · ${session.adapterName || "OBD adapter"}`
      : "No real session yet");
    VD.setText("reviewMaxSpeed", maxSpeed ? VD.units.speedText(maxSpeed) : "--");
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

  function buildRealInsightItem(title: string, detail: string) {
    const article = document.createElement("article");
    article.className = "real-insight-item";
    const strong = document.createElement("strong");
    strong.textContent = title;
    const small = document.createElement("small");
    small.textContent = detail;
    article.append(strong, small);
    return article;
  }

  function buildWarningItem(item: { code?: string; count?: number; detail?: string }) {
    const article = document.createElement("article");
    article.className = "warning-item";
    const strong = document.createElement("strong");
    strong.textContent = `${item.code || "warning"}${item.count ? ` · ${item.count}` : ""}`;
    const small = document.createElement("small");
    small.textContent = item.detail || "";
    article.append(strong, small);
    return article;
  }

  function buildTimelineItem(item: { detail?: string; state?: string; kind?: string; atMs?: number }) {
    const article = document.createElement("article");
    article.className = "timeline-item";
    const wrapper = document.createElement("span");
    const strong = document.createElement("strong");
    strong.textContent = item.detail || item.state || item.kind || "event";
    const small = document.createElement("small");
    small.textContent = `${item.kind || "event"} · ${VD.formatWhen(item.atMs)}`;
    wrapper.append(strong, small);
    article.append(wrapper);
    return article;
  }

  function buildPidFrameItem(frame: { command?: string; name?: string; valueText?: string; rawResponse?: string; parsed?: boolean }) {
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

  function buildRealInsights(review: VoltSessionReview) {
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
        title: maxSpeed ? `Max speed ${VD.units.speedText(maxSpeed)}` : "No speed peak yet",
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

  function stateCountSummary(counts: Record<string, number>) {
    return Object.keys(counts || {})
      .filter((key) => key && key !== "unknown")
      .map((key) => `${key}: ${counts[key]}`)
      .join(", ") || "Only unknown state samples stored.";
  }

  function renderRealV2Ui() {
    const storage = state.storage || {};
    const overview: Record<string, unknown> = storage.overview || {};
    const battery: Record<string, unknown> = storage.batterySummary || {};
    const charge = storage.chargeSummary || {};
    const route = VD.selectedMapRoute(storage);
    const hasRows = VD.dbRowCount(storage) > 0;
    const _hasRoute = Number(route.pointCount || 0) >= 2;
    const hasCharge = Number(charge.chargeSessionCount || charge.chargingHintCount || 0) > 0;
    const latestSnapshot = battery.latestBatterySnapshot as Record<string, unknown> | undefined;
    const latest: Record<string, unknown> = latestSnapshot && Object.keys(latestSnapshot).length
      ? latestSnapshot
      : ((battery.latestTelemetry as Record<string, unknown>) || (overview.latestTelemetry as Record<string, unknown>) || {});
    toggleHidden("appEmptyState", hasRows);
    toggleHidden("chargeEmptyState", hasCharge);
    // Gate the Insights first-run guide on actual insight content, not raw dbRowCount. dbRowCount
    // sums unrelated tables (PID observations, location samples, diagnostic codes...), so a single
    // connect/scan that writes any row but completes no trip used to hide the guide while every
    // Insights stat still read "--" — a screen that looked broken. Mirror the chargeEmptyState
    // pattern and key on the data the Insights screen actually renders: a logged trip/distance or
    // a battery reading.
    const insightsSummary = state.insights || {};
    const insightSoc = Number(latest.soc);
    const hasInsights =
      Number(insightsSummary.tripCount || 0) > 0 ||
      Number(insightsSummary.totalDistanceMeters || 0) > 0 ||
      (Number.isFinite(insightSoc) && insightSoc > 0);
    toggleHidden("insightsEmptyState", hasInsights);
    const routeDistance = Number(route.distanceMeters || overview.distanceMeters || 0);
    VD.setText("overviewDistance", routeDistance ? VD.formatDistance(routeDistance) : "--");
    VD.setText("overviewDistanceSub", route.pointCount ? `${route.pointCount} GPS samples in latest route` : "waiting for route samples");
    VD.setText("overviewMaxSpeed", overview.maxSpeedKph ? VD.units.speedText(Number(overview.maxSpeedKph)) : "--");
    const soc = Number(latest.soc);
    const power = Number(latest.powerKw ?? latest.packPowerKw);
    VD.setText("overviewBattery", Number.isFinite(soc) && soc > 0 ? `${Math.round(soc)}%` : (Number.isFinite(power) && power ? `${power.toFixed(1)} kW` : "--"));
    VD.setText("overviewBatterySub", Number.isFinite(power) && power ? `${power.toFixed(1)} kW latest power` : "SOC/power once observed");
    VD.setText("overviewChargeHints", Number(charge.chargingHintCount || overview.chargingHints || 0));

    VD.setText("realChargeSessions", Number(charge.chargeSessionCount || 0));
    VD.setText("realChargeHints", Number(charge.chargingHintCount || 0));
    VD.setText("realChargePower", charge.maxPowerKw ? `${Number(charge.maxPowerKw).toFixed(1)} kW` : "--");
    const chargingNow = (Array.isArray(charge.recentSessions) ? charge.recentSessions : [])
      .some(isChargeInProgress);
    VD.setText("realChargeStatus", chargingNow ? "charging" : (charge.chargeSessionCount ? "recorded" : (charge.chargingHintCount ? "needs review" : "needs data")));
    renderChargeSessions(charge);

    const ring = el("realPackRing");
    const ringValue = el("realPackValue");
    if (Number.isFinite(soc) && soc > 0) {
      if (ring) ring.style.setProperty("--v", String(Math.max(0, Math.min(100, soc))));
      if (ringValue) ringValue.textContent = `${Math.round(soc)}%`;
      VD.setText("realPackTitle", "Latest battery reading captured.");
      VD.setText("realPackCopy", `${Number.isFinite(power) ? power.toFixed(1) + " kW · " : ""}${latest.vehicleState || "vehicle state unknown"} · accuracy improves as more drives are logged.`);
    } else {
      if (ring) ring.style.setProperty("--v", "0");
      if (ringValue) ringValue.textContent = "--";
      VD.setText("realPackTitle", "Waiting for battery readings.");
      VD.setText("realPackCopy", "Battery charge, power, and pack health appear here once the adapter has logged a few readings.");
    }
    renderPackStats(latest);

    const maintenance = el("maintenanceList");
    if (maintenance) {
      const rows: Array<[string, string, string]> = [
        ["Tire rotation", "Log rotations against your odometer reading above", "manual"],
        ["Battery coolant", "No service-interval data yet — track manually for now", "watch"],
        ["Engine oil", "Log oil changes manually — live oil-life tracking is planned", "pending"]
      ];
      maintenance.replaceChildren(...rows.map(([name, detail, tag]) => buildMaintenanceRow(name, detail, tag)));
    }

    renderVehicleUi();
  }

  function buildMaintenanceRow(name: string, detail: string, tag: string) {
    const article = document.createElement("article");
    article.className = "real-insight-item";
    const center = document.createElement("span");
    const strong = document.createElement("strong");
    strong.textContent = name;
    const small = document.createElement("small");
    small.textContent = detail;
    center.append(strong, small);
    const right = document.createElement("b");
    right.className = "maint-tag";
    right.dataset.tag = String(tag || "").toLowerCase();
    right.textContent = tag;
    article.append(center, right);
    return article;
  }

  // A charge row counts as "in progress" only when it has no end time AND a
  // live signal (start SOC or power). A row missing all fields is "details
  // pending", not charging — so the active-charge badge never fires on a stub.
  function isChargeInProgress(session: VoltChargeSessionRow) {
    return Boolean(
      session && session.endedAtMs == null && session.startedAtMs &&
        (session.startSoc != null || session.powerKw != null),
    );
  }

  // Per-session charge history for the Charge tab. The native chargeSummary now
  // ships a `recentSessions` array (newest first); the card stays hidden until
  // at least one real session exists so the empty tab keeps its first-run guide.
  function renderChargeSessions(charge: VoltChargeSummary) {
    const card = el("chargeSessionsCard");
    const list = el("chargeSessionsList");
    const sessions = Array.isArray(charge.recentSessions) ? charge.recentSessions : [];
    if (card) card.hidden = sessions.length === 0;
    if (!list) return;
    if (!sessions.length) {
      list.replaceChildren();
      return;
    }
    VD.setText("chargeSessionsTitle", `${sessions.length} recent charge${sessions.length === 1 ? "" : "s"}`);
    list.replaceChildren(...sessions.slice(0, 12).map(buildChargeSessionRow));
    renderChargeEnergy(sessions);
  }

  function formatMoney(value: number) {
    return "$" + value.toFixed(2);
  }

  // Sums energy across the logged charge sessions and, when the user has set an
  // electricity rate (Settings → Preferences), shows the estimated cost. The rate
  // is a display-layer preference, so the math lives here in JS.
  function renderChargeEnergy(sessions: VoltChargeSessionRow[]) {
    const card = el("chargeEnergyCard");
    if (!card) return;
    const total = sessions.reduce((acc, session) => {
      const e = chargeNum(session.energyKwh);
      return Number.isFinite(e) && e > 0 ? acc + e : acc;
    }, 0);
    if (total <= 0) {
      card.hidden = true;
      return;
    }
    card.hidden = false;
    VD.setText("chargeEnergyTotal", `${total.toFixed(1)} kWh`);
    VD.setText("chargeEnergySub", `across ${sessions.length} logged charge${sessions.length === 1 ? "" : "s"}`);
    const price = VD.prefs.get<number>("pricePerKwh", 0);
    const hint = el("chargeEnergyHint");
    if (price > 0) {
      VD.setText("chargeEnergyCost", formatMoney(total * price));
      if (hint) hint.hidden = true;
    } else {
      VD.setText("chargeEnergyCost", "--");
      if (hint) hint.hidden = false;
    }
  }

  function chargeNum(value: unknown) {
    // Native sends JSON null for missing fields; coerce those to NaN so a real
    // 0 reading and "no data" don't both render as "0".
    return value == null || value === "" ? NaN : Number(value);
  }

  function chargerLabel(type: unknown) {
    const raw = String(type == null ? "" : type).trim();
    const key = raw.toLowerCase().replace(/[\s-]+/g, "_");
    if (!key || key === "unknown" || key === "null") return "";
    const known: Record<string, string> = {
      level1: "Level 1",
      level2: "Level 2",
      dc_fast: "DC fast",
      dcfast: "DC fast"
    };
    return known[key] || raw.charAt(0).toUpperCase() + raw.slice(1);
  }

  function buildChargeSessionRow(session: VoltChargeSessionRow) {
    const row = document.createElement("article");
    row.className = "charge-session-row";
    const inProgress = isChargeInProgress(session);
    if (inProgress) row.dataset.charging = "1";
    const center = document.createElement("span");
    const strong = document.createElement("strong");
    strong.textContent = [VD.formatWhen(session.startedAtMs), chargerLabel(session.chargerType)]
      .filter(Boolean)
      .join(" · ");
    const small = document.createElement("small");
    const startSoc = chargeNum(session.startSoc);
    const endSoc = chargeNum(session.endSoc);
    const power = chargeNum(session.powerKw);
    const endedAtMs = chargeNum(session.endedAtMs);
    const durationMs = Number.isFinite(endedAtMs) ? endedAtMs - Number(session.startedAtMs) : NaN;
    const parts: string[] = [];
    if (Number.isFinite(startSoc) && Number.isFinite(endSoc)) parts.push(`${Math.round(startSoc)}% → ${Math.round(endSoc)}%${inProgress ? " now" : ""}`);
    if (Number.isFinite(power) && power > 0) parts.push(`${power.toFixed(1)} kW`);
    if (inProgress) parts.push("charging now");
    else if (Number.isFinite(durationMs) && durationMs > 0 && typeof VD.formatDuration === "function") parts.push(VD.formatDuration(durationMs));
    small.textContent = parts.length ? parts.join(" · ") : "charge details pending";
    center.append(strong, small);
    const right = document.createElement("b");
    const energy = chargeNum(session.energyKwh);
    const socGain = Number.isFinite(startSoc) && Number.isFinite(endSoc) ? endSoc - startSoc : NaN;
    const price = VD.prefs.get<number>("pricePerKwh", 0);
    if (Number.isFinite(energy) && energy > 0) {
      right.textContent =
        price > 0 ? `${energy.toFixed(1)} kWh · ${formatMoney(energy * price)}` : `${energy.toFixed(1)} kWh`;
    } else if (Number.isFinite(socGain) && socGain > 0) {
      right.textContent = `+${Math.round(socGain)}%`;
    } else {
      right.textContent = "--";
    }
    row.append(center, right);
    return row;
  }

  function firstNum(values: unknown[]) {
    for (const v of values) {
      if (v == null || v === "") continue;
      const n = Number(v);
      if (Number.isFinite(n)) return n;
    }
    return NaN;
  }

  // HV-pack detail. The battery snapshot already rides in the storage payload —
  // surface voltage / temp / health / power as a stat row beneath the SOC ring
  // so the Insights hero shows the pack, not just a charge percentage. Hidden
  // until at least one field is real.
  function renderPackStats(latest: Record<string, unknown>) {
    const row = el("realPackStats");
    if (!row) return;
    const voltage = firstNum([latest.packVoltage]);
    const temp = firstNum([latest.batteryTempC, latest.batteryTemp]);
    const soh = firstNum([latest.sohPct]);
    const packPower = firstNum([latest.packPowerKw, latest.powerKw]);
    const stats: Array<[string, string | null]> = [
      ["Pack", Number.isFinite(voltage) ? `${Math.round(voltage)} V` : null],
      ["Temp", Number.isFinite(temp) ? VD.units.tempText(temp) : null],
      ["Health", Number.isFinite(soh) && soh > 0 ? `${Math.round(soh)}%` : null],
      ["Power", Number.isFinite(packPower) && packPower !== 0 ? `${packPower.toFixed(1)} kW` : null]
    ].filter((pair) => pair[1] != null) as Array<[string, string]>;
    if (!stats.length) {
      row.hidden = true;
      row.replaceChildren();
      return;
    }
    row.hidden = false;
    row.replaceChildren(...stats.map((pair) => buildPackStat(String(pair[0]), String(pair[1]))));
  }

  function buildPackStat(label: string, value: string) {
    const cell = document.createElement("div");
    const span = document.createElement("span");
    span.textContent = label;
    const strong = document.createElement("strong");
    strong.textContent = value;
    cell.append(span, strong);
    return cell;
  }

  // Vehicle identity card. Reads state.appState.vehicle once the OBD bridge can
  // supply it; every field degrades to "--" until its PID/source is validated.
  // Expected vehicle fields: name, vin, year, make, model, odometerMiles (or
  // odometerKm). (Electric-mix % and battery-health % were dropped: no PID
  // captures state-of-health and there's no EV/engine distance split, so the
  // native layer can't populate them — see demo-native-contract.test.js.)
  function renderVehicleUi() {
    const vehicle: Record<string, unknown> = (state.appState || {}).vehicle || {};
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
    // Odometer keeps thousands separators (large numbers), so it formats locally
    // rather than via units.distance which targets short trip/route figures.
    const odoMetric = VD.units.system() === "metric";
    let odometer = "--";
    if (Number.isFinite(odoMiles) && odoMiles > 0) {
      const v = odoMetric ? odoMiles / 0.621371 : odoMiles;
      odometer = `${Math.round(v).toLocaleString()} ${odoMetric ? "km" : "mi"}`;
    } else if (Number.isFinite(odoKm) && odoKm > 0) {
      const v = odoMetric ? odoKm : odoKm * 0.621371;
      odometer = `${Math.round(v).toLocaleString()} ${odoMetric ? "km" : "mi"}`;
    }
    VD.setText("vehicleOdometer", odometer);

    const loggedMeters = Number(insights.totalDistanceMeters || 0);
    VD.setText("vehicleLoggedDistance", loggedMeters > 0 ? VD.formatDistance(loggedMeters) : "--");
  }

  function toggleHidden(id: string, hidden: unknown) {
    const node = el(id);
    if (node) node.hidden = Boolean(hidden);
  }

  Object.assign(VD, {
    isNativeError,
    reportNativeReadError,
    buildStatusCopy,
    setStorage,
    updateStorageUi,
    updateDiagnosticCodeUi,
    updateReviewUi,
    buildRealInsights,
    stateCountSummary,
    renderRealV2Ui,
    renderVehicleUi,
    toggleHidden
  });
})();

export {};
