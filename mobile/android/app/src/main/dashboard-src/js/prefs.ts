// prefs.ts — small persisted user-preferences store, loaded FIRST in the eager
// bundle so every renderer can read a preference at init time.
//
// Scope: display-layer preferences only (units, $/kWh, drive-tile layout, the
// active Settings sub-tab, …). Anything that changes *native* behaviour (poll
// interval, data retention, auto-start) is owned by the native side and reached
// through the VoltTrackerAndroid bridge, NOT this store.
//
// Backed by WebView localStorage (WebViewBootstrap enables domStorage). Values
// are JSON-encoded so booleans/numbers/arrays round-trip. A change fires every
// subscriber for that key plus the wildcard "*" subscribers, so a renderer can
// re-render the moment a preference flips without a full reload.

// prefs is the FIRST module to evaluate in the eager bundle (before core.ts),
// so its cross-module calls into later modules (updateLiveUi, setView,
// ensureSignalsModule, ...) must stay late-bound through the VD registry —
// importing those modules from here would flip the bundle's side-effect
// evaluation order. See vd-registry.ts for the policy.
import { VD } from "./vd-registry";

  const PREFIX = "vt.pref.";
  const keyListeners: Record<string, Array<(value: unknown) => void>> = {};

  // Once-per-session notice when localStorage is unusable and prefs silently
  // degrade to in-memory storage. The silent fallback kept the dashboard alive
  // but the user had no idea their units/tiles/$-rate would reset every launch.
  // console.warn fires immediately; the visible notice rides the #statusToast
  // aria-live node directly (prefs loads FIRST in the eager bundle, before
  // telemetry.ts attaches the setStatus/toast plumbing) one tick later.
  let storageFallbackNoted = false;

  function noteStorageFallback(): void {
    if (storageFallbackNoted) return;
    storageFallbackNoted = true;
    try {
      console.warn(
        "prefs: localStorage is unavailable; preferences will not persist across app restarts this session."
      );
    } catch (_err) {
      /* console is best-effort on legacy WebViews */
    }
    try {
      window.setTimeout(() => {
        const toast = document.getElementById("statusToast");
        if (!toast) return;
        const notice = "Preferences can't be saved on this device — settings will reset when the app closes.";
        // A degraded-storage warning is an actionable failure, so interrupt the
        // screen reader (assertive) rather than queue behind whatever it's
        // reading. telemetry.ts re-points this shared node per status push.
        toast.setAttribute("aria-live", "assertive");
        toast.textContent = notice;
        toast.hidden = false;
        window.setTimeout(() => {
          // Only auto-hide our own notice: a newer status may have reused the
          // shared toast node in the meantime and must not be dismissed.
          if (toast.textContent === notice) toast.hidden = true;
        }, 6000);
      }, 0);
    } catch (_err) {
      /* notice is best-effort; prefs still work in-memory */
    }
  }

  function store(): Storage | null {
    try {
      if (typeof window !== "undefined" && window.localStorage) return window.localStorage;
    } catch (_err) {
      // Some WebView configurations throw on localStorage access (e.g. storage
      // disabled). Fall back to in-memory so callers still get their defaults.
    }
    noteStorageFallback();
    return null;
  }

  const memoryFallback: Record<string, string> = {};

  function rawGet(key: string): string | null {
    const s = store();
    if (s) {
      try {
        return s.getItem(PREFIX + key);
      } catch (_err) {
        noteStorageFallback();
        /* fall through to memory */
      }
    }
    return Object.prototype.hasOwnProperty.call(memoryFallback, key) ? (memoryFallback[key] ?? null) : null;
  }

  function rawSet(key: string, serialized: string): void {
    const s = store();
    if (s) {
      try {
        s.setItem(PREFIX + key, serialized);
        return;
      } catch (_err) {
        noteStorageFallback();
        /* fall through to memory */
      }
    }
    memoryFallback[key] = serialized;
  }

  function notify(key: string, value: unknown): void {
    const fire = (list?: Array<(value: unknown) => void>) => {
      if (!list) return;
      list.slice().forEach((cb) => {
        try {
          cb(value);
        } catch (_err) {
          /* a bad subscriber must not break the others */
        }
      });
    };
    fire(keyListeners[key]);
    fire(keyListeners["*"]);
  }

  function get<T>(key: string, fallback: T): T {
    const raw = rawGet(key);
    if (raw == null) return fallback;
    try {
      return JSON.parse(raw) as T;
    } catch (_err) {
      return fallback;
    }
  }

  function set(key: string, value: unknown): void {
    let serialized: string;
    try {
      serialized = JSON.stringify(value);
    } catch (_err) {
      return;
    }
    rawSet(key, serialized);
    notify(key, value);
  }

  // Subscribe to changes for one key, or "*" for all changes. Returns an
  // unsubscribe function.
  function subscribe(key: string, callback: (value: unknown) => void): () => void {
    const list = keyListeners[key] || (keyListeners[key] = []);
    list.push(callback);
    return () => {
      const current = keyListeners[key];
      if (!current) return;
      const idx = current.indexOf(callback);
      if (idx >= 0) current.splice(idx, 1);
    };
  }

  // Exported for in-bundle ESM consumers (A3): cross-module callers import
  // { prefs } from "./prefs" instead of reading window.VoltDashboard.prefs at
  // runtime. The VD.prefs assignment is retained because the unit suite and any
  // future late-binding consumers still read it off the global.
  const BACKUP_PREF_KEYS = [
    "units",
    "pricePerKwh",
    "publicPricePerKwh",
    "mpg",
    "gasPricePerGal",
    "chargeTargetSoc",
    "fontScale",
    "highContrast",
    "quietTelemetry",
    "driveTiles",
    "driveTilesDetailedBackup",
    "drivePreset",
    "diagnosticsMode",
    "weekChartMode",
    "effChartView",
    "effHideOutliers",
    "mapLayer",
    "mapDetailsExpanded",
  ] as const;

  function exportForBackup(): string {
    const preferences: Record<string, unknown> = {};
    BACKUP_PREF_KEYS.forEach((key) => {
      const raw = rawGet(key);
      if (raw == null) return;
      try {
        preferences[key] = JSON.parse(raw);
      } catch (_err) {
        /* a corrupt preference is omitted instead of poisoning the backup */
      }
    });
    return JSON.stringify({ schemaVersion: 1, preferences });
  }

  function restoreFromBackup(payload: unknown): boolean {
    let parsed: unknown = payload;
    if (typeof parsed === "string") {
      try {
        parsed = JSON.parse(parsed);
      } catch (_err) {
        return false;
      }
    }
    if (!parsed || typeof parsed !== "object") return false;
    const root = parsed as Record<string, unknown>;
    const incoming = root.preferences;
    if (!incoming || typeof incoming !== "object") return false;
    const values = incoming as Record<string, unknown>;
    BACKUP_PREF_KEYS.forEach((key) => {
      if (Object.prototype.hasOwnProperty.call(values, key)) set(key, values[key]);
    });
    applyUnitsAttr();
    syncUnitButtons();
    applyAccessibilityAttrs();
    syncAccessibilityControls();
    applyDriveTiles();
    renderTilesEditor();
    rerenderForUnits();
    return true;
  }

  export const prefs = { get, set, subscribe, exportForBackup, restoreFromBackup };
  VD.prefs = prefs;

  // ----- units --------------------------------------------------------------
  // Central unit formatting. Data crosses the bridge in SI-ish units (speed in
  // km/h, distance in km/m, temp in °C, efficiency in mi/kWh); renderers call
  // these so a single `units` preference flips every surface consistently.
  const KM_TO_MI = 0.621371;

  function unitSystem(): "imperial" | "metric" {
    return get<string>("units", "imperial") === "metric" ? "metric" : "imperial";
  }

  function speed(kph: number): { value: number; unit: string } {
    const metric = unitSystem() === "metric";
    return { value: Math.round(metric ? kph : kph * KM_TO_MI), unit: metric ? "km/h" : "mph" };
  }

  function distanceKm(km: number): { value: string; unit: string } {
    const metric = unitSystem() === "metric";
    const v = metric ? km : km * KM_TO_MI;
    // Sub-0.1 trips need two decimals: toFixed(1) rounds a real 0.03 mi trip to
    // "0.0", reading as zero distance. Finer branch keeps small trips nonzero.
    return { value: v < 0.1 ? v.toFixed(2) : v < 10 ? v.toFixed(1) : String(Math.round(v)), unit: metric ? "km" : "mi" };
  }

  function temp(celsius: number): { value: number; unit: string } {
    const metric = unitSystem() === "metric";
    return { value: Math.round(metric ? celsius : celsius * 9 / 5 + 32), unit: metric ? "°C" : "°F" };
  }

  // Efficiency source is always mi/kWh; metric shows km/kWh.
  function efficiencyText(miPerKwh: number): string {
    const metric = unitSystem() === "metric";
    return metric ? `${(miPerKwh / KM_TO_MI).toFixed(1)} km/kWh` : `${miPerKwh.toFixed(1)} mi/kWh`;
  }

  // Exported for in-bundle ESM consumers (A3): cross-module renderers import
  // { units } from "./prefs" rather than reading window.VoltDashboard.units.
  // VD.units stays assigned for the unit suite / any late-binding consumers.
  export const units = {
    system: unitSystem,
    speed,
    speedText: (kph: number) => {
      const s = speed(kph);
      return `${s.value} ${s.unit}`;
    },
    speedUnit: () => (unitSystem() === "metric" ? "km/h" : "mph"),
    distanceKm,
    distanceText: (km: number) => {
      const d = distanceKm(km);
      return `${d.value} ${d.unit}`;
    },
    distanceMeters: (meters: number) => distanceKm(meters / 1000),
    distanceMiles: (miles: number) => distanceKm(miles / KM_TO_MI),
    distanceUnit: () => (unitSystem() === "metric" ? "km" : "mi"),
    temp,
    tempText: (celsius: number) => {
      const t = temp(celsius);
      return `${t.value}${t.unit}`;
    },
    efficiencyText,
    efficiencyUnit: () => (unitSystem() === "metric" ? "km/kWh" : "mi/kWh"),
  };
  VD.units = units;

  // ----- preferences UI bootstrap -------------------------------------------
  // Self-contained wiring for the Preferences surface: the Settings|Diagnostics
  // segmented filter and the units toggle. Runs at load (the bundle is injected at
  // </body>, so the DOM is ready). Cross-module renderers (setView/updateLiveUi)
  // are referenced lazily inside the click handler, after the full bundle loads.
  function applyUnitsAttr(): void {
    try {
      if (document.body) document.body.dataset.units = unitSystem();
    } catch (_err) {
      /* no-op */
    }
  }

  function syncUnitButtons(): void {
    const active = unitSystem();
    document.querySelectorAll<HTMLElement>("[data-pref-units]").forEach((btn) => {
      btn.classList.toggle("is-active", btn.getAttribute("data-pref-units") === active);
      btn.setAttribute("aria-pressed", String(btn.getAttribute("data-pref-units") === active));
    });
  }

  // ----- accessibility (M9): font scale + high-contrast theme ----------------
  // Display-layer prefs persisted exactly like units: a --font-scale multiplier
  // and a data-contrast="high" attr on :root (document.documentElement), both
  // swapping centralized CSS tokens (base.css). Loaded at boot so the chosen
  // size/contrast is in place before first paint.
  const FONT_SCALE_CHOICES = [1, 1.25, 1.5] as const;
  const FONT_SCALE_MIN = 1;
  const FONT_SCALE_MAX = 1.5;

  function fontScale(): number {
    const raw = Number(get<number>("fontScale", 1));
    if (!Number.isFinite(raw)) return 1;
    return Math.min(FONT_SCALE_MAX, Math.max(FONT_SCALE_MIN, raw));
  }

  function highContrast(): boolean {
    return get<boolean>("highContrast", false);
  }

  function quietTelemetry(): boolean {
    return get<boolean>("quietTelemetry", true);
  }

  function diagnosticsMode(): "basic" | "advanced" {
    return get<string>("diagnosticsMode", "basic") === "advanced" ? "advanced" : "basic";
  }

  // Exported for in-bundle ESM consumers (C7): core.ts calls it at boot.
  export function applyDiagnosticsMode(): void {
    const advanced = diagnosticsMode() === "advanced";
    document.querySelectorAll<HTMLElement>("[data-diagnostics-expert]").forEach((node) => {
      node.hidden = !advanced;
    });
    document.querySelectorAll<HTMLElement>("[data-diagnostics-mode]").forEach((button) => {
      const active = button.dataset.diagnosticsMode === diagnosticsMode();
      button.classList.toggle("is-active", active);
      button.setAttribute("aria-pressed", String(active));
    });
    const hint = document.getElementById("diagnosticsModeHint");
    if (hint) {
      hint.textContent = advanced
        ? "Live signals, enhanced probes, demo, and raw session tools"
        : "Recovery, car codes, and data health";
    }
    if (advanced) {
      if (typeof VD.ensureSignalsModule === "function") {
        void VD.ensureSignalsModule()
          .then(() => {
            if (typeof VD.updateEnhancedCapabilityUi === "function") VD.updateEnhancedCapabilityUi();
          })
          .catch(() => {});
      }
      try {
        if (typeof VD.updateLiveUi === "function") VD.updateLiveUi();
        if (typeof VD.updateDiagnostics === "function") VD.updateDiagnostics();
      } catch (_err) {
        /* the next telemetry/storage render will fill the newly visible tools */
      }
    }
  }

  VD.applyDiagnosticsMode = applyDiagnosticsMode;

  // Snap an arbitrary stored scale to the nearest offered choice so the
  // segmented control always has exactly one active button.
  function nearestFontScaleChoice(value: number): number {
    return FONT_SCALE_CHOICES.reduce((best, choice) =>
      Math.abs(choice - value) < Math.abs(best - value) ? choice : best
    , FONT_SCALE_CHOICES[0] as number);
  }

  function applyAccessibilityAttrs(): void {
    try {
      const root = document.documentElement;
      if (!root) return;
      root.style.setProperty("--font-scale", String(fontScale()));
      if (highContrast()) root.setAttribute("data-contrast", "high");
      else root.removeAttribute("data-contrast");
      root.dataset.drivePreset = drivePreset();
      document.querySelectorAll<HTMLElement>("[data-live-telemetry]").forEach((node) => {
        node.setAttribute("aria-live", quietTelemetry() ? "off" : "polite");
      });
    } catch (_err) {
      /* no-op: a missing documentElement (non-DOM host) leaves defaults intact */
    }
  }

  // Visible action confirmation (v2) — also the spoken one: #statusToast is a
  // polite live region, so TalkBack announces the change without a second
  // dedicated announcer (the old #prefA11yAnnounce path double-spoke every
  // toggle). Late-bound off the global: prefs is the first eager module, so
  // telemetry's VD.showToast doesn't exist yet at load time — but it does by
  // the time any click handler runs.
  function toast(message: string): void {
    const show = VD.showToast;
    if (typeof show === "function") show(message);
  }

  function syncAccessibilityControls(): void {
    const active = nearestFontScaleChoice(fontScale());
    document.querySelectorAll<HTMLElement>("[data-pref-font-scale]").forEach((btn) => {
      const on = Number(btn.getAttribute("data-pref-font-scale")) === active;
      btn.classList.toggle("is-active", on);
      btn.setAttribute("aria-pressed", String(on));
    });
    const toggle = document.querySelector<HTMLElement>("[data-pref-contrast-toggle]");
    if (toggle) {
      const on = highContrast();
      toggle.dataset.on = String(on);
      toggle.setAttribute("aria-pressed", String(on));
      toggle.setAttribute("aria-label", `High-contrast theme is ${on ? "on" : "off"}`);
      toggle.textContent = on ? "On" : "Off";
    }
    const quietToggle = document.querySelector<HTMLElement>("[data-pref-quiet-telemetry]");
    if (quietToggle) {
      const on = quietTelemetry();
      quietToggle.dataset.on = String(on);
      quietToggle.setAttribute("aria-pressed", String(on));
      quietToggle.textContent = on ? "On" : "Off";
    }
    document.querySelectorAll<HTMLElement>("button[data-drive-preset]").forEach((button) => {
      const active = button.dataset.drivePreset === drivePreset();
      button.classList.toggle("is-active", active);
      button.setAttribute("aria-pressed", String(active));
    });
  }


  // ----- customizable Drive tiles ------------------------------------------
  // Lets the user show/hide and reorder the Drive live-readout tiles. Persisted
  // as an ordered [{key,on}] list; applied by reordering/hiding the cells (which
  // carry data-tile-key). New tiles added later default to shown.
  type TileConfig = { key: string; on: boolean };
  const DRIVE_TILES: Array<{ key: string; label: string }> = [
    { key: "rpm", label: "RPM" },
    { key: "aux12v", label: "Aux 12V" },
    { key: "coolant", label: "Coolant" },
    { key: "throttle", label: "Throttle" },
    { key: "load", label: "Load" },
    { key: "gps", label: "GPS" }
  ];
  type DrivePreset = "focus" | "detailed";

  function drivePreset(): DrivePreset {
    return get<string>("drivePreset", "detailed") === "focus" ? "focus" : "detailed";
  }

  function tileLabel(key: string): string {
    const found = DRIVE_TILES.find((tile) => tile.key === key);
    return found ? found.label : key;
  }

  function tilesConfig(): TileConfig[] {
    const known = new Set(DRIVE_TILES.map((tile) => tile.key));
    const saved = get<TileConfig[]>("driveTiles", []);
    const cfg: TileConfig[] = Array.isArray(saved)
      ? saved
          .filter((entry) => entry && typeof entry.key === "string" && known.has(entry.key))
          .map((entry) => ({ key: entry.key, on: entry.on !== false }))
      : [];
    DRIVE_TILES.forEach((tile) => {
      if (!cfg.some((entry) => entry.key === tile.key)) cfg.push({ key: tile.key, on: true });
    });
    return cfg;
  }

  function applyDriveTiles(): void {
    const readout = document.getElementById("liveReadout");
    if (!readout) return;
    const cells: Record<string, HTMLElement> = {};
    readout.querySelectorAll<HTMLElement>("[data-tile-key]").forEach((cell) => {
      cells[cell.dataset.tileKey || ""] = cell;
    });
    tilesConfig().forEach((entry) => {
      const cell = cells[entry.key];
      if (!cell) return;
      cell.hidden = !entry.on;
      readout.appendChild(cell); // reorder DOM to the configured order
    });
  }

  function renderTilesEditor(): void {
    const root = document.getElementById("driveTilesEditor");
    if (!root) return;
    const cfg = tilesConfig();
    // Manual clear (not replaceChildren): prefs.ts loads before core installs the
    // legacy-WebView replaceChildren polyfill, so this must not depend on it.
    while (root.firstChild) root.removeChild(root.firstChild);
    cfg.forEach((entry, index) => {
      const label = tileLabel(entry.key);
      const row = document.createElement("div");
      row.className = "tile-edit-row";
      const up = document.createElement("button");
      up.type = "button";
      up.className = "tile-move";
      up.textContent = "↑";
      up.setAttribute("aria-label", `Move ${label} up`);
      up.disabled = index === 0;
      up.addEventListener("click", () => reorderTile(entry.key, -1));
      const down = document.createElement("button");
      down.type = "button";
      down.className = "tile-move";
      down.textContent = "↓";
      down.setAttribute("aria-label", `Move ${label} down`);
      down.disabled = index === cfg.length - 1;
      down.addEventListener("click", () => reorderTile(entry.key, 1));
      const name = document.createElement("span");
      name.className = "tile-edit-label";
      name.textContent = label;
      const toggle = document.createElement("button");
      toggle.type = "button";
      toggle.className = "tile-toggle";
      toggle.dataset.on = String(entry.on);
      toggle.setAttribute("aria-pressed", String(entry.on));
      toggle.setAttribute("aria-label", `${label} is ${entry.on ? "shown" : "hidden"} in Drive live readout`);
      toggle.textContent = entry.on ? "Shown" : "Hidden";
      toggle.addEventListener("click", () => toggleTile(entry.key));
      row.appendChild(up);
      row.appendChild(down);
      row.appendChild(name);
      row.appendChild(toggle);
      root.appendChild(row);
    });
  }

  function persistTiles(cfg: TileConfig[]): void {
    set("driveTiles", cfg);
    applyDriveTiles();
    renderTilesEditor();
    applyDiagnosticsMode();
  }

  export function selectDrivePreset(next: DrivePreset): void {
    const current = drivePreset();
    if (next === "focus") {
      if (current !== "focus") set("driveTilesDetailedBackup", tilesConfig());
      const focusKeys = new Set(["rpm", "aux12v", "gps"]);
      persistTiles(DRIVE_TILES.map((tile) => ({ key: tile.key, on: focusKeys.has(tile.key) })));
    } else {
      const saved = get<TileConfig[]>("driveTilesDetailedBackup", []);
      if (Array.isArray(saved) && saved.length) persistTiles(saved);
      else persistTiles(DRIVE_TILES.map((tile) => ({ key: tile.key, on: true })));
    }
    set("drivePreset", next);
    applyAccessibilityAttrs();
    syncAccessibilityControls();
    toast(next === "focus" ? "Drive Focus on" : "Detailed Drive view restored");
  }

  VD.selectDrivePreset = selectDrivePreset;

  export function scrollToSettingsSection(id: string): boolean {
    const section = document.getElementById(id);
    if (!section) return false;
    section.setAttribute("tabindex", "-1");
    settingsScrollSpyHoldUntil = Date.now() + 800;
    setActiveSettingsSection(id);
    if (typeof section.scrollIntoView === "function") {
      section.scrollIntoView({ behavior: "smooth", block: "start" });
    }
    window.setTimeout(() => section.focus({ preventScroll: true }), 220);
    return true;
  }

  VD.scrollToSettingsSection = scrollToSettingsSection;

  function setActiveSettingsSection(id: string): void {
    const nav = document.querySelector<HTMLElement>(".settings-section-nav");
    if (!nav) return;
    nav.querySelectorAll<HTMLButtonElement>("button[data-settings-target]").forEach((button) => {
      const active = button.dataset.settingsTarget === id;
      button.classList.toggle("is-active", active);
      if (active) {
        button.setAttribute("aria-current", "location");
        if (typeof button.scrollIntoView === "function") {
          button.scrollIntoView({ block: "nearest", inline: "center" });
        }
      } else {
        button.removeAttribute("aria-current");
      }
    });
  }

  let settingsScrollSpyQueued = false;
  let settingsScrollSpyHoldUntil = 0;
  function updateSettingsScrollSpy(): void {
    settingsScrollSpyQueued = false;
    if (Date.now() < settingsScrollSpyHoldUntil) return;
    if (document.body.dataset.activeView !== "settings") return;
    const buttons = Array.from(document.querySelectorAll<HTMLElement>(".settings-section-nav [data-settings-target]"));
    const sections = buttons
      .map((button) => document.getElementById(button.dataset.settingsTarget || ""))
      .filter((section): section is HTMLElement => Boolean(section && !section.hidden));
    if (!sections.length) return;
    const anchor = Math.max(96, window.innerHeight * 0.22);
    let current = sections[0]!;
    for (const section of sections) {
      if (section.getBoundingClientRect().top <= anchor) current = section;
      else break;
    }
    setActiveSettingsSection(current.id);
  }

  function queueSettingsScrollSpy(): void {
    if (settingsScrollSpyQueued) return;
    settingsScrollSpyQueued = true;
    window.requestAnimationFrame(updateSettingsScrollSpy);
  }

  function toggleTile(key: string): void {
    persistTiles(tilesConfig().map((entry) => (entry.key === key ? { key: entry.key, on: !entry.on } : entry)));
  }

  function reorderTile(key: string, direction: number): void {
    const cfg = tilesConfig();
    const from = cfg.findIndex((entry) => entry.key === key);
    const to = from + direction;
    if (from < 0 || to < 0 || to >= cfg.length) return;
    const moved = cfg[from];
    const displaced = cfg[to];
    if (!moved || !displaced) return;
    cfg[from] = displaced;
    cfg[to] = moved;
    persistTiles(cfg);
  }

  // Re-render everything a units / rate change can affect: live tiles, the
  // storage-driven surfaces (charge cost, odometer, session review), and the
  // active stored view (trips/insights/map re-run their loaders via setView).
  function rerenderForUnits(): void {
    // Re-running setView() scrolls the app to the top; a units/rate tweak
    // shouldn't yank the user away from the control they're using.
    const scrollY = window.scrollY;
    const safe = (fn: unknown) => {
      try {
        if (typeof fn === "function") (fn as () => void)();
      } catch (_err) {
        /* a single renderer failing must not block the others */
      }
    };
    safe(VD.updateLiveUi);
    safe(VD.updateStorageUi);
    // updateStorageUi doesn't touch the storage-overview tiles (Drive Max
    // speed/Distance, "This trip" efficiency, Insights pack Temp) — those are
    // owned by renderRealV2Ui, which every other refresh site pairs with
    // updateStorageUi. Without this, a units toggle leaves them in the old units
    // until the next native storage broadcast (never, when reviewing history).
    safe(VD.renderRealV2Ui);
    try {
      const view = VD.state && VD.state.view;
      if (view && typeof VD.setView === "function") VD.setView(view);
    } catch (_err) {
      /* no-op */
    }
    window.scrollTo({ top: scrollY, behavior: "auto" });
  }

  function syncChargeTargetPresets(): void {
    const current = Math.round(get<number>("chargeTargetSoc", 100));
    document.querySelectorAll<HTMLButtonElement>("button[data-charge-target]").forEach((button) => {
      const raw = button.dataset.chargeTarget || "";
      const active = raw === "other"
        ? ![80, 90, 100].includes(current)
        : Number(raw) === current;
      button.classList.toggle("is-active", active);
      button.setAttribute("aria-pressed", String(active));
    });
  }

  function commitChargeTarget(value: number, announce = false): void {
    const clamped = Math.min(100, Math.max(50, Math.round(value)));
    set("chargeTargetSoc", clamped);
    ["prefChargeTargetInput", "liveChargeTargetInput"].forEach((id) => {
      const input = document.getElementById(id) as HTMLInputElement | null;
      if (input) input.value = String(clamped);
    });
    try {
      const bridge = window.VoltTrackerAndroid;
      if (bridge && typeof bridge.setChargeTargetSoc === "function") {
        bridge.setChargeTargetSoc(clamped);
      }
    } catch (_err) {
      /* bridge absent in browser preview / older host — pref still drives the ETA */
    }
    try {
      if (typeof VD.updateLiveUi === "function") VD.updateLiveUi();
    } catch (_err) {
      /* no-op */
    }
    syncChargeTargetPresets();
    if (announce) toast(`Charge target saved at ${clamped}%`);
  }

  // Finds (or lazily creates) the transient hint node that bindNumericPref uses to
  // tell a screen-reader / sighted user that their out-of-range entry was adjusted.
  // It rides next to the field as a polite live region so the announcement isn't a
  // silent rewrite. Inserted after the input's wrapping <label> (or the input
  // itself) so it lands just below the control in both the pref-row and the
  // live-charge layouts.
  function numericPrefHint(input: HTMLInputElement): HTMLElement | null {
    const existingId = input.getAttribute("aria-describedby");
    if (existingId) {
      const existing = document.getElementById(existingId);
      if (existing) return existing;
    }
    const anchor =
      input.closest("label") instanceof HTMLElement ? (input.closest("label") as HTMLElement) : input;
    const parent = anchor.parentNode;
    if (!parent) return null;
    const hint = document.createElement("p");
    hint.id = `${input.id}Clamp`;
    hint.className = "numeric-pref-hint";
    hint.setAttribute("role", "status");
    hint.setAttribute("aria-live", "polite");
    hint.hidden = true;
    parent.insertBefore(hint, anchor.nextSibling);
    input.setAttribute("aria-describedby", hint.id);
    return hint;
  }

  // Wires a clamped numeric preference input: hydrate from the store, persist the
  // settled value on commit (blur / Enter / spinner step, clamped to [min, max]),
  // reflect the clamped value back only when it actually differs (so typing a
  // valid "0.14" isn't disrupted) and never advertise a value the store didn't
  // keep, and debounce the dashboard-wide re-render so a commit doesn't re-render
  // four times. Shared by the electricity rate, gas MPG, and gas price fields.
  //
  // When an in-range entry is silently clamped (e.g. "140" → 100), surface a
  // transient inline hint in a polite live region and mark the field aria-invalid;
  // a subsequent valid entry clears both so the rewrite is never invisible.
  //
  // `defaultValue` is the value used for an unset pref / a cleared (blank) field —
  // it defaults to 0, which suits the cost fields (an unset rate means "no rate").
  // The charge-target field passes 100 so an empty field means a full charge, not 0.
  // `rangeUnit` is appended to the upper bound in the hint ("%" for the charge
  // target) so it reads "Adjusted to the 50–100% range".
  // `onCommit` runs with the clamped value after each edit (e.g. to push it across
  // the native bridge), in addition to the standard units re-render.
  function bindNumericPref(
    inputId: string,
    prefKey: string,
    min: number,
    max: number,
    options?: { defaultValue?: number; rangeUnit?: string; onCommit?: (value: number) => void }
  ): void {
    const input = document.getElementById(inputId) as HTMLInputElement | null;
    if (!input) return;
    const fallback = options && typeof options.defaultValue === "number" ? options.defaultValue : 0;
    const rangeUnit = options && typeof options.rangeUnit === "string" ? options.rangeUnit : "";
    const stored = get<number>(prefKey, fallback);
    // Show the stored value; for the cost fields (fallback 0) keep the old "blank
    // when unset" behaviour, but for a non-zero default (charge target) always
    // reflect it so the field never reads empty.
    if (stored > 0 || fallback > 0) input.value = String(stored);
    let rerenderTimer = 0;
    let hintTimer = 0;
    const clearHint = () => {
      input.removeAttribute("aria-invalid");
      const hint = numericPrefHint(input);
      if (hint) hint.hidden = true;
    };
    const showClampHint = () => {
      input.setAttribute("aria-invalid", "true");
      const hint = numericPrefHint(input);
      if (hint) {
        hint.textContent = `Adjusted to the ${min}–${max}${rangeUnit} range`;
        hint.hidden = false;
      }
      // Transient: drop the notice (and the invalid flag) after a few seconds so a
      // stale "adjusted" message doesn't linger over a now-valid field.
      window.clearTimeout(hintTimer);
      hintTimer = window.setTimeout(clearHint, 4000);
    };
    input.addEventListener("input", () => {
      // Per keystroke, only cap an over-MAXIMUM entry ("140" → "100") so an
      // obviously-too-big value can't grow further as it's typed. The MIN clamp
      // is deliberately NOT applied here: a leading digit below the floor (e.g.
      // "6" en route to "60" for the 50-100% charge target, or "2" toward "25"
      // MPG) would otherwise be rewritten to the minimum on the first keypress,
      // making those values impossible to type. Persisting (set) and the bridge
      // push (onCommit) also wait for the field to settle (commitEdit) so
      // intermediate keystrokes never stick.
      const parsed = parseFloat(input.value);
      if (Number.isFinite(parsed) && parsed > max) {
        input.value = String(max);
        showClampHint();
      }
    });
    // Commit on settle (blur / Enter / spinner step): apply the full [min, max]
    // clamp, reflect the corrected value, flag a silent rewrite, persist it, and
    // push it across the bridge. Only the settled value is stored/pushed, so
    // typing "120" no longer commits "12" then "120" mid-entry.
    const commitEdit = () => {
      const parsed = parseFloat(input.value);
      const clamped = Number.isFinite(parsed) ? Math.min(max, Math.max(min, parsed)) : fallback;
      const reflect = Number.isFinite(parsed)
        ? clamped !== parsed
        : input.value.trim() !== "";
      if (reflect) input.value = String(clamped);
      // A finite entry that the bounds had to move is the "silently rewritten"
      // case worth flagging; a blank/NaN field (falling back to the default) is not.
      if (Number.isFinite(parsed) && clamped !== parsed) showClampHint();
      else clearHint();
      set(prefKey, clamped);
      if (options && options.onCommit) {
        try {
          options.onCommit(clamped);
        } catch (_err) {
          /* a bridge hiccup must not break the input */
        }
      }
      window.clearTimeout(rerenderTimer);
      rerenderTimer = window.setTimeout(rerenderForUnits, 400);
    };
    input.addEventListener("change", commitEdit);
    // Keep this field in sync when the SAME pref is changed elsewhere — e.g. the
    // charge-target lives in BOTH the Preferences panel and the live-charge card
    // (C3), and editing one must reflect in the other. Skip the input the user is
    // actively typing in (it already holds the authoritative value) so a mirror
    // update never clobbers mid-edit text. Show the stored value only when it
    // matches the field's hydrate rule (non-zero, or a non-zero default).
    subscribe(prefKey, (value) => {
      if (document.activeElement === input) return;
      const next = Number(value);
      if (!Number.isFinite(next)) return;
      if (next > 0 || fallback > 0) input.value = String(next);
    });
  }

  function bootPrefsUi(): void {
    applyUnitsAttr();
    syncUnitButtons();
    applyAccessibilityAttrs();
    syncAccessibilityControls();
    applyDriveTiles();
    renderTilesEditor();
    // Home electricity-rate ($/kWh) preference: hydrate the field and persist on
    // edit. This is the default rate for all charge-cost / savings math. C8: a
    // realistic ceiling — even extreme DCFC/TOU rates stay under $2/kWh, so a
    // fat-fingered "25" (meant as $0.25) can't silently poison the cost model.
    // Bounds mirror the min/max on the partial's inputs (preferences.html).
    bindNumericPref("pricePerKwhInput", "pricePerKwh", 0, 2);
    // Optional public / DC-fast rate (M3). When set (> 0), charge-cost
    // estimates bill public/DCFC sessions at this rate and home (Level 1/2,
    // unknown) sessions at the home rate; left blank, every session uses the
    // single home rate so behaviour is unchanged for users who never set it.
    bindNumericPref("publicPricePerKwhInput", "publicPricePerKwh", 0, 2);
    // Comparison gas vehicle: MPG + gas price drive the "Estimated savings vs gas"
    // stat on Insights (insights-panel.ts). Clamped to plausible ranges so a
    // fat-fingered entry can't poison the estimate.
    bindNumericPref("gasMpgInput", "mpg", 5, 150);
    bindNumericPref("gasPricePerGalInput", "gasPricePerGal", 0, 10);
    // Charge target SOC (M2/C3): now lives in BOTH the Preferences panel
    // (#prefChargeTargetInput — the canonical, always-reachable home) and the
    // live-charge card (#liveChargeTargetInput — a synced mirror, shown only while
    // charging). Both bind the SAME "chargeTargetSoc" pref; bindNumericPref's
    // subscribe() keeps the inactive field in sync when the other is edited.
    // The pref drives the live-charge ETA (telemetry.ts reads "chargeTargetSoc")
    // and is mirrored to the native side so EventNotificationDecider can fire the
    // "target reached" notification and tell a finished charge from an interrupted
    // one. Default 100 = full charge.
    const chargeTargetOptions = {
      defaultValue: 100,
      rangeUnit: "%",
      onCommit: (value: number) => commitChargeTarget(value),
    };
    bindNumericPref("prefChargeTargetInput", "chargeTargetSoc", 50, 100, chargeTargetOptions);
    bindNumericPref("liveChargeTargetInput", "chargeTargetSoc", 50, 100, chargeTargetOptions);
    subscribe("chargeTargetSoc", syncChargeTargetPresets);
    syncChargeTargetPresets();
    setActiveSettingsSection("settingsConnection");
    window.addEventListener("scroll", queueSettingsScrollSpy, { passive: true });
    window.addEventListener("resize", queueSettingsScrollSpy, { passive: true });
  }

  function bindPreferencesClickHandler(): void {
    // Install the static preferences click handlers at most once per document.
    // Production loads prefs once, but the guard keeps a stray double-load (or a
    // re-imported test module) from stacking a second toggle listener — which
    // would double-fire the high-contrast toggle and cancel itself out. The flag
    // rides on document (not a module-scoped let) so it survives a re-import.
    const doc = document as Document & { __voltPrefsClickBound?: boolean };
    if (doc.__voltPrefsClickBound) return;
    doc.__voltPrefsClickBound = true;
    const handleClick = (event: Event) => {
      const target = event.target instanceof Element ? event.target : null;
      if (!target) return;
      const chargeTargetButton = target.closest<HTMLElement>("button[data-charge-target]");
      if (chargeTargetButton) {
        const raw = chargeTargetButton.dataset.chargeTarget || "";
        if (raw === "other") {
          const input = document.getElementById(chargeTargetButton.dataset.chargeTargetInput || "") as HTMLInputElement | null;
          if (input) {
            input.focus();
            input.select();
          }
        } else {
          commitChargeTarget(Number(raw), true);
        }
        return;
      }
      const unitBtn = target.closest("[data-pref-units]");
      if (unitBtn) {
        const metric = unitBtn.getAttribute("data-pref-units") === "metric";
        set("units", metric ? "metric" : "imperial");
        applyUnitsAttr();
        syncUnitButtons();
        rerenderForUnits();
        toast(metric ? "Metric · km · °C" : "Imperial · mi · °F");
        return;
      }
      // Accessibility font scale (M9): pick one of the offered multipliers.
      // The toast live region carries the announcement alone (same
      // single-announcement pattern as the high-contrast toggle below).
      const scaleBtn = target.closest("[data-pref-font-scale]");
      if (scaleBtn) {
        const value = Number(scaleBtn.getAttribute("data-pref-font-scale"));
        const clamped = Number.isFinite(value)
          ? Math.min(FONT_SCALE_MAX, Math.max(FONT_SCALE_MIN, value))
          : 1;
        set("fontScale", clamped);
        applyAccessibilityAttrs();
        syncAccessibilityControls();
        toast(clamped <= 1 ? "Default text size" : clamped >= FONT_SCALE_MAX ? "Largest text size" : "Large text size");
        return;
      }
      // Accessibility high-contrast theme toggle (M9). The toast is itself a
      // polite live region, so it carries the announcement alone — pushing the
      // same string through #prefA11yAnnounce too made screen readers say it
      // twice per toggle.
      const contrastBtn = target.closest("[data-pref-contrast-toggle]");
      if (contrastBtn) {
        set("highContrast", !highContrast());
        applyAccessibilityAttrs();
        syncAccessibilityControls();
        toast(highContrast() ? "High contrast on" : "High contrast off");
        return;
      }
      const quietBtn = target.closest("[data-pref-quiet-telemetry]");
      if (quietBtn) {
        set("quietTelemetry", !quietTelemetry());
        applyAccessibilityAttrs();
        syncAccessibilityControls();
        toast(quietTelemetry() ? "Quiet live data on" : "Live values will be announced");
        return;
      }
      // :root also carries data-drive-preset for CSS. Restrict this lookup to
      // the actual controls or every click resolves to <html> and returns here
      // before the Diagnostics/Settings actions can run.
      const presetBtn = target.closest<HTMLElement>("button[data-drive-preset]");
      if (presetBtn) {
        selectDrivePreset(presetBtn.dataset.drivePreset === "focus" ? "focus" : "detailed");
        return;
      }
      const diagnosticsBtn = target.closest<HTMLElement>("button[data-diagnostics-mode]");
      if (diagnosticsBtn) {
        set("diagnosticsMode", diagnosticsBtn.dataset.diagnosticsMode === "advanced" ? "advanced" : "basic");
        applyDiagnosticsMode();
        toast(diagnosticsMode() === "advanced" ? "Advanced diagnostics shown" : "Basic diagnostics shown");
        return;
      }
      const settingsButton = target.closest<HTMLElement>("button[data-settings-target]:not([data-nav-jump])");
      if (settingsButton) {
        const id = settingsButton.dataset.settingsTarget || "";
        scrollToSettingsSection(id);
      }
    };
    // Capture before the dashboard's broad action delegates. A few nested
    // settings surfaces intentionally stop bubbling so their row action does
    // not also fire; preference controls must remain independent of that.
    document.addEventListener("click", handleClick, true);
  }

  // The bundle is loaded after the full body, so delegated clicks can bind
  // immediately. Initial value hydration may still wait for DOMContentLoaded.
  // Keeping those lifecycles separate prevents a late/missed ready event from
  // leaving visible preference controls inert.
  bindPreferencesClickHandler();

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", bootPrefsUi);
  } else {
    bootPrefsUi();
  }
