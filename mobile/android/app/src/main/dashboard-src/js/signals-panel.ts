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
  const state = VD.state;
  const el = VD.el;
  let enhancedSignalFilter = "all";
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
    const total = rows.length || Number(storage.fieldCapabilityCount || 0);
    const list = el("enhancedCapabilityList");
    VD.setText("enhancedTitle", total ? `${total} detailed signal${total === 1 ? "" : "s"} tracked` : "No detailed signal results yet");
    setEnhancedBadge(counts.confirmed ? "working data" : total ? "evidence saved" : "ready", counts.confirmed ? "working" : total ? "saved" : "ready");
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
    if (!rows.length) {
      list.replaceChildren(VD.buildStatusCopy("Run Scan or Detail Probe once to collect detailed signal evidence."));
      return;
    }
    const visible = rows.filter((row) => matchesEnhancedFilter(row));
    if (!visible.length) {
      list.replaceChildren(VD.buildStatusCopy("No detailed signals match this filter yet."));
      return;
    }
    list.replaceChildren(...visible.slice(0, 18).map(buildEnhancedCapabilityRow));
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
      button.classList.toggle("is-active", button.dataset.signalFilter === enhancedSignalFilter);
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
        button.classList.toggle("is-active", button.dataset.signalStage === stage);
      });
    }
    const count = rows.filter((row) => String(row.scanStage || sampleOf(row).scanStage || "") === stage).length;
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
    small.textContent = [
      stage ? `${stage} probe` : null,
      capability.header || "no header",
      capability.command || capability.pid || "no command",
      capability._hasEvidence && capability.lastSeenMs ? VD.formatWhen(capability.lastSeenMs) : "not tried",
      sample.rawResponse || capability.notes || capability.source || ""
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
