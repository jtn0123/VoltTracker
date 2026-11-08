// The harness owns every timer the dashboard schedules during a test, not only the
// ones it starts while booting. Committing a numeric preference debounces a units
// re-render by 400 ms (prefs.ts rerenderForUnits). When that timer outlived its test,
// it fired into whatever ran next, or after vitest had torn the jsdom window down,
// which failed main's CI on 2026-11-08 with "ReferenceError: window is not defined"
// even though every test passed.
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { setTimeout as nodeSetTimeout } from 'node:timers';
import { fileURLToPath } from 'node:url';

import { describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const PREFS_SRC = resolve(HERE, '../app/src/main/dashboard-src/js/prefs.ts');

// Read the debounce off the source so the wait below can't silently fall short of it.
const RERENDER_DEBOUNCE_MS = Number(
  /setTimeout\(rerenderForUnits,\s*(\d+)\)/.exec(readFileSync(PREFS_SRC, 'utf8'))?.[1],
);

const sleep = (ms) => new Promise((done) => nodeSetTimeout(done, ms));

function commitChargeTarget(value) {
  const input = document.getElementById('prefChargeTargetInput');
  expect(input).not.toBeNull();
  input.value = String(value);
  input.dispatchEvent(new window.Event('change', { bubbles: true }));
}

describe('dashboard timers end with their test', () => {
  let rerenders = 0;

  it('a preference commit schedules the units re-render', async () => {
    expect(RERENDER_DEBOUNCE_MS).toBeGreaterThan(0);
    await loadDashboard();
    const VD = window.VoltDashboard;
    // Only the debounced rerenderForUnits calls updateStorageUi after a commit (the
    // commit itself refreshes the live tiles synchronously), and it reads the hook at
    // call time, so this counts the re-render's runs.
    VD.updateStorageUi = () => {
      rerenders += 1;
    };
    commitChargeTarget(85);
    // End the test inside the debounce window: the re-render is still pending.
    expect(rerenders).toBe(0);
  });

  it('the previous test\'s pending re-render never fires into this one', async () => {
    await sleep(RERENDER_DEBOUNCE_MS + 150);
    expect(rerenders).toBe(0);
  });

  it('a test that settles its own pending re-render still sees it run', async () => {
    await loadDashboard();
    const VD = window.VoltDashboard;
    let ran = 0;
    VD.updateStorageUi = () => {
      ran += 1;
    };
    commitChargeTarget(90);
    await sleep(RERENDER_DEBOUNCE_MS + 150);
    expect(ran).toBe(1);
  });

  it('fake timers installed after the load still drive dashboard timers', async () => {
    await loadDashboard();
    const VD = window.VoltDashboard;
    let ran = 0;
    VD.updateStorageUi = () => {
      ran += 1;
    };
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    try {
      commitChargeTarget(95);
      expect(ran).toBe(0);
      vi.advanceTimersByTime(RERENDER_DEBOUNCE_MS);
      expect(ran).toBe(1);
    } finally {
      vi.useRealTimers();
    }
  });
});
