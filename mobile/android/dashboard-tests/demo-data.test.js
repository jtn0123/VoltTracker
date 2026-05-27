import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const DASHBOARD_ASSETS = resolve(HERE, '../app/src/main/assets/dashboard');
const DASHBOARD_SRC = resolve(HERE, '../app/src/main/dashboard-src');

describe('dashboard demo data', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltDashboardDemoData;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
  });

  it('keeps demo fixture rows out of core.js and the eager template', () => {
    const core = readFileSync(resolve(DASHBOARD_ASSETS, 'js/core.js'), 'utf8');
    const template = readFileSync(resolve(DASHBOARD_SRC, 'index.template.html'), 'utf8');

    expect(core).not.toContain('Home -> Office');
    expect(core).not.toContain('Best month yet for EV ratio');
    expect(template).not.toContain('js/demo-data.js');
  });

  it('loads demo rows through the lazy demo-data asset before demo mode renders', async () => {
    await loadDashboard({ extras: ['demo-data.js'] });

    const VD = window.VoltDashboard;
    expect(VD.data.demoLoaded).toBe(false);

    VD.actions.startDemo();

    expect(VD.data.demoLoaded).toBe(true);
    expect(VD.state.demoActive).toBe(true);
    expect(document.getElementById('tripList').textContent).toContain('Home -> Office');
    expect(document.getElementById('insightList').textContent).toContain('Best month yet');
  });
});
