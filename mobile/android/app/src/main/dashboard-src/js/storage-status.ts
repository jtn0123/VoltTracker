// storage-status.ts — Settings/Database storage summary, the post-session
// review block, the DTC (diagnostic trouble code) report, the Insights "real
// v2" hero (battery/charge/vehicle) and the Charge tab history.
//
// Split out of the old panels.ts god-module (C2). Cross-module render entry
// points are attached to the shared VD global exactly as before; the few helpers
// the sibling panels (signals-panel.ts, insights-panel.ts) reach for —
// isNativeError, reportNativeReadError, buildStatusCopy, toggleHidden — are
// published on VD here so this module stays the single owner of them.
import { el, setSvgAttrs } from "./core";
import { setDataState } from "./dataset-state";
import { validatePayload } from "./payload-validators";
import { prefs, units } from "./prefs";

(function () {
  "use strict";

  const VD = window.VoltDashboard;
  const state = VD.state;
  const bridge = VD.bridge;

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
    validatePayload("setStorage", parsed);
    if (isNativeError(parsed)) {
      const err = parsed as VoltNativeError;
      reportNativeReadError(parsed, "Could not read local storage summary.");
      const storageError: VoltStorageSummary = { message: err.message || "" };
      if (err.error) storageError.error = err.error;
      state.storage = storageError;
      updateStorageUi();
      updateReviewUi();
      renderRealV2Ui();
      VD.renderMapIfLoaded();
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
    VD.renderMapIfLoaded();
    VD.updateValidationUi();
    VD.loadTrips();
    VD.loadInsights();
    loadMaintenanceLog();
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
    // Presence check, not truthiness: a legitimate rawTelemetryCount of 0 must
    // render as 0 — only fall back to the sample count when the field is
    // missing (null/undefined) or non-numeric.
    const rawTelemetry = storage.rawTelemetryCount == null ? NaN : Number(storage.rawTelemetryCount);
    VD.setText("dbRawTelemetryCount", Number.isFinite(rawTelemetry) ? rawTelemetry : samples);
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

  // Normalize a DTC severity to one of three buckets. The dtc-causes database
  // tags codes "info" / "warning" / "critical"; when a code isn't in the
  // database (severity null), fall back to a heuristic on the code family:
  //   U (network), B (body), P0 powertrain → warning by default
  //   C (chassis: ABS/brakes), P safety/driveability families → critical
  //   anything else → info
  // The heuristic is deliberately conservative: a chassis/brake code maps to
  // "critical" (stop safely) because those affect vehicle control, while a
  // generic powertrain or body code maps to "warning" (service soon).
  function dtcSeverity(rawCode: unknown, metaSeverity: string | null): "critical" | "warning" | "info" {
    const meta = String(metaSeverity || "").toLowerCase();
    if (meta === "critical" || meta === "warning" || meta === "info") return meta;
    const code = String(rawCode || "").trim().toUpperCase();
    const family = code.charAt(0);
    if (family === "C") return "critical"; // chassis: ABS / brakes / steering
    if (family === "B" || family === "U") return "warning"; // body / network
    if (family === "P") return "warning"; // powertrain default
    return "info";
  }

  function severityLabel(severity: "critical" | "warning" | "info"): string {
    if (severity === "critical") return "Critical";
    if (severity === "warning") return "Warning";
    return "Info";
  }

  // Plain-language "is it safe to drive?" line per severity bucket.
  function drivabilityLine(severity: "critical" | "warning" | "info"): string {
    if (severity === "critical") return "Stop safely — have it checked before driving on";
    if (severity === "warning") return "Service soon — generally safe to drive in the meantime";
    return "Safe to drive — monitor at your next service";
  }

  function buildDtcItem(code: VoltDtcRow, isExample: boolean) {
    const article = document.createElement("article");
    article.className = "dtc-item";
    article.dataset.status = String(code.status || "stored");
    if (isExample) article.dataset.example = "true";
    const info = typeof VD.dtcInfo === "function" ? VD.dtcInfo(String(code.dtc || "")) : null;
    const severity = dtcSeverity(code.dtc, info ? info.severity : null);
    article.dataset.severity = severity;

    const codeBlock = document.createElement("span");
    codeBlock.className = "dtc-code-block";
    const codeB = document.createElement("b");
    codeB.className = "dtc-code";
    codeB.textContent = code.dtc || "--";
    // Severity pill + plain-language drivability line. Both are derived from the
    // dtc-causes metadata when present, otherwise from the code family (see
    // dtcSeverity), so every scanned code carries a "safe to drive" read.
    const sevBadge = document.createElement("span");
    sevBadge.className = "dtc-sev";
    sevBadge.dataset.severity = severity;
    sevBadge.textContent = severityLabel(severity);
    const codeSmall = document.createElement("small");
    codeSmall.textContent = code.statusLabel || code.status || "stored";
    codeBlock.append(codeB, sevBadge, codeSmall);

    const moduleBlock = document.createElement("span");
    moduleBlock.className = "dtc-module-block";
    const headline = document.createElement("strong");
    if (info && info.description) {
      headline.textContent = info.description;
    } else if (info && info.category) {
      headline.textContent = info.category;
    } else {
      headline.textContent = code.moduleName || "Unknown code - tap to look up";
    }
    moduleBlock.append(headline);

    const drive = document.createElement("span");
    drive.className = "dtc-drivability";
    drive.dataset.severity = severity;
    drive.textContent = drivabilityLine(severity);
    moduleBlock.append(drive);

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
    VD.setText("reviewMaxSpeed", maxSpeed ? units.speedText(maxSpeed) : "--");
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
        title: maxSpeed ? `Max speed ${units.speedText(maxSpeed)}` : "No speed peak yet",
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

  function selectedRouteForOverview(storage: VoltStorageSummary): VoltRoute {
    if (typeof VD.selectedMapRoute === "function") {
      return VD.selectedMapRoute(storage);
    }
    const routes = Array.isArray(storage.recentRoutes) ? storage.recentRoutes : [];
    if (!routes.length) return {};
    const selectedId = String(state.selectedMapSessionId || "");
    const selected = selectedId
      ? routes.find((route) => String((route.session || {}).id || "") === selectedId)
      : null;
    return (selected || routes[0] || {}) as VoltRoute;
  }

  // The latest battery/telemetry reading the Insights hero renders — preferring
  // the stored battery snapshot, then the battery/overview telemetry echoes.
  function latestInsightReading(storage: VoltStorageSummary): Record<string, unknown> {
    const overview: Record<string, unknown> = storage.overview || {};
    const battery: Record<string, unknown> = storage.batterySummary || {};
    const latestSnapshot = battery.latestBatterySnapshot as Record<string, unknown> | undefined;
    return latestSnapshot && Object.keys(latestSnapshot).length
      ? latestSnapshot
      : ((battery.latestTelemetry as Record<string, unknown>) || (overview.latestTelemetry as Record<string, unknown>) || {});
  }

  // Gate the Insights first-run guide on actual insight content, not raw dbRowCount. dbRowCount
  // sums unrelated tables (PID observations, location samples, diagnostic codes...), so a single
  // connect/scan that writes any row but completes no trip used to hide the guide while every
  // Insights stat still read "--" — a screen that looked broken. Mirror the chargeEmptyState
  // pattern and key on the data the Insights screen actually renders: a logged trip/distance or
  // a battery reading. Shared with insights-panel.ts (via VD) so the empty-state toggle can be
  // re-evaluated AFTER loadInsights() refreshes state.insights — setStorage runs renderRealV2Ui
  // before the insights payload lands, so this single render pass would otherwise gate on stale
  // data.
  function hasInsightContent(): boolean {
    const storage = state.storage || {};
    const insightsSummary = state.insights || {};
    const insightSoc = Number(latestInsightReading(storage).soc);
    return (
      Number(insightsSummary.tripCount || 0) > 0 ||
      Number(insightsSummary.totalDistanceMeters || 0) > 0 ||
      (Number.isFinite(insightSoc) && insightSoc > 0)
    );
  }

  type SohPoint = { at: number; soh: number; cap: number };

  // SOH history changes at most once per session (the pack-capacity PID is rare),
  // but renderRealV2Ui runs on every app-state broadcast. Throttle the SQLite-backed
  // bridge fetch and only rebuild the chart DOM when the payload actually changed.
  const SOH_REFETCH_MS = 30_000;
  let sohLastFetchMs = 0;
  let sohLastRaw: string | null = null;
  let sohPoints: SohPoint[] = [];
  let sohDirty = true;
  // Battery-snapshot row count at the last fetch. When it changes (a new snapshot
  // landed, or storage was cleared) we refetch immediately instead of waiting out
  // the throttle, so the chart never shows stale history after a storage change.
  let sohLastCount = -1;

  function sohSpanLabel(fromMs: number, toMs: number): string {
    const days = Math.max(0, Math.round((toMs - fromMs) / 86_400_000));
    if (days < 1) return "today";
    if (days < 14) return `${days} days`;
    if (days < 60) return `${Math.round(days / 7)} weeks`;
    return `${Math.round(days / 30)} months`;
  }

  function buildSohSvg(points: SohPoint[]): SVGElement {
    const ns = "http://www.w3.org/2000/svg";
    const w = 320;
    const h = 120;
    const padL = 30;
    const padR = 10;
    const padT = 12;
    const padB = 18;
    const plotW = w - padL - padR;
    const plotH = h - padT - padB;
    const ts = points.map((p) => p.at);
    const ss = points.map((p) => p.soh);
    const tMin = Math.min(...ts);
    const tMax = Math.max(...ts);
    let yMin = Math.min(...ss);
    let yMax = Math.max(...ss);
    if (yMax - yMin < 1) {
      yMin -= 1;
      yMax += 1;
    }
    const yPad = (yMax - yMin) * 0.12;
    yMin -= yPad;
    yMax += yPad;
    const tSpan = tMax - tMin;
    const xOf = (t: number) => padL + (tSpan === 0 ? plotW / 2 : ((t - tMin) / tSpan) * plotW);
    const yOf = (s: number) => padT + (1 - (s - yMin) / (yMax - yMin)) * plotH;
    const make = (tag: string, attrs: Record<string, string | number>) =>
      setSvgAttrs(document.createElementNS(ns, tag) as SVGElement, attrs);
    const svg = make("svg", {
      viewBox: `0 0 ${w} ${h}`,
      class: "soh-trend-svg",
      role: "img",
      "aria-label": `Battery state of health trend, latest ${ss[ss.length - 1].toFixed(1)} percent`,
    });
    for (const val of [yMax, (yMin + yMax) / 2, yMin]) {
      const y = yOf(val);
      svg.appendChild(make("line", { x1: String(padL), x2: String(w - padR), y1: y.toFixed(1), y2: y.toFixed(1), class: "soh-grid" }));
      const label = make("text", { x: "2", y: (y + 3).toFixed(1), class: "soh-axis" });
      label.textContent = val.toFixed(0);
      svg.appendChild(label);
    }
    let d = "";
    points.forEach((p, i) => {
      d += `${i === 0 ? "M" : "L"}${xOf(p.at).toFixed(1)} ${yOf(p.soh).toFixed(1)} `;
    });
    svg.appendChild(make("path", { d: d.trim(), class: "soh-line" }));
    const last = points[points.length - 1];
    svg.appendChild(make("circle", { cx: xOf(last.at).toFixed(1), cy: yOf(last.soh).toFixed(1), r: "3", class: "soh-dot" }));
    return svg;
  }

  // Battery state-of-health trend on the Battery tab. SOH/capacity snapshots are
  // logged whenever the car answers the (rare) pack-capacity PID, so this fills in
  // slowly over weeks — empty state until at least two readings exist.
  function renderBatterySohTrend() {
    const card = el("sohTrendCard");
    if (!card) return;
    const now = Date.now();
    const batteryCount = Number((state.storage || {}).batterySnapshotCount || 0);
    const countChanged = batteryCount !== sohLastCount;
    if (
      bridge &&
      typeof bridge.getBatterySohHistory === "function" &&
      (sohLastRaw === null || countChanged || now - sohLastFetchMs >= SOH_REFETCH_MS)
    ) {
      sohLastFetchMs = now;
      sohLastCount = batteryCount;
      const raw = bridge.getBatterySohHistory();
      if (raw !== sohLastRaw) {
        sohLastRaw = raw;
        const parsed = VD.parsePayload<Array<Record<string, unknown>>>(raw, []);
        sohPoints = Array.isArray(parsed)
          ? parsed
              .map((r) => ({ at: Number(r.capturedAtMs), soh: Number(r.sohPct), cap: Number(r.capacityAh) }))
              .filter((p) => Number.isFinite(p.at) && Number.isFinite(p.soh))
          : [];
        sohDirty = true;
      }
    }
    // Nothing fetched/changed since the last DOM build — skip the rebuild.
    if (!sohDirty) return;
    sohDirty = false;
    const points = sohPoints;
    const has = points.length >= 2;
    const chart = el("sohTrendChart");
    const stats = el("sohTrendStats");
    const empty = el("sohTrendEmpty");
    const latestEl = el("sohTrendLatest");
    if (chart) chart.hidden = !has;
    if (stats) stats.hidden = !has;
    if (empty) empty.hidden = has;
    if (!has) {
      VD.setText("sohTrendTitle", "No battery-health readings yet");
      if (latestEl) latestEl.textContent = points.length === 1 ? `${points[0].soh.toFixed(1)}%` : "--";
      return;
    }
    const latest = points[points.length - 1];
    VD.setText("sohTrendTitle", "Pack state-of-health over time");
    if (latestEl) latestEl.textContent = `${latest.soh.toFixed(1)}%`;
    VD.setText("sohTrendCapacity", Number.isFinite(latest.cap) ? `${latest.cap.toFixed(1)} Ah` : "--");
    VD.setText("sohTrendCount", String(points.length));
    VD.setText("sohTrendSpan", sohSpanLabel(points[0].at, latest.at));
    if (chart) chart.replaceChildren(buildSohSvg(points));
  }

  function renderRealV2Ui() {
    const storage = state.storage || {};
    const overview: Record<string, unknown> = storage.overview || {};
    const charge = storage.chargeSummary || {};
    const route = selectedRouteForOverview(storage);
    const hasRows = VD.dbRowCount(storage) > 0;
    const _hasRoute = Number(route.pointCount || 0) >= 2;
    const hasCharge = Number(charge.chargeSessionCount || charge.chargingHintCount || 0) > 0;
    const latest = latestInsightReading(storage);
    toggleHidden("appEmptyState", hasRows);
    toggleHidden("chargeEmptyState", hasCharge);
    toggleHidden("insightsEmptyState", hasInsightContent());
    const routeDistance = Number(route.distanceMeters || overview.distanceMeters || 0);
    VD.setText("overviewDistance", routeDistance ? VD.formatDistance(routeDistance) : "--");
    VD.setText("overviewDistanceSub", route.pointCount ? `${route.pointCount} GPS samples in latest route` : "waiting for route samples");
    VD.setText("overviewMaxSpeed", overview.maxSpeedKph ? units.speedText(Number(overview.maxSpeedKph)) : "--");
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
    // Keep the status pill's color in sync with the text (see base.css badge states).
    setDataState(
      el("realChargeStatusBadge"),
      chargingNow
        ? "charging"
        : (charge.chargeSessionCount ? "recorded" : (charge.chargingHintCount ? "needs-review" : "waiting"))
    );
    renderChargeSessions(charge);
    renderBatterySohTrend();

    const ring = el("realPackRing");
    const ringValue = el("realPackValue");
    if (Number.isFinite(soc) && soc > 0) {
      if (ring) {
        ring.style.setProperty("--v", String(Math.max(0, Math.min(100, soc))));
        // Leave the "waiting" neutral track once a real SOC reading exists.
        ring.removeAttribute("data-state");
      }
      if (ringValue) ringValue.textContent = `${Math.round(soc)}%`;
      VD.setText("realPackTitle", "Latest battery reading captured.");
      VD.setText("realPackCopy", `${Number.isFinite(power) ? power.toFixed(1) + " kW · " : ""}${latest.vehicleState || "vehicle state unknown"} · accuracy improves as more drives are logged.`);
    } else {
      if (ring) {
        ring.style.setProperty("--v", "0");
        setDataState(ring, "waiting");
      }
      if (ringValue) ringValue.textContent = "--";
      VD.setText("realPackTitle", "Waiting for battery readings.");
      VD.setText("realPackCopy", "Battery charge, power, and pack health appear here once the adapter has logged a few readings.");
    }
    renderPackStats(latest);

    renderMaintenanceList();
    renderVehicleUi();
  }

  // Renders the user's real maintenance log (M5) into #maintenanceList, replacing the old
  // hardcoded placeholder rows. When empty, shows next-due GUIDANCE only (never a fake logged
  // entry) so the card reads as a prompt to start logging, not as fabricated history.
  function renderMaintenanceList() {
    const maintenance = el("maintenanceList");
    if (!maintenance) return;
    const entries = Array.isArray(state.maintenanceLog) ? state.maintenanceLog : [];
    if (!entries.length) {
      maintenance.replaceChildren(buildMaintenanceEmptyState());
      return;
    }
    maintenance.replaceChildren(...entries.map(buildMaintenanceEntryRow));
  }

  // Empty-state guidance: a short "what to track" hint, NOT a logged row. Pure DOM (no innerHTML).
  function buildMaintenanceEmptyState() {
    const article = document.createElement("article");
    article.className = "real-insight-item maint-empty";
    const center = document.createElement("span");
    const strong = document.createElement("strong");
    strong.textContent = "No maintenance logged yet";
    const small = document.createElement("small");
    small.textContent =
      "Log oil changes, tire rotations, and coolant service against your odometer to start a history.";
    center.append(strong, small);
    article.append(center);
    return article;
  }

  // One real maintenance entry. Title is the type; the detail line carries the date, odometer (when
  // known) and note. A delete button carries the entry id for actions.ts. createElement/textContent
  // only — never innerHTML — so a hostile type/note can't inject markup (DOM XSS-safe).
  function buildMaintenanceEntryRow(entry: VoltMaintenanceEntry) {
    const article = document.createElement("article");
    article.className = "real-insight-item";
    const center = document.createElement("span");
    const strong = document.createElement("strong");
    const type = String(entry.type || "").trim();
    strong.textContent = type || "Service";
    const small = document.createElement("small");
    small.textContent = maintenanceDetailLine(entry);
    center.append(strong, small);

    const right = document.createElement("button");
    right.type = "button";
    right.className = "maint-del";
    right.dataset.maintDelete = entry.id == null ? "" : String(entry.id);
    right.title = "Remove this maintenance entry.";
    right.textContent = "Remove";
    article.append(center, right);
    return article;
  }

  function maintenanceDetailLine(entry: VoltMaintenanceEntry) {
    const parts: string[] = [];
    const at = Number(entry.createdAtMs);
    if (Number.isFinite(at) && at > 0) parts.push(VD.formatWhen(at));
    const odo = Number(entry.odometerKm);
    if (Number.isFinite(odo) && odo > 0) {
      // Reuse the unit-aware distance formatter so the odometer respects imperial/metric.
      parts.push(units.distanceText(odo));
    }
    const note = String(entry.note || "").trim();
    if (note) parts.push(note);
    return parts.length ? parts.join(" · ") : "Logged";
  }

  // Loads the maintenance log from native into state, then re-renders the list. Guards the bridge
  // (absent outside the WebView) and tolerates a malformed payload by falling back to an empty log.
  function loadMaintenanceLog() {
    if (!bridge || typeof bridge.getMaintenanceLog !== "function") return;
    const parsed = VD.parsePayload<VoltMaintenanceEntry[]>(bridge.getMaintenanceLog(), []);
    state.maintenanceLog = Array.isArray(parsed) ? parsed : [];
    renderMaintenanceList();
  }

  // Prompts for a maintenance entry and forwards it to native (M5). Uses sequential prompts (the
  // WebView has no inline form here) — type is required; odometer/note are optional; cancel aborts.
  function addMaintenanceEntry() {
    if (!bridge || typeof bridge.addMaintenanceEntry !== "function") {
      VD.setStatus({ state: "idle", detail: "Maintenance logging is available inside the Android app." });
      return;
    }
    const type = window.prompt("What service? (e.g. Oil change, Tire rotation)", "");
    if (type === null) return;
    if (!type.trim()) {
      VD.setStatus({ state: "blocked", detail: "Add a service type before saving." });
      return;
    }
    const odoRaw = window.prompt("Odometer reading (optional, in your distance unit):", "");
    if (odoRaw === null) return;
    const note = window.prompt("Note (optional):", "");
    if (note === null) return;
    const payload: { type: string; note: string; date: number; odometerKm?: number } = {
      type: type.trim(),
      note: note.trim(),
      date: Date.now()
    };
    const odoKm = parseOdometerKm(odoRaw);
    if (odoKm != null) payload.odometerKm = odoKm;
    bridge.addMaintenanceEntry(JSON.stringify(payload));
  }

  // Converts a user-entered odometer reading (in their display distance unit) to km for storage.
  // Returns null for blank/invalid input so the entry simply omits the odometer.
  function parseOdometerKm(raw: string): number | null {
    const value = Number(String(raw || "").trim());
    if (!Number.isFinite(value) || value <= 0) return null;
    return units.distanceUnit() === "mi" ? value * 1.609344 : value;
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
      // renderChargeEnergy owns the energy card's hidden flag — it must run on
      // the empty path too, or a stale "Energy logged" total survives clearing
      // the stored data.
      renderChargeEnergy(sessions);
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
      // Clear the previous totals so a momentary unhide can never flash stale
      // kWh / cost figures from a cleared database.
      VD.setText("chargeEnergyTotal", "-- kWh");
      VD.setText("chargeEnergyCost", "--");
      return;
    }
    card.hidden = false;
    VD.setText("chargeEnergyTotal", `${total.toFixed(1)} kWh`);
    VD.setText("chargeEnergySub", `across ${sessions.length} logged charge${sessions.length === 1 ? "" : "s"}`);
    const price = prefs.get<number>("pricePerKwh", 0);
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
    const price = prefs.get<number>("pricePerKwh", 0);
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
      ["Temp", Number.isFinite(temp) ? units.tempText(temp) : null],
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
    const odoMetric = units.system() === "metric";
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
    hasInsightContent,
    setStorage,
    updateStorageUi,
    updateDiagnosticCodeUi,
    updateReviewUi,
    buildRealInsights,
    stateCountSummary,
    renderRealV2Ui,
    renderVehicleUi,
    loadMaintenanceLog,
    renderMaintenanceList,
    addMaintenanceEntry,
    toggleHidden
  });
})();

export {};
