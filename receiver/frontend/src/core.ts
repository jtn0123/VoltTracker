/**
 * VoltTracker - Core Module
 * Utilities, caching, and shared helpers.
 * State is now managed by the reactive store (@/store).
 */

// Re-export state and store from the reactive store module
export { state, store } from '@/store';
export type { AppState } from '@/store';

// Debug mode
export const DEBUG = new URLSearchParams(window.location.search).has('debug');

/**
 * HTML Escaping Helper
 */
export function escapeHtml(str: unknown): string {
  if (str === null || str === undefined) return '';
  const div = document.createElement('div');
  div.textContent = String(str);
  return div.innerHTML;
}

/**
 * Sanitize a number for safe HTML insertion
 */
export function sanitizeNumber(value: unknown, decimals = 1): string {
  if (value === null || value === undefined || isNaN(value as number)) {
    return '--';
  }
  return Number(value).toFixed(decimals);
}

/**
 * Sanitize and format a date for safe HTML insertion
 */
export function sanitizeDate(dateValue: unknown): string {
  if (!dateValue) return '--';
  try {
    const date = new Date(dateValue as string);
    if (isNaN(date.getTime())) return '--';
    return escapeHtml(formatDateTime(date));
  } catch {
    return '--';
  }
}

interface CacheEntry {
  url: string;
  data: unknown;
  timestamp: number;
  maxAge: number;
}

/**
 * IndexedDB API Cache for client-side caching
 */
export class APICache {
  private readonly dbName = 'volttracker-cache';
  private readonly storeName = 'api-responses';
  private db: IDBDatabase | null = null;
  private readonly initPromise: Promise<void>;

  constructor() {
    this.initPromise = this.init();
  }

  private async init(): Promise<void> {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open(this.dbName, 1);
      request.onerror = () => reject(new Error(String(request.error)));
      request.onsuccess = () => {
        this.db = request.result;
        if (DEBUG) console.log('[Cache] IndexedDB initialized');
        resolve();
      };
      request.onupgradeneeded = (event) => {
        const db = (event.target as IDBOpenDBRequest).result;
        if (!db.objectStoreNames.contains(this.storeName)) {
          const store = db.createObjectStore(this.storeName, { keyPath: 'url' });
          store.createIndex('timestamp', 'timestamp', { unique: false });
        }
      };
    });
  }

  async get(url: string, maxAge = 300000): Promise<unknown | null> {
    await this.initPromise;
    return new Promise((resolve) => {
      const tx = this.db!.transaction([this.storeName], 'readonly');
      const store = tx.objectStore(this.storeName);
      const request = store.get(url);

      request.onsuccess = () => {
        const cached = request.result as CacheEntry | undefined;
        if (cached && Date.now() - cached.timestamp < maxAge) {
          if (DEBUG) console.log(`[Cache] Hit: ${url}`);
          resolve(cached.data);
        } else {
          if (DEBUG) console.log(`[Cache] Miss/Expired: ${url}`);
          resolve(null);
        }
      };
      request.onerror = () => resolve(null);
    });
  }

  async set(url: string, data: unknown, maxAge = 300000): Promise<void> {
    await this.initPromise;
    const tx = this.db!.transaction([this.storeName], 'readwrite');
    const store = tx.objectStore(this.storeName);
    store.put({ url, data, timestamp: Date.now(), maxAge } as CacheEntry);
    if (DEBUG) console.log(`[Cache] Set: ${url}`);
  }

  async clear(): Promise<void> {
    await this.initPromise;
    const tx = this.db!.transaction([this.storeName], 'readwrite');
    const store = tx.objectStore(this.storeName);
    store.clear();
    if (DEBUG) console.log('[Cache] Cleared');
  }
}

// Initialize API cache
export const apiCache = new APICache();

interface FetchOptions extends RequestInit {
  useCache?: boolean;
  maxAge?: number;
}

/**
 * Fetch helper with timeout and JSON validation.
 */
export async function fetchJson<T = unknown>(url: string, options: FetchOptions = {}, timeoutMs = 10000): Promise<T> {
  const { useCache = false, maxAge = 300000, ...fetchOpts } = options;

  if (useCache) {
    const cached = await apiCache.get(url, maxAge);
    if (cached) {
      // Background revalidation
      fetch(url)
        .then((res) => res.json())
        .then((data) => apiCache.set(url, data, maxAge))
        .catch((err) => {
          if (DEBUG) console.error('[Cache] Background revalidation failed:', err);
        });
      return cached as T;
    }
  }

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);

  try {
    const response = await fetch(url, {
      ...fetchOpts,
      signal: controller.signal,
    });

    if (!response.ok) {
      const errorText = await response.text().catch(() => 'Unknown error');
      throw new Error(`HTTP ${response.status}: ${errorText}`);
    }

    const data = await response.json();

    if (useCache) {
      await apiCache.set(url, data, maxAge);
    }

    return data as T;
  } catch (error) {
    if ((error as Error).name === 'AbortError') {
      throw new Error(`Request timeout after ${timeoutMs}ms`);
    }
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}

/**
 * Format date for display
 */
export function formatDate(date: Date): string {
  const now = new Date();
  const options: Intl.DateTimeFormatOptions = { month: 'short', day: 'numeric' };
  if (date.getFullYear() !== now.getFullYear()) {
    options.year = '2-digit';
  }
  return date.toLocaleDateString('en-US', options);
}

/**
 * Format date for chart labels
 */
export function formatChartDate(date: Date): string {
  return date.toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
    year: '2-digit',
  });
}

/**
 * Format date and time for display
 */
export function formatDateTime(date: Date): string {
  const now = new Date();
  const options: Intl.DateTimeFormatOptions = {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  };
  if (date.getFullYear() !== now.getFullYear()) {
    options.year = '2-digit';
  }
  return date.toLocaleDateString('en-US', options);
}

/**
 * Format time only
 */
export function formatTime(date: Date): string {
  return date.toLocaleTimeString('en-US', {
    hour: 'numeric',
    minute: '2-digit',
  });
}

/**
 * Format duration between two dates
 */
export function formatDuration(start: Date, end: Date): string {
  const minutes = Math.round((end.getTime() - start.getTime()) / 60000);
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(minutes / 60);
  const mins = minutes % 60;
  return mins > 0 ? `${hours}h ${mins}m` : `${hours}h`;
}

/**
 * Calculate elapsed time from a start timestamp
 */
export function getElapsedTime(startTime: string): string {
  const start = new Date(startTime);
  const now = new Date();
  const diffMs = now.getTime() - start.getTime();
  const diffMins = Math.floor(diffMs / 60000);
  const diffHours = Math.floor(diffMins / 60);

  if (diffHours > 0) {
    return `${diffHours}h ${diffMins % 60}m`;
  }
  return `${diffMins}m`;
}
