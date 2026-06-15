// State-seam discipline guard (grade-report item C2).
//
// The shared `state` bag is mutated from every module, but a few fields carry a CROSS-MODULE
// invariant and must not be written ad-hoc from anywhere:
//
//   - `demoActive` — demo isolation: while a demo is active, native storage/app-state pushes must
//     NOT overwrite the synthetic preview. It is written ONLY through setState()/setDemoActive() in
//     core.ts. This guard fails if a direct `state.demoActive = …` write creeps into any other
//     module, which would reopen the demo-vs-live clobber bug class.
//   - `liveRoutePoints` / `liveRouteStartedAtMs` — the live GPS buffer is owned by map.ts, which
//     holds a module-local reference to it; cross-module seeding goes through VD.setLiveRoutePoints
//     (see telemetry.ts hydration, report item C1). Writes are therefore allowed ONLY in map.ts
//     (the owner) and in the demo/teardown reset fallbacks. A stray reassignment elsewhere would
//     desync map.ts's reference from `state` and silently stop the route rendering.
//
// (selectedMapSessionId is a free scalar — read/written directly — and intentionally NOT guarded.)
import { readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

const JS_DIR = resolve('../app/src/main/dashboard-src/js');

function sourceFiles() {
  return readdirSync(JS_DIR)
    .filter((name) => name.endsWith('.ts'))
    .map((name) => ({ name, text: readFileSync(resolve(JS_DIR, name), 'utf8') }));
}

function directWrites(text, key) {
  // `state.<key> =` but not `==`/`===` comparisons.
  const re = new RegExp(`\\bstate\\.${key}\\s*=(?!=)`, 'g');
  return (text.match(re) || []).length;
}

describe('state-seam discipline', () => {
  it('demoActive is written only through the core.ts seam (setState/setDemoActive)', () => {
    const offenders = sourceFiles()
      .filter((f) => f.name !== 'core.ts')
      .filter((f) => directWrites(f.text, 'demoActive') > 0)
      .map((f) => f.name);
    expect(
      offenders,
      `demoActive must go through setState()/setDemoActive(); direct writes found in: ${offenders.join(', ')}`,
    ).toEqual([]);
  });

  it('the live-route buffer is written only by its owner module (map.ts) and reset fallbacks', () => {
    const allowed = new Set(['map.ts', 'telemetry.ts', 'actions.ts', 'actions-demo.ts']);
    for (const key of ['liveRoutePoints', 'liveRouteStartedAtMs']) {
      const offenders = sourceFiles()
        .filter((f) => !allowed.has(f.name))
        .filter((f) => directWrites(f.text, key) > 0)
        .map((f) => f.name);
      expect(
        offenders,
        `${key} writes are confined to map.ts + reset fallbacks; unexpected writes in: ${offenders.join(', ')}`,
      ).toEqual([]);
    }
  });
});
