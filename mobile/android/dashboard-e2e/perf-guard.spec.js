// Phone browser performance guard (G4).
//
// The jsdom startup-budget suite measures algorithmic cost (enqueue/flush, route
// math) but not real layout/paint on a constrained device. This runs the REAL
// dashboard in Chromium under CDP CPU throttling — a stand-in for a low-end
// Android WebView — and guards two things a regression would blow up:
//   1. the dashboard still boots within a generous budget while throttled, and
//   2. switching across every bottom-nav tab stays interactive (no multi-second
//      jank per switch).
//
// Budgets are deliberately generous: this is a catastrophic-regression tripwire
// (e.g. a synchronous per-frame O(n^2) added to a hot path), NOT a micro-bench.
// CI runners vary, so the numbers leave wide headroom over locally-observed cost.
const { test, expect } = require('@playwright/test');
const { openDashboard, setView } = require('./harness');

const CPU_THROTTLE_RATE = 4; // 4x slowdown ~ a budget phone's WebView.
const BOOT_BUDGET_MS = 12_000;
const TAB_SWITCH_BUDGET_MS = 1_500;
const TABS = ['map', 'charge', 'insights', 'diagnostics', 'settings', 'drive'];

test.describe('phone perf guard — throttled CPU', () => {
  test('boots and tab-switches within budget under 4x CPU throttle', async ({ page }) => {
    const client = await page.context().newCDPSession(page);
    await client.send('Emulation.setCPUThrottlingRate', { rate: CPU_THROTTLE_RATE });
    // try/finally so a budget assertion failing mid-test can't leave the page
    // context throttled for whatever runs next on this worker.
    try {
      const bootStart = Date.now();
      await openDashboard(page);
      const bootMs = Date.now() - bootStart;
      expect(bootMs, `throttled boot took ${bootMs}ms`).toBeLessThan(BOOT_BUDGET_MS);

      for (const tab of TABS) {
        const start = Date.now();
        await setView(page, tab);
        // Wait for the target view to actually be the active one before timing the
        // next switch, so we measure switch+render, not a queued no-op.
        await page.locator(`#view-${tab}`).waitFor({ state: 'visible' });
        const elapsed = Date.now() - start;
        expect(elapsed, `switch to ${tab} took ${elapsed}ms (throttled)`).toBeLessThan(
          TAB_SWITCH_BUDGET_MS,
        );
      }
    } finally {
      await client.send('Emulation.setCPUThrottlingRate', { rate: 1 });
    }
  });
});
