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

  const VD = (window.VoltDashboard = window.VoltDashboard || ({} as VoltDashboard));

  const PREFIX = "vt.pref.";
  const keyListeners: Record<string, Array<(value: unknown) => void>> = {};

  function store(): Storage | null {
    try {
      return typeof window !== "undefined" && window.localStorage ? window.localStorage : null;
    } catch (_err) {
      // Some WebView configurations throw on localStorage access (e.g. storage
      // disabled). Fall back to in-memory so callers still get their defaults.
      return null;
    }
  }

  const memoryFallback: Record<string, string> = {};

  function rawGet(key: string): string | null {
    const s = store();
    if (s) {
      try {
        return s.getItem(PREFIX + key);
      } catch (_err) {
        /* fall through to memory */
      }
    }
    return Object.prototype.hasOwnProperty.call(memoryFallback, key) ? memoryFallback[key] : null;
  }

  function rawSet(key: string, serialized: string): void {
    const s = store();
    if (s) {
      try {
        s.setItem(PREFIX + key, serialized);
        return;
      } catch (_err) {
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

  VD.prefs = { get, set, subscribe };

  // ----- units --------------------------------------------------------------
  // Central unit formatting. Data crosses the bridge in SI-ish units (speed in
  // km/h, distance in km/m, temp in °C, efficiency in mi/kWh); renderers call
  // these so a single `units` preference flips every surface consistently.
  const KPH_TO_MPH = 0.621371;
  const KM_TO_MI = 0.621371;

  function unitSystem(): "imperial" | "metric" {
    return get<string>("units", "imperial") === "metric" ? "metric" : "imperial";
  }

  function speed(kph: number): { value: number; unit: string } {
    const metric = unitSystem() === "metric";
    return { value: Math.round(metric ? kph : kph * KPH_TO_MPH), unit: metric ? "km/h" : "mph" };
  }

  function distanceKm(km: number): { value: string; unit: string } {
    const metric = unitSystem() === "metric";
    const v = metric ? km : km * KM_TO_MI;
    return { value: v < 10 ? v.toFixed(1) : String(Math.round(v)), unit: metric ? "km" : "mi" };
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

  VD.units = {
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
      const row = document.createElement("div");
      row.className = "tile-edit-row";
      const up = document.createElement("button");
      up.type = "button";
      up.className = "tile-move";
      up.textContent = "↑";
      up.setAttribute("aria-label", `Move ${tileLabel(entry.key)} up`);
      up.disabled = index === 0;
      up.addEventListener("click", () => reorderTile(entry.key, -1));
      const down = document.createElement("button");
      down.type = "button";
      down.className = "tile-move";
      down.textContent = "↓";
      down.setAttribute("aria-label", `Move ${tileLabel(entry.key)} down`);
      down.disabled = index === cfg.length - 1;
      down.addEventListener("click", () => reorderTile(entry.key, 1));
      const name = document.createElement("span");
      name.className = "tile-edit-label";
      name.textContent = tileLabel(entry.key);
      const toggle = document.createElement("button");
      const label = tileLabel(entry.key);
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
    cfg[from] = cfg[to];
    cfg[to] = moved;
    persistTiles(cfg);
  }

  // Re-render everything a units / rate change can affect: live tiles, the
  // storage-driven surfaces (charge cost, odometer, session review), and the
  // active stored view (trips/insights/map re-run their loaders via setView).
  function rerenderForUnits(): void {
    const safe = (fn: unknown) => {
      try {
        if (typeof fn === "function") (fn as () => void)();
      } catch (_err) {
        /* a single renderer failing must not block the others */
      }
    };
    safe(VD.updateLiveUi);
    safe(VD.updateStorageUi);
    try {
      const view = VD.state && VD.state.view;
      if (view && typeof VD.setView === "function") VD.setView(view);
    } catch (_err) {
      /* no-op */
    }
  }

  function bootPrefsUi(): void {
    applyUnitsAttr();
    syncUnitButtons();
    applyDriveTiles();
    renderTilesEditor();
    // Electricity-rate ($/kWh) preference: hydrate the field and persist on edit.
    const priceInput = document.getElementById("pricePerKwhInput") as HTMLInputElement | null;
    if (priceInput) {
      const stored = get<number>("pricePerKwh", 0);
      if (stored > 0) priceInput.value = String(stored);
      priceInput.addEventListener("input", () => {
        const value = parseFloat(priceInput.value);
        set("pricePerKwh", Number.isFinite(value) && value >= 0 ? value : 0);
        rerenderForUnits();
      });
    }
    document.addEventListener("click", (event) => {
      const target = event.target instanceof Element ? event.target : null;
      if (!target) return;
      const unitBtn = target.closest("[data-pref-units]");
      if (unitBtn) {
        set("units", unitBtn.getAttribute("data-pref-units") === "metric" ? "metric" : "imperial");
        applyUnitsAttr();
        syncUnitButtons();
        rerenderForUnits();
      }
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", bootPrefsUi);
  } else {
    bootPrefsUi();
  }
