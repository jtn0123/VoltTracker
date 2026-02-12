/**
 * VoltTracker - Reactive State Store
 * Event-driven state management with typed subscriptions
 */

import type { ImportResult } from '@/types/api';

/** Mutable application state shared across modules */
export interface AppState {
  mpgChart: Chart | null;
  socChart: Chart | null;
  tripSpeedChart: Chart | null;
  tripSocChart: Chart | null;
  chargingCurveChart: Chart | null;
  tripMap: L.LeafletMap | null;
  currentTimeframe: number;
  dateFilter: { start: string | null; end: string | null };
  flatpickrInstance: FlatpickrInstance | null;
  liveRefreshInterval: ReturnType<typeof setInterval> | null;
  statusRefreshInterval: ReturnType<typeof setInterval> | null;
  tripsRefreshInterval: ReturnType<typeof setInterval> | null;
  socket: SocketIOClient | null;
  useWebSocket: boolean;
  modalTriggerElement: HTMLElement | null;
  statusErrorCount: number;
  STATUS_ERROR_THRESHOLD: number;
  liveTripActive: boolean;
  chartJsLoaded: boolean;
  chartJsLoading: boolean;
  leafletLoaded: boolean;
  leafletLoading: boolean;
  lastImportResults: ImportResult[];
  connectionStatus: string;
  theme: string;
}

type StateKey = keyof AppState;
type StateCallback<K extends StateKey> = (value: AppState[K], oldValue: AppState[K]) => void;
type EventCallback = (...args: unknown[]) => void;

/**
 * Reactive store with event-driven subscriptions.
 *
 * Usage:
 *   store.subscribe('theme', (newVal, oldVal) => { ... });
 *   store.setState({ theme: 'light' });          // fires subscribers
 *   store.on('telemetry:update', handler);
 *   store.emit('telemetry:update', data);
 */
export class AppStore {
  private readonly _state: AppState;
  private readonly _subscribers: Map<StateKey, Set<StateCallback<any>>> = new Map();
  private readonly _events: Map<string, Set<EventCallback>> = new Map();

  constructor(initial: AppState) {
    this._state = initial;
  }

  /** Return a readonly snapshot of state */
  getState(): Readonly<AppState> {
    return this._state;
  }

  /** Merge partial state and notify subscribers for changed keys */
  setState(partial: Partial<AppState>): void {
    const keys = Object.keys(partial) as StateKey[];
    for (const key of keys) {
      const oldValue = this._state[key];
      const newValue = (partial as any)[key];
      if (oldValue !== newValue) {
        (this._state as any)[key] = newValue;
        this._notifySubscribers(key, newValue, oldValue);
      }
    }
  }

  /** Direct mutable access (for legacy code that does `state.foo = bar`) */
  get state(): AppState {
    return this._state;
  }

  /** Subscribe to changes on a specific state key */
  subscribe<K extends StateKey>(key: K, callback: StateCallback<K>): () => void {
    if (!this._subscribers.has(key)) {
      this._subscribers.set(key, new Set());
    }
    this._subscribers.get(key)!.add(callback);
    return () => {
      this._subscribers.get(key)?.delete(callback);
    };
  }

  /** Listen for a custom event */
  on(event: string, callback: EventCallback): () => void {
    if (!this._events.has(event)) {
      this._events.set(event, new Set());
    }
    this._events.get(event)!.add(callback);
    return () => {
      this._events.get(event)?.delete(callback);
    };
  }

  /** Emit a custom event */
  emit(event: string, ...args: unknown[]): void {
    this._events.get(event)?.forEach((cb) => {
      try {
        cb(...args);
      } catch (e) {
        console.error(`[Store] Event "${event}" handler error:`, e);
      }
    });
  }

  private _notifySubscribers<K extends StateKey>(key: K, newValue: AppState[K], oldValue: AppState[K]): void {
    this._subscribers.get(key)?.forEach((cb) => {
      try {
        cb(newValue, oldValue);
      } catch (e) {
        console.error(`[Store] Subscriber "${key}" error:`, e);
      }
    });
  }
}

/** The singleton store instance */
export const store = new AppStore({
  mpgChart: null,
  socChart: null,
  tripSpeedChart: null,
  tripSocChart: null,
  chargingCurveChart: null,
  tripMap: null,
  currentTimeframe: 30,
  dateFilter: { start: null, end: null },
  flatpickrInstance: null,
  liveRefreshInterval: null,
  statusRefreshInterval: null,
  tripsRefreshInterval: null,
  socket: null,
  useWebSocket: true,
  modalTriggerElement: null,
  statusErrorCount: 0,
  STATUS_ERROR_THRESHOLD: 3,
  liveTripActive: false,
  chartJsLoaded: false,
  chartJsLoading: false,
  leafletLoaded: false,
  leafletLoading: false,
  lastImportResults: [],
  connectionStatus: 'disconnected',
  theme: localStorage.getItem('theme') || 'dark',
});

/**
 * Legacy compatibility — re-export `state` as direct mutable reference.
 * New code should use `store.setState()` / `store.subscribe()`.
 */
export const state = store.state;
