/**
 * VoltTracker - Centralized API Module
 * Wraps fetchJson with error handling, status-specific responses, and typed results
 */

import { DEBUG, fetchJson } from '@/core';
import type { z } from 'zod';
import { validateResponse } from '@/types/schemas';

/** Discriminated union for API results */
export type ApiResult<T> = { data: T; error?: never } | { data?: never; error: string };

interface ApiOptions extends RequestInit {
  useCache?: boolean;
  maxAge?: number;
  /** Max retries for 429 rate limiting (default: 2) */
  maxRetries?: number;
  /** Timeout in ms (default: 10000) */
  timeoutMs?: number;
  /** Suppress toast on error */
  silent?: boolean;
  /** Zod schema for runtime validation (warns on failure, doesn't break) */
  schema?: z.ZodType<any>;
}

/**
 * Centralized API wrapper around fetchJson.
 * Handles:
 *  - 401 → redirect to login
 *  - 429 → retry with backoff
 *  - 500 → user-friendly toast
 *  - Network errors → toast
 * Returns typed ApiResult<T> instead of throwing.
 */
export async function api<T>(url: string, options: ApiOptions = {}): Promise<ApiResult<T>> {
  const { maxRetries = 2, timeoutMs = 10000, silent = false, schema, ...fetchOpts } = options;

  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    try {
      const data = await fetchJson<T>(url, fetchOpts, timeoutMs);
      if (schema) {
        validateResponse<T>(schema, data, url);
      }
      return { data } as ApiResult<T>;
    } catch (err) {
      const message = (err as Error).message || 'Unknown error';

      // Parse HTTP status from our fetchJson error format "HTTP NNN: ..."
      const statusMatch = message.match(/^HTTP (\d+)/);
      const status = statusMatch ? parseInt(statusMatch[1], 10) : 0;

      if (status === 401) {
        if (!silent) showToast('Authentication required. Please log in.', 'warning', 3000);
        // App uses HTTP Basic Auth — reload to re-prompt browser auth dialog
        setTimeout(() => { window.location.reload(); }, 1500);
        return { error: 'Unauthorized' };
      }

      if (status === 429 && attempt < maxRetries) {
        const delay = Math.pow(2, attempt) * 1000;
        if (DEBUG) console.warn(`[API] Rate limited, retrying in ${delay}ms (attempt ${attempt + 1})`);
        await new Promise(r => setTimeout(r, delay));
        continue;
      }

      if (status === 429) {
        if (!silent) showToast('Too many requests. Please wait a moment.', 'warning', 5000);
        return { error: 'Rate limited' };
      }

      if (status >= 500) {
        if (!silent) showToast('Server error. Please try again later.', 'error', 5000);
        return { error: message };
      }

      // Network / timeout errors
      if (message.includes('timeout') || message.includes('AbortError')) {
        if (!silent) showToast('Request timed out. Check your connection.', 'warning', 4000);
        return { error: message };
      }

      // Generic network failure
      if (!silent) showToast('Network error. Please check your connection.', 'error', 4000);
      return { error: message };
    }
  }

  // Should not reach here, but just in case
  return { error: 'Request failed after retries' };
}
