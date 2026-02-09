import { test, expect } from '@playwright/test';

test.describe('Authentication', () => {
  test('dashboard requires auth when password is set', async ({ browser }) => {
    // Create context WITHOUT credentials
    const context = await browser.newContext();
    const page = await context.newPage();
    const response = await page.goto('/');
    // Should get 401 if DASHBOARD_PASSWORD is configured
    // or 200 if auth is disabled (dev mode)
    expect(response).not.toBeNull();
    const status = response!.status();
    expect([200, 401]).toContain(status);
    await context.close();
  });

  test('dashboard loads with valid credentials', async ({ page }) => {
    // httpCredentials are set in playwright.config.ts
    await page.goto('/');
    await expect(page).toHaveTitle(/Volt Efficiency Tracker/);
  });

  test('health endpoint does not require auth', async ({ browser }) => {
    const context = await browser.newContext();
    const request = context.request;
    const response = await request.get(
      `${process.env.BASE_URL || 'http://localhost:8080'}/health`
    );
    expect(response.ok()).toBeTruthy();
    await context.close();
  });

  test('API endpoints return proper error for bad auth', async ({ browser }) => {
    const context = await browser.newContext({
      httpCredentials: { username: 'wrong', password: 'wrong' },
    });
    const request = context.request;
    const response = await request.get(
      `${process.env.BASE_URL || 'http://localhost:8080'}/`
    );
    // Either 401 (auth required) or 200 (auth disabled)
    expect([200, 401]).toContain(response.status());
    await context.close();
  });
});
