import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

// Regression guard for the lazy-chunk loader (core.ts#loadDashboardScript).
// A transient failure of a lazy chunk (DTC database, Insights, map) used to
// leave a dead <script> tag in the DOM. dashboardScriptAlreadyLoaded() matches
// on the tag being present, so every retry resolved instantly against the dead
// node without re-fetching — the chunk could NEVER recover after one failure
// (DTC icons stayed raw, Insights stayed blank for the session). The fix removes
// the failed node in onerror so a retry re-appends and re-executes it.
describe('lazy-chunk loader recovery', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
    // loadDashboard() installs a harness stub that bypasses the real <script>
    // path. Drop it so this test drives the production DOM loader.
    delete window.__VoltDashboardLoadScript;
  });

  const scriptFor = (src) =>
    document.querySelector(`script[src="${src}"][data-dashboard-lazy="true"]`);

  it('removes the failed <script> so a retry re-fetches instead of resolving a dead tag', async () => {
    const VD = window.VoltDashboard;
    const src = 'js/dtc-causes.js';

    const firstAttempt = VD.loadDashboardScript(src);
    const firstScript = scriptFor(src);
    expect(firstScript).not.toBeNull();

    // Simulate a transient failure of the chunk.
    firstScript.dispatchEvent(new window.Event('error'));
    await expect(firstAttempt).rejects.toThrow();

    // The dead tag must be gone — otherwise dashboardScriptAlreadyLoaded() would
    // short-circuit every future retry.
    expect(scriptFor(src)).toBeNull();

    // The retry must append a brand-new tag (actually re-fetch), not resolve
    // instantly against a stale node.
    const secondAttempt = VD.loadDashboardScript(src);
    const secondScript = scriptFor(src);
    expect(secondScript).not.toBeNull();
    expect(secondScript).not.toBe(firstScript);

    // Drive the retry to success.
    secondScript.dispatchEvent(new window.Event('load'));
    await expect(secondAttempt).resolves.toBeUndefined();
  });

  it('short-circuits when a successfully-loaded tag is already present', async () => {
    const VD = window.VoltDashboard;
    const src = 'js/insights-panel.js';

    const firstLoad = VD.loadDashboardScript(src);
    scriptFor(src).dispatchEvent(new window.Event('load'));
    await expect(firstLoad).resolves.toBeUndefined();

    // A second call must NOT append a duplicate; the intended short-circuit
    // resolves immediately against the healthy tag.
    const before = document.querySelectorAll(`script[src="${src}"]`).length;
    await expect(VD.loadDashboardScript(src)).resolves.toBeUndefined();
    expect(document.querySelectorAll(`script[src="${src}"]`).length).toBe(before);
  });
});
