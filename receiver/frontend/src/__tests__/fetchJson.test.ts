/**
 * Tests for fetchJson from core.ts
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

const { fetchJson } = await import('../core');

describe('fetchJson', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn());
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('returns parsed JSON on success', async () => {
    const mockData = { status: 'ok', count: 42 };
    (fetch as any).mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(mockData),
    });

    const result = await fetchJson('/api/test');
    expect(result).toEqual(mockData);
    expect(fetch).toHaveBeenCalledWith('/api/test', expect.objectContaining({
      signal: expect.any(AbortSignal),
    }));
  });

  it('throws on non-ok response', async () => {
    (fetch as any).mockResolvedValue({
      ok: false,
      status: 500,
      text: () => Promise.resolve('Internal Server Error'),
    });

    await expect(fetchJson('/api/test')).rejects.toThrow('HTTP 500: Internal Server Error');
  });

  it('wraps AbortError as timeout', async () => {
    const abortError = new Error('The operation was aborted');
    abortError.name = 'AbortError';
    (fetch as any).mockRejectedValue(abortError);

    await expect(fetchJson('/api/test', {}, 5000)).rejects.toThrow('Request timeout after 5000ms');
  });

  it('passes fetch options through', async () => {
    (fetch as any).mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({}),
    });

    await fetchJson('/api/test', { method: 'POST', headers: { 'X-Custom': 'yes' } });
    expect(fetch).toHaveBeenCalledWith('/api/test', expect.objectContaining({
      method: 'POST',
      headers: { 'X-Custom': 'yes' },
    }));
  });

  it('throws on network error', async () => {
    (fetch as any).mockRejectedValue(new TypeError('Failed to fetch'));

    await expect(fetchJson('/api/test')).rejects.toThrow('Failed to fetch');
  });
});
