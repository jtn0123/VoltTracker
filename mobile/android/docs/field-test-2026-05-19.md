# Field Test: OBDLink MX+ Drive And Charge Pass

Date: 2026-05-19
Phone: Samsung SM-S928U1
Adapter: OBDLink MX+ 54242
Pulled snapshot: `mobile/android/field-logs/pull-20260519-210717/`
Primary session: `session-1779248882889-obd.jsonl`

## User Timeline

Reported sequence:

1. Charged the car for about a minute.
2. Turned the car on; engine did not run at first.
3. Turned AC on.
4. Drove around the block.
5. Drove an approximately square route and expected GPS to plot it.
6. Minimized the app on the last leg at about 30-31 mph to test background behavior.
7. Brought app back to foreground.
8. Observed UI zoom/window-boundary issues.
9. Observed speed trace shown during charging, but not kW/charging metrics.
10. Observed unit confusion: dash was mph, app showed km/h in some output.

Follow-up answers:

- The short charge test used a Level 2 Tesla charger.
- The foreground notification stayed visible as persistent/running/connected to OBDLink during the minimized-background leg.
- VIN should be treated as private by default.
- The impossible `255 km/h` frames likely occurred during the short charging portion.

## Pulled Data Summary

The latest field session contains a useful amount of basic OBD data:

- Duration: about 12 minutes 25 seconds.
- Rows: 3,836 JSONL records.
- Telemetry samples: 544.
- Commands: 3,276.
- Status records: 5.
- Errors: 1 at shutdown after disconnect.

Captured fields:

- `ATRV` adapter voltage.
- `010D` vehicle speed.
- `010C` engine RPM.
- `0105` coolant temperature.
- `0104` engine load.
- `0111` throttle position.
- Raw ELM responses for every sample.
- Session state, sample count, runtime, and supported PID probe result.

Not captured in this run:

- GPS latitude/longitude.
- HV battery SOC.
- pack voltage/current/power.
- cell voltages.
- charge state / plugged-in state.
- charging power.
- fuel level.
- odometer.
- Volt drive/car mode.

The SQLite DB file did not exist on the phone before this run, which means the newer DB build was not installed before the test. This field pass is preserved in JSONL only.

## Session Interpretation

Rough timeline from logs:

- 20:48:02: Bluetooth session opened.
- 20:48:09: live polling started.
- 20:48-20:50: mostly parked/off or accessory behavior, 12.1-13.1 V.
- 20:50:47 onward: DC-DC/vehicle-on state becomes clear, voltage generally 13.4-14.6 V.
- 20:52 onward: real driving begins.
- Realistic max speed after filtering impossible `255 km/h` values: about 71 km/h / 44 mph, matching the reported dash max near 40 mph.
- 21:00:27: disconnect/end with one expected polling error after stream close.

## Important Bug Found: 255 km/h Speed Spikes

The log contains several `010D: 410DFF` responses before real driving. PID `010D` directly encodes vehicle speed in km/h, so `FF` means 255 km/h. This is not plausible for the test and should be treated as an invalid/stale/unavailable value for this car state. Justin later noted these frames likely happened during the short Level 2 charging portion, so this may be a useful charge-transition signature rather than random adapter noise.

Action:

- Filter `010D == 255` out of live UI and storage-derived analytics.
- Keep the raw frame in logs for debugging.
- Add plausibility filtering: reject speed jumps that are physically impossible over one sample interval.
- When charge-specific PIDs are added, compare plugged/charge-state responses against these `410DFF` windows.

## Unit Notes

OBD PID `010D` reports km/h by definition. The large app speed should be mph for US UX; the small secondary line can show km/h. The current UI needs another pass so labels are unmistakable and no raw km/h value is presented as the primary speed.

## GPS Gap

No route can be plotted from this run because the app does not currently request Android location permission or attach GPS samples to OBD telemetry. Background GPS also needs service-level handling, because minimizing the app should not stop trip logging.

Action:

- Add fine/coarse location permission.
- Add foreground-service location type.
- Subscribe to GPS/network location in `ObdService` during OBD sessions.
- Attach `latitude`, `longitude`, `accuracyM`, `gpsSpeedMps`, `bearing`, and `locationAgeMs` to telemetry samples.
- Store location fields in SQLite.
- Plot route from stored samples, not from demo data.

## Volt-Specific Data Direction

The archived open-source Voltage Android app validates the target feature set: SOC displayed/raw, capacity, 96 cell voltages, min/max/average/spread, odometer, VIN, and time-series storage.

Initial Voltage-derived targets to test in scan mode:

- Header `ATSH7E7`: cell voltages.
- Cell voltage request range: service 22 PIDs `224181` through `224240`, with a trailing `1` in the archived implementation.
- Header `ATSH7E4`: capacity/SOC side.
- Raw SOC HD: `2243AF1`.
- Displayed SOC: `228334`.
- Capacity Ah: `2241A31`.
- Odometer: `2234B2`.
- VIN: `0902`.

Because the source is GPL-licensed, use it as a reference for PID discovery and formulas, not as copied app code.

Privacy decision: future scan logs should confirm whether `0902` responds, but redact the VIN payload by default unless explicit export/show behavior is added.

## Next Validation Build

Before the next car pass, install a build with:

1. SQLite DB build installed.
2. GPS capture in foreground service.
3. Speed spike filtering.
4. A Volt PID scan panel that logs every response, even failed ones.
5. UI viewport/scaling lock for Samsung WebView.
6. Battery/charge placeholders tied to real data only.

## Questions For Justin

- Was the car plugged into Level 1 or Level 2 during the short charge test? Answer: Level 2 Tesla charger.
- Did Android ask for location permission at any point? It should not have yet, which confirms GPS was not active.
- During the minimized/background leg, did the notification remain visible the whole time? Answer: yes, persistent/running/connected to OBDLink was visible.
- Was the impossible `255 km/h` segment before you actually moved, likely during accessory/charging/on transition? Answer: likely during charging.
- Do you want VIN stored in the local DB by default, or treated as private and only shown/exported when explicitly enabled? Answer: likely private.

## References

- Voltage archived GitHub repo: https://github.com/thanxx/voltage
- Voltage source PID implementation: https://raw.githubusercontent.com/thanxx/voltage/master/app/src/main/java/io/tripovan/voltage/communication/obd2/Volt2Obd2Impl.kt
- Voltage app listing / feature benchmark: https://play.google.com/store/apps/details?id=io.tripovan.voltage
- Voltage forum thread: https://www.gm-volt.com/threads/voltage-an-android-app-for-volt.346938/

## Follow-Up Pull: 2026-05-20

Pulled snapshot: `mobile/android/field-logs/pull-20260520-154721/`

This run confirms the SQLite build is installed and collecting real OBD/GPS data. The local DB is about 20 MB and the full private backup is about 31 MB. That is acceptable for a debug field day, but too noisy for long-term retention until repeated error samples are throttled.

Table counts:

- `obd_sessions`: 5
- `telemetry_samples`: 13,069
- `pid_observations`: 16,533
- `location_samples`: 2,750
- `status_events`: 37,266
- `trip_segments`, `charge_sessions`, `battery_snapshots`, `cell_snapshots`: 0

Useful sessions:

- Session 2: 42.3 minutes, 1,933 useful telemetry rows, 1,933 GPS rows, about 23.46 GPS miles.
- Session 3: 15.1 minutes, 390 useful telemetry rows, 390 GPS rows, about 3.59 GPS miles.
- Session 4: 232.6 minutes in the DB, but only 427 useful telemetry/GPS rows over about 9.8 minutes, about 3.3 GPS miles. The rest was broken-pipe noise.

Captured and parsed:

- `ATRV` adapter voltage: 11.9-14.9 V.
- `010D` speed: 0-147 km/h; GPS max also reaches about 91 mph in session 2, so this looks internally consistent.
- `010C` engine RPM: parsed successfully, but always 0 rpm.
- `0105` coolant: 27-33 C.
- `0104` engine load: parsed successfully, but always 0%.
- `0111` throttle position: 33-41%.
- GPS route points with roughly 7-11 m average reported accuracy.

Not captured yet:

- HV battery SOC, displayed SOC, raw SOC, pack voltage/current/power, kWh, charge power, charger voltage/current, cell voltages, cell temperatures/resistance, and normalized trip/charge/battery records.
- The code has scan-mode probes/parsers for a first batch of Volt-specific service 22 commands, but this pull is live polling only. We need a diagnostic scan pass and then promotion of validated Volt PIDs into the live loop.

Important bug found and fixed:

- After a Bluetooth `Broken pipe`, the service kept looping and persisted empty `{}` telemetry rows. Sessions 3 and 4 contain 10,319 empty telemetry rows and matching broken-pipe status/error events. The service now stops polling on a sample read failure and refuses to broadcast/persist empty telemetry payloads.
- Useful samples reported `foregroundServiceActive: false` even while Android showed the persistent notification. The service was starting foreground mode before `startObdSession()`, then immediately resetting the internal foreground flag while clearing the prior session. Foreground startup now happens after the old session is cleared, so future samples should show the background logging state correctly.

Validation interpretation:

- The GPS and standard OBD Mode 01 fields are connected correctly enough for route review, max speed, sample interval, basic ready/parked/driving state, and adapter voltage state.
- The data is not yet enough for battery-health, charging-power, kWh, cell, or Volt drive-mode insights. Those require validated Volt-specific PIDs.
- RPM being zero may be correct if the gas engine did not run during these captures. If the engine definitely ran, standard `010C` is not enough for this Volt path and we should add/validate a GM/Volt engine-speed PID.

Next validation targets:

1. Run diagnostic scan mode on the car and inspect responses for `ATSH7E4` and `ATSH7E7` service 22 commands.
2. Add live polling for only the Volt PIDs that respond cleanly and store them into `battery_snapshots`, `cell_snapshots`, and `charge_sessions`.
3. Add repeated-error throttling/count aggregation in `status_events`.
4. Build trip segmentation from useful telemetry/GPS rows only.
5. Show a clear "last useful sample" age in the UI so broken connections do not look like valid idle logging.

## Waiting On Field Validation

Tag these as the current blocked-by-car-test items:

- `WAITING-FIELD-ENGINE-RPM`: During a known engine-running drive, confirm whether standard PID `010C` still returns `410C0000`. If yes, find a Volt/GM-specific engine state or RPM PID.
- `WAITING-FIELD-SCAN-READY`: Run Scan while the car is on/ready and parked. We need raw responses for `ATSH7E4`, `ATSH7E7`, and the existing service 22 probe set.
- `WAITING-FIELD-SCAN-ENGINE`: Run Scan while the gas engine is definitely running. Compare responses against the ready/parked scan to identify engine mode/state.
- `WAITING-FIELD-SCAN-CHARGE`: Run Scan while plugged into the Level 2 charger. Compare charger current/voltage candidates against the `410DFF` charge-transition hint.
- `WAITING-FIELD-BACKGROUND`: Start a fresh OBD session, background the app for a short drive, and verify future samples show `foregroundServiceActive: true` and nonzero background sample counts.
- `WAITING-FIELD-BROKEN-PIPE`: Intentionally disconnect or power off the adapter during a short session. Confirm the app stops logging instead of persisting empty telemetry rows.
- `WAITING-FIELD-GPS-ROUTE`: Do a short square route with the updated build. Confirm route distance and map shape match the actual drive.
- `WAITING-FIELD-BATTERY-PIDS`: Once scan responses exist, promote only validated SOC, pack, charger, and cell PIDs into live polling and normalized DB tables.

Non-field work that is unblocked:

- `LOCAL-FIX-USEFUL-TELEMETRY`: Done in the 2026-05-20 update. Diagnostics, latest telemetry, state counts, speed trace, sample intervals, and latest review now ignore empty telemetry rows from old broken-pipe sessions. Storage summary reports useful samples separately from raw telemetry row count.
- `LOCAL-FIX-ERROR-THROTTLE`: Collapse repeated identical polling errors into counted events instead of storing thousands of duplicate status rows.
- `LOCAL-FIX-TRIP-SEGMENTS`: Materialize basic trip segments from useful telemetry plus GPS rows.
