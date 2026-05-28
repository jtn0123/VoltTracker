import { afterEach } from 'vitest';

import { clearDashboardTimers } from './load-dashboard.js';

afterEach(() => {
  clearDashboardTimers();
});
