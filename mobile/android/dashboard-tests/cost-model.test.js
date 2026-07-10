// cost-model.ts — the single source of truth for the EV cost / savings-vs-gas
// math (shared by the lifetime Insights figure and the per-trip trip-detail
// figure) and the per-charge-session home-vs-public rate selection (M3). Pure
// logic, so it's exercised here directly without the DOM.
import { describe, expect, it } from 'vitest';

import {
  ASSUMED_VOLT_MI_PER_KWH,
  METERS_PER_MILE,
  computeSavingsVsGas,
  formatSignedMoney,
  isPublicChargerType,
  rateForCharger,
  savingsPrefsReady,
} from '../app/src/main/dashboard-src/js/cost-model.ts';

describe('cost-model savings math', () => {
  it('matches the documented gas-vs-EV formula', () => {
    const miles = 5000;
    const meters = miles * METERS_PER_MILE;
    const { gasCost, evCost, savings } = computeSavingsVsGas({
      meters,
      mpg: 30,
      gasPricePerGal: 4,
      pricePerKwh: 0.14,
    });
    // gas cost = (5000 / 30) * 4 = 666.667
    expect(gasCost).toBeCloseTo((miles / 30) * 4, 6);
    // EV cost  = (5000 / 3.5) * 0.14 = 200.000 (3.5 mi/kWh assumed)
    expect(evCost).toBeCloseTo((miles / ASSUMED_VOLT_MI_PER_KWH) * 0.14, 6);
    expect(savings).toBeCloseTo(gasCost - evCost, 6);
  });

  it('scales linearly with distance — a per-trip figure is the lifetime figure for that distance', () => {
    const prefs = { mpg: 30, gasPricePerGal: 4, pricePerKwh: 0.14 };
    const oneTrip = computeSavingsVsGas({ meters: 20_000, ...prefs });
    const tenTrips = computeSavingsVsGas({ meters: 200_000, ...prefs });
    expect(tenTrips.savings).toBeCloseTo(oneTrip.savings * 10, 6);
    expect(tenTrips.evCost).toBeCloseTo(oneTrip.evCost * 10, 6);
  });

  it('prefers logged energy over the distance assumption', () => {
    const meters = 100 * METERS_PER_MILE;
    const result = computeSavingsVsGas({
      meters,
      mpg: 30,
      gasPricePerGal: 4,
      pricePerKwh: 0.2,
      energyKwh: 20,
    });
    expect(result.evCost).toBeCloseTo(4, 6);
    expect(result.energySource).toBe('logged');
    expect(result.evCost).not.toBeCloseTo((100 / ASSUMED_VOLT_MI_PER_KWH) * 0.2, 6);
  });

  it('returns negative savings when electricity is pricier than the gas it replaced', () => {
    const { savings } = computeSavingsVsGas({
      meters: 100 * METERS_PER_MILE,
      mpg: 60, // very efficient gas car
      gasPricePerGal: 2,
      pricePerKwh: 1.0, // absurdly pricey power
    });
    expect(savings).toBeLessThan(0);
  });

  it('gates on all three prefs being set', () => {
    expect(savingsPrefsReady(30, 4, 0.14)).toBe(true);
    expect(savingsPrefsReady(0, 4, 0.14)).toBe(false);
    expect(savingsPrefsReady(30, 0, 0.14)).toBe(false);
    expect(savingsPrefsReady(30, 4, 0)).toBe(false);
  });

  it('formats a signed dollar amount with a leading -$ only when negative', () => {
    expect(formatSignedMoney(12.5)).toBe('$12.50');
    expect(formatSignedMoney(-3)).toBe('-$3.00');
    expect(formatSignedMoney(0)).toBe('$0.00');
  });
});

describe('cost-model charger-rate selection (M3)', () => {
  it('classifies public / DC-fast charger types (loose free-text and canonical keys)', () => {
    expect(isPublicChargerType('dc_fast')).toBe(true);
    expect(isPublicChargerType('DC fast')).toBe(true);
    expect(isPublicChargerType('DC fast (CCS, 150 kW pedestal)')).toBe(true);
    expect(isPublicChargerType('CHAdeMO')).toBe(true);
    expect(isPublicChargerType('Supercharger')).toBe(true);
    expect(isPublicChargerType('public L2')).toBe(true);
    // Home / non-public types are not public.
    expect(isPublicChargerType('level1')).toBe(false);
    expect(isPublicChargerType('level2')).toBe(false);
    expect(isPublicChargerType('unknown')).toBe(false);
    expect(isPublicChargerType(null)).toBe(false);
    expect(isPublicChargerType('')).toBe(false);
  });

  it('bills public sessions at the public rate when one is set', () => {
    expect(rateForCharger('dc_fast', 0.12, 0.45)).toBe(0.45);
    expect(rateForCharger('level2', 0.12, 0.45)).toBe(0.12);
    expect(rateForCharger('level1', 0.12, 0.45)).toBe(0.12);
    expect(rateForCharger('unknown', 0.12, 0.45)).toBe(0.12);
  });

  it('falls back to the home rate everywhere when the public rate is unset', () => {
    // Unchanged behaviour for users who never set a public rate: even a DC-fast
    // session uses the single home rate.
    expect(rateForCharger('dc_fast', 0.12, 0)).toBe(0.12);
    expect(rateForCharger('level2', 0.12, 0)).toBe(0.12);
    // A non-positive public rate is treated as unset.
    expect(rateForCharger('dc_fast', 0.12, -1)).toBe(0.12);
  });
});
