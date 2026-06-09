import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

describe('insights HV-pack detail stats', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  it('hides the pack stat row when there is no battery snapshot', () => {
    window.VoltDashboard.setStorage({ batterySummary: {} });
    expect(document.getElementById('realPackStats').hidden).toBe(true);
  });

  it('surfaces voltage, temp, health and power from the latest battery snapshot', () => {
    window.VoltDashboard.setStorage({
      batterySummary: {
        latestBatterySnapshot: {
          soc: 64,
          packVoltage: 364.2,
          batteryTempC: 23,
          sohPct: 92,
          packPowerKw: -7.4,
        },
      },
    });

    const row = document.getElementById('realPackStats');
    expect(row.hidden).toBe(false);
    const text = row.textContent;
    expect(text).toContain('Pack');
    expect(text).toContain('364 V');
    // Units default to imperial, so the seeded 23°C renders as °F (23·9/5+32 = 73).
    expect(text).toContain('73°F');
    expect(text).toContain('92%');
    expect(text).toContain('-7.4 kW');
  });

  it('omits only the missing fields and keeps the present ones', () => {
    window.VoltDashboard.setStorage({
      batterySummary: {
        latestBatterySnapshot: { soc: 50, packVoltage: 360, sohPct: null, packPowerKw: null },
      },
    });
    const row = document.getElementById('realPackStats');
    expect(row.hidden).toBe(false);
    expect(row.textContent).toContain('360 V');
    expect(row.textContent).not.toContain('Health');
    expect(row.querySelectorAll('div')).toHaveLength(1);
  });
});
