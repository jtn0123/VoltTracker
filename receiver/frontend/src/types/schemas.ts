/**
 * VoltTracker - Zod Schemas for API Response Validation
 * TypeScript types are derived from these schemas via z.infer<>
 */

import { z } from 'zod';

const DEBUG = new URLSearchParams(window.location.search).has('debug');

// ── Telemetry ──────────────────────────────────────────────

export const TelemetryPointSchema = z.object({
  timestamp: z.string(),
  speed_mph: z.number().nullable(),
  state_of_charge: z.number().nullable(),
  fuel_level_percent: z.number().nullable(),
  engine_rpm: z.number().nullable(),
  latitude: z.number().nullable(),
  longitude: z.number().nullable(),
  hv_battery_power_kw: z.number().nullable(),
  hv_battery_voltage_v: z.number().nullable(),
  hv_battery_current_a: z.number().nullable(),
  motor_a_rpm: z.number().nullable(),
  motor_b_rpm: z.number().nullable(),
  generator_rpm: z.number().nullable(),
  charger_status: z.number().nullable(),
  motor_temp_1_f: z.number().nullable(),
  motor_temp_2_f: z.number().nullable(),
  motor_temp_3_f: z.number().nullable(),
  motor_temp_4_f: z.number().nullable(),
  soc: z.number().optional(),
  fuel_percent: z.number().optional(),
});

// ── Trips ──────────────────────────────────────────────────

export const TripSummarySchema = z.object({
  id: z.number(),
  start_time: z.string(),
  end_time: z.string().nullable(),
  distance_miles: z.number().nullable(),
  electric_miles: z.number().nullable(),
  gas_miles: z.number().nullable(),
  gas_mpg: z.number().nullable(),
  gas_mode_entered: z.boolean(),
  soc_at_gas_transition: z.number().nullable(),
});

export const TripStatsSchema = z.object({
  miles_driven: z.number().nullable(),
  kwh_used: z.number().nullable(),
  kwh_per_mile: z.number().nullable(),
  in_gas_mode: z.boolean(),
  gas_mpg: z.number().nullable(),
});

export const TripDetailSchema = z.object({
  trip: TripSummarySchema,
  telemetry: z.array(TelemetryPointSchema),
});

// ── Live Telemetry ─────────────────────────────────────────

export const LiveTelemetryResponseSchema = z.object({
  active: z.boolean(),
  data: TelemetryPointSchema.nullable(),
  start_time: z.string().nullable(),
  trip_stats: TripStatsSchema.nullable(),
});

// ── Efficiency ─────────────────────────────────────────────

export const EfficiencySummarySchema = z.object({
  lifetime_gas_mpg: z.number().nullable(),
  lifetime_gas_miles: z.number().nullable(),
  current_tank_mpg: z.number().nullable(),
  current_tank_miles: z.number().nullable(),
  total_miles_tracked: z.number().nullable(),
  avg_kwh_per_mile: z.number().nullable(),
  mi_per_kwh: z.number().nullable(),
  total_electric_miles: z.number().nullable(),
  total_kwh_used: z.number().nullable(),
  ev_ratio: z.number().nullable(),
});

// ── MPG Trend ──────────────────────────────────────────────

export const MpgTrendPointSchema = z.object({
  date: z.string(),
  mpg: z.number(),
  gas_miles: z.number(),
  ambient_temp: z.number().nullable(),
});

// ── Charging ───────────────────────────────────────────────

export const ChargingSessionSchema = z.object({
  id: z.number(),
  start_time: z.string(),
  end_time: z.string().nullable(),
  start_soc: z.number().nullable(),
  end_soc: z.number().nullable(),
  kwh_added: z.number().nullable(),
  charge_type: z.string().nullable(),
  cost: z.number().nullable(),
  cost_per_kwh: z.number().nullable(),
  electricity_rate: z.number().nullable(),
  location_name: z.string().nullable(),
  notes: z.string().nullable(),
  peak_power_kw: z.number().nullable(),
  avg_power_kw: z.number().nullable(),
});

export const ChargingSummarySchema = z.object({
  total_kwh: z.number().nullable(),
  total_sessions: z.number(),
  avg_kwh_per_session: z.number().nullable(),
  monthly_cost: z.number().nullable(),
  total_cost: z.number().nullable(),
  has_explicit_costs: z.boolean(),
  electricity_rate: z.number().nullable(),
  l1_sessions: z.number(),
  l2_sessions: z.number(),
  cost_per_mile_electric: z.number().nullable(),
  cost_per_mile_gas: z.number().nullable(),
});

export const ChargingCurvePointSchema = z.object({
  timestamp: z.string(),
  power_kw: z.number(),
  soc: z.number(),
});

export const ChargingCurveResponseSchema = z.object({
  curve: z.array(ChargingCurvePointSchema),
});

// ── Battery ────────────────────────────────────────────────

export const BatteryHealthResponseSchema = z.object({
  has_data: z.boolean(),
  current_capacity_kwh: z.number().nullable(),
  original_capacity_kwh: z.number().nullable(),
  health_percent: z.number().nullable(),
  health_status: z.string().nullable(),
  yearly_trend_percent: z.number().nullable(),
});

export const CellReadingSchema = z.object({
  timestamp: z.string(),
  cell_voltages: z.array(z.number().nullable()),
  voltage_delta: z.number().nullable(),
  min_voltage: z.number().nullable(),
  max_voltage: z.number().nullable(),
  avg_voltage: z.number().nullable(),
  module1_avg: z.number().nullable(),
  module2_avg: z.number().nullable(),
  module3_avg: z.number().nullable(),
});

export const BatteryCellsResponseSchema = z.object({
  reading: CellReadingSchema.nullable(),
});

// ── SOC Analysis ───────────────────────────────────────────

export const SocAnalysisSchema = z.object({
  average_soc: z.number().nullable(),
  min_soc: z.number().nullable(),
  max_soc: z.number().nullable(),
  count: z.number(),
  temperature_correlation: z
    .object({
      cold_avg_soc: z.number(),
      cold_count: z.number(),
      warm_avg_soc: z.number(),
      warm_count: z.number(),
    })
    .nullable(),
  trend: z
    .object({
      direction: z.string(),
      early_avg: z.number(),
      recent_avg: z.number(),
    })
    .nullable(),
  histogram: z.record(z.string(), z.number()).nullable(),
});

// ── Import ─────────────────────────────────────────────────

export const ImportStatsSchema = z.object({
  total_rows: z.number(),
  parsed_rows: z.number(),
  skipped_rows: z.number(),
  duplicate_rows: z.number(),
  columns_detected: z.array(z.string()),
});

export const ImportResultSchema = z.object({
  filename: z.string().optional(),
  status: z.string(),
  message: z.string(),
  import_code: z.string().nullable(),
  original_import_code: z.string().optional(),
  trip_id: z.number().optional(),
  failure_reason: z.string().optional(),
  suggestion: z.string().optional(),
  reportable: z.string().optional(),
  stats: ImportStatsSchema,
});

// ── Status ─────────────────────────────────────────────────

export const StatusResponseSchema = z.object({
  status: z.string(),
  last_sync: z.string().nullable(),
});

// ── Validation Helper ──────────────────────────────────────

/**
 * Validate API response data against a Zod schema.
 * On failure: logs a warning in debug mode but still returns the data
 * so the app doesn't break on unexpected API changes.
 */
export function validateResponse<T>(schema: z.ZodType<T>, data: unknown, endpoint?: string): T {
  const result = schema.safeParse(data);
  if (!result.success) {
    if (DEBUG) {
      console.warn(
        `[Validation] ${endpoint || 'API'} response validation failed:`,
        result.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`),
      );
    }
    // Return data as-is so the app keeps working
    return data as T;
  }
  return result.data;
}
