/**
 * Playwright global setup for Istanbul coverage collection.
 *
 * Usage:
 *   1. Build the frontend with instrumentation:
 *        INSTRUMENT_COVERAGE=true npm run build --prefix receiver/frontend
 *   2. Start the app (serves instrumented JS)
 *   3. Run tests:
 *        npm test --prefix e2e
 *   4. Coverage is written to e2e/coverage/e2e-coverage.json
 *      Convert to lcov with: npx nyc report --reporter=lcov --temp-dir=e2e/coverage -t e2e/coverage --report-dir=e2e/coverage
 *
 * The global teardown is a no-op; coverage is collected per-page in the fixture below.
 */

import { test as base, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

const COVERAGE_DIR = path.join(__dirname, 'coverage');

/**
 * Extended test fixture that collects Istanbul coverage from each page after every test.
 * Import this instead of @playwright/test in your spec files when collecting coverage.
 */
export const test = base.extend({
  page: async ({ page }, use) => {
    await use(page);

    // After the test, grab coverage from the page if available
    try {
      const coverage = await page.evaluate(() => (window as any).__coverage__);
      if (coverage) {
        if (!fs.existsSync(COVERAGE_DIR)) {
          fs.mkdirSync(COVERAGE_DIR, { recursive: true });
        }
        const filename = `coverage-${Date.now()}-${Math.random().toString(36).slice(2, 8)}.json`;
        fs.writeFileSync(
          path.join(COVERAGE_DIR, filename),
          JSON.stringify(coverage),
        );
      }
    } catch {
      // Page may have closed already — ignore
    }
  },
});

export { expect };
