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

export {};

/**
 * A telemetry/status payload as it crosses the native -> WebView boundary: the
 * bridge hands the WebView either a JSON string or an already-parsed object, and
 * the dashboard re-parses it with `parsePayload`. The concrete fields vary by
 * message, so this is an open record keyed by string. Intentionally permissive:
 * a precise shape here would be dishonest given the union of payload kinds.
 */
type VoltPayload = Record<string, any>;

/** A single decoded route point used by the map + scrubber. */
interface VoltRoutePoint {
  lat: number;
  lng: number;
  [key: string]: any;
}

/** A logged drive/route the map renders and the scrubber walks. */
interface VoltRoute {
  points?: VoltRoutePoint[];
  [key: string]: any;
}

/** Result of a DTC lookup (dtc-lookup.ts `dtcInfo`) — shape mirrors that function exactly. */
interface VoltDtcInfo {
  code: string;
  description: string | null;
  known: boolean;
  category: string | null;
  causes: any;
  severity: any;
  [key: string]: any;
}

/** Status object passed to `setStatus` / surfaced by the error controller. */
interface VoltStatus {
  state: string;
  detail?: string;
  [key: string]: any;
}

declare global {
  /** Leaflet runtime global; kept broad while the map/panel runtime surface is still globally shared. */
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
    /** Bind a listener by element id; returns false (and warns) if the id is missing. */
    bindListenerGuarded(
      id: string,
      event: string,
      handler: EventListenerOrEventListenerObject,
      opts?: boolean | AddEventListenerOptions,
    ): boolean;
    /** Tears down the window-level error/rejection listeners on reset. */
    errorController?: AbortController;
    /** Mutable in-memory dashboard data (trips/sessions/hourly/insights). */
    data: {
      trips: any[];
      sessions: any[];
      hourly: any[];
      insights: any[];
      demoLoaded: boolean;
      [key: string]: any;
    };
    /** Mutable UI/runtime state bag. */
    state: Record<string, any>;
    reportClientError(label: string, detail?: string): void;
    escapeHtml(value: unknown): string;
    /** Parse a native payload (JSON string or object); returns `fallback` on failure. */
    parsePayload(payload: unknown, fallback?: any): any;
    /** Set an element's text (with "--" fallback); returns whether the node existed. */
    setText(id: string, value: unknown): boolean;
    /** Set a meter element's fill width (0-100); returns whether the node existed. */
    setMeter(id: string, value: unknown): boolean;
    setView(view: string): void;
    updateViewHeading(): void;
    setDemoActive(active: boolean, detail?: string): void;
    clearDemoTelemetry(): void;
    ensureDemoData(callback?: (data: any) => void): void;
    ensureDtcData(): Promise<VoltDashboard>;
    dtcDataLoaded(): boolean;
    dtcSearchUrl(code: string): string;
    setDevices(payload: unknown): void;
    setHistory(payload: unknown): void;
    selectDevice(address: string, name?: string): void;
    getLastDevice(): any;
    getSelectedDevice(): any;
    relativeTime(value: unknown): string;
    realViewMeta: Record<string, [string, string]>;

    // ----- telemetry.ts ------------------------------------------------------
    setStatus(payload: VoltStatus): void;
    setAppState(payload: unknown): void;
    updateTelemetry(payload: VoltPayload): void;
    updateLiveUi(): void;
    drawTrace(): void;
    renderOperationalState(): void;
    updateValidationUi(): void;
    formatDistance(meters: unknown): string;
    formatDuration(ms: unknown): string;
    formatShortDuration(ms: unknown): string;
    formatWhen(value: unknown): string;
    formatBytes(value: unknown): string;
    dbRowCount(storage: unknown): number;

    // ----- panels.ts ---------------------------------------------------------
    setStorage(payload: unknown): void;
    loadTrips(): void;
    loadInsights(): void;
    renderRealTrips(): void;
    renderInsightStats(): void;
    selectRealTrip(id: string | number): void;
    updateDiagnosticCodeUi(): void;
    enrichRouteEff(route: VoltRoute): void;

    // ----- map.ts ------------------------------------------------------------
    renderMap(): void;
    loadSampleData(): void;
    haversineMetersJs(lat1: number, lng1: number, lat2: number, lng2: number): number;
    /** Resolve the route for the currently-selected map session from a storage payload. */
    selectedMapRoute(storage: any): VoltRoute;

    // ----- scrubber.ts -------------------------------------------------------
    renderScrubber(route: VoltRoute): void;
    hideScrubber(): void;
    scrubberAttachMap(map: any): void;
    scrubAtLatLng(lat: number, lng: number): void;

    // ----- drive.ts ----------------------------------------------------------
    renderDriveLive(): void;

    // ----- dtc-lookup.ts / dtc-causes.ts (lazy: present only after ensureDtcData) ----
    dtcInfo?(code: string): VoltDtcInfo | null;
    dtcSampleCodes?: any[];
    dtcLookupCodes?: ReadonlyArray<string>;
    dtcLookupSize?: number;
    DTC_CAUSES?: Record<string, any>;

    // ----- actions.ts / troubleshooter.ts ------------------------------------
    actions: Record<string, any>;
    troubleshooter: Record<string, any>;

    // Members attached by files not yet individually enumerated above remain
    // reachable; new exports should get a real signature here.
    [key: string]: any;
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
    getStorageSummary(): string;
    exportDebugBundle(): string;
    getTrips(): string;
    getInsights(): string;
    getTripRoute(sessionId: string): string;
    getRecentSessions(n: number): string;

    // void methods that hand work off to MainActivity / TroubleshooterBridge.
    dashboardReady(): void;
    requestPermissions(): void;
    refreshDevices(): void;
    connect(address: string, name: string): void;
    scan(address: string, name: string): void;
    tpmsScan(address: string, name: string): void;
    detailProbe(address: string, name: string, stage: string): void;
    exportDetailedSignalLog(id: string): string;
    exportDetailedSignalLogs(): string;
    deleteDetailedSignalLog(id: string): void;
    shareBackup(): void;
    shareEncryptedBackup(passphrase: string): void;
    restoreBackup(): void;
    restoreEncryptedBackup(passphrase: string): void;
    clearStoredData(): void;
    rememberDevice(address: string, name: string): void;
    connectLast(): void;
    scanLast(): void;
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
    shareDiagnostics(): void;
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
    /** Interval handle for the demo-preview ticker (actions.ts). */
    __voltDemoTimer?: ReturnType<typeof setInterval> | null;
  }

  /** Leaflet, loaded as a side-effecting global from lib/leaflet/leaflet.js. */
  const L: any;
}
