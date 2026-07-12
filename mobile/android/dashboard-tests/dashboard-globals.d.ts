// Ambient declarations for the globals the dashboard IIFEs share (I3).
//
// The dashboard source is bundled into classic IIFEs that hang their
// API off `window.VoltDashboard` (aliased `VD`) and talk to the native side via
// `window.VoltTrackerAndroid`. These shapes are concrete interfaces (not `any`) so
// that `noImplicitAny` and `strictNullChecks` can both stay ON: every cross-file
// `VD.<name>` and bridge call is type-checked against a real signature.
//
// Member optionality models RUNTIME nullability, not load order. The eagerly-loaded
// scripts (core/panels/map/scrubber/drive/telemetry/actions/troubleshooter + the two
// connection-* files, loaded in order by index.html) have all attached their members
// by the time any user-triggered call runs, so their API is declared REQUIRED. Only
// genuinely-absent-at-call-time members stay optional: `bridge` is null outside the
// WebView, and the dtc-lookup.ts / dtc-causes.ts members exist only after the lazy
// `ensureDtcData()` load resolves (call sites guard with `dtcDataLoaded()`).

import type { DataStateValue } from "../app/src/main/dashboard-src/js/dataset-state";

export {};

// All dashboard ambient types live inside `declare global` so that not just the
// VoltDashboard interface below but every bundled source file (telemetry.ts,
// panels.ts and its split siblings, …) can name them without an import — the
// dashboard ships as ordered classic IIFEs, never ES modules.
declare global {

/**
 * A telemetry/status payload as it crosses the native -> WebView boundary: the
 * bridge hands the WebView either a JSON string or an already-parsed object, and
 * the dashboard re-parses it with `parsePayload`. The concrete fields vary by
 * message, so this is an open record keyed by string. Intentionally permissive:
 * a precise shape here would be dishonest given the union of payload kinds.
 */
type VoltPayload = Record<string, unknown>;

// ---------------------------------------------------------------------------
// Native bridge payload shapes (C1)
//
// These mirror the JSON the Kotlin side emits — StorageSummaryJson.kt,
// data/ObdStoreReports.kt, data/DiagnosticCodeReport.kt and AppStatePayload.kt
// (see demo-native-contract.test.js, which derives the field set live from those
// emitters). The dashboard re-parses each payload with parsePayload() and reads
// fields very defensively (Number(x || 0), `x == null`, optional sub-objects), so
// EVERY field is optional here: the native layer omits absent columns and sends
// JSON `null` for missing nullable doubles. Numeric fields are `number | null`
// where the native side boxes a nullable double; string fields that can be JSON
// null are `string | null`. A trailing index signature is kept ONLY on the small
// number of shapes that are spread/merged with arbitrary extra keys.
// ---------------------------------------------------------------------------

/** A native read that failed: `{ ok: false, error, message }` (panels isNativeError). */
interface VoltNativeError {
  ok?: boolean;
  error?: string;
  message?: string;
}

/** Result of a bridge export call (debug bundle / signal-log export): a status
 *  object the actions layer reads for `ok`/`path`/`error`/`message`. */
interface VoltExportResult {
  ok?: boolean;
  path?: string;
  filename?: string;
  content?: string;
  error?: string;
  message?: string;
  [key: string]: unknown;
}

/** A row in storage.recentSessions[] (StorageSummaryJson.recentSessionsJson). */
interface VoltRecentSession {
  id?: string | number;
  mode?: string;
  adapterAddress?: string;
  adapterName?: string;
  startedAtMs?: number;
  endedAtMs?: number | null;
  status?: string;
  supportedPids?: unknown;
  sampleCount?: number;
  usefulSampleCount?: number;
  emptySampleCount?: number;
  lastEventAtMs?: number;
}

/** A diagnostic-trouble-code row (data/DiagnosticCodeReport.toJson). */
interface VoltDtcRow {
  id?: string | number;
  dtc?: string;
  status?: string;
  statusLabel?: string;
  moduleKey?: string;
  moduleName?: string;
  header?: string;
  firstSeenMs?: number;
  lastSeenMs?: number;
  seenCount?: number;
  lastSessionId?: string | number | null;
  rawResponse?: string;
  /** Conditions captured when the fault set (freeze frame), label -> display value.
   *  Native does not populate this yet; the demo fault scenario does. */
  freezeFrame?: Record<string, string | number> | null;
}

/** A charge-session row (data/ObdStoreReports.chargeSummaryRowJson). */
interface VoltChargeSessionRow {
  id?: string | number;
  startedAtMs?: number;
  endedAtMs?: number | null;
  chargerType?: string | null;
  startSoc?: number | null;
  endSoc?: number | null;
  powerKw?: number | null;
  energyKwh?: number | null;
}

/** storage.chargeSummary (data/ObdStoreReports.chargeSummaryJson). */
interface VoltChargeSummary {
  chargeSessionCount?: number;
  chargingHintCount?: number;
  maxPowerKw?: number | null;
  latest?: VoltChargeSessionRow | null;
  recentSessions?: VoltChargeSessionRow[];
}

/** The latest captured sample attached to an enhanced-capability evidence row.
 *  Open record (raw decoded fields ride along), but the classification fields
 *  the signals panel reads are concrete. */
interface VoltEnhancedSample {
  pollLane?: string;
  validationStatus?: string;
  category?: string;
  scanStage?: string;
  risk?: string;
  rawResponse?: string;
  [key: string]: unknown;
}

/** A single enhanced/detailed-signal capability or catalog profile row. The
 *  catalog profiles and the captured evidence rows are merged with `{...}`, and
 *  the merge adds the `_status`/`_hasEvidence`/`_effDone` markers, so this shape
 *  stays open. */
interface VoltEnhancedCapability {
  id?: string | number;
  name?: string;
  pid?: string;
  command?: string;
  header?: string;
  category?: string;
  scanStage?: string;
  pollLane?: string;
  risk?: string;
  notes?: string;
  source?: string;
  supported?: boolean;
  responseCount?: number;
  validationStatus?: string;
  lastSeenMs?: number;
  sample?: VoltEnhancedSample | null;
  /** Derived in panels: capability status bucket + whether real evidence exists. */
  _status?: string;
  _hasEvidence?: boolean;
  [key: string]: unknown;
}

/** storage.latestReview (post-session review block). Read field-by-field with
 *  heavy `|| 0` / `Array.isArray` guards, so every member is optional/open. */
interface VoltSessionReview {
  session?: VoltRecentSession & { detail?: string };
  warnings?: Array<{ code?: string; count?: number; detail?: string }>;
  timeline?: Array<{ detail?: string; state?: string; kind?: string; atMs?: number }>;
  recentPidFrames?: Array<{
    command?: string;
    name?: string;
    valueText?: string;
    rawResponse?: string;
    parsed?: boolean;
  }>;
  stateCounts?: Record<string, number>;
  latestHealth?: { backgroundSampleCount?: number; sampleGapCount?: number };
  maxSpeedKph?: number;
  locationSampleCount?: number;
  parsedPidCount?: number;
  unknownPidCount?: number;
  avgSampleIntervalMs?: number;
  backgroundSampleCount?: number;
  sampleGapEventCount?: number;
  usefulTelemetryCount?: number;
  emptyTelemetryCount?: number;
  [key: string]: unknown;
}

/** The storage payload (StorageSummaryJson.buildOverview plus lazy storageDetails).
 *  Surfaced via VD.setStorage and stashed on state.storage. Nested blocks
 *  (overview/batterySummary) are read field-by-field, so they stay open records. */
interface VoltStorageSummary {
  error?: string;
  message?: string;
  storageDetails?: boolean;
  database?: string;
  databaseBytes?: number;
  sessionCount?: number;
  rawTelemetryCount?: number;
  sampleCount?: number;
  emptyTelemetryCount?: number;
  eventCount?: number;
  adapterCount?: number;
  pidObservationCount?: number;
  diagnosticCodeCount?: number;
  diagnosticCodeStatusCounts?: Record<string, number>;
  locationSampleCount?: number;
  vehicleCount?: number;
  fieldCapabilityCount?: number;
  tripSegmentCount?: number;
  chargeSessionCount?: number;
  batterySnapshotCount?: number;
  cellSnapshotCount?: number;
  exportCount?: number;
  lastSessionId?: string | number;
  lastMode?: string;
  lastStatus?: string;
  lastStartedAtMs?: number;
  lastEventAtMs?: number;
  lastSampleCount?: number;
  lastAdapter?: string;
  recentSessions?: VoltRecentSession[];
  adapters?: Array<Record<string, unknown>>;
  latestDiagnosticCodes?: VoltDtcRow[];
  latestReview?: VoltSessionReview;
  latestRoute?: VoltRoute;
  recentRoutes?: VoltRoute[];
  overview?: Record<string, unknown>;
  chargeSummary?: VoltChargeSummary;
  batterySummary?: Record<string, unknown>;
  latestVehicle?: Record<string, unknown>;
  enhancedCapabilities?: VoltEnhancedCapability[];
  detailedSignalCatalog?: VoltEnhancedCapability[];
  [key: string]: unknown;
}

/** The logged-trip rollup rows returned by bridge.getTrips() and stashed on
 *  state.trips. Read field-by-field with Number()/String() coercion. */
interface VoltTrip {
  id?: string | number;
  routeId?: string | number;
  sessionId?: string | number;
  hasRoute?: boolean;
  pointCount?: number;
  sampleCount?: number;
  startedAtMs?: number;
  endedAtMs?: number;
  durationMs?: number;
  distanceMeters?: number;
  avgMovingSpeedKph?: number;
  maxSpeedKph?: number;
  status?: string;
  adapterName?: string;
  /** User-authored trip label (M4); empty string when unset. */
  label?: string;
  /** User favorite flag (M4 favorites half); absent/false when not favorited. */
  favorite?: boolean;
  /** Net HV energy over the trip in kWh (drive minus regen); null when no power was logged. */
  energyKwh?: number | null;
  /** Share (0..1) of the trip's driving done on electric; null when unclassified. */
  evShare?: number | null;
  /** Window-averaged outside air temperature in deg C; null when never logged. */
  avgOutsideTempC?: number | null;
  [key: string]: unknown;
}

/** A user-authored maintenance-log row (M5) returned by bridge.getMaintenanceLog(). */
interface VoltMaintenanceEntry {
  id?: string | number;
  createdAtMs?: number;
  odometerKm?: number | null;
  type?: string;
  note?: string;
  /** Optional service interval (M1/C4): JSON null when unset. Drives the "next due" line. */
  intervalKm?: number | null;
  intervalMonths?: number | null;
  [key: string]: unknown;
}

/** The vehicle-insights rollup returned by bridge.getInsights() (state.insights). */
interface VoltInsights {
  tripCount?: number;
  totalDistanceMeters?: number;
  totalDriveMs?: number;
  maxSpeedKph?: number;
  longestTripMeters?: number;
  gpsTripCount?: number;
  /** Lifetime "% of driving on electric" (0..100); null until classified driving exists. */
  electricDrivingPct?: number | null;
  /** Whole-history positive drive energy and the distance it covers. */
  loggedEnergyKwh?: number;
  loggedEnergyDistanceMeters?: number;
  [key: string]: unknown;
}

/** appState.adapter block (AppStateJson.build). Open record: the native side
 *  may add fields, but the ones the dashboard reads are concrete. */
interface VoltAdapterState {
  name?: string;
  address?: string;
  remembered?: boolean;
  connected?: boolean;
  [key: string]: unknown;
}

/** appState.session block (AppStateJson.build). */
interface VoltSessionState {
  mode?: string;
  state?: string;
  detail?: string;
  sampleCount?: number;
  sessionMs?: number;
  runtimeMs?: number;
  backgroundSampleCount?: number;
  sampleGapCount?: number;
  maxSampleGapMs?: number;
  [key: string]: unknown;
}

/** appState.gps block (AppStateJson.build). */
interface VoltGpsState {
  state?: string;
  accuracyM?: number;
  ageMs?: number;
  [key: string]: unknown;
}

/** appState payload (AppStatePayload.toJson) stashed on state.appState. Nested
 *  blocks are read defensively; the most cross-referenced ones (adapter /
 *  session / gps) now carry concrete optional fields so reads are checked. */
interface VoltAppState {
  app?: { version?: string; schemaVersion?: number };
  permissions?: {
    bluetooth?: boolean;
    bluetoothPermission?: boolean;
    bluetoothEnabled?: boolean;
    location?: boolean;
    notifications?: boolean;
  };
  adapter?: VoltAdapterState;
  session?: VoltSessionState;
  vehicle?: Record<string, unknown>;
  gps?: VoltGpsState;
  lifecycle?: Record<string, unknown>;
  latestTelemetry?: Record<string, unknown>;
  storage?: VoltStorageSummary;
  [key: string]: unknown;
}

/** The live-telemetry slot on state. The known sample fields are concrete; the
 *  slot is also spread with arbitrary sample keys (telemetry.ts `{...sample}`),
 *  so it keeps an index signature. Numeric readings are `number | string | null`:
 *  the native/demo layer can send a JSON number, a raw string, JSON null, or omit
 *  the key, and the readers guard all of those (`x == null || x === ""`, Number()). */
type VoltReading = number | string | null;
interface VoltTelemetry {
  speedKph?: VoltReading;
  rpm?: VoltReading;
  voltage?: VoltReading;
  coolantC?: VoltReading;
  loadPct?: VoltReading;
  throttlePct?: VoltReading;
  soc?: VoltReading;
  batteryTemp?: VoltReading;
  powerKw?: VoltReading;
  updatedAt?: VoltReading;
  source?: string;
  raw?: string;
  sampleCount?: number;
  sessionMs?: VoltReading;
  latitude?: VoltReading;
  longitude?: VoltReading;
  [key: string]: unknown;
}

/** Private runtime bag the troubleshooter IIFE keeps under state.troubleshooter.
 *  The counters are seeded with numeric defaults and mutated as numbers, so they
 *  are required; the rest carry their initial values. */
interface VoltTroubleshooterState {
  consecutiveFailedSessions: number;
  retriesThisBurst: number;
  autoOpened: boolean;
  dismissedThisBurst: boolean;
  forgetMode: boolean;
  lastSessionState: string;
  lastTelemetry: VoltTelemetry | null;
  [key: string]: unknown;
}

/** A remembered/paired device entry (state.lastDevice / state.deviceHistory[]). */
interface VoltDevice {
  address?: string;
  name?: string;
  candidate?: boolean;
  lastSeen?: unknown;
  connectCount?: number;
  [key: string]: unknown;
}

/**
 * The central runtime/UI state bag (core.ts seeds it; every module mutates it).
 * Closed shape: the known fields carry real types, and the demo/real-shadow
 * fields used while demo mode is streaming are declared explicitly rather than
 * leaking back to `any`. The state-shape.test.js pins the seeded key set.
 */
interface DashboardState {
  view: string;
  mode: string;
  selectedRealTripId: string | null;
  signalProbeStage: string;
  lastDevice: VoltDevice | null;
  deviceHistory: VoltDevice[];
  storage: VoltStorageSummary;
  trips: VoltTrip[];
  insights: VoltInsights;
  /** User-authored maintenance log (M5); from bridge.getMaintenanceLog(). */
  maintenanceLog: VoltMaintenanceEntry[];
  tripsReadError: string | null;
  insightsReadError: string | null;
  tripsLoaded?: boolean;
  insightsLoaded?: boolean;
  appState: VoltAppState;
  demoActive: boolean;
  demoScenario?: string;
  mapLayer: string;
  mapRemoteTilesEnabled: boolean;
  mapFull: boolean;
  mapBrowserOpen: boolean;
  tripReceiptMode: boolean;
  liveSignalsFilter: string;
  mapFollowLive: boolean;
  selectedMapSessionId: string | null;
  liveRouteStartedAtMs: number | null;
  liveRoutePoints: VoltRoutePoint[];
  status: VoltStatus;
  speedHistory: number[];
  powerHistory: number[];
  socHistory: number[];
  sessionStartSoc: number | null;
  sessionDistanceM: number;
  sessionLastLat: number | null;
  sessionLastLng: number | null;
  lastSampleAt: number;
  rafPending: number;
  telemetry: VoltTelemetry;
  // Demo-mode shadow copies: while demo streams, the real native payloads are
  // parked on these so they can be restored when demo stops, and the demo
  // preview payloads drive the visible UI. Reset to null when demo stops.
  realStorage?: VoltStorageSummary | null;
  realTrips?: VoltTrip[] | null;
  realInsights?: VoltInsights | null;
  realAppState?: VoltAppState | null;
  demoPreviewStorage?: VoltStorageSummary | null;
  demoPreviewTrips?: VoltTrip[] | null;
  demoPreviewInsights?: VoltInsights | null;
  demoPreviewAppState?: VoltAppState | null;
  // Internal map-render flag: true once the recent-routes sample fallback loaded.
  _mapSampleLoaded?: boolean;
  /** Private state bag the troubleshooter IIFE keeps on the shared state. */
  troubleshooter?: VoltTroubleshooterState;
}

/** A single decoded route point used by the map + scrubber. The derived `eff`
 *  field is written by enrichRouteEff (null = regen/no-data segment). */
interface VoltRoutePoint {
  lat: number;
  lng: number;
  atMs?: number;
  speedMps?: number;
  altM?: number;
  eff?: number | null;
  [key: string]: unknown;
}

/** A power sample on a route's powerTrack (used to derive per-point efficiency). */
interface PowerTrackSample {
  atMs: number;
  powerKw: number | string;
}

/** A logged drive/route the map renders and the scrubber walks. */
interface VoltRoute {
  points?: VoltRoutePoint[];
  powerTrack?: PowerTrackSample[];
  session?: { id?: string | number; [key: string]: unknown };
  pointCount?: number;
  distanceMeters?: number;
  /** Set true by enrichRouteEff once it has annotated points with `eff`. */
  _effDone?: boolean;
  [key: string]: unknown;
}

/** A minimal structural view of the Leaflet map instances the trip mini-maps
 *  create. Leaflet itself is the untyped `L` global; this names just the methods
 *  the trip-map lifecycle touches. */
interface LeafletMap {
  remove(): void;
  invalidateSize(animate?: boolean): void;
  fitBounds(bounds: unknown, options?: unknown): void;
}

// ---------------------------------------------------------------------------
// Minimal Leaflet handle types (C5)
//
// Leaflet ships as a side-effecting untyped global (`L`), and we don't vendor
// its @types. These interfaces name ONLY the handful of map / layer / latlng
// members the dashboard source actually touches (map.ts + the trip mini-maps in
// insights-panel.ts). They are deliberately structural and minimal — not a full
// Leaflet typing — so the `any`-typed handles in map.ts can be tightened without
// pulling in the whole Leaflet surface.
// ---------------------------------------------------------------------------

/** `{ lat, lng }` carried by Leaflet map-click events. */
interface LeafletLatLng {
  lat: number;
  lng: number;
}

/** The tile element on a Leaflet `tileerror` event (`event.tile.src`). */
interface LeafletTileErrorEvent {
  tile?: { src?: string };
}

/** A Leaflet layer / layer-group handle (polyline, circleMarker, layerGroup,
 *  tileLayer) — only the members map.ts touches. Methods that mutate-and-return
 *  the layer are chainable. The `addTo` target is broad (map OR layer-group) and
 *  tolerates the possibly-null layer-group slots map.ts stores. */
interface LeafletLayer {
  addTo(target: LeafletMapInstance | LeafletLayer | null): this;
  bindTooltip(content: string): this;
  on(event: string, handler: (event: LeafletTileErrorEvent) => void): this;
}

/** Edge accessors on the bounds handle `map.getBounds()` returns — used by the
 *  live-follow recenter check in map.ts. */
interface LeafletLatLngBounds {
  getNorth(): number;
  getSouth(): number;
  getEast(): number;
  getWest(): number;
}

/** The Leaflet map handle map.ts stores as `mapInstance`. Superset of LeafletMap
 *  so it stays assignable wherever the trip mini-maps expect LeafletMap. */
interface LeafletMapInstance extends LeafletMap {
  setView(center: LatLngTuple, zoom: number): this;
  attributionControl?: { setPrefix(prefix: string): void };
  on(event: string, handler: (event: { latlng?: LeafletLatLng }) => void): this;
  removeLayer(layer: LeafletLayer): this;
  getBounds(): LeafletLatLngBounds;
}

/** A `[lat, lng]` pair as Leaflet accepts for points/markers. */
type LatLngTuple = [number, number];
/** A `[start, end]` line segment of two points (map.ts heat/eff bands). */
type LatLngSegment = [LatLngTuple, LatLngTuple];

/** One entry in the dtc-causes.ts DTC_CAUSES table. */
interface VoltDtcCause {
  causes: string[];
  severity: string;
  category: string;
}

/** Result of a DTC lookup (dtc-lookup.ts `dtcInfo`) — shape mirrors that function exactly. */
interface VoltDtcInfo {
  code: string;
  description: string | null;
  known: boolean;
  category: string | null;
  causes: string[] | null;
  severity: string | null;
  [key: string]: unknown;
}

/** Status object passed to `setStatus` / surfaced by the error controller
 *  (StatusPayload.kt). `state` is read with a `|| "idle"` fallback, so it is
 *  optional here; the rest are the fields the telemetry / troubleshooter /
 *  connection-status readers touch. */
interface VoltStatus {
  state?: string;
  detail?: string;
  adapter?: string;
  blocked?: boolean;
  bluetoothReady?: boolean;
  competingApps?: unknown;
  failureClass?: string;
  lastAddress?: string;
  lastName?: string;
  lastVoltage?: number | string | null;
  [key: string]: unknown;
}

interface VoltRestoreProgress {
  visible?: boolean;
  busy?: boolean;
  title?: string;
  detail?: string;
  operation?: string;
  tone?: string;
  phase?: string;
  bytesDone?: number | string;
  bytesTotal?: number | string;
  rowsDone?: number | string;
  rowsTotal?: number | string;
  percent?: number | string;
  etaSeconds?: number | string;
}

  /** Leaflet runtime global. Kept as the untyped `L` because scrubber.ts and
   *  insights-panel.ts chain Leaflet methods (setLatLng / bindPopup-with-opts /
   *  number[] latlngs) that a minimal static type can't model without churn.
   *  map.ts narrows the handles it stores (mapInstance / tile + layer groups)
   *  to the LeafletMapInstance / LeafletLayer interfaces above at assignment.
   *  (This .d.ts is not linted — only the dashboard source under js/ is — so the
   *  `any` here is invisible to the no-explicit-any ratchet.) */
  const L: any;

  /**
   * Shared dashboard namespace every IIFE extends. Members are attached across
   * core.ts (most helpers), telemetry.ts, panels.ts, map.ts, scrubber.ts,
   * drive.ts, dtc-lookup.ts and dtc-causes.ts. Eager-script members are required;
   * the lazy dtc-* members are optional (see file header).
   */
  interface VoltDashboard {
    // ----- core.ts -----------------------------------------------------------
    /** Dashboard -> native bridge handle (null when running outside the WebView). */
    bridge: VoltBridge | null;
    /** `document.getElementById` wrapper. */
    el(id: string): HTMLElement | null;
    setSvgAttrs(node: SVGElement, attrs: Record<string, string | number>): SVGElement;
    /** Bind a listener by element id; returns false (and warns) if the id is missing. */
    bindListenerGuarded(
      id: string,
      event: string,
      handler: EventListenerOrEventListenerObject,
      opts?: boolean | AddEventListenerOptions,
    ): boolean;
    /** Tears down the window-level error/rejection listeners on reset. */
    errorController?: AbortController;
    // ----- prefs.ts ----------------------------------------------------------
    /** Persisted display-layer user preferences (localStorage-backed). Prefs that
     *  change native behaviour go through the bridge, not here. */
    prefs: {
      get<T>(key: string, fallback: T): T;
      set(key: string, value: unknown): void;
      subscribe(key: string, callback: (value: unknown) => void): () => void;
      exportForBackup(): string;
      restoreFromBackup(payload: unknown): boolean;
    };
    selectDrivePreset(preset: "focus" | "detailed"): void;
    scrollToSettingsSection(id: string): boolean;
    /** Unit-aware formatters driven by the `units` preference (imperial|metric).
     *  Inputs are SI-ish (speed km/h, distance km/m/mi, temp °C, eff mi/kWh). */
    units: {
      system(): "imperial" | "metric";
      speed(kph: number): { value: number; unit: string };
      speedText(kph: number): string;
      speedUnit(): string;
      distanceKm(km: number): { value: string; unit: string };
      distanceText(km: number): string;
      distanceMeters(meters: number): { value: string; unit: string };
      distanceMiles(miles: number): { value: string; unit: string };
      distanceUnit(): string;
      temp(celsius: number): { value: number; unit: string };
      tempText(celsius: number): string;
      efficiencyText(miPerKwh: number): string;
      efficiencyUnit(): string;
    };
    /** Mutable in-memory dashboard data (trips/sessions/hourly/insights). */
    data: {
      trips: unknown[];
      sessions: unknown[];
      hourly: unknown[];
      insights: unknown[];
      demoLoaded: boolean;
      [key: string]: unknown;
    };
    /** Mutable UI/runtime state bag. */
    state: DashboardState;
    /** Patch-merge writes into the shared state bag (C3 accessor seam). Used for
     *  the fields with cross-module invariants (demo lifecycle, map/trip
     *  selection); behaves like Object.assign(state, patch). */
    setState(patch: Partial<DashboardState>): DashboardState;
    /** True while demo telemetry is streaming. */
    isDemoActive(): boolean;
    /** The currently-selected map session id (or null). */
    getSelectedMapSessionId(): string | null;
    reportClientError(label: string, detail?: string): void;
    /** Invoke a bridge method only if this native build exposes it; returns
     *  undefined (after a once-per-method console.warn + logClientError) when
     *  the method is missing or the bridge is absent. */
    callBridge<K extends keyof VoltBridge>(
      name: K,
      ...args: Parameters<VoltBridge[K]>
    ): ReturnType<VoltBridge[K]> | undefined;
    setRestoreProgress(payload: VoltRestoreProgress | string): void;
    /**
     * Parse a native payload (JSON string or object); returns `fallback` on
     * failure. Generic so a caller can name the expected shape
     * (`VD.parsePayload<VoltStorageSummary>(raw, {})`); defaults to the open
     * `VoltPayload` record so untyped callers still get a readable object.
     */
    parsePayload<T = VoltPayload>(payload: unknown, fallback?: unknown): T;
    /** Set an element's text (with "--" fallback); returns whether the node existed. */
    setText(id: string, value: unknown): boolean;
    /** The EAGER bundle's i18n lookup (i18n.ts `t`), published by core.ts for
     *  lazy chunks: a chunk importing ./i18n directly would re-bundle its own
     *  catalog copy and miss the locale resolved on the eager side. Typed with
     *  MessageKey so cross-chunk call sites keep compile-time key checking. */
    t?(
      key: import("../app/src/main/dashboard-src/js/i18n").MessageKey,
      params?: Readonly<Record<string, string | number>>,
    ): string;
    /** Set a meter element's fill width (0-100); returns whether the node existed. */
    setMeter(id: string, value: unknown): boolean;
    scrollAppToTop(): void;
    scrollAppBy(deltaY: number): void;
    canScrollApp(): boolean;
    setView(view: string): void;
    openTripFromNative(routeKey: string, receipt?: boolean): void;
    formatRowCount(count: unknown): string;
    setBackupReceipt(payload: unknown): void;
    updateViewHeading(): void;
    setDemoActive(active: boolean, detail?: string): void;
    clearDemoTelemetry(): void;
    ensureDemoData(callback?: (error: Error | null, data: VoltDashboard["data"]) => void): void;
    loadDashboardScript(src: string): Promise<unknown>;
    ensureDtcData(): Promise<VoltDashboard>;
    /** Harness seam: settles when in-flight lazy-chunk loads have run their handlers. */
    pendingLazyLoads(): Promise<unknown[]>;
    dtcDataLoaded(): boolean;
    ensureInsightsModule(): Promise<VoltDashboard>;
    /** G2 split: Charge-tab history/cost renders + the shared monthly trend chart. */
    ensureChargeHistoryModule(): Promise<VoltDashboard>;
    /** G2 split: the Insights maintenance log list + add-entry form. */
    ensureMaintenancePanelModule(): Promise<VoltDashboard>;
    /** G2 split: the DTC detail bottom sheet + scan-progress narration. */
    ensureDtcDetailModule(): Promise<VoltDashboard>;
    ensureSignalsModule(): Promise<VoltDashboard>;
    hydrateConnectionTools(): boolean;
    ensureConnectionToolsModule(): Promise<VoltDashboard>;
    ensureMapModule(): Promise<VoltDashboard>;
    requestMapRender(): Promise<VoltDashboard>;
    renderMapIfLoaded(): void;
    /** Map module (lazy): replace the live-route buffer from an external seed, updating the
     *  module-local reference and state together. Present only once map.js has loaded. */
    setLiveRoutePoints?(points: unknown, startedAtMs?: unknown): void;
    /** Connection-tools module: (re)bind the proactive-tools buttons under a fresh
     *  AbortController so actions.resetListeners() can re-arm them alongside the rest of the UI. */
    bindConnectionTools?(): void;
    refreshConnectionToolsAvailability?(): void;
    applyDiagnosticsMode?(): void;
    ensureTroubleshooterModule(): Promise<VoltDashboard>;
    dtcSearchUrl(code: string): string;
    setDevices(payload: unknown): void;
    setHistory(payload: unknown): void;
    selectDevice(address: unknown, name?: unknown): void;
    getLastDevice(): VoltDevice;
    /** The adapter currently chosen in the device <select>, or null. Both fields
     *  are always concrete strings when a device is returned. */
    getSelectedDevice(): { address: string; name: string } | null;
    relativeTime(value: unknown): string;
    realViewMeta: Record<string, [string, string]>;

    // ----- app-dialog.ts -----------------------------------------------------
    // The themed modal (one #appDialog node, one mutable controller). The
    // module ships ONLY in the eager bundle (via actions.ts) and registers
    // these here so the lazy action chunks reach the single dialog instance
    // instead of bundling a duplicate stateful copy (which would stack focus
    // traps and settle two promises with one Confirm).
    confirmAppDialog(options: {
      title: string;
      message: string;
      confirmLabel?: string;
      cancelLabel?: string;
    }): Promise<boolean>;
    promptAppDialog(options: {
      title: string;
      message: string;
      confirmLabel?: string;
      cancelLabel?: string;
      inputLabel?: string;
      autocomplete?: string;
      inputType?: "text" | "password";
      initialValue?: string;
      allowEmpty?: boolean;
    }): Promise<string | null>;
    /** Three-way confirm: true = primary button, false = the explicit
     *  secondary (cancel-slot) button, null = dismissed (Escape/X/backdrop). */
    choiceAppDialog(options: {
      title: string;
      message: string;
      confirmLabel?: string;
      cancelLabel?: string;
    }): Promise<boolean | null>;

    // ----- payload-validators.ts ---------------------------------------------
    /** Warn-only runtime shape check for native callback payloads
     *  (see docs/bridge-abi.md). Never
     *  throws; logs one console.warn per distinct (kind, field, issue). */
    validatePayload(kind: string, payload: unknown): void;

    // ----- telemetry.ts ------------------------------------------------------
    setStatus(payload: VoltStatus): void;
    setAppState(payload: unknown): void;
    updateTelemetry(payload: VoltPayload): void;
    /** Resume catch-up: replays natively buffered background samples into the chart histories. */
    backfillTelemetry(payload: VoltPayload): void;
    updateLiveUi(): void;
    renderOperationalState(): void;
    /** Live time-to-full estimate on the Charge tab; reads live telemetry. */
    renderLiveCharge(): void;
    updateValidationUi(): void;
    /** Applies a persisted 96-cell probe snapshot ({capturedAtMs, cells:[{index, voltage}]})
     *  to the per-cell voltage map; empty/error payloads clear the stored map. */
    applyCellSnapshot(payload: unknown): void;
    formatDistance(meters: unknown): string;
    formatDuration(ms: unknown): string;
    /** Action-confirmation toast (v2): CSV exported, favorite toggled, report
     *  copied, units changed. Reuses the #statusToast pill without the
     *  status-stream gating; `urgent` announces assertively (failures).
     *  Optional: telemetry.ts attaches it after the earliest eager modules
     *  (prefs) have loaded. */
    showToast?(message: unknown, urgent?: boolean): void;
    formatShortDuration(ms: unknown): string;
    formatWhen(value: unknown): string;
    formatBytes(value: unknown): string;
    dbRowCount(storage: unknown): number;

    // ----- storage-status.ts (split from the old panels.ts) ------------------
    setStorage(payload: unknown): void;
    updateStorageUi(): void;
    updateDiagnosticCodeUi(): void;
    updateReviewUi(): void;
    renderRealV2Ui(): void;
    renderVehicleUi(): void;
    /** The latest battery/telemetry reading the Insights hero renders (shared with the
     *  lazy maintenance-panel.ts chunk for the next-due odometer math). */
    latestInsightReading(storage: VoltStorageSummary): Record<string, unknown>;
    /** Report a failed bridge write: status toast + logClientError (shared with the
     *  lazy charge-history.ts chunk). */
    reportBridgeWriteFailure(label: string, detail: string, err: unknown): void;
    buildRealInsights(review: VoltSessionReview): Array<{ title: string; detail: string }>;
    stateCountSummary(counts: Record<string, number>): string;
    /** True when a parsed native payload is a failed read (`ok === false`). */
    isNativeError(payload: unknown): boolean;
    reportNativeReadError(payload: unknown, fallbackDetail: string): void;
    buildStatusCopy(text: string): HTMLParagraphElement;
    toggleHidden(id: string, hidden: unknown): void;
    /** True when the Insights screen has real content to show (logged trip /
     *  distance or a battery reading) — gates insightsEmptyState. */
    hasInsightContent(): boolean;
    // ----- maintenance-panel.ts (lazy; G2 split from storage-status.ts) -------
    /** M5 maintenance log: load from native into state, render the list with M1/C4 next-due lines,
     *  toggle the inline add-entry form, submit/cancel it. Registered when the lazy chunk loads
     *  (core.ts#ensureMaintenancePanelModule); eager callers guard with typeof checks. */
    loadMaintenanceLog(): void;
    renderMaintenanceList(): void;
    addMaintenanceEntry(): void;
    submitMaintenanceForm(): void;
    closeMaintenanceForm(): void;

    // ----- dtc-detail.ts (lazy; G2 split from storage-status.ts) --------------
    /** DTC detail bottom sheet: opened from a scanned-code row or a lookup hit. Registered when
     *  the lazy chunk loads (core.ts#ensureDtcDetailModule); eager callers guard with typeof. */
    openDtcDetail(code: VoltDtcRow): void;
    closeDtcDetail(): void;
    /** Cosmetic Mode 03/07/02 scan narration; completes on the real scan-complete status. */
    startDtcScanProgress(quick?: boolean): void;
    /** Severity vocabulary owned by storage-status.ts (the eager code rows render it) and
     *  shared with the lazy dtc-detail.ts sheet. */
    dtcSeverity(rawCode: unknown, metaSeverity: string | null): 'critical' | 'warning' | 'info';
    severityLabel(severity: 'critical' | 'warning' | 'info'): string;
    drivabilityLine(severity: 'critical' | 'warning' | 'info'): string;

    // ----- charge-history.ts (lazy; G2 split from storage-status.ts) ----------
    /** Charge-tab per-session history rows + energy/cost rollup. Registered when the lazy
     *  chunk loads (core.ts#ensureChargeHistoryModule); eager callers guard with typeof checks. */
    renderChargeSessions(charge: VoltChargeSummary): void;
    /** Insights hero pack-stat row (voltage/temp/health/power). */
    renderPackStats(latest: Record<string, unknown>): void;
    /** M1 charge-history CSV export: read the electricity-rate pref and forward it to
     *  bridge.exportChargeSessionsCsv so native can append an estimated-cost column. */
    exportChargeSessionsCsv(): void;
    /** Calendar-month key + short label ("May ’26") for a timestamp (shared by the trend charts). */
    monthBucketKey(ms: number): { key: string; label: string; firstMs: number };
    /** Monthly bar chart shared by the charging (Battery tab) and driving (Insights) trends. */
    buildMonthlyTrendSvg(
      labels: string[],
      values: number[],
      ariaLabel: string,
      host?: Element | null,
      opts?: {
        colorVar?: string;
        highlightIndex?: number;
        showValues?: boolean;
        valueFormat?: (v: number) => string;
        dashEmpty?: boolean;
      },
    ): SVGElement;

    // ----- signals-panel.ts (split from the old panels.ts) -------------------
    updateEnhancedCapabilityUi(): void;
    setEnhancedBadge(label: string, tone?: DataStateValue): void;

    // ----- insights-panel.ts (split from the old panels.ts) ------------------
    /** Data-loader only (the Trips tab was removed): populates state.trips for
     *  Insights/map and surfaces read errors via the global status. */
    loadTrips(force?: boolean): void;
    loadAllTrips(): void;
    setTripsPage(payload: unknown): void;
    loadInsights(force?: boolean): void;
    forceLazyStorageRead?: boolean;
    renderInsightStats(): void;
    /** Monthly driving distance/efficiency/cost trend on the Insights tab. */
    renderDriveTrend(): void;
    /** This-week per-day bars card (Distance/Efficiency toggle). */
    renderThisWeek(): void;
    renderInsightScatter(): void;
    enrichRouteEff(route: VoltRoute): void;

    // ----- map.ts ------------------------------------------------------------
    renderMapLoaded?: boolean;
    /** Resolves once the lazy Map stylesheets (leaflet.css + screens-map.css) have
     *  applied; requestMapRender() awaits it to avoid a flash of unstyled map. */
    mapStylesReady?: Promise<void>;
    renderMap(): void;
    /** Re-render only the trip list from current storage (M4 search/sort/favorites
     *  controls call this so a keystroke/toggle re-filters without refitting the map). */
    refreshMapSessionList?(): void;
    /** Populate + show the per-trip detail sheet for a route key (M7). Returns true
     *  when the route was found (so the caller activates the focus trap). */
    openTripDetail?(routeKey: string): boolean;
    /** Shares the open drive as a PNG summary card via the native share sheet. */
    shareTripCard?(): boolean;
    /** Hide the per-trip detail sheet (M7). */
    closeTripDetail?(): void;
    setMapTileError(show: boolean, detail?: string): void;
    retryMapTiles(): void;
    loadSampleData(): void;
    haversineMetersJs(lat1: number, lng1: number, lat2: number, lng2: number): number;
    /** Resolve the route for the currently-selected map session from a storage payload. */
    selectedMapRoute(storage: VoltStorageSummary): VoltRoute;

    // ----- scrubber.ts -------------------------------------------------------
    renderScrubber(route: VoltRoute): void;
    hideScrubber(): void;
    scrubberAttachMap(map: unknown): void;
    scrubAtLatLng(lat: number, lng: number): void;

    // ----- connection-status.ts ----------------------------------------------
    /** Toggle the topbar status popover (state badge / last-connected tap target). */
    toggleStatusPopover?(): void;
    /** Close the popover; true when it was open (Android Back consumes the press). */
    closeStatusPopover?(): boolean;

    // ----- drive.ts ----------------------------------------------------------
    renderDriveLive(): void;

    // ----- dtc-lookup.ts / dtc-causes.ts (lazy: present only after ensureDtcData) ----
    dtcInfo?(code: string): VoltDtcInfo | null;
    dtcSampleCodes?: VoltDtcRow[];
    dtcLookupCodes?: ReadonlyArray<string>;
    dtcLookupFamilyCounts?: Readonly<Record<string, number>>;
    dtcLookupSize?: number;
    DTC_CAUSES?: Record<string, VoltDtcCause>;

    // ----- actions.ts / troubleshooter.ts ------------------------------------
    // Write-only API bags (Object.assign targets); no source reads members back
    // off them, so a `unknown`-valued record keeps them honest without `any`.
    actions: Record<string, unknown>;
    troubleshooter: Record<string, unknown>;

    // Members attached by files not yet individually enumerated above remain
    // reachable; new exports should get a real signature here.
    [key: string]: unknown;
  }

  /**
   * Dashboard -> native bridge: the `@JavascriptInterface` methods exposed on
   * `VoltBridge.kt` (+ the troubleshooter/proactive helpers it delegates to).
   * Names and arities mirror that file exactly — keep them in sync.
   */
  interface VoltBridge {
    // String returners (the real bridge returns JSON-encoded Strings).
    listDevices(): string;
    getLastDevice(): string;
    getDeviceHistory(): string;
    getAutoConnectState(): string;
    getEventNotificationState(): string;
    getStorageSummary(): string;
    requestStorageSummary(): boolean;
    getStorageDetails(): string;
    requestStorageDetails(): boolean;
    exportDebugBundle(): string;
    getTrips(): string;
    getTripsPage(offset: number): string;
    getInsights(): string;
    getTripRoute(sessionId: string): string;
    getCurrentSessionRoute(): string;
    getBatterySohHistory(): string;
    requestTrips(): boolean;
    requestTripsPage(offset: number): boolean;
    requestInsights(): boolean;
    requestTripRoute(sessionId: string): boolean;
    requestCurrentSessionRoute(): boolean;
    requestBatterySohHistory(): boolean;
    getRecentSessions(n: number): string;

    // void methods that hand work off to MainActivity / TroubleshooterBridge.
    dashboardReady(): void;
    startupMark(name: string): void;
    requestPermissions(): void;
    refreshDevices(): void;
    connect(address: string, name: string): void;
    scan(address: string, name: string): void;
    quickScan(address: string, name: string): void;
    tpmsScan(address: string, name: string): void;
    detailProbe(address: string, name: string, stage: string): void;
    exportDetailedSignalLog(id: string): string;
    exportDetailedSignalLogs(): string;
    exportTripGpx(routeKeyOrSessionId: string): string;
    exportTripCsv(routeKeyOrSessionId: string): string;
    exportAllTripsCsv(): string;
    exportChargeSessionsCsv(pricePerKwh: string): string;
    shareTripCard(cardJson: string): string;
    deleteDetailedSignalLog(id: string): void;
    markTripNotTrip(routeKey: string): void;
    restoreTrip(routeKey: string): void;
    setTripLabel(routeKey: string, label: string): void;
    setTripFavorite(routeKey: string, favorite: boolean): void;
    addMaintenanceEntry(json: string): void;
    getMaintenanceLog(): string;
    deleteMaintenanceEntry(id: string): void;
    shareBackup(dashboardPreferencesJson?: string): void;
    shareEncryptedBackup(passphrase: string, dashboardPreferencesJson?: string): void;
    restoreBackup(): void;
    restoreEncryptedBackup(passphrase: string): void;
    clearStoredData(): void;
    rememberDevice(address: string, name: string): void;
    setAutoConnectEnabled(enabled: boolean): void;
    setChargeCompleteNotify(enabled: boolean): void;
    setNewDtcNotify(enabled: boolean): void;
    setLowSocNotify(enabled: boolean, thresholdPct: number): void;
    setHighPackTempNotify(enabled: boolean, thresholdC: number): void;
    setChargeTargetSoc(targetPct: number): void;
    setAutoScanOnConnect(enabled: boolean): void;
    setMaintenanceDueNotify(enabled: boolean): void;
    getDashboardExperienceState(): string;
    setKeepScreenAwake(enabled: boolean): void;
    setTripSummaryNotify(enabled: boolean): void;
    setActiveDashboardView(view: string): void;
    connectLast(): void;
    scanLast(): void;
    quickScanLast(): void;
    tpmsScanLast(): void;
    detailProbeLast(stage: string): void;
    demo(): void;
    disconnect(): void;
    logClientError(label: string, detail: string): void;
    clearVehicleDtcCodes(): void;
    openExternalSearch(dtc: string): void;
    cancelRetry(): void;
    tryReconnectNow(): void;
    openBluetoothSettings(): void;
    openSetupGuide(): void;
    shareDiagnostics(): void;
    shareDiagnosticsDigest(): void;
    startTestConnection(): void;
    scheduleAdapterReadyNotify(mins: number): void;
    cancelAdapterReadyNotify(): void;

    // boolean returner.
    forceStopPackage(packageName: string): boolean;
  }

  interface Window {
    /** Shared dashboard namespace every IIFE extends. */
    VoltDashboard: VoltDashboard;
    /** Native -> dashboard callback surface (updateTelemetry, setStatus, …). */
    VoltTrackerNative?: Record<string, (...args: any[]) => any>;
    /** Dashboard -> native bridge (@JavascriptInterface methods on VoltBridge). */
    VoltTrackerAndroid?: VoltBridge;
    /** Test seam: lets the Vitest harness intercept lazy script loads. */
    __VoltDashboardLoadScript?: (src: string) => unknown;
    /** Demo telemetry fixture factory (demo-data.ts source, shipped as demo-data.js). */
    VoltDashboardDemoData?: (() => any) | any;
    /** Leaflet runtime global, loaded lazily before map.js. */
    L?: {
      map?: (...args: any[]) => any;
    };
    /** Lazy action-module registry populated by actions-*.js chunks. */
    VoltDashboardActionModules?: {
      createStorageActions?: (context: {
        VD: VoltDashboard;
        bridge: VoltBridge | null;
        withBusy: <T>(button: (HTMLElement & { disabled?: boolean }) | null | undefined, fn: () => T) => T | undefined;
      }) => Record<string, (...args: any[]) => any>;
      createSignalActions?: (context: {
        VD: VoltDashboard;
        bridge: VoltBridge | null;
      }) => Record<string, (...args: any[]) => any>;
      runBrowserDemoStream?: (dashboard: VoltDashboard, state: DashboardState) => void;
    };
    /** Interval handle for the demo-preview ticker (actions.ts). */
    __voltDemoTimer?: ReturnType<typeof setInterval> | null;
  }
}
