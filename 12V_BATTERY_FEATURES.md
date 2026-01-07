# 12V Auxiliary Battery Monitoring Features

This document describes the comprehensive 12V auxiliary battery monitoring system added to VoltTracker.

## Overview

The 12V AGM battery in the Chevy Volt is critical for vehicle operation. A weak or failing 12V battery can cause:
- Multiple fault codes (P1FFF, P0700, P079A, P077B, P0AC4)
- Sensor malfunctions and false error messages
- Starting and unlocking issues
- General electrical system instability

This feature set provides comprehensive monitoring, health tracking, anomaly detection, and replacement forecasting for the 12V battery.

## Features Implemented

### 1. Health Tracking Database Models

**File:** `receiver/models.py`

Two new database models:

#### AuxBatteryHealthReading
Tracks 12V battery voltage over time with environmental context:
- Voltage readings (at rest and while charging)
- Charging status (charger connected, engine running)
- Current draw (if available from OBD)
- Environmental context (temperature, odometer, HV battery SOC)
- Health status calculation (healthy/warning/critical)
- Health percentage estimation

**Health Thresholds:**
- **Healthy**: ≥12.4V at rest, ≥13.2V when charging
- **Warning**: 12.0-12.4V at rest, 12.6-13.2V when charging
- **Critical**: <12.0V at rest, <12.6V when charging

#### AuxBatteryEvent
Logs battery anomalies and events:
- Event types: low_voltage, voltage_drop, charging_issue, parasitic_drain
- Severity levels: info, warning, critical
- Event details (voltage, voltage change, duration)
- Resolution tracking

### 2. Service Layer

**File:** `receiver/services/auxiliary_battery_service.py`

Comprehensive service functions:

#### Health Calculation
- `calculate_battery_health()` - Overall health status with trends
- `get_voltage_statistics()` - Min/max/avg voltage analysis
- Trend detection (improving/stable/declining)
- Unresolved event tracking

#### Anomaly Detection
- `detect_voltage_anomalies()` - Real-time voltage monitoring
- Detects:
  - **Sudden voltage drops** (>0.5V change)
  - **Sustained low voltage** (5+ consecutive readings <11.5V)
  - **Overcharge conditions** (>14.8V, potential alternator issue)
  - **Parasitic drain** (voltage drop at rest)

#### Replacement Forecasting
- `forecast_replacement_timing()` - Predict replacement needs
- **Time-based forecast**: Typical AGM lifespan (3-5 years)
- **Voltage-based forecast**: Linear regression on voltage degradation
- Urgency assessment (low/medium/high)
- Specific replacement window recommendations

#### Event Management
- `log_battery_event()` - Log events with context
- `get_recent_events()` - Query events by timeframe and severity
- Resolution tracking

### 3. API Endpoints

**File:** `receiver/routes/auxiliary_battery.py`

RESTful API for 12V battery data:

#### GET /api/battery/auxiliary/health
Get current health status with trends and recommendations

**Response:**
```json
{
  "current_voltage": 12.5,
  "health_status": "healthy",
  "health_percentage": 90,
  "is_charging": false,
  "avg_rest_voltage_30d": 12.48,
  "voltage_trend": "stable",
  "rest_readings_30d": 450,
  "unresolved_events": 0,
  "recommendations": [],
  "thresholds": {
    "healthy_rest": 12.4,
    "warning_rest": 12.0,
    "critical_rest": 11.5,
    "healthy_charging": 13.2
  }
}
```

#### GET /api/battery/auxiliary/voltage/history
Get voltage readings over time

**Query Params:**
- `days` - Lookback period (default: 30, max: 365)
- `at_rest` - Filter for at-rest readings only (true/false)

#### GET /api/battery/auxiliary/forecast
Get replacement timing forecast

**Response:**
```json
{
  "time_based_forecast": {
    "estimated_age_days": 730,
    "estimated_age_years": 2.0,
    "typical_lifespan_years": "3-5 years",
    "days_to_minimum_lifespan": 365,
    "replacement_window_start": "2027-01-07",
    "replacement_window_end": "2028-01-07"
  },
  "voltage_based_forecast": {
    "days_remaining": 450,
    "estimated_date": "2027-04-01",
    "degradation_rate_per_year": -0.15
  },
  "voltage_trend": "declining",
  "recommendation": "Voltage-based forecast indicates replacement needed in ~450 days",
  "urgency": "medium"
}
```

#### GET /api/battery/auxiliary/events
Get recent battery events (anomalies, warnings)

**Query Params:**
- `days` - Lookback period (default: 7)
- `severity` - Filter by severity (info/warning/critical/all)
- `unresolved_only` - Show only unresolved events (true/false)

#### POST /api/battery/auxiliary/events/<event_id>/resolve
Mark an event as resolved

**Body:**
```json
{
  "resolution_notes": "Replaced battery, issue resolved"
}
```

#### POST /api/battery/auxiliary/events/log
Manually log a battery event

**Body:**
```json
{
  "event_type": "user_reported",
  "severity": "warning",
  "description": "Noticed slow cranking this morning",
  "voltage_v": 12.1,
  "timestamp": "2026-01-07T10:30:00Z"
}
```

#### GET /api/battery/auxiliary/latest
Get the most recent voltage reading

#### GET /api/battery/auxiliary/statistics
Get voltage statistics for a period

### 4. Background Monitoring

**File:** `receiver/services/scheduler.py`

Added `monitor_12v_battery()` scheduled task:
- Runs every 5 minutes
- Samples voltage from recent telemetry
- Creates AuxBatteryHealthReading records
- Detects and logs anomalies
- Prevents duplicate event logging

**Sampling Strategy:**
- Samples every ~5th reading to avoid excessive database growth
- Always includes latest reading for immediate tracking
- Analyzes last 15 minutes of telemetry for anomalies

### 5. Database Migration

**File:** `receiver/migrations/005_add_aux_battery_monitoring.sql`

PostgreSQL migration script:
- Creates `aux_battery_health_readings` table
- Creates `aux_battery_events` table
- Adds appropriate indexes for time-series queries
- Includes comprehensive table and column comments

**Indexes:**
- `ix_aux_battery_health_readings_timestamp` - Fast time-series queries
- `ix_aux_battery_events_timestamp` - Event timeline queries
- `ix_aux_battery_events_type_timestamp` - Event type filtering
- `ix_aux_battery_events_severity` - Severity filtering

### 6. Comprehensive Tests

**File:** `tests/test_auxiliary_battery_service.py`

Test coverage for all major features:
- Health reading storage and retrieval
- Health status calculation
- Anomaly detection (voltage drops, low voltage, overcharge)
- Event logging and querying
- Replacement forecasting
- Voltage statistics
- Linear regression algorithms

## Usage Examples

### Check Current Battery Health
```bash
curl http://localhost:5000/api/battery/auxiliary/health
```

### Get 90-Day Voltage History (At Rest Only)
```bash
curl http://localhost:5000/api/battery/auxiliary/voltage/history?days=90&at_rest=true
```

### Get Replacement Forecast
```bash
curl http://localhost:5000/api/battery/auxiliary/forecast
```

### Get Recent Critical Events
```bash
curl http://localhost:5000/api/battery/auxiliary/events?days=30&severity=critical
```

### Log Manual Event
```bash
curl -X POST http://localhost:5000/api/battery/auxiliary/events/log \
  -H "Content-Type: application/json" \
  -d '{
    "event_type": "user_reported",
    "severity": "warning",
    "description": "Fault codes appeared this morning",
    "voltage_v": 11.9
  }'
```

## Data Collection

Voltage data is automatically collected from:
- **Primary source**: `battery_voltage` field in telemetry (OBD PID k42)
- **Collection frequency**: Every telemetry upload (typically 1-5 second intervals)
- **Storage**: Raw data in `telemetry_raw`, sampled to `aux_battery_health_readings`

## Health Assessment Logic

### Voltage Interpretation

**At Rest (Not Charging, Engine Off):**
- **12.6V+** - 100% health, excellent condition
- **12.4-12.6V** - 90% health, good condition
- **12.2-12.4V** - 75% health, monitor closely
- **12.0-12.2V** - 60% health, replacement soon
- **<12.0V** - <40% health, replace immediately

**During Charging (Charger On or Engine Running):**
- **13.2-14.5V** - Normal charging voltage
- **12.6-13.2V** - Low charging voltage, potential issue
- **>14.8V** - Overcharge, alternator/charger issue

### Anomaly Detection Rules

1. **Voltage Drop**: Change >0.5V in consecutive readings
2. **Low Voltage**: Sustained <11.5V for 5+ readings when at rest
3. **Overcharge**: >14.8V when charging
4. **Parasitic Drain**: >0.3V drop at rest between readings

## Replacement Forecasting

### Time-Based Method
Uses typical AGM battery lifespan (3-5 years) and estimated battery age from data span.

### Voltage-Based Method
- Linear regression on voltage vs. time
- Calculates degradation rate (V/year)
- Projects when voltage will reach critical threshold (11.5V)
- More accurate than time-based for batteries with unusual usage patterns

### Combined Recommendation
System provides both forecasts and recommends the more conservative estimate, with urgency levels:
- **Low**: >6 months until replacement
- **Medium**: 1-6 months until replacement
- **High**: <1 month until replacement

## Inspiration from Forums

Based on research from GM-Volt.com forums, this implementation addresses common owner needs:
- Voltage monitoring tools (manual Bluetooth monitors → automatic tracking)
- Early warning before battery failure
- Replacement timing guidance (3-5 year typical lifespan)
- Internal resistance tracking (approximated via voltage trends)
- Correlation with fault codes

## Performance Considerations

- **Background task**: Runs every 5 minutes (low overhead)
- **Sampling strategy**: ~20% of telemetry readings saved to health table
- **Index optimization**: All time-series queries use indexed timestamps
- **Duplicate prevention**: Event deduplication within 1-minute windows
- **Query limits**: Historical queries capped at 1 year maximum

## Future Enhancements

Potential improvements:
1. **Internal resistance measurement** - If OBD-II exposes current draw PIDs
2. **Load testing simulation** - Detect weak batteries under load
3. **Temperature compensation** - Adjust voltage thresholds based on ambient temp
4. **Predictive fault code correlation** - ML model to predict fault codes from voltage patterns
5. **Dashboard UI** - Real-time voltage chart and health gauge
6. **Push notifications** - Alert users of critical events via email/SMS

## Files Modified/Created

### New Files
- `receiver/models.py` (modified - added 2 models)
- `receiver/services/auxiliary_battery_service.py` (new)
- `receiver/routes/auxiliary_battery.py` (new)
- `receiver/routes/__init__.py` (modified - registered blueprint)
- `receiver/services/scheduler.py` (modified - added background task)
- `receiver/migrations/005_add_aux_battery_monitoring.sql` (new)
- `tests/test_auxiliary_battery_service.py` (new)
- `12V_BATTERY_FEATURES.md` (this file)

## Sources

Research based on:
- [GM Volt Forum: Testing the health of the 12V AGM battery](https://www.gm-volt.com/threads/testing-the-health-of-the-12v-agm-battery.334523/)
- [GM Volt Forum: Anyone monitor the 12V battery?](https://www.gm-volt.com/threads/2017-anyone-monitor-the-12v-battery.339736/)
- [GM Volt Forum: Why does failing 12V battery cause weird issues?](https://www.gm-volt.com/threads/why-does-failing-12v-battery-cause-weird-issues.173121/)
- [GM Volt Forum: 12V Battery replacement FAQ](https://www.gm-volt.com/threads/chevy-volt-2011-2015-12v-battery-replacement-faq.279833/)

---

**Created**: 2026-01-07
**VoltTracker Version**: 1.0+
**Author**: Claude Code
