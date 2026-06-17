// storage-status.ts — Settings/Database storage summary, the post-session
// review block, the DTC (diagnostic trouble code) report, the Insights "real
// v2" hero (battery/charge/vehicle) and the Charge tab history.
//
// Split out of the old panels.ts god-module (C2). Cross-module render entry
// points are attached to the shared VD global exactly as before; the few helpers
// the sibling panels (signals-panel.ts, insights-panel.ts) reach for —
// isNativeError, reportNativeReadError, buildStatusCopy, toggleHidden — are
// published on VD here so this module stays the single owner of them.
import { rateForCharger } from "./cost-model";
import { el, setSvgAttrs } from "./core";
import { setDataState, type DataStateValue } from "./dataset-state";
import { validatePayload } from "./payload-validators";
import { prefs, units } from "./prefs";

(function () {
  "use strict";

  const VD = window.VoltDashboard;
  const state = VD.state;
  const bridge = VD.bridge;
  let storageDetailsScheduled = false;
  let applyingStorageDetails = false;

  function invalidateLazyRollups() {
    state.tripsLoaded = false;
    state.insightsLoaded = false;
  }

  function loadRollupsForActiveView() {
    const activeView = () => document.body?.dataset?.activeView || "";
    const initialView = activeView();
    if (initialView !== "map" && initialView !== "insights") return;
    const load = () => {
      const view = activeView();
      if (view === "map" && typeof VD.loadTrips === "function") {
        VD.loadTrips();
      } else if (view === "insights") {
        if (typeof VD.loadTrips === "function") VD.loadTrips();
        if (typeof VD.loadInsights === "function") VD.loadInsights();
      }
    };
    if (typeof VD.ensureInsightsModule === "function") {
      void VD.ensureInsightsModule().then(load).catch(() => {});
      return;
    }
    load();
  }

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

  function isStorageDetailsPayload(payload: unknown): boolean {
    return Boolean(
      payload != null &&
      typeof payload === "object" &&
      (payload as { storageDetails?: unknown }).storageDetails
    );
  }

  function scheduleStorageDetailsLoad() {
    if (!bridge || typeof bridge.getStorageDetails !== "function") return;
    if (storageDetailsScheduled) return;
    storageDetailsScheduled = true;
    const load = () => {
      storageDetailsScheduled = false;
      const payload =
        typeof VD.callBridge === "function"
          ? VD.callBridge("getStorageDetails")
          : bridge.getStorageDetails();
      if (payload != null) {
        applyingStorageDetails = true;
        try {
          setStorage(payload);
        } finally {
          applyingStorageDetails = false;
        }
      }
    };
    if (typeof window.requestIdleCallback === "function") {
      window.requestIdleCallback(load, { timeout: 2000 });
    } else {
      setTimeout(load, 0);
    }
  }

  function setStorage(payload: unknown) {
    const parsed = VD.parsePayload<VoltStorageSummary>(payload, {});
    validatePayload("setStorage", parsed);
    if (isNativeError(parsed)) {
      const err = parsed as VoltNativeError;
      reportNativeReadError(parsed, "Could not read local storage summary.");
      if (applyingStorageDetails || err.error === "storage_details_failed") {
        return;
      }
      const storageError: VoltStorageSummary = { message: err.message || "" };
      if (err.error) storageError.error = err.error;
      state.storage = storageError;
      updateStorageUi();
      renderRealV2Ui();
      VD.renderMapIfLoaded();
      VD.updateValidationUi();
      return;
    }
    const isDetails = isStorageDetailsPayload(parsed);
    const newRoutes =
      parsed && Array.isArray(parsed.recentRoutes) ? parsed.recentRoutes : [];
    if (state.demoActive && state.demoPreviewStorage) {
      // Park the real summary while demo preview owns the screen (cross-module
      // invariant: restored when demo stops).
      if (isDetails) {
        const details = { ...(parsed as Record<string, unknown>) };
        delete details.storageDetails;
        VD.setState({ realStorage: { ...(state.realStorage || {}), ...details } });
      } else {
        VD.setState({ realStorage: parsed });
        scheduleStorageDetailsLoad();
      }
      return;
    }
    if (newRoutes.length > 0) {
      state._mapSampleLoaded = false;
    }
    if (isDetails) {
      const details = { ...(parsed as Record<string, unknown>) };
      delete details.storageDetails;
      state.storage = { ...(state.storage || {}), ...details };
    } else {
      state.storage = parsed;
    }
    updateStorageUi();
    renderRealV2Ui();
    VD.renderMapIfLoaded();
    VD.updateValidationUi();
    if (!isDetails) {
      invalidateLazyRollups();
      loadRollupsForActiveView();
      loadMaintenanceLog();
      scheduleStorageDetailsLoad();
    }
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
  // tags codes "info" / "warning" / "critical" and ALWAYS wins when present, so
  // a genuinely safety-critical powertrain code carried in the DB (e.g. P0AA6,
  // an HV-interlock fault) still resolves to "critical" via its metadata. The
  // family heuristic below only applies when a code is NOT in the database
  // (severity null):
  //   C (chassis: ABS / brakes / steering) → critical  (affects vehicle control)
  //   P (powertrain), B (body), U (network) → warning   (service soon)
  //   anything else → info
  // The P-family default is deliberately "warning", NOT "critical": without DB
  // metadata we can't tell a safety-critical powertrain fault from a routine
  // emissions code, and over-calling every unknown P-code "Stop safely" would
  // cry wolf on the most common scan result. Erring toward "warning" (service
  // soon — generally safe to drive) is the conservative, honest call for an
  // unknown powertrain code; the DB promotes the truly critical ones above.
  function dtcSeverity(rawCode: unknown, metaSeverity: string | null): "critical" | "warning" | "info" {
    const meta = String(metaSeverity || "").toLowerCase();
    if (meta === "critical" || meta === "warning" || meta === "info") return meta;
    const code = String(rawCode || "").trim().toUpperCase();
    const family = code.charAt(0);
    if (family === "C") return "critical"; // chassis: ABS / brakes / steering
    if (family === "B" || family === "U") return "warning"; // body / network
    if (family === "P") return "warning"; // powertrain default (see note above)
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

  // Whether a stored DTC can be erased by the OBD-II Mode 04 clear command. Permanent
  // (Mode 0A) codes are intentionally NON-clearable — the ECU re-asserts them until the
  // underlying fault clears its own readiness monitors — so the clear flow must not promise
  // they'll disappear (M10). Every other status (current/stored/pending/freeze-frame)
  // is erased by Mode 04.
  function isPermanentDtc(status: unknown): boolean {
    return String(status || "").trim().toLowerCase() === "permanent";
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
    // Clearability tag (M10): permanent codes survive a Mode 04 clear, every other status is
    // erased by it. Surfaced per-code so a user reading the report knows which codes the
    // "Clear codes" button can actually turn off. textContent only (XSS-safe).
    const permanent = isPermanentDtc(code.status);
    const clearTag = document.createElement("span");
    clearTag.className = "dtc-clearable";
    clearTag.dataset.clearable = permanent ? "no" : "yes";
    clearTag.textContent = permanent ? "permanent — won't clear" : "clearable";
    const codeSmall = document.createElement("small");
    codeSmall.textContent = code.statusLabel || code.status || "stored";
    codeBlock.append(codeB, sevBadge, clearTag, codeSmall);

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

    const small = document.createElement("small");
    const headerLabel = code.header ? `header ${code.header} · ` : "";
    if (info && info.description) {
      small.textContent = `${code.moduleName || "generic OBD-II"} · ${headerLabel}first ${VD.formatWhen(code.firstSeenMs)}`;
    } else {
      const moduleLabel = info && info.category ? (code.moduleName || "generic OBD-II") + " · " : "";
      small.textContent = `${moduleLabel}${headerLabel}first ${VD.formatWhen(code.firstSeenMs)} · last ${VD.formatWhen(code.lastSeenMs)}`;
    }
    moduleBlock.append(small);

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

  // Single source of truth for the charge-status badge: each key maps to BOTH
  // its visible label and its data-state color token, so the badge text and the
  // pill color are derived together and can never drift apart. Labels keep the
  // existing strings ("needs data" for the empty/waiting case).
  const CHARGE_STATUS_DISPLAY: Record<string, { label: string; state: DataStateValue }> = {
    charging: { label: "charging", state: "charging" },
    recorded: { label: "recorded", state: "recorded" },
    "needs-review": { label: "needs review", state: "needs-review" },
    waiting: { label: "needs data", state: "waiting" },
  };

  function renderRealV2Ui() {
    const storage = state.storage || {};
    const overview: Record<string, unknown> = storage.overview || {};
    const charge = storage.chargeSummary || {};
    const route = selectedRouteForOverview(storage);
    const hasRows = VD.dbRowCount(storage) > 0;
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
    // Derive the badge label AND its data-state color from one key so the text
    // and the pill color can never desync (they used to be twin ternaries that
    // could drift). Keys map to the existing strings exactly.
    const chargeStatusKey = chargingNow
      ? "charging"
      : (charge.chargeSessionCount ? "recorded" : (charge.chargingHintCount ? "needs-review" : "waiting"));
    const chargeStatus = CHARGE_STATUS_DISPLAY[chargeStatusKey];
    VD.setText("realChargeStatus", chargeStatus.label);
    // Keep the status pill's color in sync with the text (see base.css badge states).
    setDataState(el("realChargeStatusBadge"), chargeStatus.state);
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

  // The latest odometer reading the app has seen, in km, or null when unknown. Sourced from the
  // latest battery snapshot's odometer (written whenever the car answers the odometer PID) and,
  // failing that, the vehicle identity card's odometer. Drives the maintenance "next due / overdue"
  // distance math (M1/C4).
  function latestOdometerKm(): number | null {
    const storage = state.storage || {};
    const latest = latestInsightReading(storage);
    const snapshotOdo = Number(latest.odometerKm);
    if (Number.isFinite(snapshotOdo) && snapshotOdo > 0) return snapshotOdo;
    const vehicle = ((state.appState || {}).vehicle || {}) as Record<string, unknown>;
    const vehicleKm = Number(vehicle.odometerKm);
    if (Number.isFinite(vehicleKm) && vehicleKm > 0) return vehicleKm;
    const vehicleMiles = Number(vehicle.odometerMiles);
    if (Number.isFinite(vehicleMiles) && vehicleMiles > 0) return vehicleMiles * 1.609344;
    return null;
  }

  const MAINTENANCE_DUE_SOON_KM = 1609.344; // ~1,000 mi of remaining distance counts as "due soon".
  const MAINTENANCE_DUE_SOON_DAYS = 30;
  const MS_PER_DAY = 86_400_000;
  const AVG_DAYS_PER_MONTH = 30.4375;

  // Per-entry due status against the latest logged odometer and/or elapsed months. Returns null when
  // the entry has no interval set (a plain history row shows no due line). `state` is the worst of
  // the distance/time checks: "overdue" > "due-soon" > "ok".
  type MaintenanceDue = { state: "overdue" | "due-soon" | "ok"; text: string };
  function maintenanceDue(entry: VoltMaintenanceEntry, nowMs: number, odometerKm: number | null): MaintenanceDue | null {
    const parts: string[] = [];
    let worst: MaintenanceDue["state"] = "ok";
    const escalate = (next: MaintenanceDue["state"]) => {
      if (next === "overdue") worst = "overdue";
      else if (next === "due-soon" && worst !== "overdue") worst = "due-soon";
    };

    const intervalKm = Number(entry.intervalKm);
    const loggedOdo = Number(entry.odometerKm);
    if (Number.isFinite(intervalKm) && intervalKm > 0 && Number.isFinite(loggedOdo) && loggedOdo > 0 && odometerKm != null) {
      const dueAtKm = loggedOdo + intervalKm;
      const remainingKm = dueAtKm - odometerKm;
      if (remainingKm <= 0) {
        escalate("overdue");
        parts.push(`overdue by ${units.distanceText(Math.abs(remainingKm))}`);
      } else {
        if (remainingKm <= MAINTENANCE_DUE_SOON_KM) escalate("due-soon");
        parts.push(`${units.distanceText(remainingKm)} until due`);
      }
    }

    const intervalMonths = Number(entry.intervalMonths);
    const createdAt = Number(entry.createdAtMs);
    if (Number.isFinite(intervalMonths) && intervalMonths > 0 && Number.isFinite(createdAt) && createdAt > 0) {
      const dueAtMs = createdAt + intervalMonths * AVG_DAYS_PER_MONTH * MS_PER_DAY;
      const remainingDays = Math.round((dueAtMs - nowMs) / MS_PER_DAY);
      if (remainingDays <= 0) {
        escalate("overdue");
        parts.push(`overdue by ${Math.abs(remainingDays)}d`);
      } else {
        if (remainingDays <= MAINTENANCE_DUE_SOON_DAYS) escalate("due-soon");
        parts.push(`${remainingDays}d until due`);
      }
    }

    if (!parts.length) return null;
    return { state: worst, text: parts.join(" · ") };
  }

  // Renders the user's real maintenance log (M5) into #maintenanceList, replacing the old
  // hardcoded placeholder rows. When empty, shows next-due GUIDANCE only (never a fake logged
  // entry) so the card reads as a prompt to start logging, not as fabricated history. Entries with
  // a service interval gain a per-row "next due / overdue" line, and an aggregate "service due
  // soon" hint surfaces when any tracked item is due-soon/overdue (M1/C4).
  function renderMaintenanceList() {
    const maintenance = el("maintenanceList");
    if (!maintenance) return;
    const entries = Array.isArray(state.maintenanceLog) ? state.maintenanceLog : [];
    if (!entries.length) {
      maintenance.replaceChildren(buildMaintenanceEmptyState());
      renderMaintenanceDueHint([]);
      return;
    }
    const nowMs = Date.now();
    const odometerKm = latestOdometerKm();
    const dueByEntry = entries.map((entry) => maintenanceDue(entry, nowMs, odometerKm));
    maintenance.replaceChildren(...entries.map((entry, i) => buildMaintenanceEntryRow(entry, dueByEntry[i])));
    renderMaintenanceDueHint(dueByEntry);
  }

  // Aggregate "service due soon / overdue" banner above the list. Counts the tracked items that are
  // overdue or due-soon; stays hidden when nothing is tracked or everything is comfortably ahead.
  function renderMaintenanceDueHint(dueByEntry: Array<MaintenanceDue | null>) {
    const hint = el("maintenanceDueHint");
    if (!hint) return;
    const overdue = dueByEntry.filter((d) => d && d.state === "overdue").length;
    const dueSoon = dueByEntry.filter((d) => d && d.state === "due-soon").length;
    if (!overdue && !dueSoon) {
      hint.hidden = true;
      hint.textContent = "";
      hint.removeAttribute("data-due");
      return;
    }
    const segs: string[] = [];
    if (overdue) segs.push(`${overdue} overdue`);
    if (dueSoon) segs.push(`${dueSoon} due soon`);
    hint.textContent = `Service ${segs.join(" · ")}`;
    hint.dataset.due = overdue ? "overdue" : "due-soon";
    hint.hidden = false;
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
  // known) and note. When the entry has a service interval, a second "next due / overdue" line is
  // appended, color-coded via data-due (M1/C4). A delete button carries the entry id for actions.ts.
  // createElement/textContent only — never innerHTML — so a hostile type/note can't inject markup
  // (DOM XSS-safe).
  function buildMaintenanceEntryRow(entry: VoltMaintenanceEntry, due: MaintenanceDue | null = null) {
    const article = document.createElement("article");
    article.className = "real-insight-item";
    const center = document.createElement("span");
    const strong = document.createElement("strong");
    const type = String(entry.type || "").trim();
    strong.textContent = type || "Service";
    const small = document.createElement("small");
    small.textContent = maintenanceDetailLine(entry);
    center.append(strong, small);
    if (due) {
      const dueLine = document.createElement("small");
      dueLine.className = "maint-due";
      dueLine.dataset.due = due.state;
      dueLine.textContent = due.state === "overdue" ? `Overdue · ${due.text}` : `Next due · ${due.text}`;
      center.append(dueLine);
    }

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

  // The maintenance "Add entry" inline form (M1/C4) — type + odometer + note + optional interval,
  // replacing the old sequential window.prompt() chain. Opening reveals the static <form>; Save
  // reads the inputs, builds the JSON payload (odometer/interval distances converted to km), and
  // forwards to native; Cancel/Save both collapse and reset the form. Distance-unit labels follow
  // the user's preference.
  function openMaintenanceForm() {
    if (!bridge || typeof bridge.addMaintenanceEntry !== "function") {
      VD.setStatus({ state: "idle", detail: "Maintenance logging is available inside the Android app." });
      return;
    }
    const form = el("maintenanceForm");
    const btn = el("addMaintenanceBtn");
    if (!form) return;
    const unitLabel = units.distanceUnit() === "mi" ? "mi" : "km";
    VD.setText("maintOdometerLabel", `Odometer (${unitLabel})`);
    VD.setText("maintIntervalKmLabel", `Every (${unitLabel})`);
    clearMaintFormErrors();
    form.hidden = false;
    if (btn) btn.setAttribute("aria-expanded", "true");
    const typeInput = el("maintTypeInput") as HTMLInputElement | null;
    if (typeInput) typeInput.focus();
  }

  function closeMaintenanceForm() {
    const form = el("maintenanceForm") as HTMLFormElement | null;
    const btn = el("addMaintenanceBtn");
    if (form) {
      form.reset();
      form.hidden = true;
    }
    clearMaintFormErrors();
    if (btn) btn.setAttribute("aria-expanded", "false");
  }

  // Toggle entry point used by the "Add entry" button (data-action=addMaintenance).
  function addMaintenanceEntry() {
    const form = el("maintenanceForm");
    if (form && !form.hidden) {
      closeMaintenanceForm();
      return;
    }
    openMaintenanceForm();
  }

  // Shows/clears an inline error message inside the maintenance form (next to the
  // Save button), instead of the far-away topbar status. role="alert" on the
  // element announces the message to screen readers.
  function setMaintFormError(message: string) {
    const node = el("maintFormError");
    if (!node) return;
    node.textContent = message;
    node.hidden = !message;
  }

  // Shows/clears a per-field "enter a number above 0" hint and toggles aria-invalid
  // on the input so the bad value is surfaced inline instead of being silently dropped.
  function setMaintFieldHint(inputId: string, hintId: string, message: string) {
    const hint = el(hintId);
    if (hint) {
      hint.textContent = message;
      hint.hidden = !message;
    }
    const input = el(inputId);
    if (input) {
      if (message) input.setAttribute("aria-invalid", "true");
      else input.removeAttribute("aria-invalid");
    }
  }

  // Clears every inline validation message (form-level + per-field). Run on
  // open/close and at the start of each submit so stale errors never linger.
  function clearMaintFormErrors() {
    setMaintFormError("");
    setMaintFieldHint("maintOdometerInput", "maintOdometerHint", "");
    setMaintFieldHint("maintIntervalKmInput", "maintIntervalKmHint", "");
  }

  // Reads the inline form and forwards a JSON payload to native. Type is required; odometer, note,
  // and the two interval fields are optional. Distances are converted from the display unit to km.
  function submitMaintenanceForm() {
    if (!bridge || typeof bridge.addMaintenanceEntry !== "function") {
      VD.setStatus({ state: "idle", detail: "Maintenance logging is available inside the Android app." });
      return;
    }
    clearMaintFormErrors();
    const type = String((el("maintTypeInput") as HTMLInputElement | null)?.value || "").trim();
    const note = String((el("maintNoteInput") as HTMLInputElement | null)?.value || "").trim();
    if (!type && !note) {
      // Inline error next to Save (role=alert announces it) rather than the far-away topbar status.
      setMaintFormError("Pick a service type or add a note before saving.");
      return;
    }
    const payload: {
      type: string;
      note: string;
      date: number;
      odometerKm?: number;
      intervalKm?: number;
      intervalMonths?: number;
    } = { type, note, date: Date.now() };
    // Distinguish blank (omit, fine) from invalid (0/negative/NaN): a blank field
    // simply omits the value, but an invalid entry surfaces a per-field hint and
    // blocks the save instead of being silently dropped.
    const odo = parseOdometerKm((el("maintOdometerInput") as HTMLInputElement | null)?.value || "");
    if (odo.invalid) setMaintFieldHint("maintOdometerInput", "maintOdometerHint", "Enter a number above 0.");
    else if (odo.km != null) payload.odometerKm = odo.km;
    const interval = parseOdometerKm((el("maintIntervalKmInput") as HTMLInputElement | null)?.value || "");
    if (interval.invalid) setMaintFieldHint("maintIntervalKmInput", "maintIntervalKmHint", "Enter a number above 0.");
    else if (interval.km != null) payload.intervalKm = interval.km;
    if (odo.invalid || interval.invalid) return;
    const months = Math.round(Number(String((el("maintIntervalMonthsInput") as HTMLInputElement | null)?.value || "").trim()));
    if (Number.isFinite(months) && months > 0) payload.intervalMonths = months;
    bridge.addMaintenanceEntry(JSON.stringify(payload));
    closeMaintenanceForm();
  }

  // Converts a user-entered odometer/interval reading (in their display distance unit) to km for
  // storage. Returns { km: null, invalid: false } for blank (the entry simply omits the value) and
  // { km: null, invalid: true } for a non-blank but invalid (0/negative/NaN) entry, so the caller
  // can surface a per-field hint instead of silently dropping a bad value.
  function parseOdometerKm(raw: string): { km: number | null; invalid: boolean } {
    const text = String(raw || "").trim();
    if (!text) return { km: null, invalid: false };
    const value = Number(text);
    if (!Number.isFinite(value) || value <= 0) return { km: null, invalid: true };
    return { km: units.distanceUnit() === "mi" ? value * 1.609344 : value, invalid: false };
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
    const shown = Math.min(sessions.length, 12);
    VD.setText(
      "chargeSessionsTitle",
      sessions.length > 12
        ? `Latest ${shown} of ${sessions.length} charges`
        : `${sessions.length} recent charge${sessions.length === 1 ? "" : "s"}`
    );
    list.replaceChildren(...sessions.slice(0, 12).map(buildChargeSessionRow));
    renderChargeEnergy(sessions);
  }

  function formatMoney(value: number) {
    return "$" + value.toFixed(2);
  }

  // The two electricity rates that drive every charge-cost figure. `home` is the
  // single rate used everywhere by default; `public` (optional) is billed only
  // to public / DC-fast sessions when set. A non-positive home rate means "no
  // rate set" — callers hide the cost. See cost-model.rateForCharger for the
  // per-session selection and the unset-public fallback.
  function chargeRates(): { home: number; public: number } {
    return {
      home: prefs.get<number>("pricePerKwh", 0),
      public: prefs.get<number>("publicPricePerKwh", 0)
    };
  }

  // Total $ to charge a set of sessions, billing each session at the rate its
  // charger type selects (public/DCFC → public rate when set; else home rate).
  // Only positive energy contributes. Returns 0 when the home rate is unset.
  function chargeCostFor(sessions: VoltChargeSessionRow[]): number {
    const rates = chargeRates();
    if (!(rates.home > 0)) return 0;
    return sessions.reduce((acc, session) => {
      const energy = chargeNum(session.energyKwh);
      if (!Number.isFinite(energy) || energy <= 0) return acc;
      return acc + energy * rateForCharger(session.chargerType, rates.home, rates.public);
    }, 0);
  }

  // Charge-history CSV export (M1). Forwards to native, which serializes every logged charge into one
  // CSV (one row per charge) and opens the share sheet. Passes the user's electricity rate (Settings →
  // Preferences) through so native can append an estimated-cost column when it is set. The rate is a
  // display-layer preference read the same way the charge cost/savings math reads it; native treats a
  // non-positive / unparseable rate as "no cost column". Degrades to a status hint without the bridge.
  function exportChargeSessionsCsv() {
    if (!bridge || typeof bridge.exportChargeSessionsCsv !== "function") {
      VD.setStatus({ state: "idle", detail: "Charge-history export is available inside the Android app." });
      return;
    }
    const price = prefs.get<number>("pricePerKwh", 0);
    bridge.exportChargeSessionsCsv(price > 0 ? String(price) : "");
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
      // No positive energy → the monthly trend has nothing to plot either; hide
      // it so a cleared database can't leave a stale chart behind.
      renderChargeCostTrend(sessions);
      return;
    }
    card.hidden = false;
    VD.setText("chargeEnergyTotal", `${total.toFixed(1)} kWh`);
    VD.setText("chargeEnergySub", `across ${sessions.length} logged charge${sessions.length === 1 ? "" : "s"}`);
    // Bill each session at the rate its charger type selects (home vs public),
    // rather than a single flat rate on the lifetime kWh, so a mix of cheap
    // overnight + pricey DC-fast charges estimates honestly.
    const price = prefs.get<number>("pricePerKwh", 0);
    const hint = el("chargeEnergyHint");
    if (price > 0) {
      VD.setText("chargeEnergyCost", formatMoney(chargeCostFor(sessions)));
      if (hint) hint.hidden = true;
    } else {
      VD.setText("chargeEnergyCost", "--");
      if (hint) hint.hidden = false;
    }
    renderChargeCostTrend(sessions);
  }

  // ---- Charging cost / energy trend over time (M5) -------------------------
  // Buckets logged charge sessions into calendar months and plots monthly
  // energy (kWh) — or estimated cost (kWh × electricity rate) when the rate is
  // set — as an SVG bar chart, reusing the SOH-trend rendering pattern (pure
  // createElement/setSvgAttrs, theme-aware tokens, XSS-safe). The single flat
  // lifetime cost figure on the Energy card answers "how much total"; this
  // answers "is it trending up or down, month to month".
  // `costUsd` is the month's estimated cost with each session billed at the rate
  // its charger type selects (home vs public). It's accumulated alongside the raw
  // energy so the trend can plot cost without re-deriving a per-session rate at
  // render time; it's 0 when the home rate is unset (the render falls back to
  // plotting energy in that case).
  type MonthBucket = { key: string; label: string; ms: number; energyKwh: number; costUsd: number };

  // Calendar-month key + short label ("May ’26") for a charge timestamp.
  function monthBucketKey(ms: number): { key: string; label: string; firstMs: number } {
    const d = new Date(ms);
    const year = d.getFullYear();
    const month = d.getMonth();
    // `undefined` locale follows the device's runtime locale; the month-short
    // option keeps the compact "May ’26" shape across locales.
    const label = d.toLocaleDateString(undefined, { month: "short" }) + " ’" + String(year).slice(-2);
    return { key: `${year}-${String(month + 1).padStart(2, "0")}`, label, firstMs: new Date(year, month, 1).getTime() };
  }

  // Group sessions by month, summing positive energy and the per-session cost
  // (each session billed at its charger type's rate — home vs public). Months
  // with no energy are dropped (a charge stub with no kWh contributes nothing).
  // Ascending by month.
  function bucketChargesByMonth(sessions: VoltChargeSessionRow[]): MonthBucket[] {
    const rates = chargeRates();
    const byKey = new Map<string, MonthBucket>();
    for (const session of sessions) {
      const ms = Number(session.startedAtMs);
      const energy = chargeNum(session.energyKwh);
      if (!Number.isFinite(ms) || ms <= 0 || !Number.isFinite(energy) || energy <= 0) continue;
      const cost = rates.home > 0 ? energy * rateForCharger(session.chargerType, rates.home, rates.public) : 0;
      const { key, label, firstMs } = monthBucketKey(ms);
      const existing = byKey.get(key);
      if (existing) {
        existing.energyKwh += energy;
        existing.costUsd += cost;
      } else {
        byKey.set(key, { key, label, ms: firstMs, energyKwh: energy, costUsd: cost });
      }
    }
    return Array.from(byKey.values()).sort((a, b) => a.ms - b.ms);
  }

  function buildChargeTrendSvg(buckets: MonthBucket[], values: number[], unitSuffix: string): SVGElement {
    const ns = "http://www.w3.org/2000/svg";
    const w = 320;
    const h = 132;
    const padL = 30;
    const padR = 10;
    const padT = 12;
    const padB = 28;
    const plotW = w - padL - padR;
    const plotH = h - padT - padB;
    const maxV = Math.max(...values, 0) || 1;
    // Theme-aware colors: CSS variables don't cascade into SVG fill/stroke, so
    // resolve the tokens once (mirrors the insights scatter approach).
    const tokens = getComputedStyle(document.documentElement);
    const token = (name: string, fallback: string) => (tokens.getPropertyValue(name) || "").trim() || fallback;
    const barColor = token("--volt", "#ff7a45");
    const axisColor = token("--muted", "#aaaab4");
    const lineColor = token("--line", "rgba(255,255,255,0.1)");
    const make = (tag: string, attrs: Record<string, string | number>) =>
      setSvgAttrs(document.createElementNS(ns, tag) as SVGElement, attrs);
    const svg = make("svg", {
      viewBox: `0 0 ${w} ${h}`,
      class: "charge-cost-trend-svg",
      role: "img",
      "aria-label": `Monthly charging ${unitSuffix === "$" ? "cost" : "energy"} trend, latest ${
        unitSuffix === "$" ? "$" + values[values.length - 1].toFixed(2) : values[values.length - 1].toFixed(1) + " kWh"
      }`,
    });
    // Baseline.
    svg.appendChild(make("line", {
      x1: String(padL), x2: String(w - padR), y1: String(padT + plotH), y2: String(padT + plotH), stroke: lineColor,
    }));
    const n = values.length;
    const slot = plotW / n;
    const barW = Math.max(4, Math.min(34, slot * 0.6));
    values.forEach((v, i) => {
      const cx = padL + slot * (i + 0.5);
      const barH = (v / maxV) * plotH;
      svg.appendChild(make("rect", {
        x: (cx - barW / 2).toFixed(1),
        y: (padT + plotH - barH).toFixed(1),
        width: barW.toFixed(1),
        height: Math.max(0, barH).toFixed(1),
        rx: 3,
        fill: barColor,
        "fill-opacity": 0.85,
      }));
      const label = make("text", {
        x: cx.toFixed(1), y: (h - padB + 16).toFixed(1), fill: axisColor,
        "font-size": 9, "font-family": "ui-monospace,monospace", "text-anchor": "middle",
      });
      // Show every label when few buckets; thin to every other when crowded.
      label.textContent = n <= 6 || i % 2 === 0 ? buckets[i].label : "";
      svg.appendChild(label);
    });
    return svg;
  }

  function renderChargeCostTrend(sessions: VoltChargeSessionRow[]) {
    const card = el("chargeCostTrendCard");
    if (!card) return;
    const buckets = bucketChargesByMonth(sessions);
    const chart = el("chargeCostTrendChart");
    const empty = el("chargeCostTrendEmpty");
    const stats = el("chargeCostTrendStats");
    // Need at least two months for a meaningful "trend". One month (or none)
    // hides the whole card — the flat Energy card already covers single-month.
    if (buckets.length < 2) {
      card.hidden = true;
      if (chart) chart.replaceChildren();
      return;
    }
    card.hidden = false;
    if (empty) empty.hidden = true;
    if (stats) stats.hidden = false;
    const price = prefs.get<number>("pricePerKwh", 0);
    const showCost = price > 0;
    // costUsd already bills each session at its charger type's rate (home vs
    // public); fall back to energy when no home rate is set.
    const values = buckets.map((b) => (showCost ? b.costUsd : b.energyKwh));
    const total = values.reduce((acc, v) => acc + v, 0);
    const avg = total / values.length;
    const fmt = (v: number) => (showCost ? formatMoney(v) : `${v.toFixed(1)} kWh`);
    VD.setText("chargeCostTrendTitle", showCost ? "Monthly charging cost" : "Monthly charging energy");
    VD.setText("chargeCostTrendLatest", fmt(values[values.length - 1] as number));
    VD.setText("chargeCostTrendSpanLabel", "Avg / month");
    VD.setText("chargeCostTrendAvg", fmt(avg));
    VD.setText("chargeCostTrendMonths", String(buckets.length));
    VD.setText("chargeCostTrendTotal", fmt(total));
    if (chart) chart.replaceChildren(buildChargeTrendSvg(buckets, values, showCost ? "$" : "kWh"));
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
    // Bill this session at the rate its charger type selects: the public/DCFC
    // rate for a public charger when one is set, else the home rate.
    const rates = chargeRates();
    const sessionRate = rateForCharger(session.chargerType, rates.home, rates.public);
    if (Number.isFinite(energy) && energy > 0) {
      right.textContent =
        rates.home > 0 ? `${energy.toFixed(1)} kWh · ${formatMoney(energy * sessionRate)}` : `${energy.toFixed(1)} kWh`;
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
    const voltage = chargeNum(latest.packVoltage);
    const temp = firstNum([latest.batteryTempC, latest.batteryTemp]);
    const soh = Number(latest.sohPct);
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
    submitMaintenanceForm,
    closeMaintenanceForm,
    exportChargeSessionsCsv,
    toggleHidden
  });
})();

export {};
