// D2 — execute the SHIPPED artifacts, not just the TypeScript sources.
//
// Every other suite loads the dashboard through Node's module loader against
// dashboard-src/*.ts, so the built assets/dashboard/js/ output (what the APK
// actually ships) was never executed by any test. That gap is exactly where
// the C1 defect class lives: each lazy chunk is an INDEPENDENT esbuild bundle,
// so a stateful module imported by a chunk ships as a duplicate copy whose
// side effects re-run when the chunk loads — invisible to the source harness,
// which deduplicates modules per import graph.
//
// This spec boots the real minified app.js in jsdom against the generated
// index.html DOM, asserts the window.VoltDashboard ABI surface, then loads the
// real insights-panel.js chunk into the same window and proves the prefs boot
// did NOT re-run (single bridge commit per preference edit). Static grep pins
// on the built chunks back it up for C1 and C2.
//
// The build output is required: run `npm run build` first (CI's test jobs and
// `./gradlew :app:assembleDebug` both do). If app.js is missing, the build is
// run once here so a fresh checkout's `npm test` still works.
import { execSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';

import { installCanvasShim, installScrollShim } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const JS_DIR = resolve(HERE, '../app/src/main/assets/dashboard/js');
const INDEX_HTML = resolve(HERE, '../app/src/main/assets/dashboard/index.html');

function builtFile(name) {
  return readFileSync(resolve(JS_DIR, name), 'utf8');
}

// Execute a built classic-IIFE bundle in the jsdom window, exactly as a
// <script> tag would: indirect eval runs the code in the global scope, where
// vitest's jsdom environment exposes window/document.
function executeBundle(name) {
  // eslint-disable-next-line no-eval
  (0, eval)(builtFile(name));
}

// The boot path registers recurring timers (stale-indicator poll etc.); track
// them so this file cannot leak intervals past its own teardown.
const ownedIntervals = [];
const ownedTimeouts = [];

beforeAll(() => {
  if (!existsSync(resolve(JS_DIR, 'app.js'))) {
    execSync('node build.mjs', { cwd: HERE, stdio: 'pipe' });
  }
});

afterAll(() => {
  for (const id of ownedIntervals.splice(0)) clearInterval(id);
  for (const id of ownedTimeouts.splice(0)) clearTimeout(id);
});

describe('built dashboard bundle (assets/dashboard/js)', () => {
  it('boots app.js against the generated index.html and a lazy chunk cannot re-run the prefs boot', async () => {
    // Mount the REAL generated DOM (not the source-harness subset).
    const html = readFileSync(INDEX_HTML, 'utf8');
    const body = html.match(/<body[^>]*>([\s\S]*?)<\/body>/i);
    expect(body, 'generated index.html must have a <body>').not.toBeNull();
    document.body.innerHTML = body[1];
    installCanvasShim();
    installScrollShim();
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltDashboardActionModules;
    delete document.__voltPrefsUiBooted;
    window.localStorage.clear();

    const bridge = createVoltBridgeFixture();
    window.VoltTrackerAndroid = bridge;
    // Lazy loads inside the built world = evaluating the built chunk files.
    window.__VoltDashboardLoadScript = async (src) => {
      if (src === 'lib/leaflet/leaflet.js') return;
      executeBundle(String(src || '').replace(/^js\//, ''));
    };

    const nativeSetInterval = window.setInterval.bind(window);
    const nativeSetTimeout = window.setTimeout.bind(window);
    window.setInterval = (...args) => {
      const id = nativeSetInterval(...args);
      ownedIntervals.push(id);
      return id;
    };
    window.setTimeout = (...args) => {
      const id = nativeSetTimeout(...args);
      ownedTimeouts.push(id);
      return id;
    };
    try {
      executeBundle('app.js');
    } finally {
      window.setInterval = nativeSetInterval;
      window.setTimeout = nativeSetTimeout;
    }

    // (2) The eager bundle exposed the expected ABI surface.
    const VD = window.VoltDashboard;
    expect(VD).toBeDefined();
    expect(VD.state).toBeTypeOf('object');
    for (const fn of [
      'setStatus',
      'updateTelemetry',
      'setStorage',
      'setAppState',
      'setDevices',
      'setHistory',
      'setView',
      'pendingLazyLoads',
      'confirmAppDialog',
      'promptAppDialog',
      'choiceAppDialog',
    ]) {
      expect(VD[fn], `VoltDashboard.${fn} missing from the built bundle`).toBeTypeOf('function');
    }
    expect(VD.actions).toBeTypeOf('object');
    expect(VD.prefs).toBeTypeOf('object');
    expect(VD.units).toBeTypeOf('object');
    // The native callback surface actions.ts assembles for Kotlin.
    expect(window.VoltTrackerNative).toBeTypeOf('object');
    expect(window.VoltTrackerNative.setStatus).toBeTypeOf('function');
    // The prefs UI booted exactly once and stamped its per-document guard.
    expect(document.__voltPrefsUiBooted).toBe(true);

    // (3) Load the REAL built insights-panel.js chunk into the same window —
    // the C1 regression net. A stateful prefs copy bundled into the chunk
    // would re-run bootPrefsUi here (second change listener on every numeric
    // preference input -> double bridge commits).
    bridge.setChargeTargetSoc = vi.fn();
    executeBundle('insights-panel.js');
    expect(document.__voltPrefsUiBooted).toBe(true);
    // The chunk registered its cross-chunk entry points on the registry.
    expect(VD.loadTrips).toBeTypeOf('function');
    expect(VD.loadInsights).toBeTypeOf('function');

    const input = document.getElementById('prefChargeTargetInput');
    expect(input, '#prefChargeTargetInput missing from generated index.html').not.toBeNull();
    input.value = '80';
    input.dispatchEvent(new window.Event('change', { bubbles: true }));
    expect(bridge.setChargeTargetSoc).toHaveBeenCalledTimes(1);
    expect(bridge.setChargeTargetSoc).toHaveBeenCalledWith(80);
  });

  it('C1 pin: the built insights-panel.js chunk carries no prefs boot code', () => {
    const chunk = builtFile('insights-panel.js');
    // Markers unique to prefs.ts's boot path (numeric pref bindings, the
    // Settings scroll-spy section reset, the once-guard flag). Any of these
    // appearing means a stateful prefs copy was re-bundled into the chunk.
    for (const marker of ['gasMpgInput', 'settingsConnection', '__voltPrefsUiBooted']) {
      expect(chunk, `insights-panel.js must not bundle prefs boot code (found "${marker}")`).not.toContain(marker);
    }
  });

  it('C2 pin: the built lazy action chunks carry no app-dialog controller copy', () => {
    // app-dialog's node lookups survive minification; the eager bundle is the
    // only artifact allowed to carry them.
    for (const name of ['actions-storage.js', 'actions-signals.js']) {
      expect(builtFile(name), `${name} must reach the dialog via the VD registry`).not.toContain('appDialogTitle');
    }
    expect(builtFile('app.js')).toContain('appDialogTitle');
  });
});
