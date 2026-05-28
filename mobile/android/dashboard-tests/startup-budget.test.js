import { performance } from 'node:perf_hooks';

import { describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

describe('dashboard startup budget', () => {
  it('boots the dashboard bootstrap path inside a generous local budget', async () => {
    const start = performance.now();
    await loadDashboard();
    const elapsedMs = performance.now() - start;

    expect(elapsedMs).toBeLessThan(5000);
  });
});
