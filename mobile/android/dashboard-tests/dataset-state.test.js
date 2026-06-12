// dataset-state.ts — typed data-state / data-tone setters. The unions give
// compile-time checking at literal call sites; these tests pin the runtime
// half of the contract: values land on the element, unknown values warn (but
// still apply, so CSS keyed to a genuinely-new native state keeps working),
// and null elements are tolerated.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

describe('dataset-state typed setters', () => {
  // Re-import per test: the module keeps a warn-once cache keyed by attr:value,
  // and a shared instance would make the warning assertions order-dependent.
  let DATA_STATE_VALUES, DATA_TONE_VALUES, asDataState, asDataTone, setDataState, setDataTone;

  beforeEach(async () => {
    vi.resetModules();
    ({ DATA_STATE_VALUES, DATA_TONE_VALUES, asDataState, asDataTone, setDataState, setDataTone } =
      await import('../app/src/main/dashboard-src/js/dataset-state.ts'));
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('applies known values without warning', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const el = document.createElement('span');
    expect(setDataState(el, 'connected')).toBe(true);
    expect(el.dataset.state).toBe('connected');
    expect(setDataTone(el, 'warn')).toBe(true);
    expect(el.dataset.tone).toBe('warn');
    expect(warn).not.toHaveBeenCalled();
  });

  it('warns on an unexpected value but still applies it (CSS-forward behavior)', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const el = document.createElement('span');
    setDataState(el, asDataState('totally-new-native-state'));
    expect(el.dataset.state).toBe('totally-new-native-state');
    setDataTone(el, asDataTone('wran'));
    expect(el.dataset.tone).toBe('wran');
    expect(warn).toHaveBeenCalledTimes(2);
    expect(String(warn.mock.calls[0][0])).toContain('unexpected data-state');
    expect(String(warn.mock.calls[1][0])).toContain('unexpected data-tone');
  });

  it('warns only once per attr:value pair across repeated renders', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const el = document.createElement('span');
    setDataState(el, asDataState('repeat-unknown'));
    setDataState(el, asDataState('repeat-unknown'));
    setDataState(document.createElement('span'), asDataState('repeat-unknown'));
    expect(warn).toHaveBeenCalledTimes(1);
    // Same value on the OTHER attribute is a distinct pair: warns once more.
    setDataTone(el, asDataTone('repeat-unknown'));
    setDataTone(el, asDataTone('repeat-unknown'));
    expect(warn).toHaveBeenCalledTimes(2);
    // The value still lands on the element every time.
    expect(el.dataset.state).toBe('repeat-unknown');
    expect(el.dataset.tone).toBe('repeat-unknown');
  });

  it('tolerates a missing element', () => {
    expect(setDataState(null, 'idle')).toBe(false);
    expect(setDataTone(null, 'idle')).toBe(false);
  });

  it('keeps the published unions free of duplicates', () => {
    expect(new Set(DATA_STATE_VALUES).size).toBe(DATA_STATE_VALUES.length);
    expect(new Set(DATA_TONE_VALUES).size).toBe(DATA_TONE_VALUES.length);
  });

  it('asDataState / asDataTone normalize nullish input to an empty string', () => {
    expect(asDataState(null)).toBe('');
    expect(asDataTone(undefined)).toBe('');
  });
});
