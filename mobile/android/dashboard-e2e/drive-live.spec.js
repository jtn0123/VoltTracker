// Drive-tab live charts — functional assertions in REAL Chromium (not pixels).
//
// The jsdom suite (dashboard-tests/drive.test.js) already covers the chart *logic*: it fakes a
// clientWidth and asserts the dataset state machine. What it CANNOT see — because jsdom has no
// layout engine and no canvas — is whether the chart host actually has a non-zero width under the
// real CSS, and whether the speed-trace canvas's 2D context actually painted anything. Those are
// exactly the regressions this file guards:
//
//   - a CSS change that collapses the chart container to 0px width silently kills every chart
//     (drive.ts bails on `if (!w) return;`), and jsdom would never notice.
//   - a broken canvas draw path renders a blank trace even though dataset.traceState says "ready".
//
// We drive the same public render entry points the live telemetry loop uses (renderDriveLive and
// the individual draw* functions), then assert real layout + real pixels. No pixel BASELINES here
// (those live in visual.spec.js); we only assert "the canvas is non-blank", which is platform- and
// font-independent.
const { test, expect } = require('@playwright/test');
const { openDashboard, setView } = require('./harness');

// Count non-transparent pixels the page actually rasterised onto a canvas. Returns 0 for a blank
// (fully transparent) canvas. Runs in the browser so it reads the real 2D backing store.
async function nonBlankPixelCount(page, canvasSelector) {
  return page.evaluate((sel) => {
    const canvas = document.querySelector(sel);
    if (!canvas || !canvas.getContext) return -1;
    const ctx = canvas.getContext('2d');
    const { width, height } = canvas;
    if (!width || !height) return 0;
    const data = ctx.getImageData(0, 0, width, height).data;
    let painted = 0;
    for (let i = 3; i < data.length; i += 4) {
      if (data[i] !== 0) painted += 1; // alpha channel non-zero => something was drawn
    }
    return painted;
  }, canvasSelector);
}

test.beforeEach(async ({ page }) => {
  await openDashboard(page);
  await setView(page, 'drive');
  // First-run consolidation (drive.ts is-prelive): with no history and no live
  // samples the chart hosts are collapsed with the rest of the dead tiles.
  // These specs exercise the charts, so seed minimal live evidence and
  // re-render — the layout under test is the "data exists" one.
  await page.evaluate(() => {
    const VD = window.VoltDashboard;
    VD.state.powerHistory = [{ t: Date.now(), kw: 0 }];
    if (typeof VD.renderDriveLive === 'function') VD.renderDriveLive();
  });
});

test('the Drive chart hosts have real, non-zero layout width', async ({ page }) => {
  // This is the regression jsdom structurally cannot catch: under the real CSS, do the chart
  // containers actually occupy width? If a layout change collapses them, every chart goes blank.
  for (const id of ['liveTraceChart', 'powerBarsChart', 'socTraceChart']) {
    const box = await page.locator(`#${id}`).boundingBox();
    expect(box, `#${id} should be laid out`).not.toBeNull();
    expect(box.width, `#${id} width`).toBeGreaterThan(100);
  }
});

test('speed trace paints a non-blank canvas once samples arrive', async ({ page }) => {
  // Empty first: dataset says "waiting", and the canvas only holds the faint grid/placeholder.
  await page.evaluate(() => {
    window.VoltDashboard.state.speedHistory = [];
    window.VoltDashboard.drawLiveSpeedTrace();
  });
  const host = page.locator('#liveTraceChart');
  await expect(host).toHaveAttribute('data-trace-state', 'empty');
  await expect(host).toHaveAttribute('data-trace-label', 'speed trace appears once samples stream in');

  // Now stream a speed history and re-render: the trace must actually rasterise pixels, and the
  // dataset must flip to a live "NN mph" readout. 112 kph ≈ 70 mph.
  await page.evaluate(() => {
    window.VoltDashboard.state.speedHistory = [0, 12, 28, 45, 60, 72, 88, 96, 104, 112];
    window.VoltDashboard.drawLiveSpeedTrace();
  });
  await expect(host).toHaveAttribute('data-trace-state', 'ready');
  await expect(host).toHaveAttribute('data-trace-label', /^\d+ mph$/);

  // The unique-to-Chromium assertion: the real 2D context drew the trace (jsdom has no canvas).
  const painted = await nonBlankPixelCount(page, '#liveTraceCanvas');
  expect(painted, 'speed-trace canvas should have painted pixels').toBeGreaterThan(0);

  // The canvas is sized in device pixels from the real host width, not left at the 0x0 default.
  const dims = await page.evaluate(() => {
    const c = document.getElementById('liveTraceCanvas');
    return { w: c.width, h: c.height };
  });
  expect(dims.w).toBeGreaterThan(100);
  expect(dims.h).toBeGreaterThan(0);
});

test('power bars render +drive / -regen bars within the chart, then empty out', async ({ page }) => {
  await page.evaluate(() => {
    // Mixed sign so both the drive (>=0) and regen (<0) branches render.
    window.VoltDashboard.state.powerHistory = [4, 12, 22, -6, -14, 0, 8, 30, -3];
    window.VoltDashboard.drawLivePowerBars();
  });
  const host = page.locator('#powerBarsChart');
  await expect(host).toHaveAttribute('data-chart-state', 'ready');
  await expect(host.locator('.live-power-bar.is-drive')).not.toHaveCount(0);
  await expect(host.locator('.live-power-bar.is-regen')).not.toHaveCount(0);

  // Bars are positioned inside the real host box (left offsets are clamped to the measured width).
  const hostBox = await host.boundingBox();
  const lastBar = host.locator('.live-power-bar').last();
  const barBox = await lastBar.boundingBox();
  expect(barBox.x).toBeGreaterThanOrEqual(hostBox.x - 1);
  expect(barBox.x).toBeLessThanOrEqual(hostBox.x + hostBox.width + 1);

  // Draining the history returns the chart to its layout-stable empty state.
  await page.evaluate(() => {
    window.VoltDashboard.state.powerHistory = [];
    window.VoltDashboard.drawLivePowerBars();
  });
  await expect(host).toHaveAttribute('data-chart-state', 'empty');
  await expect(host.locator('.live-chart-empty')).toHaveText('Waiting for power samples');
});

test('SOC trace renders connected segments and a session-start baseline', async ({ page }) => {
  await page.evaluate(() => {
    window.VoltDashboard.state.socHistory = [78.4, 78.1, 77.8, 77.4, 76.9, 76.5];
    window.VoltDashboard.drawLiveSocTrace();
  });
  const host = page.locator('#socTraceChart');
  await expect(host).toHaveAttribute('data-chart-state', 'ready');
  // One segment fewer than dots; both must be present and laid out (width>0 proves real geometry).
  await expect(host.locator('.live-soc-segment')).not.toHaveCount(0);
  await expect(host.locator('.live-soc-baseline')).toHaveCount(1);
  const seg = await host.locator('.live-soc-segment').first().boundingBox();
  expect(seg.width).toBeGreaterThan(0);
});

test('renderDriveLive drives every live surface in one frame', async ({ page }) => {
  // The real telemetry loop calls renderDriveLive() once per frame; prove that single entry point
  // populates the chips, all three charts, and the micro-card header tags together.
  await page.evaluate(() => {
    const VD = window.VoltDashboard;
    VD.state.appState = {
      adapter: { connected: true, name: 'OBDLink MX+' },
      session: { state: 'connected', sampleCount: 64, sessionMs: 120_000 },
    };
    VD.state.telemetry = { soc: 76.5, powerKw: 18.2, sampleCount: 64, sessionMs: 120_000 };
    VD.state.sessionStartSoc = 78.4;
    VD.state.speedHistory = [10, 30, 55, 70, 85, 96];
    VD.state.powerHistory = [5, 14, -4, 22, -8, 18];
    VD.state.socHistory = [78.4, 78.0, 77.6, 77.1, 76.5];
    VD.renderDriveLive();
  });

  // Recording state stays out of the topbar and lives at the true bottom of
  // Settings (not as a full-width strip above the Drive hero).
  await setView(page, 'settings');
  const rec = page.locator('#driveRecording');
  await expect(rec).toBeVisible();
  await expect(rec.locator('xpath=ancestor::*[contains(@class, "topbar")]')).toHaveCount(0);
  await expect(page.locator('#view-preferences > :last-child')).toHaveId('driveRecording');
  await expect(rec).toHaveAttribute('data-tone', 'live');
  await expect(rec).toContainText('Recording');
  await expect(rec).toContainText('64 samples');
  // The retired now-chips strip stays empty.
  await expect(page.locator('#driveNowChips .drive-now-chip')).toHaveCount(0);

  // All three charts ready in the same frame.
  await setView(page, 'drive');
  await expect(page.locator('#liveTraceChart')).toHaveAttribute('data-trace-state', 'ready');
  await expect(page.locator('#powerBarsChart')).toHaveAttribute('data-chart-state', 'ready');
  await expect(page.locator('#socTraceChart')).toHaveAttribute('data-chart-state', 'ready');

  // v2 design: the micro-card corner tags are static unit labels — a render
  // pass must leave them as bare "kW" / "%" (values live in the hero above).
  await expect(page.locator('#powerMicroTag')).toHaveText('kW');
  await expect(page.locator('#socMicroTag')).toHaveText('%');
});
