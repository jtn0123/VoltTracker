# VoltTracker API Reference

All API endpoints require HTTP Basic Authentication (configured via `DASHBOARD_USER` / `DASHBOARD_PASSWORD` env vars) unless noted otherwise.

Base URL: `http://<host>:8080`

## Authentication

```
Authorization: Basic <base64(user:password)>
```

WebSocket connections authenticate via a token passed in the `auth` handshake option, matching the `WEBSOCKET_TOKEN` or `DASHBOARD_PASSWORD` env var.

## Error Responses

All endpoints return errors in a consistent format:

```json
{
  "error": "Description of what went wrong"
}
```

Common HTTP status codes:
| Code | Meaning |
|------|---------|
| 200  | Success |
| 400  | Bad request / validation error |
| 401  | Authentication required |
| 404  | Resource not found |
| 429  | Rate limit exceeded |
| 500  | Internal server error |

Rate limit headers are included on all responses:
- `X-RateLimit-Limit`
- `X-RateLimit-Remaining`
- `X-RateLimit-Reset`

---

## Dashboard & Pages

### `GET /`
Serves the main dashboard HTML page.

### `GET /map`
Serves the GPS track map page.

### `GET /api/status`
Returns system status and last telemetry timestamp.

**Response:**
```json
{
  "status": "ok",
  "last_telemetry": "2026-01-15T10:30:00Z",
  "trip_active": false
}
```

---

## Telemetry

### `POST /torque/upload`
Receives telemetry data from Torque Pro. Fields are sent as form data with PID names as keys.

**Rate Limit:** 10,000/hour

### `POST /torque/upload/<token>`
Token-authenticated variant of the upload endpoint (no Basic auth needed if token matches).

### `GET /api/telemetry/latest`
Returns the most recent telemetry data point.

**Response:**
```json
{
  "timestamp": "2026-01-15T10:30:00Z",
  "speed": 45.2,
  "soc": 62.5,
  "voltage": 380.1,
  "current": -12.3,
  "engine_rpm": 0,
  "coolant_temp": 85
}
```

---

## Trips

### `GET /api/trips`
List trips with optional filtering.

**Query Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `limit` | int | Max results (default 50) |
| `offset` | int | Pagination offset |
| `start_date` | ISO date | Filter start |
| `end_date` | ISO date | Filter end |

**Response:**
```json
{
  "trips": [
    {
      "id": 1,
      "start_time": "2026-01-15T08:00:00Z",
      "end_time": "2026-01-15T08:30:00Z",
      "total_miles": 12.5,
      "electric_miles": 10.2,
      "gas_miles": 2.3,
      "gas_mpg": 42.1,
      "soc_at_switch": 18
    }
  ],
  "total": 150
}
```

### `GET /api/trips/<trip_id>`
Get detailed data for a single trip.

### `DELETE /api/trips/<trip_id>`
Soft-delete a trip.

### `PATCH /api/trips/<trip_id>`
Update trip metadata.

### `GET /api/efficiency/summary`
Overall efficiency statistics.

**Response:**
```json
{
  "lifetime_mpg": 48.3,
  "tank_mpg": 52.1,
  "avg_soc_floor": 19.2,
  "total_miles": 5420.1,
  "total_electric_miles": 3200.5,
  "total_kwh_used": 980.2,
  "kwh_per_mile": 0.306,
  "ev_ratio": 0.59
}
```

### `GET /api/soc/analysis`
SOC floor analysis with temperature correlation.

### `GET /api/mpg/trend`
MPG trend data for charting.

**Query Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `days` | int | Lookback period (7, 30, 90, 0=all) |

### `POST /api/trips/compare`
Compare multiple trips side-by-side.

**Body:**
```json
{
  "trip_ids": [1, 2, 3]
}
```

---

## Map & GPS

### `GET /api/trips/map`
Get GPS tracks for map display with filtering.

**Query Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `date_range` | string | `last_7_days`, `last_30_days`, `last_90_days`, `all` |
| `mode` | string | `all`, `ev_only`, `gas_only` |

### `GET /api/trips/<trip_id>/gps`
Get GPS track for a single trip.

### `GET /api/trips/similar/<trip_id>`
Find trips with similar routes.

### `GET /api/trips/<trip_id>/export/gpx`
Export trip as GPX file.

### `GET /api/trips/<trip_id>/export/kml`
Export trip as KML file.

---

## Charging

### `GET /api/charging/history`
List charging sessions.

### `POST /api/charging/add`
Add a manual charging session.

**Body:**
```json
{
  "start_time": "2026-01-15T22:00:00Z",
  "end_time": "2026-01-16T06:00:00Z",
  "start_soc": 15,
  "end_soc": 100,
  "kwh_added": 12.5,
  "charge_type": "L1",
  "cost": 1.50,
  "location": "Home",
  "notes": "Overnight charge"
}
```

### `GET /api/charging/<session_id>`
Get charging session details.

### `GET /api/charging/<session_id>/curve`
Get power & SOC curve data for a session.

### `PATCH /api/charging/<session_id>`
Update a charging session.

### `DELETE /api/charging/<session_id>`
Delete a charging session.

### `GET /api/charging/summary`
Charging statistics summary (L1/L2 counts, total kWh, costs).

---

## Battery

### `GET /api/battery/health`
Battery health and degradation data.

### `GET /api/battery/cells`
Cell voltage readings history.

### `GET /api/battery/cells/latest`
Latest cell voltage reading.

### `GET /api/battery/cells/analysis`
Cell balance analysis with module comparisons.

### `POST /api/battery/cells/add`
Add a manual cell voltage reading.

---

## Fuel

### `GET /api/fuel/history`
Fuel event history (refuels detected automatically or added manually).

### `POST /api/fuel/add`
Add a manual fuel event.

### `DELETE /api/fuel/<event_id>`
Delete a fuel event.

### `PATCH /api/fuel/<event_id>`
Update a fuel event.

---

## Export & Import

### `GET /api/export/trips`
Export trips as CSV or JSON.

**Query Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `format` | string | `csv` (default) or `json` |

### `GET /api/export/fuel`
Export fuel events as CSV.

### `GET /api/export/all`
Full backup export as JSON.

### `GET /api/export/torque-pids`
Download Torque Pro PID configuration CSV.

### `POST /api/import/csv`
Import Torque Pro CSV log file(s).

**Content-Type:** `multipart/form-data`

**Response:**
```json
{
  "import_code": "IMP-20260115-ABC123",
  "status": "completed",
  "total_rows": 1500,
  "parsed_rows": 1480,
  "skipped_rows": 20,
  "duplicate_rows": 5
}
```

### `GET /api/imports`
List import history.

### `GET /api/imports/<import_code>`
Get details of a specific import.

---

## Statistics

### `GET /api/stats/quick/<timeframe>`
Quick stats for a timeframe (`7d`, `30d`, `90d`, `all`).

### `GET /api/stats/detailed`
Detailed statistical breakdown.

---

## Analytics

### `GET /api/analytics/powertrain/<trip_id>`
Powertrain analysis for a trip (motor/generator RPM, power flow).

### `GET /api/analytics/powertrain/summary/<trip_id>`
Powertrain summary for a trip.

### `GET /api/analytics/range-prediction`
Predicted remaining range based on current conditions.

### `GET /api/analytics/maintenance/summary`
Maintenance status summary.

### `GET /api/analytics/maintenance/engine-hours`
Engine operating hours breakdown.

### `GET /api/analytics/routes`
Route analysis and frequently-traveled routes.

### `GET /api/analytics/battery/degradation`
Battery degradation forecast.

---

## Weather Analytics

### `GET /api/analytics/weather/efficiency-correlation`
Efficiency correlation with weather conditions.

### `GET /api/analytics/weather/temperature-bands`
Efficiency by temperature band.

### `GET /api/analytics/weather/precipitation-impact`
Impact of precipitation on efficiency.

### `GET /api/analytics/weather/wind-impact`
Wind speed/direction impact on efficiency.

### `GET /api/analytics/weather/seasonal-trends`
Seasonal efficiency trends.

### `GET /api/analytics/weather/best-conditions`
Optimal weather conditions for efficiency.

---

## Elevation Analytics

### `GET /api/analytics/elevation/efficiency-correlation`
Efficiency correlation with elevation changes.

### `GET /api/analytics/elevation/gradient`
Efficiency by road gradient.

### `GET /api/analytics/elevation/summary`
Elevation statistics summary.

### `GET /api/analytics/elevation/route-comparison`
Compare routes by elevation profile.

### `GET /api/analytics/elevation/trip/<trip_id>`
Elevation profile for a specific trip.

---

## Combined Analytics

### `GET /api/analytics/efficiency/multi-factor`
Multi-factor efficiency analysis (weather + elevation + driving style).

### `GET /api/analytics/efficiency/predictions`
Efficiency predictions based on conditions.

### `GET /api/analytics/efficiency/time-series`
Efficiency time series with contributing factors.

### `GET /api/analytics/efficiency/optimal-conditions`
Optimal conditions for best efficiency.

---

## Bulk Operations

### `POST /api/bulk/trips/delete`
Bulk soft-delete trips.

**Body:** `{ "trip_ids": [1, 2, 3] }`

### `POST /api/bulk/trips/restore`
Bulk restore soft-deleted trips.

### `POST /api/bulk/trips/update`
Bulk update trip metadata.

### `POST /api/bulk/trips/export`
Bulk export selected trips.

### `POST /api/bulk/trips/stats`
Statistics for a set of trips.

---

## Monitoring

### `POST /api/analytics/vitals`
Record Web Vitals performance metrics (sent automatically by the frontend).

### `POST /api/errors/report`
Report frontend errors (sent automatically via `navigator.sendBeacon`).

---

## WebSocket Events

Connect via Socket.IO to receive real-time updates.

### Client → Server
- `authenticate` — Send auth token on connect

### Server → Client
- `telemetry` — Real-time telemetry data point
- `toast` — UI notification (info, warning, error, success)
