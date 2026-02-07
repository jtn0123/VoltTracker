/**
 * VoltTracker - Shared Enums
 * Replaces magic strings used across the application
 */

export enum ConnectionStatus {
  Connected = 'connected',
  Disconnected = 'disconnected',
  Live = 'live',
}

export enum Theme {
  Dark = 'dark',
  Light = 'light',
}

export enum TimeFrame {
  Week = 7,
  TwoWeeks = 14,
  Month = 30,
  Quarter = 90,
  Year = 365,
}

export enum ImportStatus {
  Success = 'success',
  Partial = 'partial',
  Duplicate = 'duplicate',
  Failed = 'failed',
}

export enum ChargingType {
  Level1 = 'L1',
  Level2 = 'L2',
  DCFC = 'DCFC',
}

export enum PowerFlowState {
  Idle = 'idle',
  Discharging = 'discharging',
  Regenerating = 'regenerating',
  Charging = 'charging',
}
