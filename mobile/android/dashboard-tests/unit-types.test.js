// unit-types.ts — the conversions, and the guarantee that the brands are erased.
//
// Two things worth pinning. First the arithmetic, because a transposed constant here is
// silently wrong everywhere at once. Second the erasure: the whole design depends on a
// branded value still being an ordinary number at runtime, so it can be compared, rounded,
// formatted and sent across the bridge with no unwrapping. If that ever stopped being true
// the brands would have to be threaded through every arithmetic expression instead of
// disappearing at the type layer.
import { describe, it, expect } from 'vitest';

import {
  km, miles, meters, kph, mph, celsius, fahrenheit,
  kmToMiles, milesToKm, metersToKm, kmToMeters,
  kphToMph, mphToKph, celsiusToFahrenheit, fahrenheitToCelsius,
} from '../app/src/main/dashboard-src/js/unit-types.ts';

describe('unit-types', () => {
  it('tags values without changing them', () => {
    // Brands are compile-time only: `km(5)` must BE 5, not a wrapper around it.
    expect(km(5)).toBe(5);
    expect(miles(5)).toBe(5);
    expect(meters(5)).toBe(5);
    expect(kph(5)).toBe(5);
    expect(mph(5)).toBe(5);
    expect(celsius(5)).toBe(5);
    expect(fahrenheit(5)).toBe(5);
    expect(typeof km(5)).toBe('number');
  });

  it('converts distance against known values', () => {
    // One international mile is exactly 1.609344 km, by definition.
    expect(milesToKm(miles(1))).toBe(1.609344);
    expect(kmToMiles(km(1.609344))).toBeCloseTo(1, 12);
    // A marathon, to catch a constant that is merely close. 42.195 km is 26.2187575 mi;
    // the rounded 0.621371 the display path uses would give 26.2187348, so this value is
    // tight enough to tell the exact reciprocal from the rounded one.
    expect(kmToMiles(km(42.195))).toBeCloseTo(26.2187575, 6);
    expect(metersToKm(meters(2500))).toBe(2.5);
    expect(kmToMeters(km(2.5))).toBe(2500);
  });

  it('converts speed against known values', () => {
    expect(mphToKph(mph(60))).toBeCloseTo(96.56064, 5);
    expect(kphToMph(kph(100))).toBeCloseTo(62.137119, 6);
  });

  it('converts temperature against the fixed points', () => {
    expect(celsiusToFahrenheit(celsius(0))).toBe(32);
    expect(celsiusToFahrenheit(celsius(100))).toBe(212);
    expect(fahrenheitToCelsius(fahrenheit(32))).toBe(0);
    expect(fahrenheitToCelsius(fahrenheit(212))).toBe(100);
    // -40 is the crossover; a sign or offset error will not survive it.
    expect(celsiusToFahrenheit(celsius(-40))).toBe(-40);
    expect(fahrenheitToCelsius(fahrenheit(-40))).toBe(-40);
  });

  // The forward and reverse factors are derived from one constant rather than written as
  // two literals, so this catches the class of bug where someone "fixes" one direction.
  it('round-trips every pair', () => {
    for (const value of [0.001, 1, 42.195, 99999]) {
      expect(kmToMiles(milesToKm(miles(value)))).toBeCloseTo(value, 9);
      expect(milesToKm(kmToMiles(km(value)))).toBeCloseTo(value, 9);
      expect(metersToKm(kmToMeters(km(value)))).toBeCloseTo(value, 9);
      expect(kphToMph(mphToKph(mph(value)))).toBeCloseTo(value, 9);
      expect(celsiusToFahrenheit(fahrenheitToCelsius(fahrenheit(value)))).toBeCloseTo(value, 9);
    }
  });

  it('keeps zero and negatives intact', () => {
    // Distances are never negative, but temperatures and power certainly are, and a
    // conversion written with Math.abs somewhere would pass every positive-only check.
    expect(milesToKm(miles(0))).toBe(0);
    expect(celsiusToFahrenheit(celsius(-17.7778))).toBeCloseTo(0, 3);
    expect(kmToMiles(km(-10))).toBeCloseTo(-6.213712, 6);
  });
});
