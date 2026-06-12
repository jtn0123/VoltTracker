import { afterEach } from 'vitest';

import { clearDashboardTimers } from './load-dashboard.js';

afterEach(async () => {
  // Drain in-flight lazy-chunk loads (DTC data / map / troubleshooter) so their
  // async success/failure handlers — including console.warn in the catch paths —
  // run inside the test context instead of racing vitest's worker teardown
  // ("Closing rpc while onUserConsoleLog was pending").
  const VD = window.VoltDashboard;
  if (VD && typeof VD.pendingLazyLoads === 'function') {
    try {
      await VD.pendingLazyLoads();
    } catch (ignored) {
      // pendingLazyLoads never rejects by contract; belt-and-braces only.
    }
  }
  clearDashboardTimers();
});
