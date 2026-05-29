import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const HERE = dirname(fileURLToPath(import.meta.url));
const DASHBOARD_ASSETS = resolve(HERE, '../app/src/main/assets/dashboard');

describe('dashboard layout css', () => {
  it('does not clip vertical touch scrolling at the app shell', () => {
    const baseCss = readFileSync(resolve(DASHBOARD_ASSETS, 'css/base.css'), 'utf8');
    const appRule = baseCss.match(/\.app\s*\{[^}]+\}/)?.[0] || '';

    // Whitespace-tolerant so `overflow:clip` (no space) can't slip past this guard.
    expect(appRule).not.toMatch(/overflow\s*:\s*clip/);
  });
});
