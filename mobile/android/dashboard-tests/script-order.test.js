import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

const DASHBOARD_HTML = resolve('../app/src/main/assets/dashboard/index.html');
const TEMPLATE_HTML = resolve('../app/src/main/dashboard-src/index.template.html');
const BOOTSTRAP_JS = resolve('../app/src/main/assets/dashboard/js/bootstrap.js');
const EXPECTED_BOOTSTRAP_SCRIPT = [{ src: 'js/bootstrap.js', type: 'module' }];
const EXPECTED_BOOTSTRAP_IMPORTS = [
  './core.js',
  './panels.js',
  './map.js',
  './scrubber.js',
  './drive.js',
  './telemetry.js',
  './actions.js',
  './troubleshooter.js',
  './connection-status.js',
  './connection-tools.js',
];

function dashboardScripts(html) {
  const parsed = new DOMParser().parseFromString(html, 'text/html');
  return [...parsed.querySelectorAll('script[src]')]
    .map((script) => {
      const src = script.getAttribute('src') ?? '';
      const type = script.getAttribute('type') ?? '';
      return { src, type };
    })
    .filter((script) => script.src.startsWith('js/'));
}

function bootstrapImports(source) {
  return [...source.matchAll(/import\s+"([^"]+)";/g)].map((match) => match[1]);
}

describe('dashboard production script order', () => {
  it('keeps the generated dashboard and source template on the module bootstrap', () => {
    const generatedScripts = dashboardScripts(readFileSync(DASHBOARD_HTML, 'utf8'));
    const templateScripts = dashboardScripts(readFileSync(TEMPLATE_HTML, 'utf8'));

    expect(generatedScripts).toEqual(EXPECTED_BOOTSTRAP_SCRIPT);
    expect(templateScripts).toEqual(EXPECTED_BOOTSTRAP_SCRIPT);
    expect(bootstrapImports(readFileSync(BOOTSTRAP_JS, 'utf8'))).toEqual(EXPECTED_BOOTSTRAP_IMPORTS);
  });
});
