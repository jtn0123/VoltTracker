/**
 * TypeScript interfaces for VoltTracker API data shapes
 * Types are derived from Zod schemas for runtime validation
 */

import { z } from 'zod';
import {
  TelemetryPointSchema,
  TripSummarySchema,
  TripDetailSchema,
  TripStatsSchema,
  EfficiencySummarySchema,
  MpgTrendPointSchema,
  LiveTelemetryResponseSchema,
  StatusResponseSchema,
  ChargingSessionSchema,
  ChargingSummarySchema,
  ChargingCurveResponseSchema,
  ChargingCurvePointSchema,
  BatteryHealthResponseSchema,
  BatteryCellsResponseSchema,
  CellReadingSchema,
  SocAnalysisSchema,
  ImportResultSchema,
  ImportStatsSchema,
} from '@/types/schemas';

/** Telemetry point from /api/trips/:id or /api/telemetry/latest */
export type TelemetryPoint = z.infer<typeof TelemetryPointSchema>;

/** Trip summary from /api/trips */
export type TripSummary = z.infer<typeof TripSummarySchema>;

/** Trip detail from /api/trips/:id */
export type TripDetail = z.infer<typeof TripDetailSchema>;

export type TripStats = z.infer<typeof TripStatsSchema>;

/** Efficiency summary from /api/efficiency/summary */
export type EfficiencySummary = z.infer<typeof EfficiencySummarySchema>;

/** MPG trend data point from /api/mpg/trend */
export type MpgTrendPoint = z.infer<typeof MpgTrendPointSchema>;

/** Live telemetry from /api/telemetry/latest */
export type LiveTelemetryResponse = z.infer<typeof LiveTelemetryResponseSchema>;

/** Status from /api/status */
export type StatusResponse = z.infer<typeof StatusResponseSchema>;

/** Charging session from /api/charging/history */
export type ChargingSession = z.infer<typeof ChargingSessionSchema>;

/** Charging summary from /api/charging/summary */
export type ChargingSummary = z.infer<typeof ChargingSummarySchema>;

/** Charging curve from /api/charging/:id/curve */
export type ChargingCurveResponse = z.infer<typeof ChargingCurveResponseSchema>;

export type ChargingCurvePoint = z.infer<typeof ChargingCurvePointSchema>;

/** Battery health from /api/battery/health */
export type BatteryHealthResponse = z.infer<typeof BatteryHealthResponseSchema>;

/** Battery cells from /api/battery/cells/latest */
export type BatteryCellsResponse = z.infer<typeof BatteryCellsResponseSchema>;

export type CellReading = z.infer<typeof CellReadingSchema>;

/** SOC analysis from /api/soc/analysis */
export type SocAnalysis = z.infer<typeof SocAnalysisSchema>;

/** Import result from /api/import/csv */
export type ImportResult = z.infer<typeof ImportResultSchema>;

export type ImportStats = z.infer<typeof ImportStatsSchema>;

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
