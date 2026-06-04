import { beforeEach, describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

describe('dashboard charge session history', () => {
  beforeEach(async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  it('keeps the sessions card hidden when there are no charge sessions', () => {
    window.VoltDashboard.setStorage({ chargeSummary: { chargeSessionCount: 0 } });
    expect(document.getElementById('chargeSessionsCard').hidden).toBe(true);
    expect(document.querySelectorAll('#chargeSessionsList .charge-session-row')).toHaveLength(0);
  });

  it('renders recent charge sessions newest-first with SOC, power and energy', () => {
    window.VoltDashboard.setStorage({
      chargeSummary: {
        chargeSessionCount: 2,
        recentSessions: [
          {
            id: 2,
            startedAtMs: Date.now() - 3_600_000,
            endedAtMs: Date.now() - 600_000,
            chargerType: 'level2',
            startSoc: 40,
            endSoc: 90,
            powerKw: 7.2,
            energyKwh: 9.6,
          },
          {
            id: 1,
            startedAtMs: Date.now() - 90_000_000,
            endedAtMs: null,
            chargerType: 'unknown',
            startSoc: null,
            endSoc: null,
            powerKw: null,
            energyKwh: null,
          },
        ],
      },
    });

    const card = document.getElementById('chargeSessionsCard');
    expect(card.hidden).toBe(false);
    expect(document.getElementById('chargeSessionsTitle').textContent).toBe('2 recent charges');

    const rows = document.querySelectorAll('#chargeSessionsList .charge-session-row');
    expect(rows).toHaveLength(2);

    // Row 0 = the level2 session: SOC delta, power, and energy badge all present.
    expect(rows[0].textContent).toContain('Level 2');
    expect(rows[0].textContent).toContain('40% → 90%');
    expect(rows[0].textContent).toContain('7.2 kW');
    expect(rows[0].querySelector('b').textContent).toBe('9.6 kWh');

    // Row 1 = the all-null session: no fake "0%" readings, falls back to placeholder copy.
    expect(rows[1].textContent).not.toContain('0% → 0%');
    expect(rows[1].textContent).not.toContain('unknown');
    expect(rows[1].textContent).toContain('charge details pending');
    expect(rows[1].querySelector('b').textContent).toBe('--');
  });

  it('marks an in-progress (still plugged in) charge as charging', () => {
    window.VoltDashboard.setStorage({
      chargeSummary: {
        chargeSessionCount: 1,
        recentSessions: [
          { id: 9, startedAtMs: Date.now() - 30 * 60 * 1000, endedAtMs: null, chargerType: 'level2', startSoc: 54, endSoc: 71, powerKw: 7.1, energyKwh: 3.0 },
        ],
      },
    });
    const rows = document.querySelectorAll('#chargeSessionsList .charge-session-row');
    expect(rows).toHaveLength(1);
    expect(rows[0].dataset.charging).toBe('1');
    expect(rows[0].textContent).toContain('charging now');
    expect(document.getElementById('realChargeStatus').textContent).toBe('charging');
  });
});
