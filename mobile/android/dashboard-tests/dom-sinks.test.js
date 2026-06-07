import { readdirSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const HERE = dirname(fileURLToPath(import.meta.url));
const DASHBOARD_TS = resolve(HERE, '../app/src/main/dashboard-src/js');

const ALLOWED_DOM_SINKS = [
  {
    file: 'core.ts',
    source: 'select.innerHTML = "";',
  },
  {
    file: 'insights-panel.ts',
    source: 'chart.innerHTML = `<svg width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">${inner}</svg>`;',
  },
];

function findDomSinks(file) {
  const source = readFileSync(resolve(DASHBOARD_TS, file), 'utf8');
  return source
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.includes('innerHTML') || line.includes('insertAdjacentHTML'))
    .filter((line) => !line.startsWith('//') && !line.startsWith('*'))
    .map((line) => ({ file, source: line }));
}

describe('dashboard DOM sink guardrail', () => {
  it('keeps HTML-writing sinks limited to audited chart/clear paths', () => {
    const files = readdirSync(DASHBOARD_TS)
      .filter((name) => name.endsWith('.ts'))
      .sort();

    const sinks = files.flatMap(findDomSinks).sort((a, b) => {
      if (a.file !== b.file) return a.file.localeCompare(b.file);
      return a.source.localeCompare(b.source);
    });
    const allowed = [...ALLOWED_DOM_SINKS].sort((a, b) => {
      if (a.file !== b.file) return a.file.localeCompare(b.file);
      return a.source.localeCompare(b.source);
    });

    expect(sinks).toEqual(allowed);
  });
});
