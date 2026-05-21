import { test, expect, devices } from '@playwright/test';

test.describe('Responsive / Mobile', () => {
  test('bottom nav is visible on mobile', async ({ page, isMobile }) => {
    test.skip(!isMobile, 'Mobile-only test');
    await page.goto('/');
    const bottomNav = page.locator('.bottom-nav');
    await expect(bottomNav).toBeVisible();
    const navItems = page.locator('.bottom-nav-item');
    await expect(navItems).toHaveCount(4);
  });

  test('bottom nav switches sections on mobile', async ({ page, isMobile }) => {
    test.skip(!isMobile, 'Mobile-only test');
    await page.goto('/');
    // Click trips nav
    await page.locator('[data-section="trips"]').click();
    await expect(page.locator('#trips-section')).toBeVisible();
    // Click charging nav
    await page.locator('[data-section="charging"]').click();
    await expect(page.locator('#charging-section')).toBeVisible();
  });

  test('dashboard is usable at desktop width', async ({ page, isMobile }) => {
    test.skip(isMobile, 'Desktop-only test');
    await page.setViewportSize({ width: 1920, height: 1080 });
    await page.goto('/');
    await expect(page.locator('header h1')).toBeVisible();
    await expect(page.locator('#summary-section')).toBeVisible();
  });
});
