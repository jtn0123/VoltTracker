// telemetry.ts derives LIVE_TILE_IDS from `[data-live-tile="true"]` in the
// DOM at boot. This test pins the source-of-truth: the count must match.
// A new live tile in a partial without the attribute will fail here (and the
// stale indicator wouldn't bind to it in prod either).
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { loadDashboard } from './setup/load-dashboard.js';

const HERE = dirname(fileURLToPath(import.meta.url));
function sourceFor(name) {
  const ts = resolve(HERE, `../app/src/main/dashboard-src/js/${name}.ts`);
  if (existsSync(ts)) return ts;
  throw new Error(`Could not locate dashboard source ${name}.ts`);
}

const TELEMETRY_SOURCE = sourceFor('telemetry');

describe('LIVE_TILE_IDS is DOM-derived', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  it('telemetry.ts no longer pins a static LIVE_TILE_IDS array', () => {
    // Guard against accidental re-introduction of the hardcoded array. The previous
    // version of this check only matched if the array contained "speedValue", so a
    // refactor that swapped to a different ID set would slip through. Match ANY
    // literal-array assignment to LIVE_TILE_IDS.
    const source = readFileSync(TELEMETRY_SOURCE, 'utf8');
    const hardcoded = /\b(?:const|let|var)\s+LIVE_TILE_IDS\s*=\s*\[/.test(source);
    expect(
      hardcoded,
      'LIVE_TILE_IDS appears to be hardcoded again — derive it from [data-live-tile="true"]',
    ).toBe(false);
  });

  it('every element with data-live-tile="true" carries a non-empty id', () => {
    const elements = Array.from(
      document.querySelectorAll('[data-live-tile="true"]'),
    );
    expect(elements.length).toBeGreaterThan(0);
    for (const el of elements) {
      expect(
        el.id,
        'data-live-tile element is missing an id (would be skipped by the stale loop)',
      ).toBeTruthy();
    }
  });

  it('every DOM tile gets the .stale class once the stale loop fires', async () => {
    // Drive the stale tick with fake timers so we can prove the derived list and
    // the DOM nodes the loop touches are the same set. If telemetry.ts's derived
    // LIVE_TILE_IDS didn't match the data-live-tile elements, some tiles would
    // miss the .stale class.
    vi.useFakeTimers();
    try {
      document.body.innerHTML = '';
      delete window.VoltDashboard;
      delete window.VoltTrackerNative;
      delete window.VoltTrackerAndroid;
      await loadDashboard();
      const tiles = Array.from(document.querySelectorAll('[data-live-tile="true"]'));
      expect(tiles.length).toBeGreaterThan(0);
      // STALE_THRESHOLD_MS = 3000 plus the 1 Hz interval — 4s past boot is well past
      // both clocks and lastSampleAt is 0, so every tile must be stale.
      vi.advanceTimersByTime(4000);
      for (const el of tiles) {
        expect(
          el.classList.contains('stale'),
          `#${el.id} should be stale after the loop fires`,
        ).toBe(true);
      }
    } finally {
      vi.useRealTimers();
    }
  });

  afterEach(() => {
    vi.useRealTimers();
  });
});
