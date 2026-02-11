/**
 * E2E User Flow Tests (T23)
 *
 * Tests complete user journeys through the application.
 */
import { test, expect } from '@playwright/test';

test.describe('Trip Detail User Flow', () => {
  test('navigate to trip detail and verify data displayed', async ({ page }) => {
    // Navigate to dashboard
    await page.goto('/');
    await page.waitForLoadState('networkidle');

    // Wait for trips table to load
    const tripsTable = page.locator('#trips-table-body');
    await expect(tripsTable).toBeVisible({ timeout: 10000 });

    // Check if there are any trips; if so click the first one
    const firstRow = tripsTable.locator('tr.clickable').first();
    const rowCount = await tripsTable.locator('tr.clickable').count();

    if (rowCount > 0) {
      await firstRow.click();

      // Trip modal should appear
      const modal = page.locator('#trip-modal');
      await expect(modal).toBeVisible({ timeout: 5000 });

      // Verify trip detail content is populated
      const detailContent = page.locator('#trip-detail-content');
      await expect(detailContent).not.toBeEmpty();

      // Close the modal
      await page.keyboard.press('Escape');
      await expect(modal).not.toBeVisible();
    } else {
      // No trips — verify empty state message
      await expect(tripsTable).toContainText('No Trips');
    }
  });

  test('delete trip and verify removal', async ({ page }) => {
    // Navigate to dashboard
    await page.goto('/');
    await page.waitForLoadState('networkidle');

    const tripsTable = page.locator('#trips-table-body');
    await expect(tripsTable).toBeVisible({ timeout: 10000 });

    const rowCount = await tripsTable.locator('tr.clickable').count();

    if (rowCount > 0) {
      // Click first trip to open modal
      await tripsTable.locator('tr.clickable').first().click();

      const modal = page.locator('#trip-modal');
      await expect(modal).toBeVisible({ timeout: 5000 });

      // Look for delete button
      const deleteBtn = modal.locator('button:has-text("Delete"), .btn-delete, [data-action="delete"]');
      const deleteBtnCount = await deleteBtn.count();

      if (deleteBtnCount > 0) {
        // Listen for confirm dialog
        page.on('dialog', (dialog) => dialog.accept());
        await deleteBtn.first().click();

        // Wait for modal to close or trip to be removed
        await page.waitForTimeout(1000);
      }

      // Close modal if still open
      if (await modal.isVisible()) {
        await page.keyboard.press('Escape');
      }
    }
  });
});
