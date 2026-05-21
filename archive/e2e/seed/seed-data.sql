-- E2E Test Seed Data for VoltTracker

-- Insert test trips
INSERT INTO trips (session_id, start_time, end_time, start_odometer, end_odometer, distance_miles,
  start_soc, electric_miles, electric_kwh_used, kwh_per_mile, gas_miles, gas_gallons_used, gas_mpg,
  avg_speed_mph, max_speed_mph, is_closed)
VALUES
  ('a0000001-0000-0000-0000-000000000001', '2026-01-15 08:00:00+00', '2026-01-15 08:30:00+00',
   50000.0, 50025.0, 25.0, 95.00, 20.0, 5.60, 0.280, 5.0, 0.12, 41.7, 35.0, 55.0, true),
  ('a0000001-0000-0000-0000-000000000002', '2026-01-16 17:00:00+00', '2026-01-16 17:45:00+00',
   50025.0, 50060.0, 35.0, 88.00, 25.0, 7.00, 0.280, 10.0, 0.25, 40.0, 40.0, 65.0, true),
  ('a0000001-0000-0000-0000-000000000003', '2026-01-17 12:00:00+00', '2026-01-17 12:15:00+00',
   50060.0, 50068.0, 8.0, 70.00, 8.0, 2.24, 0.280, 0.0, 0.00, NULL, 30.0, 45.0, true)
ON CONFLICT (session_id) DO NOTHING;

-- Insert telemetry for each trip
INSERT INTO telemetry_raw (session_id, timestamp, latitude, longitude, speed_mph, engine_rpm,
  state_of_charge, battery_voltage, hv_battery_voltage_v, hv_battery_current_a, hv_battery_power_kw,
  fuel_level_percent, odometer_miles)
SELECT
  t.session_id,
  t.start_time + (n * interval '30 seconds'),
  37.7749 + (n * 0.001),
  -122.4194 + (n * 0.001),
  35.0 + (random() * 20),
  CASE WHEN random() > 0.6 THEN 1200 + random() * 800 ELSE 0 END,
  t.start_soc - (n * 0.5),
  12.6,
  360.0 + random() * 10,
  -30.0 + random() * 60,
  -10.0 + random() * 20,
  45.0,
  t.start_odometer + (n * 0.5)
FROM trips t
CROSS JOIN generate_series(0, 30) AS n
WHERE t.session_id IN (
  'a0000001-0000-0000-0000-000000000001',
  'a0000001-0000-0000-0000-000000000002',
  'a0000001-0000-0000-0000-000000000003'
)
ON CONFLICT DO NOTHING;

-- Insert charging sessions
INSERT INTO charging_sessions (start_time, end_time, start_soc, end_soc,
  kwh_added, peak_power_kw, avg_power_kw, charge_type, is_complete)
VALUES
  ('2026-01-15 20:00:00+00', '2026-01-15 23:30:00+00', 45.0, 100.0, 8.5, 3.6, 3.2, 'L2', true),
  ('2026-01-16 21:00:00+00', '2026-01-17 01:00:00+00', 30.0, 100.0, 11.2, 3.6, 3.0, 'L2', true);
