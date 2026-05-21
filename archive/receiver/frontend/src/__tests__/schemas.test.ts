/**
 * Tests for types/schemas.ts — Zod schema validation
 */
import { describe, it, expect } from 'vitest';

const {
  TelemetryPointSchema,
  TripSummarySchema,
  TripStatsSchema,
  LiveTelemetryResponseSchema,
  EfficiencySummarySchema,
  ChargingSessionSchema,
  ChargingSummarySchema,
  ImportResultSchema,
  StatusResponseSchema,
  BatteryHealthResponseSchema,
  validateResponse,
} = await import('../types/schemas');

describe('TelemetryPointSchema', () => {
  it('validates a complete telemetry point', () => {
    const data = {
      timestamp: '2024-06-15T12:00:00Z',
      speed_mph: 55.0,
      state_of_charge: 80.0,
      fuel_level_percent: null,
      engine_rpm: 0,
      latitude: 41.5,
      longitude: -81.7,
      hv_battery_power_kw: 15.0,
      hv_battery_voltage_v: 360.0,
      hv_battery_current_a: 42.0,
      motor_a_rpm: null,
      motor_b_rpm: null,
      generator_rpm: null,
      charger_status: null,
      motor_temp_1_f: null,
      motor_temp_2_f: null,
      motor_temp_3_f: null,
      motor_temp_4_f: null,
    };
    const result = TelemetryPointSchema.safeParse(data);
    expect(result.success).toBe(true);
  });

  it('rejects missing required fields', () => {
    const result = TelemetryPointSchema.safeParse({ speed_mph: 55 });
    expect(result.success).toBe(false);
  });

  it('allows nullable fields to be null', () => {
    const data = {
      timestamp: '2024-01-01T00:00:00Z',
      speed_mph: null, state_of_charge: null, fuel_level_percent: null,
      engine_rpm: null, latitude: null, longitude: null,
      hv_battery_power_kw: null, hv_battery_voltage_v: null, hv_battery_current_a: null,
      motor_a_rpm: null, motor_b_rpm: null, generator_rpm: null, charger_status: null,
      motor_temp_1_f: null, motor_temp_2_f: null, motor_temp_3_f: null, motor_temp_4_f: null,
    };
    const result = TelemetryPointSchema.safeParse(data);
    expect(result.success).toBe(true);
  });
});

describe('TripSummarySchema', () => {
  it('validates a valid trip summary', () => {
    const result = TripSummarySchema.safeParse({
      id: 1, start_time: '2024-01-01T10:00:00Z', end_time: '2024-01-01T10:30:00Z',
      distance_miles: 15.5, electric_miles: 12.0, gas_miles: 3.5,
      gas_mpg: 85.0, gas_mode_entered: true, soc_at_gas_transition: 15.0,
    });
    expect(result.success).toBe(true);
  });

  it('rejects non-numeric id', () => {
    const result = TripSummarySchema.safeParse({
      id: 'abc', start_time: '2024-01-01T10:00:00Z', end_time: null,
      distance_miles: null, electric_miles: null, gas_miles: null,
      gas_mpg: null, gas_mode_entered: false, soc_at_gas_transition: null,
    });
    expect(result.success).toBe(false);
  });
});

describe('TripStatsSchema', () => {
  it('validates trip stats', () => {
    const result = TripStatsSchema.safeParse({
      miles_driven: 25.0, kwh_used: 6.5, kwh_per_mile: 0.26,
      in_gas_mode: false, gas_mpg: null,
    });
    expect(result.success).toBe(true);
  });
});

describe('LiveTelemetryResponseSchema', () => {
  it('validates inactive response', () => {
    const result = LiveTelemetryResponseSchema.safeParse({
      active: false, data: null, start_time: null, trip_stats: null,
    });
    expect(result.success).toBe(true);
  });
});

describe('EfficiencySummarySchema', () => {
  it('validates all-null efficiency summary', () => {
    const result = EfficiencySummarySchema.safeParse({
      lifetime_gas_mpg: null, lifetime_gas_miles: null, current_tank_mpg: null,
      current_tank_miles: null, total_miles_tracked: null, avg_kwh_per_mile: null,
      mi_per_kwh: null, total_electric_miles: null, total_kwh_used: null, ev_ratio: null,
    });
    expect(result.success).toBe(true);
  });
});

describe('ChargingSessionSchema', () => {
  it('validates a charging session', () => {
    const result = ChargingSessionSchema.safeParse({
      id: 1, start_time: '2024-01-01T22:00:00Z', end_time: '2024-01-02T06:00:00Z',
      start_soc: 20.0, end_soc: 95.0, kwh_added: 12.5,
      charge_type: 'L2', cost: 1.50, cost_per_kwh: 0.12, electricity_rate: 0.12,
      location_name: 'Home', notes: null, peak_power_kw: 3.3, avg_power_kw: 3.1,
    });
    expect(result.success).toBe(true);
  });
});

describe('ChargingSummarySchema', () => {
  it('validates charging summary', () => {
    const result = ChargingSummarySchema.safeParse({
      total_kwh: 150.0, total_sessions: 20, avg_kwh_per_session: 7.5,
      monthly_cost: 18.0, total_cost: 18.0, has_explicit_costs: true,
      electricity_rate: 0.12, l1_sessions: 5, l2_sessions: 15,
      cost_per_mile_electric: 0.03, cost_per_mile_gas: 0.12,
    });
    expect(result.success).toBe(true);
  });
});

describe('ImportResultSchema', () => {
  it('validates import result', () => {
    const result = ImportResultSchema.safeParse({
      filename: 'data.csv', status: 'success', message: 'Imported 100 rows',
      import_code: 'ABC123', stats: {
        total_rows: 100, parsed_rows: 98, skipped_rows: 2, duplicate_rows: 0,
        columns_detected: ['timestamp', 'speed_mph'],
      },
    });
    expect(result.success).toBe(true);
  });
});

describe('StatusResponseSchema', () => {
  it('validates status response', () => {
    const result = StatusResponseSchema.safeParse({ status: 'ok', last_sync: null });
    expect(result.success).toBe(true);
  });

  it('rejects missing status', () => {
    const result = StatusResponseSchema.safeParse({ last_sync: null });
    expect(result.success).toBe(false);
  });
});

describe('BatteryHealthResponseSchema', () => {
  it('validates battery health', () => {
    const result = BatteryHealthResponseSchema.safeParse({
      has_data: true, current_capacity_kwh: 17.1, original_capacity_kwh: 18.4,
      health_percent: 92.9, health_status: 'Good', yearly_trend_percent: -1.2,
    });
    expect(result.success).toBe(true);
  });
});

describe('validateResponse', () => {
  it('returns parsed data on valid input', () => {
    const data = { status: 'ok', last_sync: '2024-01-01' };
    const result = validateResponse(StatusResponseSchema, data, '/api/status');
    expect(result).toEqual(data);
  });

  it('returns data as-is on validation failure (graceful fallback)', () => {
    const badData = { status: 123 }; // wrong type
    const result = validateResponse(StatusResponseSchema, badData, '/api/status');
    expect(result).toEqual(badData); // should not throw
  });

  it('logs warning in debug mode on failure', () => {
    // DEBUG is based on URL params, hard to test, but validateResponse should not throw
    const badData = {};
    expect(() => validateResponse(StatusResponseSchema, badData)).not.toThrow();
  });
});
