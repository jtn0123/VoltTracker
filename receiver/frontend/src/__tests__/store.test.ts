/**
 * Tests for store.ts — AppStore reactive state management
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

// We need to mock localStorage before importing store
vi.stubGlobal('localStorage', {
  getItem: vi.fn(() => 'dark'),
  setItem: vi.fn(),
});

const { AppStore } = await import('../store');

function createStore() {
  return new AppStore({
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
    theme: 'dark',
  } as any);
}

describe('AppStore', () => {
  let store: InstanceType<typeof AppStore>;

  beforeEach(() => {
    store = createStore();
  });

  describe('getState', () => {
    it('returns initial state', () => {
      const state = store.getState();
      expect(state.currentTimeframe).toBe(30);
      expect(state.theme).toBe('dark');
      expect(state.connectionStatus).toBe('disconnected');
    });
  });

  describe('setState', () => {
    it('updates state with partial values', () => {
      store.setState({ currentTimeframe: 60, theme: 'light' });
      expect(store.getState().currentTimeframe).toBe(60);
      expect(store.getState().theme).toBe('light');
    });

    it('does not notify subscribers when value is the same', () => {
      const cb = vi.fn();
      store.subscribe('currentTimeframe', cb);
      store.setState({ currentTimeframe: 30 }); // same value
      expect(cb).not.toHaveBeenCalled();
    });

    it('notifies subscribers on change', () => {
      const cb = vi.fn();
      store.subscribe('theme', cb);
      store.setState({ theme: 'light' });
      expect(cb).toHaveBeenCalledWith('light', 'dark');
    });
  });

  describe('subscribe', () => {
    it('returns unsubscribe function', () => {
      const cb = vi.fn();
      const unsub = store.subscribe('currentTimeframe', cb);
      unsub();
      store.setState({ currentTimeframe: 90 });
      expect(cb).not.toHaveBeenCalled();
    });

    it('supports multiple subscribers on same key', () => {
      const cb1 = vi.fn();
      const cb2 = vi.fn();
      store.subscribe('theme', cb1);
      store.subscribe('theme', cb2);
      store.setState({ theme: 'light' });
      expect(cb1).toHaveBeenCalledOnce();
      expect(cb2).toHaveBeenCalledOnce();
    });

    it('handles subscriber errors gracefully', () => {
      const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
      store.subscribe('theme', () => { throw new Error('boom'); });
      // Should not throw
      store.setState({ theme: 'light' });
      expect(errorSpy).toHaveBeenCalled();
      errorSpy.mockRestore();
    });
  });

  describe('events (on/emit)', () => {
    it('emits events to listeners', () => {
      const cb = vi.fn();
      store.on('telemetry:update', cb);
      store.emit('telemetry:update', { speed: 55 });
      expect(cb).toHaveBeenCalledWith({ speed: 55 });
    });

    it('returns unsubscribe function for events', () => {
      const cb = vi.fn();
      const unsub = store.on('test:event', cb);
      unsub();
      store.emit('test:event');
      expect(cb).not.toHaveBeenCalled();
    });

    it('handles event listener errors gracefully', () => {
      const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
      store.on('bad:event', () => { throw new Error('oops'); });
      store.emit('bad:event');
      expect(errorSpy).toHaveBeenCalled();
      errorSpy.mockRestore();
    });

    it('emitting unknown event does nothing', () => {
      expect(() => store.emit('nonexistent')).not.toThrow();
    });

    it('passes multiple args to event listeners', () => {
      const cb = vi.fn();
      store.on('multi:args', cb);
      store.emit('multi:args', 1, 'two', { three: 3 });
      expect(cb).toHaveBeenCalledWith(1, 'two', { three: 3 });
    });
  });

  describe('state (legacy mutable access)', () => {
    it('provides direct mutable access', () => {
      store.state.currentTimeframe = 7;
      expect(store.getState().currentTimeframe).toBe(7);
    });
  });
});
