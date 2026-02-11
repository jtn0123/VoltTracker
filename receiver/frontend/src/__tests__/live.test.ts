/**
 * Tests for live.ts module
 * T22: Frontend test coverage for live telemetry
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

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
