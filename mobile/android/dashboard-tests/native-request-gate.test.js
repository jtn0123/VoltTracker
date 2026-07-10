import { afterEach, describe, expect, it, vi } from 'vitest';

import { createNativeRequestGate, NATIVE_REQUEST_TIMEOUT_MS } from '../app/src/main/dashboard-src/js/native-request-gate.ts';

describe('native request gate', () => {
  afterEach(() => vi.useRealTimers());

  it('releases a lost callback and resets the timeout when a request is replaced', () => {
    vi.useFakeTimers();
    const onTimeout = vi.fn();
    const gate = createNativeRequestGate(onTimeout);

    gate.begin();
    vi.advanceTimersByTime(NATIVE_REQUEST_TIMEOUT_MS - 1);
    expect(gate.pending).toBe(true);
    expect(onTimeout).not.toHaveBeenCalled();

    gate.begin();
    vi.advanceTimersByTime(NATIVE_REQUEST_TIMEOUT_MS - 1);
    expect(onTimeout).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);

    expect(gate.pending).toBe(false);
    expect(onTimeout).toHaveBeenCalledTimes(1);
  });

  it('cancels the watchdog when the native callback arrives', () => {
    vi.useFakeTimers();
    const onTimeout = vi.fn();
    const gate = createNativeRequestGate(onTimeout);

    gate.begin();
    gate.complete();
    vi.advanceTimersByTime(NATIVE_REQUEST_TIMEOUT_MS * 2);

    expect(gate.pending).toBe(false);
    expect(onTimeout).not.toHaveBeenCalled();
  });
});
