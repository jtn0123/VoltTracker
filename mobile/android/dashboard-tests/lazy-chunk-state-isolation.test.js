// C1/C2 — stateful modules must exist ONCE at runtime, even though every lazy
// chunk is an INDEPENDENT esbuild bundle (a chunk that imports a stateful
// module re-bundles its own copy; see vd-registry.ts).
//
//  C1: insights-panel (lazy) used to `import { prefs, units } from "./prefs"`.
//      prefs.ts has top-level boot side effects, so first open of the
//      Insights/Map tab re-ran bootPrefsUi() against the live DOM: six numeric
//      preference inputs got SECOND input/change listeners (double commits,
//      double setChargeTargetSoc bridge pushes), a second scroll-spy pair on
//      window, and a Settings-nav reset. Fixed two ways: the chunk now reads
//      VD.prefs/VD.units off the registry, and bootPrefsUi carries a
//      per-document once-guard (__voltPrefsUiBooted).
//  C2: app-dialog owns the mutable controller for the ONE #appDialog node, so
//      lazy chunks must reach it through the VD registry — a bundled duplicate
//      would stack focus traps and settle two promises with one Confirm.
//
// vi.resetModules() + a fresh import() reproduces the chunk boundary in the
// source harness: the re-imported module executes a brand-new copy, exactly
// like an independently bundled chunk would on-device. built-bundle.test.js
// asserts the same invariants against the SHIPPED minified artifacts.
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const SRC = resolve(HERE, '../app/src/main/dashboard-src/js');

function commitChargeTarget(value) {
  const input = document.getElementById('prefChargeTargetInput');
  expect(input).not.toBeNull();
  input.value = String(value);
  input.dispatchEvent(new window.Event('change', { bubbles: true }));
}

describe('C1 — prefs boot must not re-run when a lazy chunk loads', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    window.localStorage.clear();
  });

  it('loading the insights-panel chunk leaves preference commits single-fire', async () => {
    const bridge = await loadDashboard();
    bridge.setChargeTargetSoc = vi.fn();

    // Simulate the production chunk boundary: reset the module registry so the
    // next import executes a FRESH copy of insights-panel (and of anything it
    // imports), the way its independent esbuild bundle does on-device.
    vi.resetModules();
    await import('../app/src/main/dashboard-src/js/insights-panel.ts');

    commitChargeTarget(80);
    // One change event -> exactly one commit across the bridge. A re-run
    // prefs boot would have bound a second change listener (two pushes).
    expect(bridge.setChargeTargetSoc).toHaveBeenCalledTimes(1);
    expect(bridge.setChargeTargetSoc).toHaveBeenCalledWith(80);
  });

  it('a stateful re-import of prefs.ts itself cannot re-boot the preference UI (once-guard)', async () => {
    const bridge = await loadDashboard();
    bridge.setChargeTargetSoc = vi.fn();
    expect(document.__voltPrefsUiBooted).toBe(true);

    // Worst case for any FUTURE chunk that re-grows a prefs import: execute a
    // second live copy of prefs.ts. The per-document once-guard must keep it
    // from re-binding the preference inputs.
    vi.resetModules();
    await import('../app/src/main/dashboard-src/js/prefs.ts');

    commitChargeTarget(90);
    expect(bridge.setChargeTargetSoc).toHaveBeenCalledTimes(1);
    expect(bridge.setChargeTargetSoc).toHaveBeenCalledWith(90);
  });

  it('insights-panel.ts does not import the stateful prefs module (source pin)', () => {
    const source = readFileSync(resolve(SRC, 'insights-panel.ts'), 'utf8');
    expect(
      source,
      'insights-panel.ts must read VD.prefs/VD.units off the registry, not import ./prefs',
    ).not.toMatch(/^import[^;]*from\s+["']\.\/prefs["']/m);
  });
});

describe('C2 — lazy action chunks share the ONE app-dialog instance', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    window.localStorage.clear();
  });

  it('lazy action chunks must not import app-dialog (source pin)', () => {
    for (const name of ['actions-storage', 'actions-signals']) {
      const source = readFileSync(resolve(SRC, `${name}.ts`), 'utf8');
      expect(
        source,
        `${name}.ts must reach the dialog through the VD registry, not import ./app-dialog`,
      ).not.toMatch(/^import[^;]*from\s+["']\.\/app-dialog["']/m);
    }
  });

  it('the eager bundle publishes the dialog API on the VD registry', async () => {
    await loadDashboard();
    const VD = window.VoltDashboard;
    expect(typeof VD.confirmAppDialog).toBe('function');
    expect(typeof VD.promptAppDialog).toBe('function');
    expect(typeof VD.choiceAppDialog).toBe('function');
  });

  it('a chunk-opened dialog and an eager-opened dialog share one controller', async () => {
    const bridge = await loadDashboard();
    bridge.clearStoredData = vi.fn();
    const VD = window.VoltDashboard;

    // Open a dialog through the LAZY actions-storage chunk path…
    await VD.actions.clearStorage(document.createElement('button'));
    expect(document.getElementById('appDialog').hidden).toBe(false);

    // …then stack a second dialog through the EAGER registry surface. With one
    // shared controller the first dialog is force-closed (its promise settles
    // unconfirmed), so Confirm only answers the second dialog. Duplicated
    // module state — the C2 defect — would leave the chunk's listeners alive
    // and this click would ALSO fire the destructive clearStoredData path.
    const second = VD.confirmAppDialog({ title: 'Second', message: 'stacked' });
    expect(document.getElementById('appDialogTitle').textContent).toBe('Second');

    document.getElementById('appDialogConfirm').click();
    await expect(second).resolves.toBe(true);
    await Promise.resolve();
    expect(bridge.clearStoredData).not.toHaveBeenCalled();
  });
});
