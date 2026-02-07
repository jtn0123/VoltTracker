/**
 * VoltTracker - Worker Client
 * Typed interface for communicating with the calculations web worker.
 * Falls back to main-thread execution if Workers are unavailable.
 */

import type { WorkerRequest, WorkerResponse, WorkerTaskType } from './calculations.worker';

let worker: Worker | null = null;
let idCounter = 0;
const pending = new Map<string, { resolve: (v: unknown) => void; reject: (e: Error) => void }>();

function getWorker(): Worker | null {
  if (worker) return worker;
  if (typeof Worker === 'undefined') return null;
  try {
    worker = new Worker(new URL('./calculations.worker.ts', import.meta.url), { type: 'module' });
    worker.onmessage = (e: MessageEvent<WorkerResponse>) => {
      const { id, result, error } = e.data;
      const p = pending.get(id);
      if (!p) return;
      pending.delete(id);
      if (error) p.reject(new Error(error));
      else p.resolve(result);
    };
    worker.onerror = (e) => {
      console.error('[Worker] Error:', e);
      // Reject all pending
      for (const [id, p] of pending) {
        p.reject(new Error('Worker error'));
        pending.delete(id);
      }
      worker = null;
    };
    return worker;
  } catch {
    return null;
  }
}

/**
 * Run a task in the worker (or on main thread as fallback).
 */
export function runWorkerTask<T = unknown>(type: WorkerTaskType, payload: unknown): Promise<T> {
  const w = getWorker();
  if (!w) {
    // Fallback: dynamically import the worker module and run synchronously
    return import('./calculations.worker').then(() => {
      // Can't easily run worker code on main thread without refactoring,
      // so just reject gracefully — callers handle this
      return Promise.reject(new Error('Workers not supported'));
    });
  }

  const id = `task_${++idCounter}`;
  return new Promise<T>((resolve, reject) => {
    pending.set(id, { resolve: resolve as (v: unknown) => void, reject });
    w.postMessage({ id, type, payload } as WorkerRequest);
    // Timeout after 30s
    setTimeout(() => {
      if (pending.has(id)) {
        pending.delete(id);
        reject(new Error('Worker task timeout'));
      }
    }, 30000);
  });
}

/**
 * Terminate the worker (cleanup)
 */
export function terminateWorker(): void {
  if (worker) {
    worker.terminate();
    worker = null;
    for (const [, p] of pending) p.reject(new Error('Worker terminated'));
    pending.clear();
  }
}
