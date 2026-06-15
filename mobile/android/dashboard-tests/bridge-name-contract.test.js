// Native-callback name contract (Kotlin ⇄ JS).
//
// The Android side pushes data to the WebView by name through
// DashboardPublisher.ALLOWED_FUNCTIONS (an allowlist gate — a name not in the set is dropped before
// it ever reaches evaluateJavascript). The dashboard implements those names on
// window.VoltTrackerNative (the object at the bottom of actions.ts). voltbridge-abi.test.js pins the
// JS side, and DashboardPublisherTest pins the Kotlin side — but independently, so adding a name on
// one side without the other silently no-ops on device. This test reconciles them: it reads the
// Kotlin allowlist at run time and asserts it equals the real JS runtime surface. (Report item D3.)
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

const KOTLIN_ROOT = resolve('../app/src/main/kotlin/com/volttracker/obdpoc');

function readNativeSource(relPath) {
  const candidate = resolve(KOTLIN_ROOT, relPath);
  if (!existsSync(candidate)) {
    throw new Error(`bridge-name-contract: could not locate native source for ${relPath}`);
  }
  return readFileSync(candidate, 'utf8');
}

// Extract the string literals inside DashboardPublisher's `ALLOWED_FUNCTIONS … setOf( … )` block.
// Brace/paren-matches from the setOf( so a reformat or added entry is picked up automatically; a
// rename of the symbol fails loudly here instead of passing vacuously.
function kotlinAllowedFunctions() {
  const src = readNativeSource('DashboardPublisher.kt');
  const anchor = src.indexOf('ALLOWED_FUNCTIONS');
  if (anchor === -1) {
    throw new Error('bridge-name-contract: ALLOWED_FUNCTIONS not found in DashboardPublisher.kt');
  }
  const open = src.indexOf('setOf(', anchor);
  if (open === -1) throw new Error('bridge-name-contract: setOf( not found after ALLOWED_FUNCTIONS');
  let depth = 0;
  let end = -1;
  for (let i = src.indexOf('(', open); i < src.length; i += 1) {
    if (src[i] === '(') depth += 1;
    else if (src[i] === ')') {
      depth -= 1;
      if (depth === 0) {
        end = i;
        break;
      }
    }
  }
  if (end === -1) throw new Error('bridge-name-contract: unbalanced parens in ALLOWED_FUNCTIONS');
  const body = src.slice(open, end);
  return new Set([...body.matchAll(/"([A-Za-z0-9_]+)"/g)].map((m) => m[1]));
}

describe('native-callback name contract (DashboardPublisher.ALLOWED_FUNCTIONS ⇄ VoltTrackerNative)', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  it('the Kotlin allowlist exactly equals the JS native-callback surface', () => {
    const kotlin = [...kotlinAllowedFunctions()].sort();
    const js = Object.keys(window.VoltTrackerNative).sort();
    expect(kotlin).toEqual(js);
  });

  it('every allowlisted Kotlin name is an implemented function on VoltTrackerNative', () => {
    for (const name of kotlinAllowedFunctions()) {
      expect(typeof window.VoltTrackerNative[name], `VoltTrackerNative.${name}`).toBe('function');
    }
  });
});
