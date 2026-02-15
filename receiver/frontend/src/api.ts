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
/** Parse HTTP status code from fetchJson error message format "HTTP NNN: ..." */
function parseHttpStatus(message: string): number {
  const match = /^HTTP (\d+)/.exec(message);
  return match ? Number.parseInt(match[1], 10) : 0;
}

/** Handle a specific HTTP error status, returning an ApiResult or null to continue */
function handleHttpError<T>(status: number, message: string, silent: boolean): ApiResult<T> | null {
  if (status === 401) {
    if (!silent) showToast('Authentication required. Please log in.', 'warning', 3000);
    setTimeout(() => { globalThis.location.reload(); }, 1500);
    return { error: 'Unauthorized' };
  }

  if (status === 429) {
    if (!silent) showToast('Too many requests. Please wait a moment.', 'warning', 5000);
    return { error: 'Rate limited' };
  }

  if (status >= 500) {
    if (!silent) showToast('Server error. Please try again later.', 'error', 5000);
    return { error: message };
  }

  return null;
}

/** Handle network/timeout errors */
function handleNetworkError<T>(message: string, silent: boolean): ApiResult<T> {
  if (message.includes('timeout') || message.includes('AbortError')) {
    if (!silent) showToast('Request timed out. Check your connection.', 'warning', 4000);
    return { error: message };
  }

  if (!silent) showToast('Network error. Please check your connection.', 'error', 4000);
  return { error: message };
}

/** Wait with exponential backoff before retrying */
function backoffDelay(attempt: number): Promise<void> {
  const delay = Math.pow(2, attempt) * 1000;
  if (DEBUG) console.warn(`[API] Rate limited, retrying in ${delay}ms (attempt ${attempt + 1})`);
  return new Promise(r => setTimeout(r, delay));
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
      const status = parseHttpStatus(message);

      if (status === 429 && attempt < maxRetries) {
        await backoffDelay(attempt);
        continue;
      }

      const httpResult = handleHttpError<T>(status, message, silent);
      if (httpResult) return httpResult;

      return handleNetworkError<T>(message, silent);
    }
  }

  return { error: 'Request failed after retries' };
}
