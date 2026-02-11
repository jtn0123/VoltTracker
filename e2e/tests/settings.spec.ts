import { test, expect } from '@playwright/test';

test.describe('Settings / Config', () => {
  test('page loads without console errors', async ({ page }) => {
    const errors: string[] = [];
    page.on('pageerror', (err) => errors.push(err.message));
    await page.goto('/');
    // Allow short time for JS to execute
    await page.waitForTimeout(1000);
    // Filter out non-critical errors
    const critical = errors.filter(e => !e.includes('Chart') && !e.includes('ResizeObserver'));
    expect(critical.length).toBe(0);
  });

  test('navigation elements are present', async ({ page }) => {
    await page.goto('/');
    const nav = page.locator('nav, .nav, [role="navigation"]');
    await expect(nav.first()).toBeVisible();
  });
});
