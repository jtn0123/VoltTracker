/**
 * Tests for live.ts module
 * T22: Frontend test coverage for live telemetry
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// Mock api
vi.mock('@/api', () => ({
  api: vi.fn(),
}));

// Provide global io mock
(globalThis as any).io = undefined;

beforeEach(() => {
  document.body.innerHTML = `
    <section id="live-trip-section" style="display:none">
      <div id="live-trip-content"></div>
    </section>
    <section id="power-flow-section" style="display:none"></section>
    <meta name="ws-token" content="test-token">
    <span id="status-dot"></span>
    <span id="last-sync">Loading...</span>
  `;
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('live module', () => {
  it('falls back to polling when Socket.IO unavailable', async () => {
    const { initWebSocket } = await import('../live');
    // io is undefined, should not throw
    expect(() => initWebSocket()).not.toThrow();
  });

  it('loadLiveTelemetry shows section when trip active', async () => {
    const { api } = await import('@/api');
    (api as any).mockResolvedValue({
      data: {
        active: true,
        session_id: '123',
        start_time: '2025-01-01T10:00:00Z',
        start_soc: 80,
        data: {
          soc: 75,
          speed_mph: 35,
          engine_rpm: 0,
          timestamp: '2025-01-01T10:05:00Z',
        },
        trip_stats: {},
        recent: [],
      },
      error: null,
    });

    const { loadLiveTelemetry } = await import('../live');
    await loadLiveTelemetry();

    const section = document.getElementById('live-trip-section');
    expect(section?.style.display).not.toBe('none');
  });

  it('loadLiveTelemetry hides section when no active trip', async () => {
    const { api } = await import('@/api');
    (api as any).mockResolvedValue({ data: { active: false }, error: null });

    const { loadLiveTelemetry } = await import('../live');
    await loadLiveTelemetry();

    const section = document.getElementById('live-trip-section');
    expect(section?.style.display).toBe('none');
  });

  it('handles API error in loadLiveTelemetry', async () => {
    const { api } = await import('@/api');
    (api as any).mockResolvedValue({ data: null, error: 'Network error' });

    const { loadLiveTelemetry } = await import('../live');
    await expect(loadLiveTelemetry()).resolves.not.toThrow();
  });

  it('handles missing DOM elements gracefully', async () => {
    document.body.innerHTML = '';
    const { api } = await import('@/api');
    (api as any).mockResolvedValue({ data: { active: true, data: {} }, error: null });

    const { loadLiveTelemetry } = await import('../live');
    await expect(loadLiveTelemetry()).resolves.not.toThrow();
  });
});

// ---------------------------------------------------------------------------
// JTN-488: connect_error must be throttled so a flapping Socket.IO handshake
// cannot flood the browser console (previously 30+ warnings/minute on the
// dashboard, more on the map page, burying real JS errors).
// ---------------------------------------------------------------------------
describe('initWebSocket connect_error throttling (JTN-488)', () => {
  interface FakeSocket {
    handlers: Record<string, Array<(arg?: unknown) => void>>;
    io: { on: ReturnType<typeof vi.fn> };
    on: (event: string, handler: (arg?: unknown) => void) => FakeSocket;
    emit: (event: string, arg?: unknown) => void;
  }

  function makeFakeSocket(): FakeSocket {
    const socket = {
      handlers: {} as Record<string, Array<(arg?: unknown) => void>>,
      io: { on: vi.fn() },
    } as Partial<FakeSocket>;
    socket.on = (event: string, handler: (arg?: unknown) => void) => {
      (socket.handlers as Record<string, Array<(arg?: unknown) => void>>)[event] ??= [];
      (socket.handlers as Record<string, Array<(arg?: unknown) => void>>)[event].push(handler);
      return socket as FakeSocket;
    };
    socket.emit = (event: string, arg?: unknown) => {
      const list = (socket.handlers as Record<string, Array<(arg?: unknown) => void>>)[event] || [];
      for (const fn of list) fn(arg);
    };
    return socket as FakeSocket;
  }

  beforeEach(() => {
    vi.resetModules();
  });

  it('emits at most one connect_error warning per minute, no matter how often the event fires', async () => {
    vi.useFakeTimers();
    // Start time anchors Date.now() for the throttle window.
    vi.setSystemTime(new Date('2026-04-09T12:00:00Z'));

    const fakeSocket = makeFakeSocket();
    const ioMock = vi.fn().mockReturnValue(fakeSocket);
    (globalThis as any).io = ioMock;

    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const infoSpy = vi.spyOn(console, 'info').mockImplementation(() => {});

    const { initWebSocket } = await import('../live');
    initWebSocket();

    // Fire 50 connect_error events over 30 seconds (simulate the reporter's
    // "POST 400 every few seconds" loop).
    for (let i = 0; i < 50; i++) {
      fakeSocket.emit('connect_error', new Error('xhr post error'));
      vi.advanceTimersByTime(600); // 0.6s between errors -> 30s total
    }

    // Only the very first error should have produced a console.warn.
    expect(warnSpy).toHaveBeenCalledTimes(1);
    expect(warnSpy.mock.calls[0][0]).toContain('[WS] Connection error');

    // Advance past the 60s throttle window and fire another burst. A single
    // additional warning is allowed (with a "suppressed" summary).
    vi.advanceTimersByTime(31_000); // total time > 60s since last warn
    for (let i = 0; i < 10; i++) {
      fakeSocket.emit('connect_error', new Error('xhr post error'));
      vi.advanceTimersByTime(100);
    }

    expect(warnSpy).toHaveBeenCalledTimes(2);
    expect(warnSpy.mock.calls[1][0]).toContain('[WS] Connection error');
    // The second warning should include the suppressed-count summary.
    const secondWarnArgs = warnSpy.mock.calls[1];
    const combined = secondWarnArgs.map(String).join(' ');
    expect(combined).toMatch(/suppressed/);

    infoSpy.mockRestore();
  });

  it('resets the suppression counter on successful reconnect and logs the recovery', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-04-09T12:00:00Z'));

    const fakeSocket = makeFakeSocket();
    (globalThis as any).io = vi.fn().mockReturnValue(fakeSocket);

    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const infoSpy = vi.spyOn(console, 'info').mockImplementation(() => {});

    const { initWebSocket } = await import('../live');
    initWebSocket();

    // Burn through several throttled errors within the window.
    for (let i = 0; i < 5; i++) {
      fakeSocket.emit('connect_error', new Error('xhr post error'));
      vi.advanceTimersByTime(1000);
    }
    // First warn + 4 suppressed.
    expect(warnSpy).toHaveBeenCalledTimes(1);

    // A successful connect should log an info about recovered+suppressed.
    fakeSocket.emit('connect');
    expect(infoSpy).toHaveBeenCalledTimes(1);
    expect(String(infoSpy.mock.calls[0][0])).toContain('[WS] Recovered');

    // After recovery, a fresh connect_error immediately produces a warn again
    // (the counter has been reset).
    fakeSocket.emit('connect_error', new Error('xhr post error'));
    expect(warnSpy).toHaveBeenCalledTimes(2);
  });
});
