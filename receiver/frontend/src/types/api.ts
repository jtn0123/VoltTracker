/**
 * TypeScript interfaces for VoltTracker API data shapes
 */

/** Telemetry point from /api/trips/:id or /api/telemetry/latest */
export interface TelemetryPoint {
  timestamp: string;
  speed_mph: number | null;
  state_of_charge: number | null;
  fuel_level_percent: number | null;
  engine_rpm: number | null;
  latitude: number | null;
  longitude: number | null;
  hv_battery_power_kw: number | null;
  hv_battery_voltage_v: number | null;
  hv_battery_current_a: number | null;
  motor_a_rpm: number | null;
  motor_b_rpm: number | null;
  generator_rpm: number | null;
  charger_status: number | null;
  motor_temp_1_f: number | null;
  motor_temp_2_f: number | null;
  motor_temp_3_f: number | null;
  motor_temp_4_f: number | null;
  soc?: number;
  fuel_percent?: number;
}

/** Trip summary from /api/trips */
export interface TripSummary {
  id: number;
  start_time: string;
  end_time: string | null;
  distance_miles: number | null;
  electric_miles: number | null;
  gas_miles: number | null;
  gas_mpg: number | null;
  gas_mode_entered: boolean;
  soc_at_gas_transition: number | null;
}

/** Trip detail from /api/trips/:id */
export interface TripDetail {
  trip: TripSummary;
  telemetry: TelemetryPoint[];
}

/** Efficiency summary from /api/efficiency/summary */
export interface EfficiencySummary {
  lifetime_gas_mpg: number | null;
  lifetime_gas_miles: number | null;
  current_tank_mpg: number | null;
  current_tank_miles: number | null;
  total_miles_tracked: number | null;
  avg_kwh_per_mile: number | null;
  mi_per_kwh: number | null;
  total_electric_miles: number | null;
  total_kwh_used: number | null;
  ev_ratio: number | null;
}

/** MPG trend data point from /api/mpg/trend */
export interface MpgTrendPoint {
  date: string;
  mpg: number;
  gas_miles: number;
  ambient_temp: number | null;
}

/** Live telemetry from /api/telemetry/latest */
export interface LiveTelemetryResponse {
  active: boolean;
  data: TelemetryPoint | null;
  start_time: string | null;
  trip_stats: TripStats | null;
}

export interface TripStats {
  miles_driven: number | null;
  kwh_used: number | null;
  kwh_per_mile: number | null;
  in_gas_mode: boolean;
  gas_mpg: number | null;
}

/** Status from /api/status */
export interface StatusResponse {
  status: string;
  last_sync: string | null;
}

/** Charging session from /api/charging/history */
export interface ChargingSession {
  id: number;
  start_time: string;
  end_time: string | null;
  start_soc: number | null;
  end_soc: number | null;
  kwh_added: number | null;
  charge_type: string | null;
  cost: number | null;
  cost_per_kwh: number | null;
  electricity_rate: number | null;
  location_name: string | null;
  notes: string | null;
  peak_power_kw: number | null;
  avg_power_kw: number | null;
}

/** Charging summary from /api/charging/summary */
export interface ChargingSummary {
  total_kwh: number | null;
  total_sessions: number;
  avg_kwh_per_session: number | null;
  monthly_cost: number | null;
  total_cost: number | null;
  has_explicit_costs: boolean;
  electricity_rate: number | null;
  l1_sessions: number;
  l2_sessions: number;
  cost_per_mile_electric: number | null;
  cost_per_mile_gas: number | null;
}

/** Charging curve from /api/charging/:id/curve */
export interface ChargingCurveResponse {
  curve: ChargingCurvePoint[];
}

export interface ChargingCurvePoint {
  timestamp: string;
  power_kw: number;
  soc: number;
}

/** Battery health from /api/battery/health */
export interface BatteryHealthResponse {
  has_data: boolean;
  current_capacity_kwh: number | null;
  original_capacity_kwh: number | null;
  health_percent: number | null;
  health_status: string | null;
  yearly_trend_percent: number | null;
}

/** Battery cells from /api/battery/cells/latest */
export interface BatteryCellsResponse {
  reading: CellReading | null;
}

export interface CellReading {
  timestamp: string;
  cell_voltages: (number | null)[];
  voltage_delta: number | null;
  min_voltage: number | null;
  max_voltage: number | null;
  avg_voltage: number | null;
  module1_avg: number | null;
  module2_avg: number | null;
  module3_avg: number | null;
}

/** SOC analysis from /api/soc/analysis */
export interface SocAnalysis {
  average_soc: number | null;
  min_soc: number | null;
  max_soc: number | null;
  count: number;
  temperature_correlation: {
    cold_avg_soc: number;
    cold_count: number;
    warm_avg_soc: number;
    warm_count: number;
  } | null;
  trend: {
    direction: string;
    early_avg: number;
    recent_avg: number;
  } | null;
  histogram: Record<string, number> | null;
}

/** Import result from /api/import/csv */
export interface ImportResult {
  filename?: string;
  status: string;
  message: string;
  import_code: string | null;
  original_import_code?: string;
  trip_id?: number;
  failure_reason?: string;
  suggestion?: string;
  reportable?: string;
  stats: ImportStats;
}

export interface ImportStats {
  total_rows: number;
  parsed_rows: number;
  skipped_rows: number;
  duplicate_rows: number;
  columns_detected: string[];
}

/** WebSocket telemetry event data */
export interface WsTelemetryData {
  speed: number | null;
  rpm: number | null;
  soc: number | null;
  fuel_percent: number | null;
  hv_power: number | null;
}

/** WebSocket toast event data */
export interface WsToastData {
  message: string;
  type: string;
  duration?: number;
  actions?: ToastAction[];
}
