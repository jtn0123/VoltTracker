/**
 * Tests for api.ts centralized error handling wrapper
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

const { api } = await import('../api');

describe('api wrapper', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn());
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('returns data on success', async () => {
    (fetch as any).mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ items: [1, 2, 3] }),
    });

    const result = await api<{ items: number[] }>('/api/test');
    expect(result.data).toEqual({ items: [1, 2, 3] });
    expect(result.error).toBeUndefined();
  });

  it('handles 401 with redirect', async () => {
    (fetch as any).mockResolvedValue({
      ok: false,
      status: 401,
      text: () => Promise.resolve('Unauthorized'),
    });

    const result = await api('/api/test');
    expect(result.error).toBe('Unauthorized');
    expect(showToast).toHaveBeenCalledWith(expect.stringContaining('Authentication required'), 'warning', 3000);
  });

  it('handles 500 with error toast', async () => {
    (fetch as any).mockResolvedValue({
      ok: false,
      status: 500,
      text: () => Promise.resolve('Internal Server Error'),
    });

    const result = await api('/api/test');
    expect(result.error).toContain('HTTP 500');
    expect(showToast).toHaveBeenCalledWith(expect.stringContaining('Server error'), 'error', 5000);
  });

  it('suppresses toast when silent', async () => {
    (fetch as any).mockResolvedValue({
      ok: false,
      status: 500,
      text: () => Promise.resolve('Error'),
    });

    await api('/api/test', { silent: true });
    expect(showToast).not.toHaveBeenCalled();
  });

  it('handles network errors', async () => {
    (fetch as any).mockRejectedValue(new TypeError('Failed to fetch'));

    const result = await api('/api/test');
    expect(result.error).toBe('Failed to fetch');
    expect(showToast).toHaveBeenCalled();
  });
});
