// Long lists — a heavy user accumulates hundreds of stored sessions, and the recent-session list
// (#dbSessionList, storage-status.ts updateStorageUi) renders every entry of
// storage.recentSessions with no cap. Seed 250 sessions through the same VoltTrackerNative
// setStorage ABI the Android side uses and assert the list renders fully AND the page stays
// responsive (tab switches still complete, no error status). Functional assertions only — no
// pixels (see README).
const { test, expect } = require('@playwright/test');
const { openDashboard, setView } = require('./harness');

const SESSION_COUNT = 250;

// Runs in the browser: build + push a 250-session storage summary exactly as the native side
// would deliver it (a JSON string through window.VoltTrackerNative.setStorage).
function seedSessions(count) {
  const base = Date.now();
  const hour = 3600 * 1000;
  const sessions = [];
  for (let i = 0; i < count; i++) {
    sessions.push({
      id: count - i,
      mode: 'drive',
      adapterName: `OBDLink MX+ #${count - i}`,
      startedAtMs: base - (i + 1) * 2 * hour,
      status: 'complete',
      sampleCount: 600 + (i % 50),
      usefulSampleCount: 600 + (i % 50),
      emptySampleCount: i % 9 === 0 ? 3 : 0,
    });
  }
  window.VoltTrackerNative.setStorage(
    JSON.stringify({
      database: 'volttracker_obd_poc.db',
      databaseBytes: 64 * 1024 * 1024,
      sessionCount: count,
      sampleCount: count * 600,
      rawTelemetryCount: count * 600,
      eventCount: count,
      lastStartedAtMs: sessions[0].startedAtMs,
      recentSessions: sessions,
    }),
  );
}

test(`a ${SESSION_COUNT}-session list is windowed and fully reachable`, async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err && err.message ? err.message : String(err)));
  await openDashboard(page);
  await setView(page, 'diagnostics');
  await page.locator('[data-diagnostics-mode="advanced"]').click();
  await page.evaluate(seedSessions, SESSION_COUNT);
  // The session list lives inside the "Session review & debug logs" disclosure — open it (same
  // approach as interactions.spec.js) so the layout/visibility assertions below see the rows.
  await page.evaluate(() => {
    document.querySelectorAll('details').forEach((d) => {
      d.open = true;
    });
  });

  // Keep the DOM bounded, while preserving the complete count and an explicit
  // route to the tail.
  await expect(page.locator('#dbSessionList .history-row')).toHaveCount(50);
  await expect(page.locator('#dbSessionCount')).toHaveText(String(SESSION_COUNT));

  // First and last rows carry real content (not empty shells).
  let rows = page.locator('#dbSessionList .history-row');
  await expect(rows.first()).toContainText('drive · OBDLink MX+ #250');
  for (let expected = 100; expected <= SESSION_COUNT; expected += 50) {
    await page.locator('#dbSessionList [data-session-more="true"]').click();
    await expect(page.locator('#dbSessionList .history-row')).toHaveCount(expected);
  }
  rows = page.locator('#dbSessionList .history-row');
  await expect(rows.last()).toContainText('drive · OBDLink MX+ #1');

  // The tail of the list is actually reachable + laid out in the real scroll container.
  await rows.last().scrollIntoViewIfNeeded();
  await expect(rows.last()).toBeVisible();
  const lastBox = await rows.last().boundingBox();
  expect(lastBox, 'last row should have real layout').not.toBeNull();
  expect(lastBox.height).toBeGreaterThan(0);

  expect(pageErrors).toEqual([]);
});

test('the page stays responsive with the big list in the DOM', async ({ page }) => {
  const pageErrors = [];
  page.on('pageerror', (err) => pageErrors.push(err && err.message ? err.message : String(err)));
  await openDashboard(page);
  await setView(page, 'diagnostics');
  await page.evaluate(seedSessions, SESSION_COUNT);
  await expect(page.locator('#dbSessionList .history-row')).toHaveCount(50);

  // Tab switches still complete with 250 rows in the DOM (Playwright's expect timeout is the
  // responsiveness budget — a wedged main thread fails these).
  for (const tab of ['drive', 'charge', 'insights', 'diagnostics']) {
    await page.locator(`nav.bottom-nav [data-nav="${tab}"]`).click();
    await expect(page.locator('body')).toHaveAttribute('data-active-view', tab);
  }

  // The event loop is still serviceable — a queued rAF callback runs promptly.
  const rafOk = await page.evaluate(
    () =>
      new Promise((resolve) => {
        const started = performance.now();
        requestAnimationFrame(() => resolve(performance.now() - started < 2000));
      }),
  );
  expect(rafOk, 'requestAnimationFrame should still be serviced quickly').toBe(true);

  // No error surfaced: the status line is not blocked/failed and a re-render of the same large
  // payload keeps the bounded window intact.
  await expect(page.locator('#stateText')).not.toHaveText(/blocked|failed/);
  await page.evaluate(seedSessions, SESSION_COUNT);
  await expect(page.locator('#dbSessionList .history-row')).toHaveCount(50);

  expect(pageErrors).toEqual([]);
});
