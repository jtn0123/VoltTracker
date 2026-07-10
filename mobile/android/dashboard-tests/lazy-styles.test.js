import { describe, expect, it } from 'vitest';

import { loadStylesheetWithRetry } from '../app/src/main/dashboard-src/js/lazy-styles.ts';

describe('lazy stylesheet recovery', () => {
  it('recreates a link after a transient stylesheet failure', async () => {
    const pending = loadStylesheetWithRetry('css/transient.css');
    const first = document.querySelector('link[href="css/transient.css"]');
    expect(first).not.toBeNull();
    first.dispatchEvent(new Event('error'));
    await Promise.resolve();

    const second = document.querySelector('link[href="css/transient.css"]');
    expect(second).not.toBeNull();
    expect(second).not.toBe(first);
    second.dispatchEvent(new Event('load'));

    await expect(pending).resolves.toBeUndefined();
    expect(second.dataset.voltLoaded).toBe('true');
  });
});
