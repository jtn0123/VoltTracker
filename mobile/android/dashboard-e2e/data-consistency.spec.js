// Cross-surface data consistency, driven through Demo / Testing (browser stream):
// the Drive trip card describes one drive and says which, every battery surface shows the
// same SOC, the Charge tab's count tile matches its headline, and the trip-detail sheet
// sits on a dimmed, tap-to-close scrim that scroll-locks the page in light and dark.
const { test, expect } = require('@playwright/test');
const { loadDemoScenario, openDashboard, setView } = require('./harness');

const SCENARIOS = ['typical', 'empty', 'power-user', 'fault', 'extreme'];

async function startBrowserDemo(page, scenario) {
  await openDashboard(page, { withBridge: false });
  await setView(page, 'settings');
  await page.locator('#connectBtn').click();
  await expect.poll(() => page.evaluate(() => window.VoltDashboard.state.demoActive)).toBe(true);
  await loadDemoScenario(page, scenario);
}

for (const scenario of SCENARIOS) {
  test(`live demo (${scenario}): trip card, SOC and charge counts agree`, async ({ page }) => {
    await startBrowserDemo(page, scenario);
    await setView(page, 'drive');
    await expect(page.locator('#thisTripKicker')).toHaveText('Current drive');
    await expect(page.locator('#tripTimeValue')).not.toHaveText('--');
    await expect(page.locator('#overviewMaxSpeed')).not.toHaveText('--');
    if (scenario === 'typical') {
      // The demo stream averages ~6 kW, so the live efficiency appears once the drive has
      // booked its first 0.05 kWh (~30 s); it is this drive's miles over its energy.
      await expect(page.locator('#tripEffValue')).toHaveText(/mi\/kWh/, { timeout: 45_000 });
      await expect(page.locator('#tripEnergyValue')).toHaveText(/kWh/);
    }

    const soc = await page.locator('#driveSocValue').textContent();
    expect(soc).toMatch(/^\d+%$/);
    await expect(page.locator('#realPackValue')).toHaveText(soc);
    await expect(page.locator('#realPackCopy')).toHaveText(/^Live/);

    const count = Number(await page.locator('#realChargeCount').textContent());
    if (count > 0) {
      await expect(page.locator('#chargeSessionsTitle')).toContainText(`${count} charge`);
    }
  });
}

test('without a live stream the card shows the last trip and the snapshot says its age', async ({ page }) => {
  await openDashboard(page);
  await loadDemoScenario(page, 'typical');
  await setView(page, 'drive');
  await expect(page.locator('#thisTripKicker')).toHaveText(/^Last trip · \d+[smhd] ago$/);
  await expect(page.locator('#tripTimeValue')).not.toHaveText('--');
  await expect(page.locator('#tripEffValue')).toHaveText(/mi\/kWh/);
  await expect(page.locator('#realPackCopy')).toHaveText(/^Last logged \d+[smhd] ago/);
});

for (const colorScheme of ['dark', 'light']) {
  test(`trip-detail sheet sits on a tap-to-close scrim (${colorScheme})`, async ({ page }) => {
    await page.emulateMedia({ colorScheme });
    await openDashboard(page);
    await loadDemoScenario(page, 'power-user');
    await setView(page, 'map');
    await page.locator('#mapAllDrivesBtn').click();
    await page.locator('#mapSessionList [data-trip-detail]').first().click();
    const backdrop = page.locator('#tripDetailBackdrop');
    await expect(page.locator('#tripDetailSheet')).toBeVisible();
    await expect(backdrop).toBeVisible();

    const scrim = await backdrop.evaluate((node) => {
      const style = getComputedStyle(node);
      const token = getComputedStyle(document.documentElement).getPropertyValue('--scrim').trim();
      return { bg: style.backgroundColor, token, z: Number(style.zIndex) };
    });
    expect(scrim.token).not.toBe('');
    expect(scrim.bg).not.toBe('rgba(0, 0, 0, 0)');
    // Above the bottom nav (z 40), under the sheet.
    expect(scrim.z).toBeGreaterThan(40);

    // The page under the scrim does not scroll.
    const before = await page.evaluate(() => window.scrollY);
    await page.mouse.move(200, 60);
    await page.mouse.wheel(0, 600);
    await page.waitForTimeout(200);
    expect(await page.evaluate(() => window.scrollY)).toBe(before);

    // Tapping the dimmed area (above the sheet) closes it.
    await backdrop.click({ position: { x: 200, y: 40 } });
    await expect(page.locator('#tripDetailSheet')).toBeHidden();
    await expect(backdrop).toBeHidden();
    await expect(page.locator('body')).not.toHaveClass(/trip-detail-active/);
  });
}
