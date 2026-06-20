// Tab × scenario visual-regression matrix.
//
// Screenshots every bottom-nav tab across the full demo scenario set
// (typical / empty / fault / power-user / extreme) so a layout regression in
// ANY state — dense data, empty states, fault banners, value/​string extremes —
// is caught, not just the happy path. This complements visual.spec.js, which
// pins a few hand-built component shots; here the source of truth is the same
// demo data every dogfooding pass uses (VD.loadDemoScenario).
//
// Determinism:
//  - The clock is frozen (openDashboard fixedTime) so "2h ago" labels and the
//    demo's now-relative timestamps don't drift the baseline.
//  - The demo data itself is deterministic (no Math.random; see map.ts).
//  - Canvas traces (drive charts) and the Leaflet map (network tiles, live
//    marker) are non-deterministic, so they're masked — the baseline still
//    locks the surrounding card/text/SVG layout around them.
//
// Baselines are Linux-only and committed (see .gitignore + the dashboard-visual
// CI job). On a non-Linux dev box run `npm run test:visual:update` to preview;
// those *-darwin.png are gitignored.
const { test, expect } = require('@playwright/test');
const { loadDemoScenario, openDashboard, setView } = require('./harness');

const FIXED = '2026-06-15T12:00:00.000Z';
const SCENARIOS = ['typical', 'empty', 'fault', 'power-user', 'extreme'];
// Trips was folded into Insights/Map. Diagnostics is held out of the pixel
// matrix until its *-diagnostics-visual-linux.png baselines (5 scenarios) are
// generated on the Linux dashboard-visual CI runner and committed — adding it
// here before those exist would red the required gate on a missing snapshot.
// The #rawFrames mask below is already wired for when it joins.
const TABS = ['drive', 'map', 'charge', 'insights', 'settings'];

test.describe('visual matrix — tab × demo scenario', () => {
  for (const scenario of SCENARIOS) {
    for (const tab of TABS) {
      test(`${scenario} — ${tab}`, async ({ page }) => {
        await openDashboard(page, { fixedTime: FIXED });
        await loadDemoScenario(page, scenario);
        await setView(page, tab);
        // rAF is throttled in a headless/occluded tab, so force the live
        // surfaces to paint their current state synchronously before capture.
        await page.evaluate(() => {
          const VD = window.VoltDashboard;
          if (typeof VD.flushRender === 'function') VD.flushRender();
          if (typeof VD.updateLiveUi === 'function') VD.updateLiveUi();
          if (typeof VD.renderDriveLive === 'function') VD.renderDriveLive();
        });

        // Target the view by its unique id: the Settings tab actually has
        // two sections sharing data-view="settings" (#view-settings plus the
        // static #view-connection-tools diagnostics panel), so a data-view
        // selector is ambiguous. #view-<tab> is one element per nav tab.
        const section = page.locator(`#view-${tab}`);
        await section.waitFor({ state: 'visible' });
        await expect(section).toHaveScreenshot(`${scenario}-${tab}.png`, {
          // Non-deterministic regions: chart canvases, the whole Leaflet map
          // (tiles render over the network and the live marker/breadcrumb move),
          // and the Diagnostics raw-frame dump (#rawFrames streams live ELM327
          // output whose ordering/timing isn't baseline-stable).
          mask: [
            page.locator('canvas'),
            page.locator('.leaflet-container'),
            page.locator('#rawFrames'),
          ],
        });
      });
    }
  }
});
