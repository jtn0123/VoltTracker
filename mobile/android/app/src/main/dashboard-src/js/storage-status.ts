// storage-status.ts — Settings/Database storage summary, the post-session
// review block, the DTC (diagnostic trouble code) report, the Insights "real
// v2" hero (battery/charge/vehicle) and the Charge tab history.
//
// Split out of the old panels.ts god-module (C2). In-bundle cross-module calls
// use typed ESM imports (C7); the Object.assign(VD, ...) at the bottom republishes
// this module's entry points on the VD registry for the external ABI and for the
// LAZY chunks (insights-panel.ts, signals-panel.ts, charge-history.ts,
// maintenance-panel.ts, dtc-detail.ts) that reach for isNativeError /
// reportNativeReadError / buildStatusCopy / toggleHidden / dtcSeverity across
// the chunk boundary. Remaining VD.* call sites here are that same boundary in
// the other direction (chunk-owned symbols like dtcInfo/openDtcDetail/
// renderPackStats/loadTrips), telemetry-owned helpers that evaluate after this
// module (formatWhen/formatDuration/dbRowCount/...), and the runtime-wrapped
// setStatus — see vd-registry.ts.
import {
  bridge,
  callBridge,
  ensureDtcData,
  ensureDtcDetailModule,
  ensureInsightsModule,
  el,
  formatRowCount,
  parsePayload,
  renderMapIfLoaded,
  setState,
  setSvgAttrs,
  setText,
  state
} from "./core";
import { VD } from "./vd-registry";
import { setDataState } from "./dataset-state";
import { createNativeRequestGate } from "./native-request-gate";
import { validatePayload } from "./payload-validators";
import { prefs, units } from "./prefs";
import { storageRollupSignature } from "./render-signatures";

// Module scope (the old IIFE wrapper is redundant under ESM and blocks
// `export` declarations).

  let storageDetailsScheduled = false;
  const storageDetailsRead = createNativeRequestGate(() => handleStorageDetailsFailure());
  let applyingStorageDetails = false;
  // Signature of the last storage summary that drove a lazy-rollup reload. Every
  // non-details broadcast used to invalidate the trips/insights rollups and
  // re-fetch them (rebuilding every Insights chart via replaceChildren) even when
  // the summary was identical — and setStorage fires from setAppState on every
  // app-state push. Only reload when a rollup-relevant field actually changes.
  let lastRollupSig: string | null = null;

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
    void ensureInsightsModule().then(load).catch(() => {});
  }

  // Plain boolean (not a type predicate): the parsed payloads it screens —
  // VoltStorageSummary, VoltTrip[], VoltInsights — structurally overlap
  // VoltNativeError, so a `payload is VoltNativeError` guard would narrow the
  // happy-path value to `never`. Callers read the error fields off the same
  // payload after the check.
  export function isNativeError(payload: unknown): boolean {
    const candidate = payload as VoltNativeError | null;
    return (
      candidate != null &&
      typeof candidate === "object" &&
      candidate.ok === false &&
      Boolean(candidate.error)
    );
  }

  export function reportNativeReadError(payload: unknown, fallbackDetail: string) {
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

  function handleStorageDetailsFailure() {
    storageDetailsRead.complete();
    reportNativeReadError(
      {
        ok: false,
        error: "storage_details_failed",
        message: "Could not read local storage details."
      },
      "Could not read local storage details."
    );
  }

  function scheduleStorageDetailsLoad() {
    // Local capture so the narrowing from this guard flows into the deferred
    // `load` closure (TS does not narrow imported bindings inside closures).
    const bridgeApi = bridge;
    if (
      !bridgeApi ||
      (typeof bridgeApi.getStorageDetails !== "function" &&
        typeof bridgeApi.requestStorageDetails !== "function")
    ) {
      return;
    }
    if (storageDetailsScheduled) return;
    if (storageDetailsRead.pending) return;
    storageDetailsScheduled = true;
    const load = () => {
      storageDetailsScheduled = false;
      try {
        if (typeof bridgeApi.requestStorageDetails === "function" && bridgeApi.requestStorageDetails()) {
          storageDetailsRead.begin();
          return;
        }
        if (typeof bridgeApi.getStorageDetails !== "function") return;
        const payload = callBridge("getStorageDetails");
        if (payload == null) {
          handleStorageDetailsFailure();
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
        handleStorageDetailsFailure();
      }
    };
    if (typeof window.requestIdleCallback === "function") {
      window.requestIdleCallback(load, { timeout: 2000 });
    } else {
      setTimeout(load, 0);
    }
  }

  export function setStorage(payload: unknown) {
    const parsed = parsePayload<VoltStorageSummary>(payload, {});
    validatePayload("setStorage", parsed);
    if (isNativeError(parsed)) {
      const err = parsed as VoltNativeError;
      reportNativeReadError(parsed, "Could not read local storage summary.");
      if (applyingStorageDetails || err.error === "storage_details_failed") {
        storageDetailsRead.complete();
        return;
      }
      const storageError: VoltStorageSummary = { message: err.message || "" };
      if (err.error) storageError.error = err.error;
      state.storage = storageError;
      updateStorageUi();
      renderRealV2Ui();
      renderMapIfLoaded();
      VD.updateValidationUi();
      return;
    }
    const isDetails = isStorageDetailsPayload(parsed);
    if (isDetails) storageDetailsRead.complete();
    const newRoutes =
      parsed && Array.isArray(parsed.recentRoutes) ? parsed.recentRoutes : [];
    if (state.demoActive && state.demoPreviewStorage) {
      // Park the real summary while demo preview owns the screen (cross-module
      // invariant: restored when demo stops).
      if (isDetails) {
        const details = { ...(parsed as Record<string, unknown>) };
        delete details.storageDetails;
        setState({ realStorage: { ...(state.realStorage || {}), ...details } });
      } else {
        setState({ realStorage: parsed });
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
    renderMapIfLoaded();
    VD.updateValidationUi();
    if (!isDetails) {
      // Only invalidate + reload the trips/insights rollups (which rebuild every
      // Insights chart) when the summary actually changed in a rollup-relevant
      // way. Tab-switching still loads independently (core.ts setView), so an
      // unchanged broadcast can safely skip this. loadMaintenanceLog stays on
      // every broadcast (its own render is memoized) so odometer/time-driven due
      // dates still refresh — once its lazy chunk is present (it fetches the
      // current log itself on load, so earlier broadcasts are never lost).
      const rollupSig = storageRollupSignature(state.storage || {});
      if (rollupSig !== lastRollupSig) {
        lastRollupSig = rollupSig;
        invalidateLazyRollups();
        loadRollupsForActiveView();
      }
      if (typeof VD.loadMaintenanceLog === "function") VD.loadMaintenanceLog();
      scheduleStorageDetailsLoad();
    }
  }

  export function updateStorageUi() {
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
    setText("dbSessionCount", sessions);
    setText("dbSampleCount", samples);
    setText("dbEventCount", events);
    setText("dbPidCount", pidRows);
    setText("dbDtcCount", dtcRows);
    setText("dbLocationCount", locationRows);
    setText("dbTripCount", tripRows);
    setText("dbChargeCount", chargeRows);
    setText("dbBatteryCount", batteryRows + cellRows);
    setText("dbSize", VD.formatBytes(Number(storage.databaseBytes || 0)));
    // Presence check, not truthiness: a legitimate rawTelemetryCount of 0 must
    // render as 0 — only fall back to the sample count when the field is
    // missing (null/undefined) or non-numeric.
    const rawTelemetry = storage.rawTelemetryCount == null ? NaN : Number(storage.rawTelemetryCount);
    setText("dbRawTelemetryCount", Number.isFinite(rawTelemetry) ? rawTelemetry : samples);
    setText("dbEmptyTelemetryCount", Number(storage.emptyTelemetryCount || 0));
    setText("dbState", formatRowCount(VD.dbRowCount(storage)));
    const last = storage.lastEventAtMs || storage.lastStartedAtMs;
    const sampleLabel = `${samples} sample${samples === 1 ? "" : "s"}`;
    setText("dbSummaryTitle", sessions ? (last ? `${sampleLabel} · ${VD.formatWhen(last)}` : sampleLabel) : "No stored sessions yet");
    const recent = Array.isArray(storage.recentSessions) ? storage.recentSessions : [];
    const list = el("dbSessionList");
    updateDiagnosticCodeUi();
    // Detailed-signal rendering is an Advanced-only lazy chunk. Storage still
    // updates every tab before that chunk exists, so treat its renderer as an
    // optional subscriber; opening Advanced immediately renders the latest
    // stored state through prefs.ts.
    if (typeof VD.updateEnhancedCapabilityUi === "function") VD.updateEnhancedCapabilityUi();
    if (!recent.length) {
      list?.replaceChildren(buildStatusCopy("Connect or scan to create local SQLite rows. Preview data stays isolated in the sandbox."));
      updateReviewUi();
      return;
    }
    if (list) renderRecentSessionWindow(list, recent);
    updateReviewUi();
  }

  const RECENT_SESSION_INITIAL_LIMIT = 50;
  const RECENT_SESSION_PAGE_SIZE = 50;

  function renderRecentSessionWindow(
    list: HTMLElement,
    recent: VoltRecentSession[],
    requestedLimit = Number(list.dataset.sessionLimit || RECENT_SESSION_INITIAL_LIMIT),
  ) {
    const limit = Math.min(recent.length, Math.max(RECENT_SESSION_INITIAL_LIMIT, requestedLimit));
    list.dataset.sessionLimit = String(limit);
    const nodes: HTMLElement[] = recent.slice(0, limit).map(buildRecentSessionRow);
    if (limit < recent.length) {
      const more = document.createElement("div");
      more.className = "history-more";
      const copy = buildStatusCopy(`Showing ${limit} of ${recent.length} sessions.`);
      const button = document.createElement("button");
      button.type = "button";
      button.className = "history-export-btn";
      button.dataset.sessionMore = "true";
      button.textContent = "Show 50 more";
      button.addEventListener("click", () => {
        renderRecentSessionWindow(list, recent, limit + RECENT_SESSION_PAGE_SIZE);
        const next = list.querySelector<HTMLElement>("[data-session-more='true']");
        (next || list.querySelector<HTMLElement>(".history-row:last-of-type"))?.focus();
      });
      more.append(copy, button);
      nodes.push(more);
    }
    list.replaceChildren(...nodes);
  }

  // ---- Row builders: prefer document.createElement + textContent over
  // innerHTML += template literals so storage strings can never be reinterpreted
  // as markup. Each builder returns a single root Element.

  export function buildStatusCopy(text: string) {
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
  // Signature of the last rendered post-session review. updateReviewUi runs on
  // every storage push (setStorage fires from setAppState ~1 Hz during a live
  // drive), but latestReview only changes at session end — so the same ~20-30
  // warning/insight/timeline/PID-frame nodes were torn down and rebuilt for
  // nothing. Memoized like lastDtcListSig.
  let lastReviewSig = "";

  export function updateDiagnosticCodeUi() {
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
    setText("dtcTitle", totalCodes ? `${totalCodes} code${totalCodes === 1 ? "" : "s"} saved` : "No car-code scan yet");
    setText("dtcReportBadge", needsDtcData ? "loading details" : totalCodes ? "evidence saved" : "ready");
    setText("dtcTotalCount", totalCodes);
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
    setText("dtcStoredCount", storedOrCurrent);
    setText("dtcPendingCount", Number(statusCounts.pending || 0));
    setText("dtcPermanentCount", Number(statusCounts.permanent || 0));
    setText("dtcFreezeCount", Number(statusCounts["freeze-frame"] || 0));
    setText("dtcLastSeen", latestSeen ? VD.formatWhen(latestSeen) : "--");
    if (!list) return;
    if (needsDtcData) {
      ensureDtcData()
        .then(() => {
          updateDiagnosticCodeUi();
        })
        .catch(() => {
          setText("dtcReportBadge", "details unavailable");
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
    // Only cascade on the first empty->populated render. When rows are already
    // visible (e.g. the dtc-causes DB just resolved and flipped the signature
    // from raw: to db:), rebuild silently so the icons/descriptions fill in
    // without the rows flashing out to opacity 0 and cascading in a second time.
    const hadRows = Boolean(list.querySelector(".dtc-item"));
    list.replaceChildren(...codes.map((c, i) => buildDtcItem(c, false, i, !hadRows)));
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

  function buildDtcItem(code: VoltDtcRow, isExample: boolean, index = 0, animate = true) {
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
      openDtcDetailLazy(code);
    });
    article.addEventListener("keydown", (event) => {
      if (event.key !== "Enter" && event.key !== " ") return;
      event.preventDefault();
      openDtcDetailLazy(code);
    });
    // Staggered entrance: rows cascade in 90ms apart the first time the list
    // populates after a scan. `backwards` holds them hidden until their turn.
    // Gated on `animate` so the necessary content rebuild when the lazy
    // dtc-causes DB resolves (raw:->db: signature flip) does NOT reset
    // already-visible rows to opacity 0 and replay the whole cascade.
    if (animate) {
      article.classList.add("dtc-item-enter");
      article.style.animationDelay = `${Math.min(index, 6) * 90}ms`;
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
    // Every rendered output derives solely from `review`, so an unchanged review
    // makes the whole body safely skippable. Sign over each field the card reads.
    const reviewSig = JSON.stringify([
      session.id || "",
      session.mode || "",
      session.adapterName || "",
      Number(review.maxSpeedKph || 0),
      Number(review.locationSampleCount || 0),
      Number(review.parsedPidCount || 0),
      Number(review.unknownPidCount || 0),
      Number(review.avgSampleIntervalMs || 0),
      review.backgroundSampleCount ?? null,
      review.sampleGapEventCount ?? null,
      Number(review.usefulTelemetryCount || 0),
      Number(review.emptyTelemetryCount || 0),
      warnings,
      timeline,
      frames,
      review.latestHealth || null,
      units.system(),
    ]);
    if (reviewSig === lastReviewSig) return;
    lastReviewSig = reviewSig;
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

    setText("reviewTitle", hasSession
      ? `${session.mode || "session"} · ${session.adapterName || "OBD adapter"}`
      : "No real session yet");
    setText("reviewMaxSpeed", maxSpeed ? units.speedText(maxSpeed) : "--");
    setText("reviewGpsCount", gpsCount ? `${gpsCount}` : "--");
    setText("reviewPidParse", (parsed || unknown) ? `${parsed}/${parsed + unknown}` : "--");
    setText("reviewInterval", interval ? VD.formatShortDuration(interval) : "--");
    setText("reviewBackground", backgroundSamples ? `${backgroundSamples} sample${backgroundSamples === 1 ? "" : "s"}` : "--");
    setText("reviewGaps", sampleGaps ? `${sampleGaps}` : "0");
    setText("reviewUsefulSamples", usefulSamples ? `${usefulSamples}` : "--");
    setText("reviewEmptySamples", emptySamples ? `${emptySamples}` : "0");
    setText("pidFrameTitle", frames.length ? `${frames.length} latest frame${frames.length === 1 ? "" : "s"}` : "Waiting for scan data");

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
  const sohRead = createNativeRequestGate(() => handleSohReadFailure());
  // Battery-snapshot row count at the last fetch. When it changes (a new snapshot
  // landed, or storage was cleared) we refetch immediately instead of waiting out
  // the throttle, so the chart never shows stale history after a storage change.
  let sohLastCount = -1;

  function handleSohReadFailure() {
    sohRead.complete();
    sohLastFetchMs = Date.now();
    if (sohLastRaw === null) sohLastRaw = "[]";
    reportNativeReadError(
      {
        ok: false,
        error: "battery_soh_history_failed",
        message: "Could not read battery health history."
      },
      "Could not read battery health history."
    );
  }

  function applyBatterySohHistory(payload: unknown) {
    sohRead.complete();
    sohLastFetchMs = Date.now();
    sohLastCount = Number((state.storage || {}).batterySnapshotCount || 0);
    const raw = typeof payload === "string" ? payload : JSON.stringify(payload ?? []);
    if (raw === sohLastRaw) return;
    sohLastRaw = raw;
    const parsed = parsePayload<Array<Record<string, unknown>>>(raw, []);
    validatePayload("setBatterySohHistory", parsed);
    sohPoints = Array.isArray(parsed)
      ? parsed
          // A warn-only schema validator must not let malformed native rows crash
          // the entire dashboard. Keep only plain objects before dereferencing
          // the fields below; arrays, nulls, and primitives are invalid rows.
          .filter(
            (row): row is Record<string, unknown> =>
              row != null && typeof row === "object" && !Array.isArray(row),
          )
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
    const latestSoh = ss[ss.length - 1]!;
    const tone = latestSoh >= 85 ? "ok" : latestSoh >= 70 ? "warn" : "bad";
    const svg = make("svg", {
      viewBox: `0 0 ${w} ${h}`,
      class: "soh-trend-svg",
      "data-soh": tone,
      role: "img",
      "aria-label": `Battery state of health trend, latest ${latestSoh.toFixed(1)} percent`,
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
    const firstPoint = points[0]!;
    const last = points[points.length - 1]!;
    const firstX = xOf(firstPoint.at).toFixed(1);
    const lastX = xOf(last.at).toFixed(1);
    svg.appendChild(make("path", { d: `${d.trim()} L${lastX} ${baseY} L${firstX} ${baseY} Z`, class: "soh-area" }));
    svg.appendChild(make("path", { d: d.trim(), class: "soh-line" }));
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
      !sohRead.pending &&
      (sohLastRaw === null || countChanged || now - sohLastFetchMs >= SOH_REFETCH_MS)
    ) {
      try {
        if (typeof bridge.requestBatterySohHistory === "function" && bridge.requestBatterySohHistory()) {
          sohLastFetchMs = now;
          sohLastCount = batteryCount;
          sohRead.begin();
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
        handleSohReadFailure();
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
    card.hidden = !has;
    if (chart) chart.hidden = !has;
    if (stats) stats.hidden = !has;
    if (empty) empty.hidden = has;
    if (!has) {
      setText("sohTrendTitle", "No battery-health readings yet");
      if (latestEl) latestEl.textContent = points.length === 1 ? `${points[0]!.soh.toFixed(1)}%` : "--";
      return;
    }
    const latest = points[points.length - 1]!;
    setText("sohTrendTitle", "Pack state-of-health over time");
    if (latestEl) latestEl.textContent = `${latest.soh.toFixed(1)}%`;
    setText("sohTrendCapacity", Number.isFinite(latest.cap) ? `${latest.cap.toFixed(1)} Ah` : "--");
    setText("sohTrendCount", String(points.length));
    setText("sohTrendSpan", sohSpanLabel(points[0]!.at, latest.at));
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
    setText(
      "tripTimeValue",
      Number.isFinite(durationMs) && durationMs > 0 && typeof VD.formatDuration === "function"
        ? VD.formatDuration(durationMs)
        : "--"
    );
    const energyKwh = trip && trip.energyKwh != null ? Number(trip.energyKwh) : NaN;
    const hasEnergy = Number.isFinite(energyKwh) && energyKwh > 0;
    const tripMeters = trip && trip.distanceMeters != null ? Number(trip.distanceMeters) : routeDistance;
    const miles = Number.isFinite(tripMeters) && tripMeters > 0 ? tripMeters / 1609.344 : NaN;
    setText(
      "tripEffValue",
      hasEnergy && Number.isFinite(miles) && miles > 0
        ? units.efficiencyText(miles / energyKwh)
        : "--"
    );
    setText("tripEnergyValue", hasEnergy ? `${energyKwh.toFixed(1)} kWh` : "--");
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

  export function renderRealV2Ui() {
    const storage = state.storage || {};
    const overview: Record<string, unknown> = storage.overview || {};
    const charge = storage.chargeSummary || {};
    const route = selectedRouteForOverview(storage);
    const hasRows = VD.dbRowCount(storage) > 0;
    const hasCharge = Number(charge.chargeSessionCount || charge.chargingHintCount || 0) > 0;
    const latest = latestInsightReading(storage);
    toggleHidden("appEmptyState", hasRows);
    toggleHidden("chargeEmptyState", hasCharge);
    toggleHidden("chargeSummaryGrid", !hasCharge);
    toggleHidden("insightsEmptyState", hasInsightContent());
    const routeDistance = Number(route.distanceMeters || overview.distanceMeters || 0);
    setText("overviewDistance", routeDistance ? VD.formatDistance(routeDistance) : "--");
    setText("overviewMaxSpeed", overview.maxSpeedKph ? units.speedText(Number(overview.maxSpeedKph)) : "--");
    const soc = Number(latest.soc);
    const power = Number(latest.powerKw ?? latest.packPowerKw);
    renderThisTripCard(route, routeDistance);

    setText("realChargeHints", Number(charge.chargingHintCount || 0));
    setText("realChargePower", charge.maxPowerKw ? `${Number(charge.maxPowerKw).toFixed(1)} kW` : "--");
    // Charge history is an on-demand lazy chunk (charge-history.ts); treat its
    // renderer as an optional subscriber, like updateEnhancedCapabilityUi.
    if (typeof VD.renderChargeSessions === "function") VD.renderChargeSessions(charge);
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
      setText("realPackTitle", `Pack at ${socRound}%${socRound <= 15 ? " — low" : socRound <= 30 ? " — getting low" : ""}`);
      const stateText = latest.vehicleState && latest.vehicleState !== "unknown" ? String(latest.vehicleState) : "";
      const powerText = Number.isFinite(power) ? (power < -0.05 ? `Regenerating ${Math.abs(power).toFixed(1)} kW` : power > 0.05 ? `Drawing ${power.toFixed(1)} kW` : "") : "";
      setText("realPackCopy", [stateText, powerText].filter(Boolean).join(" · ") || "From the latest logged reading.");
    } else {
      if (ring) {
        ring.style.setProperty("--v", "0");
        setDataState(ring, "waiting");
        delete ring.dataset.level;
      }
      if (ringValue) ringValue.textContent = "--";
      setText("realPackTitle", "Waiting for battery readings");
      setText("realPackCopy", "Battery charge, power, and pack health appear here once the adapter has logged a few readings.");
    }
    if (typeof VD.renderPackStats === "function") VD.renderPackStats(latest);

    if (typeof VD.renderMaintenanceList === "function") VD.renderMaintenanceList();
    renderVehicleUi();
  }

  // The maintenance log (renderMaintenanceList/loadMaintenanceLog and the
  // add-entry form) and the charge history / pack-stat renders
  // (renderChargeSessions/renderPackStats, the monthly trend chart, and the
  // CSV export) live in the LAZY maintenance-panel.ts / charge-history.ts
  // chunks (G2 startup-headroom split): none of them paint on the Drive-first
  // startup path. The renders here call them through optional-subscriber
  // guards — the same pattern as updateEnhancedCapabilityUi — and each chunk
  // re-renders the current state when it loads, so a broadcast that lands
  // before the chunk exists is never lost.

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

    setText("vehicleName", name || identity || "No vehicle identified yet");
    setText("vehicleSummary", known
      ? "Read from your vehicle. Blank fields fill in as more readings come through."
      : "Your vehicle's details fill in automatically once the VIN and odometer are read.");
    setText("vehicleVin", vehicle.vin || "--");
    setText("vehicleYear", year || "--");

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
    setText("vehicleOdometer", odometer);

    const loggedMeters = Number(insights.totalDistanceMeters || 0);
    setText("vehicleLoggedDistance", loggedMeters > 0 ? VD.formatDistance(loggedMeters) : "--");
  }

  export function toggleHidden(id: string, hidden: unknown) {
    const node = el(id);
    if (node) node.hidden = Boolean(hidden);
  }

  // The DTC detail bottom sheet and the scan-progress narration live in the
  // LAZY dtc-detail.ts chunk (G2 startup-headroom split): both only appear
  // after a Diagnostics-tab interaction. Row taps load the chunk on demand via
  // openDtcDetailLazy below; the severity vocabulary stays here (the eager
  // code rows render it) and is shared with the chunk through VD.

  // Opens the detail sheet for a code row, loading the lazy chunk on first use.
  // The chunk load is a local-asset fetch (single-digit ms on-device), so the
  // first tap opens without a perceptible delay.
  function openDtcDetailLazy(code: VoltDtcRow): void {
    if (typeof VD.openDtcDetail === "function") {
      VD.openDtcDetail(code);
      return;
    }
    void ensureDtcDetailModule()
      .then(() => {
        if (typeof VD.openDtcDetail === "function") VD.openDtcDetail(code);
      })
      .catch(() => {});
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
    // Shared with the lazy maintenance-panel.ts / charge-history.ts chunks
    // (G2 split): latest-odometer math and bridge-write error reporting stay
    // owned here so the chunks read them off VD like the other shared helpers.
    latestInsightReading,
    reportBridgeWriteFailure,
    toggleHidden,
    // Severity vocabulary shared with the lazy dtc-detail.ts sheet chunk (the
    // eager code rows above render the same badges, so ownership stays here).
    dtcSeverity,
    severityLabel,
    drivabilityLine
  });

export {};
