import { test, expect } from '@playwright/test';

test.describe('Map Page Load', () => {
  test('map page loads successfully', async ({ page }) => {
    await page.goto('/map');
    await expect(page).toHaveURL(/map/);
  });

  test('map container is present', async ({ page }) => {
    await page.goto('/map');
    const mapContainer = page.locator('#map, .map-container, [data-map]');
    await expect(mapContainer.first()).toBeVisible();
  });
});
