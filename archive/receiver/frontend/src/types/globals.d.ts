/* eslint-disable no-var */
/**
 * Global type declarations for dynamically loaded libraries and toast.js
 */

// toast.js globals
declare function showToast(message: string, type?: string, duration?: number, actions?: ToastAction[]): void;
declare function showInfo(message: string, duration?: number): void;
declare function showWarning(message: string, duration?: number): void;
declare function showError(message: string, duration?: number): void;
declare function showSuccess(message: string, duration?: number): void;

interface ToastAction {
  label: string;
  onClick: () => void;
}

// Socket.IO client (loaded from CDN)
declare var io: {
  (url?: string, opts?: Record<string, unknown>): SocketIOClient;
};

interface SocketIOClient {
  on(event: string, callback: (...args: any[]) => void): void;
  emit(event: string, ...args: any[]): void;
  disconnect(): void;
  connected: boolean;
}

// Chart.js (loaded dynamically)
declare class Chart {
  constructor(ctx: HTMLCanvasElement | CanvasRenderingContext2D, config: any);
  destroy(): void;
  update(): void;
  data: any;
  options: any;
}

// Leaflet (loaded dynamically)
declare namespace L {
  function map(element: HTMLElement | string, options?: any): LeafletMap;
  function tileLayer(urlTemplate: string, options?: any): any;
  function polyline(latlngs: [number, number][], options?: any): LeafletPolyline;
  function marker(latlng: [number, number], options?: any): LeafletMarker;
  function latLngBounds(latlngs: [number, number][]): any;
  function divIcon(options: any): any;
  function control(options: any): LeafletControl;

  namespace DomUtil {
    function create(tagName: string, className?: string, container?: HTMLElement): HTMLElement;
  }

  namespace DomEvent {
    function disableClickPropagation(element: HTMLElement): void;
  }

  interface LeafletMap {
    setView(center: [number, number], zoom: number): LeafletMap;
    fitBounds(bounds: any, options?: any): LeafletMap;
    remove(): void;
    addLayer(layer: any): LeafletMap;
  }

  interface LeafletPolyline {
    addTo(map: LeafletMap): LeafletPolyline;
    getBounds(): any;
  }

  interface LeafletMarker {
    addTo(map: LeafletMap): LeafletMarker;
    bindPopup(content: string): LeafletMarker;
  }

  interface LeafletControl {
    addTo(map: LeafletMap): LeafletControl;
    onAdd?: (map: LeafletMap) => HTMLElement;
  }
}

// flatpickr (loaded from CDN)
declare function flatpickr(element: string | HTMLElement, options?: any): FlatpickrInstance;

interface FlatpickrInstance {
  clear(): void;
  destroy(): void;
  setDate(date: Date | string | Date[], triggerChange?: boolean): void;
  selectedDates: Date[];
}

// Extend globalThis for functions exposed to inline HTML handlers
declare var openTripModal: (tripId: number) => Promise<void>;
declare var closeTripModal: () => void;
declare var deleteTrip: (tripId: number) => Promise<void>;
declare var setTimeframe: (days: number) => void;
declare var toggleTheme: () => void;
declare var clearDateFilter: () => void;
declare var toggleExportMenu: () => void;
declare var openAddChargingModal: () => void | Promise<void>;
declare var closeChargingModal: () => void | Promise<void>;
declare var submitChargingSession: (event: Event) => Promise<void>;
declare var deleteChargingSession: (sessionId: number) => Promise<void>;
declare var openChargingDetailModal: (sessionId: number) => Promise<void>;
declare var closeChargingDetailModal: () => void | Promise<void>;
declare var handleImport: (event: Event) => Promise<void>;
declare var closeImportResultModal: () => void | Promise<void>;
declare var copyImportCode: () => void | Promise<void>;
declare var copyImportReport: () => void | Promise<void>;
// Keep Window interface for Chart.js and Leaflet (loaded dynamically)
interface Window {
  Chart?: typeof Chart;
  L?: typeof L;
}

// Extend globalThis for dynamically loaded libraries
declare namespace globalThis {
  var Chart: typeof Chart | undefined;
  var L: typeof L | undefined;
}
