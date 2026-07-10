// signals-panel.ts — the Detailed Signals (enhanced PID) discovery workspace:
// the capability scoreboard, status/stage filter chips, the "next candidates"
// strip, and the per-signal evidence rows.
//
// Split out of the old panels.ts god-module (C2). The single cross-module render
// entry point (updateEnhancedCapabilityUi) is attached to the shared VD global;
// storage-status.ts calls it after each storage refresh. The shared buildStatusCopy
// helper is owned by storage-status.ts and read off VD here.
import { setDataState } from "./dataset-state";
import type { DataStateValue } from "./dataset-state";

(function () {
  "use strict";

  const VD = window.VoltDashboard;
  const el = VD.el;
  const state = VD.state;
  let enhancedSignalFilter = "all";
  // Signature of the last rendered detailed-signal list. updateEnhancedCapabilityUi
  // runs on every storage broadcast (~1 Hz), and it used to replaceChildren the
  // whole list unconditionally — rebuilding up to 18 rows with focusable
  // Export/Delete buttons even when nothing changed, destroying focus/selection
  // mid-session. Memoized like updateDiagnosticCodeUi (lastDtcListSig).
  let lastSignalListSig = "";
  type SignalStageMeta = { label: string; hint: string };
  const signalStageMeta: Record<string, SignalStageMeta> = {
    passive: {
      label: "Passive",
      hint: "Only logs adapter state and passive targets; no active enhanced PID requests."
    },
    "low-risk": {
      label: "Low-risk",
      hint: "Standard optional and known low-risk enhanced reads; avoids DTC and freeze-frame reads."
    },
    tires: {
      label: "Tires",
      hint: "Narrow tire receiver candidates; avoids DTC and freeze-frame reads."
    },
    experimental: {
      label: "Experimental",
      hint: "Higher-value enhanced candidates with cooldowns; keep this for short controlled tests."
    }
  };

  // Typed accessor for the optional captured sample on an evidence row — one
  // place that narrows `sample?: VoltEnhancedSample | null` to a usable record.
  function sampleOf(row: VoltEnhancedCapability): VoltEnhancedSample {
    return row && typeof row.sample === "object" && row.sample ? row.sample : {};
  }

  function enhancedCapabilityStatus(capability: VoltEnhancedCapability) {
    const sample = sampleOf(capability);
    const lane = String(sample.pollLane || capability.pollLane || "").toLowerCase();
    if (lane === "passive") return "deferred";
    if (capability.supported === true) return "confirmed";
    if (capability.supported === false && Number(capability.responseCount || 0) <= 0) return "rejected";
    const validation = String(sample.validationStatus || capability.validationStatus || "").toLowerCase();
    if (validation === "confirmed") return "confirmed";
    if (validation === "rejected_on_this_vehicle") return "rejected";
    return "candidate";
  }

  function updateEnhancedCapabilityUi() {
    const storage = state.storage || {};
    const rows = detailedSignalRows(storage);
    const counts = rows.reduce((tally: Record<string, number>, row) => {
      const status = String(row._status);
      tally[status] = (tally[status] || 0) + 1;
      const sample = sampleOf(row);
      const category = String(row.category || sample.category || "").toLowerCase();
      if (category === "tpms") tally.tpms = (tally.tpms || 0) + 1;
      return tally;
    }, { confirmed: 0, rejected: 0, candidate: 0, deferred: 0, tpms: 0 });
    // With no rows the per-status chips and the list all read "0 / run a scan",
    // so the title + All chip must agree — falling back to fieldCapabilityCount
    // here made them claim "N tracked" while everything else showed 0.
    const total = rows.length;
    const list = el("enhancedCapabilityList");
    VD.setText("enhancedTitle", total ? `${total} detailed signal${total === 1 ? "" : "s"} tracked` : "No detailed signal results yet");
    // Zero-result pill uses the "idle" tone (the one distinctly-styled neutral
    // state on .signal-status-pill, alongside "blocked") so an empty scoreboard
    // no longer renders the same green as the "saved"/"working" has-data states.
    setEnhancedBadge(counts.confirmed ? "working data" : total ? "evidence saved" : "ready", counts.confirmed ? "working" : total ? "saved" : "idle");
    // The scoreboard counts and the status filter chips share one control now,
    // so each count is written once to the chip that also filters by it.
    VD.setText("enhancedAllCount", total);
    VD.setText("enhancedConfirmedCount", counts.confirmed || 0);
    VD.setText("enhancedCandidateCount", counts.candidate || 0);
    VD.setText("enhancedRejectedCount", counts.rejected || 0);
    VD.setText("enhancedDeferredCount", counts.deferred || 0);
    VD.setText("enhancedTiresTabCount", counts.tpms || 0);
    updateSignalStageUi(rows);
    updateEnhancedFilterButtons();
    updateEnhancedNextList(rows);
    if (!list) return;
    // The cheap idempotent setText/badge/stage/filter/next-list updates above run
    // every pass (like updateDiagnosticCodeUi); only the expensive row rebuild
    // below is memoized. Signature covers the filter, the visible count, and
    // every field each visible row (capped at 18) renders — so a real change
    // (new signal, status flip, fresh last-seen) still rebuilds, but an
    // unchanged broadcast leaves the existing rows (and their focus) untouched.
    const visible = rows.length ? rows.filter((row) => matchesEnhancedFilter(row)) : [];
    const shown = visible.slice(0, 18);
    const rowSig = (r: VoltEnhancedCapability) => {
      const s = sampleOf(r);
      return [
        r._status,
        r.name || r.pid || r.command || "",
        String(r.category || s.category || ""),
        String(r.scanStage || s.scanStage || ""),
        String(r.risk || s.risk || ""),
        r.header || "",
        r.command || r.pid || "",
        r._hasEvidence ? 1 : 0,
        r._hasEvidence && r.lastSeenMs ? r.lastSeenMs : "",
        r.notes || r.source || "",
        r.id || "",
      ].join("");
    };
    const listSig = !rows.length
      ? "empty"
      : !visible.length
        ? "nomatch:" + enhancedSignalFilter
        : ["rows", enhancedSignalFilter, visible.length, ...shown.map(rowSig)].join("");
    if (listSig === lastSignalListSig) return;
    lastSignalListSig = listSig;
    if (!rows.length) {
      list.replaceChildren(VD.buildStatusCopy("Run Scan or Detail Probe once to collect detailed signal evidence."));
      return;
    }
    if (!visible.length) {
      list.replaceChildren(VD.buildStatusCopy("No detailed signals match this filter yet."));
      return;
    }
    const nodes: Node[] = shown.map(buildEnhancedCapabilityRow);
    // The list caps at 18 rows while the title/chips advertise the full count —
    // say so instead of silently truncating, and point at the filter chips as
    // the way to reach the rest.
    if (visible.length > 18) {
      nodes.push(VD.buildStatusCopy(
        `Showing 18 of ${visible.length}. Use the status chips above to narrow the list.`
      ));
    }
    list.replaceChildren(...nodes);
  }

  function setEnhancedBadge(label: string, tone?: DataStateValue) {
    const badge = el("enhancedBadge") as HTMLElement | null;
    if (!badge) return;
    badge.textContent = label;
    if (tone) setDataState(badge, tone);
    else delete badge.dataset.state;
  }

  function detailedSignalRows(storage: VoltStorageSummary): VoltEnhancedCapability[] {
    const capabilities = Array.isArray(storage.enhancedCapabilities) ? storage.enhancedCapabilities : [];
    const catalog = Array.isArray(storage.detailedSignalCatalog) ? storage.detailedSignalCatalog : [];
    const evidenceByKey = new Map<string, VoltEnhancedCapability>();
    capabilities.forEach((capability) => {
      evidenceByKey.set(signalKey(capability), capability);
    });
    const rows: VoltEnhancedCapability[] = catalog.map((profile) => {
      const evidence = evidenceByKey.get(signalKey(profile));
      if (evidence) evidenceByKey.delete(signalKey(profile));
      const merged: VoltEnhancedCapability = { ...profile, ...(evidence || {}) };
      merged._hasEvidence = Boolean(evidence);
      merged._status = evidence ? enhancedCapabilityStatus(merged) : catalogSignalStatus(profile);
      return merged;
    });
    evidenceByKey.forEach((evidence) => {
      rows.push({ ...evidence, _hasEvidence: true, _status: enhancedCapabilityStatus(evidence) });
    });
    return rows;
  }

  function signalKey(item: VoltEnhancedCapability) {
    return `${String(item.header || "").toUpperCase()}|${String(item.command || item.pid || "").toUpperCase()}`;
  }

  function catalogSignalStatus(profile: VoltEnhancedCapability) {
    const lane = String(profile.pollLane || "").toLowerCase();
    if (lane === "passive") return "deferred";
    const validation = String(profile.validationStatus || "").toLowerCase();
    if (validation === "confirmed") return "confirmed";
    if (validation === "rejected_on_this_vehicle") return "rejected";
    return "candidate";
  }

  function matchesEnhancedFilter(row: VoltEnhancedCapability) {
    if (enhancedSignalFilter === "all") return true;
    if (enhancedSignalFilter === "tpms") {
      const sample = sampleOf(row);
      return String(row.category || sample.category || "").toLowerCase() === "tpms";
    }
    return row._status === enhancedSignalFilter;
  }

  function updateEnhancedFilterButtons() {
    const bar = el("enhancedFilterBar");
    if (!bar) return;
    bar.querySelectorAll<HTMLElement>("[data-signal-filter]").forEach((button) => {
      const on = button.dataset.signalFilter === enhancedSignalFilter;
      button.classList.toggle("is-active", on);
      button.setAttribute("aria-pressed", String(on));
    });
  }

  function updateEnhancedNextList(rows: VoltEnhancedCapability[]) {
    const list = el("enhancedNextList");
    const label = el("signalNextLabel");
    if (!list) return;
    const stage = state.signalProbeStage || "tires";
    const next = rows
      .filter((row) => row._status === "candidate" && !row._hasEvidence)
      .filter((row) => String(row.scanStage || sampleOf(row).scanStage || "tires") === stage)
      .slice(0, 3);
    // Hide the whole section (label + list) when this probe mode has no fresh
    // candidates, rather than showing a loud full-width empty message.
    const hasNext = next.length > 0;
    if (label) label.hidden = !hasNext;
    list.hidden = !hasNext;
    if (!hasNext) {
      list.replaceChildren();
      return;
    }
    list.replaceChildren(...next.map(buildEnhancedNextItem));
  }

  function buildEnhancedNextItem(row: VoltEnhancedCapability) {
    const item = document.createElement("article");
    item.className = "enhanced-next-item";
    const strong = document.createElement("strong");
    strong.textContent = row.name || row.command || "Detailed signal";
    const sample = sampleOf(row);
    const small = document.createElement("small");
    // Same row→sample fallback as the admit filter above: a row admitted because
    // its scanStage lives on the captured sample should label from that sample
    // too, not degrade to the generic "catalog · probe" placeholders.
    small.textContent = [
      row.category || sample.category || "catalog",
      row.pollLane || sample.pollLane || "probe",
      row.header || "standard"
    ].filter(Boolean).join(" · ");
    item.append(strong, small);
    return item;
  }

  function updateSignalStageUi(rows: VoltEnhancedCapability[]) {
    const stage = String(state.signalProbeStage || "tires");
    const meta = signalStageMeta[stage] || signalStageMeta.tires;
    if (!meta) return;
    VD.setText("signalStageLabel", meta.label);
    VD.setText("signalStageHint", meta.hint);
    const bar = el("signalStageBar");
    if (bar) {
      bar.querySelectorAll<HTMLElement>("[data-signal-stage]").forEach((button) => {
        const on = button.dataset.signalStage === stage;
        button.classList.toggle("is-active", on);
        button.setAttribute("aria-pressed", String(on));
      });
    }
    // Default a stageless row to "tires" to match updateEnhancedNextList's admit
    // filter — otherwise a default-'tires' row shows in the next-list but is
    // excluded from this button count, so the two disagree.
    const count = rows.filter((row) => String(row.scanStage || sampleOf(row).scanStage || "tires") === stage).length;
    const button = el("detailProbeBtn") as HTMLButtonElement | null;
    if (button) {
      button.textContent = count ? `Run ${meta.label} (${count})` : `Run ${meta.label}`;
    }
  }

  function buildSignalChip(text: string, kind: string, value: string) {
    const chip = document.createElement("span");
    chip.className = "signal-chip";
    if (kind) chip.dataset[kind] = String(value || text).toLowerCase();
    chip.textContent = text;
    return chip;
  }

  function buildEnhancedCapabilityRow(capability: VoltEnhancedCapability) {
    const row = document.createElement("article");
    row.className = "enhanced-capability-item";
    row.dataset.status = capability._status || enhancedCapabilityStatus(capability);
    const center = document.createElement("span");
    const strong = document.createElement("strong");
    strong.textContent = capability.name || capability.pid || capability.command || "Enhanced PID";

    const sample = sampleOf(capability);
    // Classification chips — quick-scan tags. Technical evidence (header,
    // command, last-seen, raw bytes) drops to the mono line below so the row
    // reads top-to-bottom instead of as one long " - " run.
    const chips = document.createElement("div");
    chips.className = "signal-chips";
    const category = String(capability.category || sample.category || "catalog");
    const stage = String(capability.scanStage || sample.scanStage || "probe");
    const risk = String(capability.risk || sample.risk || "");
    chips.append(buildSignalChip(category, "category", category));
    // Risk chip only (the scan-stage used to sit here too, but "low-risk" stage
    // next to "low risk" risk read as a duplicate). Stage now lives in the detail
    // line below. "safe" reads oddly with " risk", so show it bare.
    if (risk) chips.append(buildSignalChip(risk === "safe" ? "safe" : `${risk} risk`, "risk", risk));

    const small = document.createElement("small");
    // Raw response bytes stay OUT of the visible summary — they read as hex
    // noise in a list a user scans; the export flow carries the full frame.
    small.textContent = [
      stage ? `${stage} probe` : null,
      capability.header || "no header",
      capability.command || capability.pid || "no command",
      capability._hasEvidence && capability.lastSeenMs ? VD.formatWhen(capability.lastSeenMs) : "not tried",
      capability.notes || capability.source || ""
    ].filter(Boolean).join(" · ");
    center.append(strong, chips, small);
    const status = document.createElement("b");
    status.textContent = enhancedStatusLabel(capability._status || enhancedCapabilityStatus(capability));
    row.append(center, status);
    if (capability._hasEvidence && capability.id) {
      const actions = document.createElement("span");
      actions.className = "signal-log-actions";
      const exportBtn = document.createElement("button");
      exportBtn.type = "button";
      exportBtn.className = "icon-link-btn";
      exportBtn.dataset.signalExport = String(capability.id);
      exportBtn.title = "Export this log";
      exportBtn.textContent = "Export";
      const deleteBtn = document.createElement("button");
      deleteBtn.type = "button";
      deleteBtn.className = "icon-link-btn danger";
      deleteBtn.dataset.signalDelete = String(capability.id);
      deleteBtn.title = "Delete this saved evidence row";
      deleteBtn.textContent = "Delete";
      actions.append(exportBtn, deleteBtn);
      row.append(actions);
    }
    return row;
  }

  function enhancedStatusLabel(status: string) {
    if (status === "confirmed") return "working";
    if (status === "rejected") return "no hit";
    return status || "candidate";
  }

  Object.assign(VD, {
    updateEnhancedCapabilityUi,
    setEnhancedBadge
  });

  (function bindEnhancedSignalFilters() {
    const bar = el("enhancedFilterBar");
    if (!bar) return;
    bar.addEventListener("click", (event) => {
      const target = event.target instanceof Element ? event.target : null;
      const button = target ? target.closest<HTMLElement>("[data-signal-filter]") : null;
      if (!button) return;
      enhancedSignalFilter = button.dataset.signalFilter || "all";
      updateEnhancedCapabilityUi();
    });
  })();

  (function bindSignalStages() {
    const bar = el("signalStageBar");
    if (!bar) return;
    bar.addEventListener("click", (event) => {
      const target = event.target instanceof Element ? event.target : null;
      const button = target ? target.closest<HTMLElement>("[data-signal-stage]") : null;
      if (!button) return;
      state.signalProbeStage = button.dataset.signalStage || "tires";
      updateEnhancedCapabilityUi();
    });
  })();
})();

export {};
