// maintenance-panel.ts — the user-authored maintenance log (M5): the entry
// list with M1/C4 next-due lines, the aggregate "service due soon" hint, and
// the inline add-entry form.
//
// Split out of storage-status.ts (G2 startup-headroom pass) as a LAZY chunk:
// the maintenance card lives on the Insights tab and none of it renders on the
// Drive-first startup path, so the code loads through
// core.ts#ensureMaintenancePanelModule (setView("insights") / the maintenance
// data-actions) instead of riding the eager app.js bundle. Entry points are
// attached to the shared VD global; storage-status.ts calls them through
// optional-subscriber guards (the updateEnhancedCapabilityUi pattern), and this
// chunk loads + renders the current native log on arrival so an already-open
// tab hydrates immediately. The shared native-read-error helpers and
// latestInsightReading stay owned by storage-status.ts and are read off VD.

(function () {
  "use strict";

  const VD = window.VoltDashboard;
  const state = VD.state;
  const bridge = VD.bridge;
  const el = VD.el;
  const units = VD.units;

  // The latest odometer reading the app has seen, in km, or null when unknown. Sourced from the
  // latest battery snapshot's odometer (written whenever the car answers the odometer PID) and,
  // failing that, the vehicle identity card's odometer. Drives the maintenance "next due / overdue"
  // distance math (M1/C4).
  function latestOdometerKm(): number | null {
    const storage = state.storage || {};
    const latest = VD.latestInsightReading(storage);
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

  // Memo signature: renderMaintenanceList runs on every renderRealV2Ui tick
  // (every app-state/storage broadcast); without it the list rebuilt its DOM
  // ~1-2 Hz during a live stream — churn/reflow plus the risk of recreating the
  // delete button under a user's finger mid-tap.
  let lastMaintenanceSig = "";

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
    const nowMs = Date.now();
    const odometerKm = latestOdometerKm();
    const dueByEntry = entries.length
      ? entries.map((entry) => maintenanceDue(entry, nowMs, odometerKm))
      : [];
    // Sign over the entries AND the computed due state, so a time/odometer-driven
    // due-date change still busts the memo even when the entries are unchanged.
    const sig = JSON.stringify([entries, dueByEntry]);
    if (sig === lastMaintenanceSig) return;
    lastMaintenanceSig = sig;
    if (!entries.length) {
      maintenance.replaceChildren(buildMaintenanceEmptyState());
      renderMaintenanceDueHint([]);
      return;
    }
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
      VD.reportNativeReadError(
        {
          ok: false,
          error: "maintenance_log_failed",
          message: "Could not read the maintenance log."
        },
        "Could not read the maintenance log."
      );
      return;
    }
    if (VD.isNativeError(parsed)) {
      state.maintenanceLog = [];
      renderMaintenanceList();
      VD.reportNativeReadError(parsed, "Could not read the maintenance log.");
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
    setMaintFieldHint("maintIntervalMonthsInput", "maintIntervalMonthsHint", "");
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
    const monthsText = String((el("maintIntervalMonthsInput") as HTMLInputElement | null)?.value || "").trim();
    const months = Number(monthsText);
    const monthsInvalid = Boolean(monthsText) && (!Number.isFinite(months) || months <= 0 || !Number.isInteger(months));
    if (monthsInvalid) {
      setMaintFieldHint(
        "maintIntervalMonthsInput",
        "maintIntervalMonthsHint",
        "Enter a positive whole number of months.",
      );
    } else if (monthsText) {
      payload.intervalMonths = months;
    }
    if (odo.invalid || interval.invalid || monthsInvalid) return;
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

  Object.assign(VD, {
    loadMaintenanceLog,
    renderMaintenanceList,
    addMaintenanceEntry,
    submitMaintenanceForm,
    closeMaintenanceForm
  });

  // Broadcasts that landed before this chunk loaded skipped the maintenance
  // refresh (setStorage calls VD.loadMaintenanceLog through an optional-
  // subscriber guard), so fetch + render the current native log now — an
  // already-open Insights tab hydrates the moment the chunk arrives.
  loadMaintenanceLog();
  // loadMaintenanceLog returns early without a bridge (browser preview), so
  // render explicitly too — the empty-state guidance must still appear. The
  // signature memo makes this a no-op when loadMaintenanceLog just rendered.
  renderMaintenanceList();
})();

export {};
