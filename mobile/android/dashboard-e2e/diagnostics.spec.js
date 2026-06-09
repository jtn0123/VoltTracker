// Diagnostics tab — real browser UX checks for the probe/export/demo controls.
const { test, expect } = require('@playwright/test');
const { openDashboard, setView } = require('./harness');

test.beforeEach(async ({ page }) => {
  await openDashboard(page);
  await setView(page, 'diagnostics');
});

test('Run probe with no adapter mirrors blocked feedback into the card badge', async ({ page }) => {
  await page.locator('#detailProbeBtn').click();

  await expect(page.locator('#stateText')).toHaveText('blocked');
  await expect(page.locator('#statusCopy')).toContainText(/adapter/i);
  await expect(page.locator('#enhancedBadge')).toHaveText('blocked');
  await expect(page.locator('#enhancedBadge')).toHaveAttribute('data-state', 'blocked');
});

test('Export logs downloads a JSON file and reports success', async ({ page }) => {
  const downloadPromise = page.waitForEvent('download');
  await page.locator('#exportSignalLogsBtn').click();
  const download = await downloadPromise;

  expect(download.suggestedFilename()).toBe('volttracker-detailed-signal-logs.json');
  await expect(page.locator('#statusCopy')).toHaveText('Detailed signal logs exported.');
});

test('Start demo preview visibly starts the sandbox from Diagnostics', async ({ page }) => {
  await page.locator('details.sandbox-tools').evaluate((node) => {
    node.open = true;
  });
  await page.locator('[data-demo-toggle]').click();

  await expect.poll(() => page.evaluate(() => window.VoltDashboard.state.demoActive)).toBe(true);
  await expect(page.locator('#stateText')).toHaveText('demo');
  await expect(page.locator('[data-demo-toggle]')).toHaveText('Stop demo preview');
  await expect(page.locator('#statusCopy')).toContainText(/demo/i);
});

test('native restore progress opens an overlay and failed restores can be dismissed', async ({ page }) => {
  await page.evaluate(() => {
    window.VoltTrackerNative.setRestoreProgress(JSON.stringify({
      visible: true,
      busy: true,
      title: 'Reading backup',
      detail: 'Large backup files can take a minute.',
      tone: 'busy',
    }));
  });

  await expect(page.locator('#restoreProgress')).toBeVisible();
  await expect(page.locator('#restoreProgressTitle')).toHaveText('Reading backup');
  await expect(page.locator('#restoreProgressClose')).toBeHidden();

  await page.evaluate(() => {
    window.VoltTrackerNative.setRestoreProgress(JSON.stringify({
      visible: true,
      busy: false,
      title: 'Restore failed',
      detail: 'Restore failed - that file is not a valid Volt Tracker backup.',
      tone: 'blocked',
    }));
  });

  await expect(page.locator('#restoreProgressClose')).toBeVisible();
  await page.locator('#restoreProgressClose').click();
  await expect(page.locator('#restoreProgress')).toBeHidden();
});
