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
import { setDataState } from "./dataset-state";
import { createFocusTrap, type FocusTrap } from "./focus-trap";
import { validatePayload } from "./payload-validators";
import { prefs, units } from "./prefs";

(function () {
  "use strict";

  const VD = window.VoltDashboard;
  const state = VD.state;
  const bridge = VD.bridge;
  let storageDetailsScheduled = false;
  let storageDetailsInFlight = false;
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
      try {
        bridge.logClientError(String(err.error || "native_read_failed"), detail);
      } catch (_ignored) {}
    }
  }

  function reportBridgeWriteFailure(label: string, detail: string, err: unknown) {
    const message = err instanceof Error && err.message ? err.message : String(err || "");
    VD.setStatus({ state: "blocked", detail });
    if (bridge && typeof bridge.logClientError === "function") {
      try {
        bridge.logClientError(label, message ? `${detail} ${message}` : detail);
      } catch (_ignored) {}
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
    if (
      !bridge ||
      (typeof bridge.getStorageDetails !== "function" &&
        typeof bridge.requestStorageDetails !== "function")
    ) {
      return;
    }
    if (storageDetailsScheduled) return;
    if (storageDetailsInFlight) return;
    storageDetailsScheduled = true;
    const load = () => {
      storageDetailsScheduled = false;
      try {
        if (typeof bridge.requestStorageDetails === "function" && bridge.requestStorageDetails()) {
          storageDetailsInFlight = true;
          return;
        }
        if (typeof bridge.getStorageDetails !== "function") return;
        const payload =
          typeof VD.callBridge === "function"
            ? VD.callBridge("getStorageDetails")
            : bridge.getStorageDetails();
        if (payload == null) {
          storageDetailsInFlight = false;
          reportNativeReadError(
            {
              ok: false,
              error: "storage_details_failed",
              message: "Could not read local storage details."
            },
            "Could not read local storage details."
          );
          return;
        }
        if (payload != null) {
          applyingStorageDetails = true;
          try {
            setStorage(payload);
          } finally {
            applyingStorageDetails = false;
          }
        }
      } catch (_err) {
        storageDetailsScheduled = false;
        storageDetailsInFlight = false;
        reportNativeReadError(
          {
            ok: false,
            error: "storage_details_failed",
            message: "Could not read local storage details."
          },
          "Could not read local storage details."
        );
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
        storageDetailsInFlight = false;
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
    if (isDetails) storageDetailsInFlight = false;
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
    VD.setText("dbState", VD.formatRowCount(VD.dbRowCount(storage)));
    const last = storage.lastEventAtMs || storage.lastStartedAtMs;
    const sampleLabel = `${samples} sample${samples === 1 ? "" : "s"}`;
    VD.setText("dbSummaryTitle", sessions ? (last ? `${sampleLabel} · ${VD.formatWhen(last)}` : sampleLabel) : "No stored sessions yet");
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

  let lastDtcListSig = "";

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
    // X3: mirror the count onto the Diag nav badge so saved codes are visible
    // from every tab (hidden at zero).
    const navBadge = document.getElementById("navDiagBadge");
    if (navBadge) {
      navBadge.textContent = String(totalCodes);
      navBadge.hidden = !totalCodes;
      // Mirror hidden into aria-hidden so the count is exposed to assistive
      // tech exactly when it is visible.
      navBadge.setAttribute("aria-hidden", totalCodes ? "false" : "true");
      // Severity: escalate the badge to the red fault tone only when a code is
      // genuinely urgent — a currently-active fault, or a code whose card-level
      // severity actually resolves to "critical". A permanent-but-warning code
      // (e.g. an unknown P-code) must NOT paint the badge red while its card
      // reads "generally safe to drive".
      const hasCurrent = Number(statusCounts.current || 0) > 0;
      const hasCritical = codes.some((code) => {
        const info = typeof VD.dtcInfo === "function" ? VD.dtcInfo(String(code.dtc || "")) : null;
        return dtcSeverity(code.dtc, info ? info.severity : null) === "critical";
      });
      navBadge.dataset.severity = hasCurrent || hasCritical ? "fault" : "info";
    }
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
    // Memo: storage pushes arrive constantly (every status broadcast), and an
    // unconditional replaceChildren would replay the rows' entrance animation
    // as flicker. Re-render only when the codes themselves (or the lookup DB
    // becoming available) change.
    // The rows' click handlers close over these row objects (openDtcDetail),
    // so the signature must cover every field the row OR the detail sheet
    // renders — a content-only change (freeze frame, module name) must rebuild.
    const listSig =
      (typeof VD.dtcInfo === "function" ? "db:" : "raw:") +
      codes
        .map((c) =>
          [
            c.dtc, c.status, c.statusLabel, c.lastSeenMs, c.firstSeenMs, c.seenCount,
            c.moduleName, c.header,
            c.freezeFrame ? JSON.stringify(c.freezeFrame) : ""
          ].join(":")
        )
        .join(";");
    if (listSig === lastDtcListSig) return;
    lastDtcListSig = listSig;
    if (!codes.length) {
      list.replaceChildren(buildDtcEmptyState());
      return;
    }
    list.replaceChildren(...codes.map((c, i) => buildDtcItem(c, false, i)));
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
      samples.forEach((sample, i) => wrap.append(buildDtcItem(sample, true, i)));
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

  // Module label to show when the code carries no reported moduleName. SAE
  // reserves a "1" or "3" in the second character for manufacturer-specific
  // codes (e.g. P1xxx / P3xxx), so calling those "generic OBD-II" is wrong —
  // fall back to "manufacturer-specific" for them and "generic OBD-II" otherwise.
  function moduleFallback(dtc: unknown): string {
    const second = String(dtc || "").trim().toUpperCase().charAt(1);
    return second === "1" || second === "3" ? "manufacturer-specific" : "generic OBD-II";
  }

  function buildDtcItem(code: VoltDtcRow, isExample: boolean, index = 0) {
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
    // The clear tag already announces "permanent — won't clear" for permanent
    // codes, so repeating the status here would print "permanent" twice; drop
    // the status small in that case.
    if (permanent) {
      codeBlock.append(codeB, sevBadge, clearTag);
    } else {
      const codeSmall = document.createElement("small");
      codeSmall.textContent = code.statusLabel || code.status || "stored";
      codeBlock.append(codeB, sevBadge, clearTag, codeSmall);
    }

    const moduleBlock = document.createElement("span");
    moduleBlock.className = "dtc-module-block";
    const headline = document.createElement("strong");
    if (info && info.description) {
      headline.textContent = info.description;
    } else if (info && info.category) {
      // No specific description — only the broad SAE family. Qualify it so an
      // area guess doesn't read as a definitive diagnosis.
      headline.textContent = `Unrecognized code — likely area: ${info.category}`;
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
      small.textContent = `${code.moduleName || moduleFallback(code.dtc)} · ${headerLabel}first ${VD.formatWhen(code.firstSeenMs)} · last ${VD.formatWhen(code.lastSeenMs)}`;
    } else {
      const moduleLabel = info && info.category ? (code.moduleName || moduleFallback(code.dtc)) + " · " : "";
      small.textContent = `${moduleLabel}${headerLabel}first ${VD.formatWhen(code.firstSeenMs)} · last ${VD.formatWhen(code.lastSeenMs)}`;
    }
    moduleBlock.append(small);

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

    // The row itself opens the detail sheet (plain description, likely causes,
    // freeze frame, copy report) — the causes list used to render inline here
    // and made every row a wall; the sheet keeps rows scannable. Chevron is the
    // affordance; Enter/Space mirror the click for keyboard users.
    const chevron = document.createElement("span");
    chevron.className = "dtc-chevron";
    chevron.setAttribute("aria-hidden", "true");
    chevron.textContent = "›";
    article.append(chevron);
    article.setAttribute("role", "button");
    article.tabIndex = 0;
    article.setAttribute("aria-label", `${code.dtc || "code"} details`);
    article.addEventListener("click", (event) => {
      // Let real links/buttons inside the row (none today) keep their own taps.
      const target = event.target as Element | null;
      if (target && target.closest("a, button")) return;
      openDtcDetail(code);
    });
    article.addEventListener("keydown", (event) => {
      if (event.key !== "Enter" && event.key !== " ") return;
      event.preventDefault();
      openDtcDetail(code);
    });
    // Staggered entrance: rows cascade in 90ms apart when the list (re)renders
    // after a scan. `backwards` holds them hidden until their turn.
    article.classList.add("dtc-item-enter");
    article.style.animationDelay = `${Math.min(index, 6) * 90}ms`;
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
    // Presence-based fallback (not `||`): a genuine current-session 0 must render
    // as 0, not fall through to a stale non-zero latestHealth count from a prior
    // session. Only reach for latestHealth when the current field is truly absent.
    const health = review.latestHealth || {};
    const firstPresent = (primary: unknown, fallback: unknown) =>
      primary != null ? Number(primary) : Number(fallback || 0);
    const backgroundSamples = firstPresent(review.backgroundSampleCount, health.backgroundSampleCount);
    const sampleGaps = firstPresent(review.sampleGapEventCount, health.sampleGapCount);
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
    VD.setText("reviewBackground", backgroundSamples ? `${backgroundSamples} sample${backgroundSamples === 1 ? "" : "s"}` : "--");
    VD.setText("reviewGaps", sampleGaps ? `${sampleGaps}` : "0");
    VD.setText("reviewUsefulSamples", usefulSamples ? `${usefulSamples}` : "--");
    VD.setText("reviewEmptySamples", emptySamples ? `${emptySamples}` : "0");
    VD.setText("pidFrameTitle", frames.length ? `${frames.length} latest frame${frames.length === 1 ? "" : "s"}` : "Waiting for scan data");

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
    // Presence-based fallback matching updateReviewUi: a genuine current-session 0
    // must not fall through to a stale non-zero latestHealth count, or this insight
    // ("N samples captured in background") would contradict the review tile above it.
    const health = review.latestHealth || {};
    const firstPresent = (primary: unknown, fallback: unknown) =>
      primary != null ? Number(primary) : Number(fallback || 0);
    const backgroundSamples = firstPresent(review.backgroundSampleCount, health.backgroundSampleCount);
    const gapCount = firstPresent(review.sampleGapEventCount, health.sampleGapCount);
    const hasChargeHint = warnings.some((item) => String(item.code || "") === "charge-speed-hint");
    const hasDriving = Object.keys(stateCounts).some((key) => key.includes("driving"));
    return [
      {
        title: maxSpeed ? `Max speed ${units.speedText(maxSpeed)}` : "No speed peak yet",
        detail: maxSpeed ? "Computed from accepted OBD speed samples for the latest session." : "Speed stays blank until accepted OBD speed samples are stored."
      },
      {
        title: gps ? `${gps} GPS sample${gps === 1 ? "" : "s"} stored` : "No GPS route stored",
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
        title: backgroundSamples ? `${backgroundSamples} sample${backgroundSamples === 1 ? "" : "s"} captured in background` : "Background logging not proven yet",
        detail: backgroundSamples ? "The foreground service kept writing samples while the app was minimized." : "Minimize the app during the next drive to prove background collection."
      },
      {
        title: gapCount ? `${gapCount} sample gap${gapCount === 1 ? "" : "s"} detected` : "No sample gaps flagged",
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
  let sohReadInFlight = false;
  // Battery-snapshot row count at the last fetch. When it changes (a new snapshot
  // landed, or storage was cleared) we refetch immediately instead of waiting out
  // the throttle, so the chart never shows stale history after a storage change.
  let sohLastCount = -1;

  function applyBatterySohHistory(payload: unknown) {
    sohReadInFlight = false;
    sohLastFetchMs = Date.now();
    sohLastCount = Number((state.storage || {}).batterySnapshotCount || 0);
    const raw = typeof payload === "string" ? payload : JSON.stringify(payload ?? []);
    if (raw === sohLastRaw) return;
    sohLastRaw = raw;
    const parsed = VD.parsePayload<Array<Record<string, unknown>>>(raw, []);
    sohPoints = Array.isArray(parsed)
      ? parsed
          // Native emits JSON null for soh_pct / capacity_ah on rows that only
          // carry the other field; Number(null) === 0 would slip a spurious 0%
          // SOH (or "0.0 Ah") past the guards, so map those nulls to NaN and let
          // the isFinite filter / capacity readout drop them.
          .map((r) => ({
            at: Number(r.capturedAtMs),
            soh: r.sohPct == null ? NaN : Number(r.sohPct),
            cap: r.capacityAh == null ? NaN : Number(r.capacityAh),
          }))
          .filter((p) => Number.isFinite(p.at) && Number.isFinite(p.soh))
      : [];
    sohDirty = true;
  }

  function sohSpanLabel(fromMs: number, toMs: number): string {
    const days = Math.max(0, Math.round((toMs - fromMs) / 86_400_000));
    if (days < 1) return "today";
    if (days < 14) return `${days} day${days === 1 ? "" : "s"}`;
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
    // Tone the trend by the LATEST health band so a healthy pack reads calm
    // (green) instead of alarm-orange: a declining-but-fine ~90% battery is
    // normal. ok >= 85, warn 70-85, bad < 70 — colored via CSS off data-soh.
    const latestSoh = ss[ss.length - 1];
    const tone = latestSoh >= 85 ? "ok" : latestSoh >= 70 ? "warn" : "bad";
    const svg = make("svg", {
      viewBox: `0 0 ${w} ${h}`,
      class: "soh-trend-svg",
      "data-soh": tone,
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
    // Soft area fill under the line (closed down to the plot baseline) so the
    // trend reads as a filled band, not a lone stroke. Drawn before the line.
    const baseY = (padT + plotH).toFixed(1);
    const firstX = xOf(points[0].at).toFixed(1);
    const lastX = xOf(points[points.length - 1].at).toFixed(1);
    svg.appendChild(make("path", { d: `${d.trim()} L${lastX} ${baseY} L${firstX} ${baseY} Z`, class: "soh-area" }));
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
      (typeof bridge.getBatterySohHistory === "function" ||
        typeof bridge.requestBatterySohHistory === "function") &&
      !sohReadInFlight &&
      (sohLastRaw === null || countChanged || now - sohLastFetchMs >= SOH_REFETCH_MS)
    ) {
      try {
        if (typeof bridge.requestBatterySohHistory === "function" && bridge.requestBatterySohHistory()) {
          sohLastFetchMs = now;
          sohLastCount = batteryCount;
          sohReadInFlight = true;
          return;
        }
        if (typeof bridge.getBatterySohHistory === "function") {
          sohLastFetchMs = now;
          sohLastCount = batteryCount;
          applyBatterySohHistory(bridge.getBatterySohHistory());
        }
      } catch (_err) {
        sohLastFetchMs = now;
        sohLastCount = batteryCount;
        if (sohLastRaw === null) sohLastRaw = "[]";
        sohReadInFlight = false;
        reportNativeReadError(
          {
            ok: false,
            error: "battery_soh_history_failed",
            message: "Could not read battery health history."
          },
          "Could not read battery health history."
        );
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

  // Latest full-pack cell snapshot (96-cell probe), carried on the storage-details
  // payload as batterySummary.latestCellSnapshot. Handed to telemetry.ts
  // (VD.applyCellSnapshot), which owns the cell-map render; memoized so the map
  // isn't rebuilt on every app-state broadcast.
  let cellSnapLastSig: string | null = null;

  function refreshCellSnapshot() {
    if (typeof VD.applyCellSnapshot !== "function") return;
    const battery: Record<string, unknown> = (state.storage || {}).batterySummary || {};
    const snapshot = battery.latestCellSnapshot as Record<string, unknown> | undefined;
    // Details not loaded yet — keep whatever the map already shows.
    if (!snapshot) return;
    const sig = `${snapshot.capturedAtMs || 0}:${snapshot.cellCount || 0}`;
    if (sig === cellSnapLastSig) return;
    cellSnapLastSig = sig;
    VD.applyCellSnapshot(snapshot);
  }

  // Drive's "This trip" card (v2 design): time + efficiency from the trip
  // rollup that matches the overview route, plus the net HV energy + estimated
  // electricity cost footer. Distance/max speed are written by the caller from
  // the overview payload. Without a rate the footer's right side stays a
  // "set rate for cost" Settings jump; without energy it shows "--" so the
  // card never invents a figure.
  function renderThisTripCard(route: VoltRoute, routeDistance: number): void {
    const cost = el("tripCostValue") as HTMLButtonElement | null;
    const sessionId = String(((route || {}).session || {}).id || "");
    const trips = Array.isArray(state.trips) ? (state.trips as VoltTrip[]) : [];
    const trip = sessionId
      ? trips.find((row) => String(row.id) === sessionId) || null
      : null;
    const durationMs = trip && trip.durationMs != null ? Number(trip.durationMs) : NaN;
    VD.setText(
      "tripTimeValue",
      Number.isFinite(durationMs) && durationMs > 0 && typeof VD.formatDuration === "function"
        ? VD.formatDuration(durationMs)
        : "--"
    );
    const energyKwh = trip && trip.energyKwh != null ? Number(trip.energyKwh) : NaN;
    const hasEnergy = Number.isFinite(energyKwh) && energyKwh > 0;
    const tripMeters = trip && trip.distanceMeters != null ? Number(trip.distanceMeters) : routeDistance;
    const miles = Number.isFinite(tripMeters) && tripMeters > 0 ? tripMeters / 1609.344 : NaN;
    VD.setText(
      "tripEffValue",
      hasEnergy && Number.isFinite(miles) && miles > 0
        ? units.efficiencyText(miles / energyKwh)
        : "--"
    );
    VD.setText("tripEnergyValue", hasEnergy ? `${energyKwh.toFixed(1)} kWh` : "--");
    if (!cost) return;
    const rate = prefs.get<number>("pricePerKwh", 0);
    if (hasEnergy && rate > 0) {
      cost.textContent = `≈ $${(energyKwh * rate).toFixed(2)}`;
      setDataState(cost, "recorded");
      cost.disabled = true;
    } else if (rate > 0) {
      // Rate is set but this drive logged no pack power — nothing to estimate.
      cost.textContent = routeDistance > 0 ? "no energy logged" : "--";
      setDataState(cost, "waiting");
      cost.disabled = true;
    } else {
      cost.textContent = "set rate for cost";
      setDataState(cost, "waiting");
      cost.disabled = false;
    }
  }

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
    VD.setText("overviewMaxSpeed", overview.maxSpeedKph ? units.speedText(Number(overview.maxSpeedKph)) : "--");
    const soc = Number(latest.soc);
    const power = Number(latest.powerKw ?? latest.packPowerKw);
    renderThisTripCard(route, routeDistance);

    VD.setText("realChargeHints", Number(charge.chargingHintCount || 0));
    VD.setText("realChargePower", charge.maxPowerKw ? `${Number(charge.maxPowerKw).toFixed(1)} kW` : "--");
    renderChargeSessions(charge);
    renderBatterySohTrend();
    refreshCellSnapshot();

    const ring = el("realPackRing");
    const ringValue = el("realPackValue");
    if (Number.isFinite(soc) && soc > 0) {
      if (ring) {
        ring.style.setProperty("--v", String(Math.max(0, Math.min(100, soc))));
        // Leave the "waiting" neutral track once a real SOC reading exists.
        ring.removeAttribute("data-state");
        // Color the battery fill by charge level (amber low, red nearly empty).
        ring.dataset.level = soc <= 15 ? "bad" : soc <= 30 ? "warn" : "ok";
      }
      if (ringValue) ringValue.textContent = `${Math.round(soc)}%`;
      // Human verdict, not a log line: lead with the pack level, add the
      // vehicle state only when the car actually reported one, and never
      // print pipeline caveats in the tab's first card.
      const socRound = Math.round(soc);
      VD.setText("realPackTitle", `Pack at ${socRound}%${socRound <= 15 ? " — low" : socRound <= 30 ? " — getting low" : ""}`);
      const stateText = latest.vehicleState && latest.vehicleState !== "unknown" ? String(latest.vehicleState) : "";
      const powerText = Number.isFinite(power) ? (power < -0.05 ? `Regenerating ${Math.abs(power).toFixed(1)} kW` : power > 0.05 ? `Drawing ${power.toFixed(1)} kW` : "") : "";
      VD.setText("realPackCopy", [stateText, powerText].filter(Boolean).join(" · ") || "From the latest logged reading.");
    } else {
      if (ring) {
        ring.style.setProperty("--v", "0");
        setDataState(ring, "waiting");
        delete ring.dataset.level;
      }
      if (ringValue) ringValue.textContent = "--";
      VD.setText("realPackTitle", "Waiting for battery readings");
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
    // Vehicle card's at-a-glance maintenance stat (v2) mirrors the list.
    VD.setText(
      "vehicleMaintenance",
      entries.length ? `${entries.length} entr${entries.length === 1 ? "y" : "ies"}` : "none logged"
    );
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
    let parsed: VoltMaintenanceEntry[] | VoltNativeError;
    try {
      parsed = VD.parsePayload<VoltMaintenanceEntry[] | VoltNativeError>(bridge.getMaintenanceLog(), []);
    } catch (_err) {
      state.maintenanceLog = [];
      renderMaintenanceList();
      reportNativeReadError(
        {
          ok: false,
          error: "maintenance_log_failed",
          message: "Could not read the maintenance log."
        },
        "Could not read the maintenance log."
      );
      return;
    }
    if (isNativeError(parsed)) {
      state.maintenanceLog = [];
      renderMaintenanceList();
      reportNativeReadError(parsed, "Could not read the maintenance log.");
      return;
    }
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
      VD.setStatus({ state: "idle", detail: "Maintenance logging is only available inside the Android app." });
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
      VD.setStatus({ state: "idle", detail: "Maintenance logging is only available inside the Android app." });
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
    try {
      bridge.addMaintenanceEntry(JSON.stringify(payload));
    } catch (err) {
      const detail = "Could not save maintenance entry.";
      setMaintFormError(detail);
      if (bridge && typeof bridge.logClientError === "function") {
        try {
          const message = err instanceof Error && err.message ? err.message : String(err || "");
          bridge.logClientError("maintenance_add_failed", message ? `${detail} ${message}` : detail);
        } catch (_ignored) {}
      }
      return;
    }
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
  // Memoized like renderCellGrid/renderLiveSignals: native re-delivers storage
  // on every broadcast, and rebuilding 12 unchanged rows each time is wasted DOM
  // churn on the busiest render path.
  let lastChargeSessionsSig = "";
  function renderChargeSessions(charge: VoltChargeSummary) {
    const card = el("chargeSessionsCard");
    const list = el("chargeSessionsList");
    const sessions = Array.isArray(charge.recentSessions) ? charge.recentSessions : [];
    if (card) card.hidden = sessions.length === 0;
    if (!list) return;
    // The rates are render inputs too: buildChargeSessionRow prices each row
    // via chargeRates()/rateForCharger(), so a $/kWh preference edit must bust
    // the memo even when recentSessions is unchanged.
    const rates = chargeRates();
    // chargeSessionCount drives the "X of Y charges" title but isn't one of the
    // newest-12 row fields, so a backfilled older charge (count 12→13, rows
    // unchanged) must still bust the memo or the title stays stale at "12 recent
    // charges" instead of "12 of 13 charges".
    const sig = rates.home + "|" + rates.public + "|" + sessions.length + "|" + (charge.chargeSessionCount || sessions.length) + "|" + sessions.slice(0, 12).map((s) => [
      s.id, s.startedAtMs, s.endedAtMs, s.startSoc, s.endSoc, s.energyKwh, s.powerKw, s.chargerType
    ].join(":")).join(";");
    if (sig === lastChargeSessionsSig) return;
    lastChargeSessionsSig = sig;
    if (!sessions.length) {
      list.replaceChildren();
      // renderChargeEnergy owns the energy card's hidden flag — it must run on
      // the empty path too, or a stale "Energy logged" total survives clearing
      // the stored data.
      renderChargeEnergy(sessions);
      return;
    }
    const shown = Math.min(sessions.length, 12);
    // v2 design: the headline carries the energy rollup too ("29.6 kWh across
    // 4 charges") — the old standalone Energy card folded in here. Native caps
    // recentSessions at 12, so compare against the true lifetime total
    // (chargeSessionCount); when more exist than we hold, say "last N of M" so
    // the copy doesn't imply a lifetime figure that contradicts the count.
    const totalCharges = Number(charge.chargeSessionCount || sessions.length);
    const shownEnergyKwh = sessions.slice(0, 12).reduce((acc, session) => {
      const e = chargeNum(session.energyKwh);
      return Number.isFinite(e) && e > 0 ? acc + e : acc;
    }, 0);
    const countLabel =
      totalCharges > sessions.length
        ? `last ${shown} of ${totalCharges} charges`
        : `${sessions.length} charge${sessions.length === 1 ? "" : "s"}`;
    VD.setText(
      "chargeSessionsTitle",
      shownEnergyKwh > 0 ? `${shownEnergyKwh.toFixed(1)} kWh across ${countLabel}` : countLabel
    );
    // Scale each row's background bar to the biggest charge on screen so the
    // list doubles as a bar chart (11.8 kWh fills the row; 3 kWh ~a quarter).
    const shownSessions = sessions.slice(0, 12);
    const maxEnergyKwh = shownSessions.reduce((acc, session) => {
      const energy = chargeNum(session.energyKwh);
      return Number.isFinite(energy) && energy > acc ? energy : acc;
    }, 0);
    list.replaceChildren(...shownSessions.map((session) => buildChargeSessionRow(session, maxEnergyKwh)));
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
      VD.setStatus({ state: "idle", detail: "Charge-history export is only available inside the Android app." });
      return;
    }
    const price = prefs.get<number>("pricePerKwh", 0);
    try {
      bridge.exportChargeSessionsCsv(price > 0 ? String(price) : "");
      VD.showToast?.("Exporting charge history CSV…");
    } catch (err) {
      reportBridgeWriteFailure("charge_export_failed", "Charge-history export failed.", err);
    }
  }

  // Estimated total charging cost for the logged sessions (v2: an Est. cost KPI
  // tile — the kWh total lives in the Recent-charges headline). Bills each
  // session at the rate its charger type selects (home vs public), rather than
  // a single flat rate on the lifetime kWh, so a mix of cheap overnight +
  // pricey DC-fast charges estimates honestly. The rate is a display-layer
  // preference, so the math lives here in JS.
  function renderChargeEnergy(sessions: VoltChargeSessionRow[]) {
    const total = sessions.reduce((acc, session) => {
      const e = chargeNum(session.energyKwh);
      return Number.isFinite(e) && e > 0 ? acc + e : acc;
    }, 0);
    const price = prefs.get<number>("pricePerKwh", 0);
    const hint = el("chargeEnergyHint");
    const costEl = el("chargeEnergyCost");
    if (total > 0 && price > 0) {
      VD.setText("chargeEnergyCost", formatMoney(chargeCostFor(sessions)));
      if (costEl) costEl.dataset.state = "recorded";
      const rates = chargeRates();
      // Mirror chargeCostFor's per-session billing in the caption: count and
      // quote only the sessions that actually contribute energy, and name the
      // rate only when every one of them bills at the same one — a home +
      // public/DC-fast mix says "mixed rates" so the caption can never
      // contradict the total above it.
      const billableSessions = sessions.filter((session) => {
        const e = chargeNum(session.energyKwh);
        return Number.isFinite(e) && e > 0;
      });
      const billedRates = new Set(
        billableSessions.map((session) => rateForCharger(session.chargerType, rates.home, rates.public))
      );
      const chargesLabel = `${billableSessions.length} charge${billableSessions.length === 1 ? "" : "s"}`;
      VD.setText(
        "chargeEnergyHint",
        billedRates.size === 1
          ? `${chargesLabel} @ ${formatMoney([...billedRates][0] as number)}/kWh`
          : `${chargesLabel} · mixed rates`
      );
      if (hint) hint.hidden = false;
    } else {
      // Clear a previous figure so a cleared database can't flash stale cost.
      VD.setText("chargeEnergyCost", "--");
      if (costEl) costEl.dataset.state = "empty";
      // Distinguish "no rate configured" from "rate set but no energy logged yet":
      // this else fires for both (price===0 OR total===0), and telling a user who
      // already set a $/kWh rate to "Set rate in Settings" is misleading — the cost
      // just can't be computed until a session records energy.
      VD.setText("chargeEnergyHint", price > 0 ? "No charge energy logged yet" : "Set rate in Settings");
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

  // Monthly bar chart shared by the charging trend (Battery tab) and the driving
  // trend (Insights tab, via VD.buildMonthlyTrendSvg) so the two can't drift.
  function buildMonthlyTrendSvg(
    labels: string[],
    values: number[],
    ariaLabel: string,
    host?: Element | null,
    opts?: {
      // Resolve a specific CSS accent (e.g. "--ev") instead of the view accent.
      colorVar?: string;
      // Full-opacity this bar, dim the rest (design "today" highlight).
      highlightIndex?: number;
      // Print each non-zero value above its bar.
      showValues?: boolean;
      valueFormat?: (v: number) => string;
      // Draw a dashed baseline placeholder for zero-value slots.
      dashEmpty?: boolean;
    },
  ): SVGElement {
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
    // Resolve on the chart's host so --view-accent cascades in: the same
    // builder renders green bars on Charge and purple on Insights instead of
    // painting Drive orange onto every tab.
    const tokens = getComputedStyle(host || document.documentElement);
    const token = (name: string, fallback: string) => (tokens.getPropertyValue(name) || "").trim() || fallback;
    const barColor = token(opts?.colorVar || "--view-accent", token("--volt", "#ff7a45"));
    const axisColor = token("--muted", "#aaaab4");
    const lineColor = token("--line", "rgba(255,255,255,0.1)");
    const make = (tag: string, attrs: Record<string, string | number>) =>
      setSvgAttrs(document.createElementNS(ns, tag) as SVGElement, attrs);
    const svg = make("svg", {
      viewBox: `0 0 ${w} ${h}`,
      class: "charge-cost-trend-svg",
      role: "img",
      "aria-label": ariaLabel,
    });
    // Baseline.
    svg.appendChild(make("line", {
      x1: String(padL), x2: String(w - padR), y1: String(padT + plotH), y2: String(padT + plotH), stroke: lineColor,
    }));
    const n = values.length;
    // Cap the per-bar slot so a 1–2 month history doesn't stretch a couple of
    // bars across the whole plot (which reads as broken/sparse); center the bar
    // group in that case. With many months the natural slot is already below the
    // cap, so the full-width layout is unchanged.
    const slot = Math.min(plotW / n, 56);
    const originX = padL + (plotW - slot * n) / 2;
    const barW = Math.max(4, Math.min(34, slot * 0.6));
    const baselineY = padT + plotH;
    values.forEach((v, i) => {
      const cx = originX + slot * (i + 0.5);
      const barH = (v / maxV) * plotH;
      if (!(v > 0) && opts?.dashEmpty) {
        // Design: a dashed placeholder marks a day/slot with no data.
        svg.appendChild(make("line", {
          x1: (cx - barW / 2).toFixed(1), x2: (cx + barW / 2).toFixed(1),
          y1: baselineY.toFixed(1), y2: baselineY.toFixed(1),
          stroke: barColor, "stroke-opacity": 0.4, "stroke-width": 2, "stroke-dasharray": "2 4",
        }));
      } else {
        // Highlight one bar (design "today"); dim the rest. No highlight → flat 0.85.
        const op = opts?.highlightIndex != null ? (i === opts.highlightIndex ? 1 : 0.3) : 0.85;
        svg.appendChild(make("rect", {
          x: (cx - barW / 2).toFixed(1),
          y: (baselineY - barH).toFixed(1),
          width: barW.toFixed(1),
          height: Math.max(0, barH).toFixed(1),
          rx: 3,
          fill: barColor,
          "fill-opacity": op,
        }));
        if (opts?.showValues && v > 0) {
          const valLabel = make("text", {
            x: cx.toFixed(1), y: Math.max(padT + 7, baselineY - barH - 4).toFixed(1), fill: axisColor,
            "font-size": 9, "font-family": "ui-monospace,monospace", "text-anchor": "middle",
          });
          valLabel.textContent = opts.valueFormat ? opts.valueFormat(v) : String(Math.round(v));
          svg.appendChild(valLabel);
        }
      }
      const label = make("text", {
        x: cx.toFixed(1), y: (h - padB + 16).toFixed(1), fill: axisColor,
        "font-size": 9, "font-family": "ui-monospace,monospace", "text-anchor": "middle",
      });
      // Show every label when few buckets; thin to every other when crowded,
      // but always keep the most-recent (last) label so it's never dropped when
      // n is even and i=n-1 lands on an odd index.
      label.textContent = n <= 6 || i % 2 === 0 || i === n - 1 ? labels[i] as string : "";
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
    // "Avg / month" is per ACTIVE charging month by design: bucketChargesByMonth
    // emits only months that logged energy, so a calendar month with no charging
    // is not in the denominator (and the bars are drawn gapless). Intentional — a
    // month you didn't plug in shouldn't dilute the per-charge-month average.
    const avg = total / values.length;
    const fmt = (v: number) => (showCost ? formatMoney(v) : `${v.toFixed(1)} kWh`);
    VD.setText("chargeCostTrendTitle", showCost ? "Monthly charging cost" : "Monthly charging energy");
    VD.setText("chargeCostTrendLatest", fmt(values[values.length - 1] as number));
    VD.setText("chargeCostTrendSpanLabel", "Avg / month");
    VD.setText("chargeCostTrendAvg", fmt(avg));
    VD.setText("chargeCostTrendMonths", String(buckets.length));
    VD.setText("chargeCostTrendTotal", fmt(total));
    if (chart) {
      const latest = values[values.length - 1] as number;
      const aria = `Monthly charging ${showCost ? "cost" : "energy"} trend, latest ${
        showCost ? "$" + latest.toFixed(2) : latest.toFixed(1) + " kWh"
      }`;
      chart.replaceChildren(buildMonthlyTrendSvg(buckets.map((b) => b.label), values, aria, chart));
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

  function buildChargeSessionRow(session: VoltChargeSessionRow, maxEnergyKwh = 0) {
    const row = document.createElement("article");
    row.className = "charge-session-row";
    const inProgress = isChargeInProgress(session);
    if (inProgress) row.dataset.charging = "1";
    // Proportional energy bar behind the text (see .charge-kwh-bar). Skipped
    // when this session logged no energy — the row keeps its flat background.
    const rowEnergy = chargeNum(session.energyKwh);
    if (Number.isFinite(rowEnergy) && rowEnergy > 0 && maxEnergyKwh > 0) {
      const bar = document.createElement("span");
      bar.className = "charge-kwh-bar";
      bar.style.width = `${Math.max(6, Math.min(100, (rowEnergy / maxEnergyKwh) * 100)).toFixed(0)}%`;
      row.append(bar);
    }
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
    if (Number.isFinite(startSoc) && Number.isFinite(endSoc)) parts.push(`${Math.round(startSoc)}% → ${Math.round(endSoc)}%`);
    if (Number.isFinite(power) && power > 0) parts.push(`${power.toFixed(1)} kW`);
    if (inProgress) parts.push("charging now");
    else if (Number.isFinite(durationMs) && durationMs > 0 && typeof VD.formatDuration === "function") parts.push(VD.formatDuration(durationMs));
    const energy = chargeNum(session.energyKwh);
    // Bill this session at the rate its charger type selects: the public/DCFC
    // rate for a public charger when one is set, else the home rate. v2 design:
    // the estimated cost joins the meta line; the right pill stays kWh-only.
    const rates = chargeRates();
    const sessionRate = rateForCharger(session.chargerType, rates.home, rates.public);
    if (Number.isFinite(energy) && energy > 0 && rates.home > 0) {
      parts.push(formatMoney(energy * sessionRate));
    }
    small.textContent = parts.length ? parts.join(" · ") : "charge details pending";
    center.append(strong, small);
    const right = document.createElement("b");
    const socGain = Number.isFinite(startSoc) && Number.isFinite(endSoc) ? endSoc - startSoc : NaN;
    if (Number.isFinite(energy) && energy > 0) {
      right.textContent = `${energy.toFixed(1)} kWh`;
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

  // ── DTC detail bottom sheet ───────────────────────────────────────────
  // Opened from a scanned-code row or a lookup hit. Renders the plain-language
  // description, severity, likely causes, and (when the row carries one) the
  // freeze-frame conditions grid. Modal with a focus trap; Close / backdrop /
  // Escape / Android back all route through closeDtcDetail.

  let dtcDetailTrap: FocusTrap | null = null;
  let dtcDetailCode: VoltDtcRow | null = null;

  function dtcDetailNodes() {
    return { sheet: el("dtcDetailSheet"), backdrop: el("dtcDetailBackdrop") };
  }

  function openDtcDetail(code: VoltDtcRow): void {
    const { sheet, backdrop } = dtcDetailNodes();
    if (!sheet || !backdrop) return;
    const dtc = String(code.dtc || "").trim().toUpperCase();
    const info = typeof VD.dtcInfo === "function" ? VD.dtcInfo(dtc) : null;
    const severity = dtcSeverity(dtc, info ? info.severity : null);
    dtcDetailCode = code;
    VD.setText("dtcDetailCode", dtc || "--");
    VD.setText(
      "dtcDetailTitle",
      info && info.description
        ? info.description
        : info && info.category
          ? `Unrecognized code — likely area: ${info.category}`
          : code.moduleName || "Unknown code"
    );
    const metaParts: string[] = [];
    metaParts.push(code.statusLabel || code.status || "stored");
    if (code.lastSeenMs) metaParts.push(`last seen ${VD.formatWhen(code.lastSeenMs)}`);
    const seen = Number(code.seenCount || 0);
    if (seen > 0) metaParts.push(`${seen}x`);
    VD.setText("dtcDetailMeta", metaParts.join(" · "));
    const sev = el("dtcDetailSev");
    if (sev) {
      sev.dataset.severity = severity;
      sev.textContent = severityLabel(severity);
    }
    VD.setText("dtcDetailPlain", drivabilityLine(severity) + ".");
    // Likely causes (from the on-device causes DB; hidden when unknown).
    const causesWrap = el("dtcDetailCausesWrap");
    const causesList = el("dtcDetailCauses");
    const causes = info && Array.isArray(info.causes) ? info.causes : [];
    if (causesWrap) causesWrap.hidden = !causes.length;
    if (causesList) {
      causesList.replaceChildren(
        ...causes.slice(0, 5).map((cause: string) => {
          const li = document.createElement("li");
          li.textContent = cause;
          return li;
        })
      );
    }
    // Freeze frame — only when this row actually carries captured conditions
    // (native doesn't yet; the demo fault scenario does). Never invented.
    const frameWrap = el("dtcDetailFrameWrap");
    const frameGrid = el("dtcDetailFrame");
    const frame = (code as VoltDtcRow & { freezeFrame?: Record<string, string | number> }).freezeFrame;
    const entries = frame && typeof frame === "object" ? Object.entries(frame) : [];
    if (frameWrap) frameWrap.hidden = !entries.length;
    if (frameGrid) {
      frameGrid.replaceChildren(
        ...entries.map(([label, value]) => {
          const cell = document.createElement("span");
          const small = document.createElement("small");
          small.textContent = label;
          const strong = document.createElement("strong");
          strong.textContent = String(value);
          cell.append(small, strong);
          return cell;
        })
      );
    }
    const search = el("dtcDetailSearch");
    if (search) search.dataset.dtcSearch = dtc;
    backdrop.hidden = false;
    sheet.hidden = false;
    // .app carries a translateZ(0) layer promotion that would turn it into the
    // containing block for this fixed sheet — drop it while the sheet is open
    // (same escape hatch the fullscreen map uses; see base.css).
    document.body.classList.add("dtc-detail-active");
    dtcDetailTrap = createFocusTrap(sheet, { onEscape: closeDtcDetail });
    dtcDetailTrap.activate();
    sheet.focus();
  }

  function closeDtcDetail(): void {
    const { sheet, backdrop } = dtcDetailNodes();
    if (dtcDetailTrap) {
      dtcDetailTrap.deactivate();
      dtcDetailTrap = null;
    }
    if (sheet) sheet.hidden = true;
    if (backdrop) backdrop.hidden = true;
    document.body.classList.remove("dtc-detail-active");
    dtcDetailCode = null;
  }

  // Plain-text report for the clipboard ("Copy report" in the sheet) — the
  // fields a mechanic or forum post actually needs, no markup.
  function copyDtcReport(): void {
    const code = dtcDetailCode;
    if (!code) return;
    const dtc = String(code.dtc || "").trim().toUpperCase();
    const info = typeof VD.dtcInfo === "function" ? VD.dtcInfo(dtc) : null;
    const severity = dtcSeverity(dtc, info ? info.severity : null);
    const lines: string[] = [];
    lines.push(`${dtc} — ${info && info.description ? info.description : code.moduleName || "unknown code"}`);
    lines.push(`Severity: ${severityLabel(severity)} · status: ${code.statusLabel || code.status || "stored"}`);
    if (code.firstSeenMs || code.lastSeenMs) {
      lines.push(
        `First seen ${VD.formatWhen(code.firstSeenMs)} · last seen ${VD.formatWhen(code.lastSeenMs)}` +
          (Number(code.seenCount || 0) > 0 ? ` · seen ${code.seenCount}x` : "")
      );
    }
    const causes = info && Array.isArray(info.causes) ? info.causes : [];
    if (causes.length) {
      lines.push("Likely causes:");
      causes.slice(0, 5).forEach((cause: string) => lines.push(`- ${cause}`));
    }
    const frame = (code as VoltDtcRow & { freezeFrame?: Record<string, string | number> }).freezeFrame;
    const entries = frame && typeof frame === "object" ? Object.entries(frame) : [];
    if (entries.length) {
      lines.push("Freeze frame (conditions at fault):");
      entries.forEach(([label, value]) => lines.push(`- ${label}: ${value}`));
    }
    lines.push("Logged by Volt Tracker");
    const text = lines.join("\n");
    const nav = navigator as Navigator & { clipboard?: { writeText?(t: string): Promise<void> } };
    if (nav.clipboard && typeof nav.clipboard.writeText === "function") {
      // Direct toast, not a status push (v2): copy is a user action, and the
      // status stream suppresses toasts on the Settings tab + dedupes repeats.
      nav.clipboard
        .writeText(text)
        .then(() => VD.showToast?.(`${dtc} report copied to clipboard`))
        .catch(() => VD.showToast?.("Could not copy the report", true));
    } else {
      VD.showToast?.("Copy is not available in this browser", true);
    }
  }

  function bindDtcDetailSheet(): void {
    el("dtcDetailClose")?.addEventListener("click", closeDtcDetail);
    el("dtcDetailBackdrop")?.addEventListener("click", closeDtcDetail);
    el("dtcDetailCopy")?.addEventListener("click", copyDtcReport);
  }
  bindDtcDetailSheet();

  // ── Scan progress narration ───────────────────────────────────────────
  // The native scan is a black box between bridge.scan() and the results
  // payload; this block narrates the OBD phases in the meantime. The % is
  // pacing only: it parks at 94% until the REAL scan-complete status (or the
  // codes payload) lands, and aborts on error/idle so it can't lie about a
  // scan that died.
  const DTC_SCAN_PHASES = [
    "Waking modules…",
    "Reading stored codes (Mode 03)…",
    "Reading pending codes (Mode 07)…",
    "Pulling freeze frames (Mode 02)…"
  ];
  const DTC_SCAN_STEP_AT = [30, 62, 92];
  let dtcScanTimer: number | null = null;
  let dtcScanPct = 0;

  function dtcScanStepIndex(pct: number): number {
    if (pct >= DTC_SCAN_STEP_AT[2]) return 3;
    if (pct >= DTC_SCAN_STEP_AT[1]) return 2;
    if (pct >= DTC_SCAN_STEP_AT[0]) return 1;
    return 0;
  }

  function paintDtcScanProgress(pct: number): void {
    const step = dtcScanStepIndex(pct);
    VD.setText("dtcScanPhase", DTC_SCAN_PHASES[step]);
    VD.setText("dtcScanPct", `${Math.round(pct)}%`);
    const fill = el("dtcScanBarFill");
    if (fill) fill.style.width = `${Math.round(pct)}%`;
    const steps = el("dtcScanSteps");
    if (steps) {
      Array.from(steps.children).forEach((chip, i) => {
        const node = chip as HTMLElement;
        node.dataset.stepState = i < step ? "done" : i === step ? "active" : "todo";
        const label = ["Modules", "Stored", "Pending", "Freeze"][i];
        node.textContent = i < step ? `✓ ${label}` : label;
      });
    }
  }

  function stopDtcScanProgress(): void {
    if (dtcScanTimer) {
      clearInterval(dtcScanTimer);
      dtcScanTimer = null;
    }
    const block = el("dtcScanProgress");
    if (block) block.hidden = true;
  }

  function startDtcScanProgress(quick = false): void {
    const block = el("dtcScanProgress");
    if (!block) return;
    if (dtcScanTimer) clearInterval(dtcScanTimer);
    dtcScanPct = 0;
    block.hidden = false;
    paintDtcScanProgress(0);
    const speed = quick ? 3.2 : 1.6;
    dtcScanTimer = window.setInterval(() => {
      const status = String((state.status || {}).state || "").toLowerCase();
      if (["error", "blocked", "failed", "idle", "disconnected"].includes(status)) {
        // The scan died (adapter dropped, native error) — stop narrating.
        stopDtcScanProgress();
        return;
      }
      const complete = status === "scan-complete";
      dtcScanPct = Math.min(complete ? 100 : 94, dtcScanPct + speed);
      paintDtcScanProgress(dtcScanPct);
      if (complete && dtcScanPct >= 100) {
        // Hold the finished bar for a beat so "100% · ✓ Freeze" registers.
        if (dtcScanTimer) window.clearInterval(dtcScanTimer);
        dtcScanTimer = window.setTimeout(stopDtcScanProgress, 900);
      }
    }, 50);
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
    setBatterySohHistory(payload: unknown) {
      applyBatterySohHistory(payload);
      renderBatterySohTrend();
    },
    monthBucketKey,
    buildMonthlyTrendSvg,
    loadMaintenanceLog,
    renderMaintenanceList,
    addMaintenanceEntry,
    submitMaintenanceForm,
    closeMaintenanceForm,
    exportChargeSessionsCsv,
    toggleHidden,
    openDtcDetail,
    closeDtcDetail,
    startDtcScanProgress
  });
})();

export {};
