// unit-types.ts — distance/speed/temperature quantities that cannot be mixed up.
//
// Every measurement in this app is a bare `number`, so nothing stops a value in miles
// reaching a parameter that means kilometres. The compiler is happy, the arithmetic runs,
// and the screen is wrong by a factor of 1.609 — the kind of wrong that looks plausible,
// which is the worst kind. The units module made this especially easy to hit: distanceKm,
// distanceMeters and distanceMiles are three entry points that differ ONLY by the unit of
// their argument, and all three accepted the same `number`.
//
// Branding attaches a phantom tag that exists only at compile time. `Km` is still a plain
// number at runtime — zero cost, no wrappers, no allocation — but `Miles` is no longer
// assignable to it.
//
// What this does and does not buy:
//   - It DOES stop a value flowing from one unit into another once tagged. That is where
//     the silent factor-of-1.609 bugs live.
//   - It does NOT verify the initial tag. `km(x)` is a cast; if you tag miles as Km you get
//     the wrong answer. The point is that tagging is a visible, single-line claim at the
//     boundary instead of an invisible assumption threaded through the call graph.
//
// Conversions live here too, so `* 1.609344` stops being copy-pasted at each call site with
// its direction inferred from a variable name.

declare const unitBrand: unique symbol;

/** A number tagged with the unit it is measured in. Erased at runtime. */
type Quantity<Unit extends string> = number & { readonly [unitBrand]: Unit };

export type Km = Quantity<"km">;
export type Miles = Quantity<"mi">;
export type Meters = Quantity<"m">;
export type Kph = Quantity<"kph">;
export type Mph = Quantity<"mph">;
export type Celsius = Quantity<"C">;
export type Fahrenheit = Quantity<"F">;

// Exact by definition (international mile), not rounded — the reciprocal is derived rather
// than written as its own literal so the two directions cannot drift apart.
const KM_PER_MILE = 1.609344;
const MI_PER_KM = 1 / KM_PER_MILE;

// --- tagging at the boundary --------------------------------------------------------------
// Use these where a raw number first becomes a measurement: a native payload field, a parsed
// user input, a literal. Each call is an assertion about what the number means.
export const km = (value: number): Km => value as Km;
export const miles = (value: number): Miles => value as Miles;
export const meters = (value: number): Meters => value as Meters;
export const kph = (value: number): Kph => value as Kph;
export const mph = (value: number): Mph => value as Mph;
export const celsius = (value: number): Celsius => value as Celsius;
export const fahrenheit = (value: number): Fahrenheit => value as Fahrenheit;

// --- conversions ---------------------------------------------------------------------------
export const kmToMiles = (value: Km): Miles => (value * MI_PER_KM) as Miles;
export const milesToKm = (value: Miles): Km => (value * KM_PER_MILE) as Km;
export const metersToKm = (value: Meters): Km => (value / 1000) as Km;
export const kmToMeters = (value: Km): Meters => (value * 1000) as Meters;
export const kphToMph = (value: Kph): Mph => (value * MI_PER_KM) as Mph;
export const mphToKph = (value: Mph): Kph => (value * KM_PER_MILE) as Kph;
export const celsiusToFahrenheit = (value: Celsius): Fahrenheit =>
  ((value * 9) / 5 + 32) as Fahrenheit;
export const fahrenheitToCelsius = (value: Fahrenheit): Celsius =>
  (((value - 32) * 5) / 9) as Celsius;
