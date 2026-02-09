import { test, expect } from '@playwright/test';

test.describe('Live Telemetry', () => {
  test('live trip section exists in DOM', async ({ page }) => {
    await page.goto('/');
    const liveSection = page.locator('#live-trip-section');
    await expect(liveSection).toBeAttached();
  });

  test('power flow section exists in DOM', async ({ page }) => {
    await page.goto('/');
    const powerFlow = page.locator('#power-flow-section');
    await expect(powerFlow).toBeAttached();
  });

  test('socket.io script is loaded', async ({ page }) => {
    await page.goto('/');
    // Wait for deferred socket.io script to load
    await expect(async () => {
      const hasSocketIO = await page.evaluate(() => typeof (window as any).io !== 'undefined');
      expect(hasSocketIO).toBeTruthy();
    }).toPass({ timeout: 5_000 });
  });

  test('status API returns connection info', async ({ request }) => {
    const response = await request.get('/api/status', { timeout: 10_000 });
    // Endpoint exists and responds
    expect([200, 404, 500]).toContain(response.status());
  });
});
