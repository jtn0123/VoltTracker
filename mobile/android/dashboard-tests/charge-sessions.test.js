import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';
import { createVoltBridgeFixture } from './setup/voltbridge.fixture.js';

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

  it('refreshes the title when the lifetime charge count grows but the newest rows are unchanged', () => {
    const rows = [
      { id: 2, startedAtMs: Date.now() - 3_600_000, endedAtMs: Date.now() - 600_000, chargerType: 'level2', startSoc: 40, endSoc: 90, powerKw: 7.2, energyKwh: 9.6 },
      { id: 1, startedAtMs: Date.now() - 90_000_000, endedAtMs: Date.now() - 86_000_000, chargerType: 'level2', startSoc: 30, endSoc: 80, powerKw: 7.0, energyKwh: 9.0 },
    ];
    window.VoltDashboard.setStorage({ chargeSummary: { chargeSessionCount: 2, recentSessions: rows } });
    expect(document.getElementById('chargeSessionsTitle').textContent).toBe('2 recent charges');

    // A backfilled older charge bumps the lifetime count while native re-ships the
    // same newest rows. chargeSessionCount is part of the memo signature now, so
    // the title switches to the "X of Y" truncation form instead of staying stale.
    window.VoltDashboard.setStorage({ chargeSummary: { chargeSessionCount: 3, recentSessions: rows } });
    expect(document.getElementById('chargeSessionsTitle').textContent).toBe('2 of 3 charges');
  });

  it('hides and clears the energy card when the charge sessions go away', () => {
    // Populate: a session with logged energy reveals the energy card.
    window.VoltDashboard.setStorage({
      chargeSummary: {
        chargeSessionCount: 1,
        recentSessions: [
          { id: 3, startedAtMs: Date.now() - 3_600_000, endedAtMs: Date.now() - 600_000, chargerType: 'level2', startSoc: 40, endSoc: 90, powerKw: 7.2, energyKwh: 9.6 },
        ],
      },
    });
    const energyCard = document.getElementById('chargeEnergyCard');
    expect(energyCard.hidden).toBe(false);
    expect(document.getElementById('chargeEnergyTotal').textContent).toBe('9.6 kWh');

    // Clear the stored data: the energy card must hide too (its hidden flag is
    // owned by renderChargeEnergy, which must also run on the empty path) and
    // drop the stale kWh total.
    window.VoltDashboard.setStorage({ chargeSummary: { chargeSessionCount: 0 } });
    expect(document.getElementById('chargeSessionsCard').hidden).toBe(true);
    expect(energyCard.hidden).toBe(true);
    expect(document.getElementById('chargeEnergyTotal').textContent).toBe('-- kWh');
    expect(document.getElementById('chargeEnergyCost').textContent).toBe('--');
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

  it('reports a blocked status when native charge-history export throws', async () => {
    document.body.innerHTML = '';
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    const logClientError = vi.fn();
    const exportChargeSessionsCsv = vi.fn(() => {
      throw new Error('charge export denied');
    });
    await loadDashboard({
      bridge: createVoltBridgeFixture({ exportChargeSessionsCsv, logClientError }),
    });

    expect(() => window.VoltDashboard.exportChargeSessionsCsv()).not.toThrow();

    expect(window.VoltDashboard.state.status).toMatchObject({
      state: 'blocked',
      detail: 'Charge-history export failed.',
    });
    expect(logClientError).toHaveBeenCalledWith(
      'charge_export_failed',
      'Charge-history export failed. charge export denied',
    );
  });

  // M3 — optional public/DC-fast rate. A public session is billed at the public
  // rate when one is set; home (Level 1/2) sessions stay on the home rate. With
  // no public rate set, every session uses the single home rate.
  describe('home vs public charging rate (M3)', () => {
    // These cases set rate prefs; the outer beforeEach doesn't clear
    // localStorage, so isolate each case so a prior rate can't leak forward.
    beforeEach(() => window.localStorage.clear());
    afterEach(() => window.localStorage.clear());

    function twoSessions() {
      const now = Date.now();
      return {
        chargeSummary: {
          chargeSessionCount: 2,
          recentSessions: [
            // Newest first: a 10 kWh public DC-fast session, then a 10 kWh home L2 session.
            { id: 2, startedAtMs: now - 3_600_000, endedAtMs: now - 600_000, chargerType: 'DC fast (CCS, 150 kW pedestal)', startSoc: 30, endSoc: 70, powerKw: 50, energyKwh: 10 },
            { id: 1, startedAtMs: now - 90_000_000, endedAtMs: now - 86_000_000, chargerType: 'level2', startSoc: 40, endSoc: 90, powerKw: 7.2, energyKwh: 10 },
          ],
        },
      };
    }

    it('bills a public session at the public rate and a home session at the home rate', () => {
      window.VoltDashboard.prefs.set('pricePerKwh', 0.12);
      window.VoltDashboard.prefs.set('publicPricePerKwh', 0.45);
      window.VoltDashboard.setStorage(twoSessions());

      const rows = document.querySelectorAll('#chargeSessionsList .charge-session-row');
      // Row 0 = public DC-fast: 10 kWh × $0.45 = $4.50.
      expect(rows[0].querySelector('b').textContent).toBe('10.0 kWh · $4.50');
      // Row 1 = home L2: 10 kWh × $0.12 = $1.20.
      expect(rows[1].querySelector('b').textContent).toBe('10.0 kWh · $1.20');
      // Lifetime energy-card cost bills each session at its own rate: 4.50 + 1.20.
      expect(document.getElementById('chargeEnergyCost').textContent).toBe('$5.70');
    });

    it('falls back to the single home rate everywhere when no public rate is set', () => {
      window.VoltDashboard.prefs.set('pricePerKwh', 0.12);
      window.VoltDashboard.setStorage(twoSessions());

      const rows = document.querySelectorAll('#chargeSessionsList .charge-session-row');
      // Both sessions billed at the home rate: 10 kWh × $0.12 = $1.20 each.
      expect(rows[0].querySelector('b').textContent).toBe('10.0 kWh · $1.20');
      expect(rows[1].querySelector('b').textContent).toBe('10.0 kWh · $1.20');
      expect(document.getElementById('chargeEnergyCost').textContent).toBe('$2.40');
    });

    it('shows kWh only (no cost) when the home rate is unset', () => {
      window.VoltDashboard.prefs.set('publicPricePerKwh', 0.45); // public set but no home rate
      window.VoltDashboard.setStorage(twoSessions());
      const rows = document.querySelectorAll('#chargeSessionsList .charge-session-row');
      expect(rows[0].querySelector('b').textContent).toBe('10.0 kWh');
      expect(rows[1].querySelector('b').textContent).toBe('10.0 kWh');
      expect(document.getElementById('chargeEnergyCost').textContent).toBe('--');
    });
  });
});
