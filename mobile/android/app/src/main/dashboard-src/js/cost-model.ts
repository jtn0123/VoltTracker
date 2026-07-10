// cost-model.ts — the single source of truth for the EV cost / savings-vs-gas
// math, shared by the lifetime Insights figure (insights-panel.ts) and the
// per-trip figure in the map trip-detail sheet (map.ts).
//
// Both surfaces MUST use the same underlying formula so a per-trip estimate and
// the lifetime estimate never contradict each other — the only difference is
// which distance they feed in (one drive vs every logged drive). Keeping the
// constants + math here means there is exactly one place to change the
// assumption, and a divergent second cost model can't creep in.
//
// Pure (no DOM, no globals) so it's trivially unit-testable and is safe to pull
// into both the eager bundle (insights-panel) and the lazy map chunk without
// dragging anything heavy along.

// Assumed Volt drive efficiency, used because the insights/route payloads don't
// carry the EV energy actually used while driving. A real-world Gen-2 Volt
// averages roughly 3.5 mi/kWh of usable energy; the resulting figure is an
// estimate and callers surface the assumed mi/kWh so it reads as approximate.
export const ASSUMED_VOLT_MI_PER_KWH = 3.5;
export const METERS_PER_MILE = 1609.344;

export type SavingsInputs = {
  /** Distance for the estimate, in meters (one trip, or lifetime total). */
  meters: number;
  /** Comparison gas vehicle fuel economy, mpg. */
  mpg: number;
  /** Comparison gas price, $/gal. */
  gasPricePerGal: number;
  /** Electricity rate, $/kWh. */
  pricePerKwh: number;
  /** Logged energy for the same distance. Preferred when finite and positive. */
  energyKwh?: number;
};

export type SavingsResult = {
  /** Gas cost of the same distance in the comparison car, $. */
  gasCost: number;
  /** Estimated EV electricity cost for the distance, $. */
  evCost: number;
  /** gasCost - evCost; negative when electricity was pricier than the gas. */
  savings: number;
  energySource: "logged" | "estimated";
};

/** True when every pref the savings math needs is set to a usable value. The
 *  per-trip and lifetime surfaces gate on this identically. */
export function savingsPrefsReady(
  mpg: number,
  gasPricePerGal: number,
  pricePerKwh: number
): boolean {
  return mpg > 0 && gasPricePerGal > 0 && pricePerKwh > 0;
}

/** The shared gas-vs-EV cost/savings formula. EV energy is estimated from
 *  distance via ASSUMED_VOLT_MI_PER_KWH (energy isn't in the payload). Callers
 *  must check savingsPrefsReady() first; with a zero mpg this would divide by
 *  zero. */
export function computeSavingsVsGas(inputs: SavingsInputs): SavingsResult {
  const miles = inputs.meters / METERS_PER_MILE;
  const gasCost = (miles / inputs.mpg) * inputs.gasPricePerGal;
  const hasLoggedEnergy = Number.isFinite(inputs.energyKwh) && Number(inputs.energyKwh) > 0;
  const evEnergyKwh = hasLoggedEnergy ? Number(inputs.energyKwh) : miles / ASSUMED_VOLT_MI_PER_KWH;
  const evCost = evEnergyKwh * inputs.pricePerKwh;
  return { gasCost, evCost, savings: gasCost - evCost, energySource: hasLoggedEnergy ? "logged" : "estimated" };
}

/** Format a signed dollar amount as the dashboard does elsewhere: a leading
 *  "-$" only when negative, two decimals. Shared so the per-trip and lifetime
 *  rows read identically. */
export function formatSignedMoney(value: number): string {
  // Snap near-zero to positive: a value like -0.004 rounds to "0.00", so the raw
  // `value < 0` sign test yielded a nonsensical "-$0.00". Only commit to "-$" once
  // the magnitude actually rounds to a nonzero cent.
  return (value < -0.005 ? "-$" : "$") + Math.abs(value).toFixed(2);
}

// ----- per-charge-session rate selection (M3) -----------------------------
// Many Volt owners pay a cheap overnight/home (TOU) rate but an expensive
// public / DC-fast rate. When the user has set an optional public rate, a
// charge session billed at a public/DCFC charger should cost the public rate;
// everything else (home Level 1/2, unknown) uses the single home rate. With no
// public rate set, every session falls back to the home rate so behaviour is
// unchanged for users who never set one.

/** Does this charger type bill at the public/DCFC rate? Recognizes the canonical
 *  "dc_fast"/"dcfast" keys plus loose free-text ("DC fast (CCS…)", "public",
 *  "supercharger"). Normalizes spacing/case/hyphens like chargerLabel does. */
export function isPublicChargerType(chargerType: unknown): boolean {
  const key = String(chargerType == null ? "" : chargerType)
    .trim()
    .toLowerCase()
    .replace(/[\s-]+/g, "_");
  if (!key) return false;
  return (
    key.includes("dc_fast") ||
    key.includes("dcfast") ||
    key.includes("dcfc") ||
    key.includes("ccs") ||
    key.includes("chademo") ||
    key.includes("supercharger") ||
    key.includes("public")
  );
}

/** Pick the $/kWh rate for one charge session by its charger type. Returns the
 *  public rate for public/DCFC chargers when a positive public rate is set;
 *  otherwise the home rate. A non-positive (unset) publicRate always falls back
 *  to homeRate, so users who never set a public rate see no change. */
export function rateForCharger(
  chargerType: unknown,
  homeRate: number,
  publicRate: number
): number {
  if (publicRate > 0 && isPublicChargerType(chargerType)) return publicRate;
  return homeRate;
}
