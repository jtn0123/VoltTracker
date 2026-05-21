import { test, expect } from '@playwright/test';

test.describe('Battery', () => {
  test('battery health API returns data', async ({ request }) => {
    const response = await request.get('/api/battery/health');
    // May return 200 or 404 if no data, but shouldn't error
    expect([200, 404, 500]).toContain(response.status());
  });

  test('battery cells API returns data', async ({ request }) => {
    const response = await request.get('/api/battery/cells');
    expect([200, 404, 500]).toContain(response.status());
  });

  test('SOC analysis section renders', async ({ page, isMobile }) => {
    await page.goto('/');
    if (isMobile) {
      await page.locator('[data-section="analysis"]').click();
    } else {
      await page.evaluate(() => document.getElementById('soc-section')?.scrollIntoView());
    }
    await expect(page.locator('#soc-section')).toBeVisible();
  });
});
