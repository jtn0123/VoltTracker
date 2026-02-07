/**
 * Test setup — mock browser APIs not available in jsdom
 */
import { vi } from 'vitest';

// Mock indexedDB for APICache
const mockStore = {
  get: vi.fn(() => {
    const req: any = { result: undefined };
    setTimeout(() => req.onsuccess?.(), 0);
    return req;
  }),
  put: vi.fn(),
  clear: vi.fn(),
  createIndex: vi.fn(),
};

const mockTransaction = { objectStore: () => mockStore };

const mockDB = {
  transaction: () => mockTransaction,
  objectStoreNames: { contains: () => true },
  createObjectStore: () => mockStore,
};

if (typeof globalThis.indexedDB === 'undefined') {
  (globalThis as any).indexedDB = {
    open: vi.fn(() => {
      const req: any = {};
      setTimeout(() => {
        req.result = mockDB;
        req.onsuccess?.();
      }, 0);
      return req;
    }),
  };
}

// Mock toast globals
(globalThis as any).showToast = vi.fn();
(globalThis as any).showInfo = vi.fn();
(globalThis as any).showWarning = vi.fn();
(globalThis as any).showError = vi.fn();
(globalThis as any).showSuccess = vi.fn();
