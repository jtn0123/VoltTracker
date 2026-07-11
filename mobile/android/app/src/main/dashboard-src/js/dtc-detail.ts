// dtc-detail.ts — the DTC detail bottom sheet (plain-language description,
// severity verdict, likely causes, freeze-frame grid, copy-report) and the
// Mode 03/07/02 scan-progress narration.
//
// Split out of storage-status.ts (G2 startup-headroom pass) as a LAZY chunk:
// both surfaces live on the Diagnostics tab and only appear after a user
// interaction (tapping a code row / lookup hit, or starting a scan), so the
// code loads through core.ts#ensureDtcDetailModule at those call sites instead
// of riding the eager app.js bundle. Entry points are attached to the shared
// VD global; the severity vocabulary (dtcSeverity/severityLabel/
// drivabilityLine) stays owned by storage-status.ts — the eager code list rows
// use it too — and is read off VD here so the two surfaces can't drift.
import { createFocusTrap, type FocusTrap } from "./focus-trap";

(function () {
  "use strict";

  const VD = window.VoltDashboard;
  const state = VD.state;
  const el = VD.el;

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
    const severity = VD.dtcSeverity(dtc, info ? info.severity : null);
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
      sev.textContent = VD.severityLabel(severity);
    }
    VD.setText("dtcDetailPlain", VD.drivabilityLine(severity) + ".");
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
    const severity = VD.dtcSeverity(dtc, info ? info.severity : null);
    const lines: string[] = [];
    lines.push(`${dtc} — ${info && info.description ? info.description : code.moduleName || "unknown code"}`);
    lines.push(`Severity: ${VD.severityLabel(severity)} · status: ${code.statusLabel || code.status || "stored"}`);
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
    if (pct >= DTC_SCAN_STEP_AT[2]!) return 3;
    if (pct >= DTC_SCAN_STEP_AT[1]!) return 2;
    if (pct >= DTC_SCAN_STEP_AT[0]!) return 1;
    return 0;
  }

  function paintDtcScanProgress(pct: number): void {
    const step = dtcScanStepIndex(pct);
    VD.setText("dtcScanPhase", DTC_SCAN_PHASES[step] ?? "Reading codes…");
    VD.setText("dtcScanPct", `${Math.round(pct)}%`);
    const fill = el("dtcScanBarFill");
    if (fill) fill.style.width = `${Math.round(pct)}%`;
    const steps = el("dtcScanSteps");
    if (steps) {
      Array.from(steps.children).forEach((chip, i) => {
        const node = chip as HTMLElement;
        node.dataset.stepState = i < step ? "done" : i === step ? "active" : "todo";
        const label = ["Modules", "Stored", "Pending", "Freeze"][i] ?? "Step";
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
    openDtcDetail,
    closeDtcDetail,
    startDtcScanProgress
  });
})();

export {};
