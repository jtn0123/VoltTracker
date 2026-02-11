import { test, expect } from '@playwright/test';

test.describe('Charging Page Load', () => {
  test('charging section renders without errors', async ({ page }) => {
    await page.goto('/');
    const section = page.locator('#charging-section');
    await expect(section).toBeVisible();
  });

  test('charging section has expected heading', async ({ page }) => {
    await page.goto('/');
    const heading = page.locator('#charging-section h2, #charging-section h3, [data-section="charging"]');
    await expect(heading.first()).toBeVisible();
  });
});
