# VoltTracker Glossary

- **12V / aux voltage:** The accessory battery voltage reported by `ATRV` or
  control-module voltage PIDs. It helps infer whether the car is awake.
- **Adapter history:** The per-adapter summary table that tracks first seen,
  last seen, connect count, sample count, and last status.
- **Carry-forward:** Reusing the last known value for a slow PID on cycles where
  it was not polled, with stale-age metadata so the dashboard can label it.
- **Charge session:** A materialized window where telemetry indicates the car
  was plugged in and charging.
- **DTC:** Diagnostic Trouble Code, such as `P0xxx`; the dashboard can scan and
  clear these from supported modules.
- **Drive window:** A contiguous movement window inside a longer OBD session.
- **ELM327:** The adapter command set used by many Bluetooth OBD-II scanners.
- **EVSE:** Electric Vehicle Supply Equipment; the external charging source.
- **Header:** The CAN module address selected with `ATSH`, for example `7E1`.
- **HV pack:** The high-voltage propulsion battery.
- **Mode-01:** Standard OBD-II live-data commands such as speed and RPM.
- **Mode-22:** Manufacturer-specific commands used for Volt details such as pack
  current and charger state.
- **PID:** Parameter ID. A command such as `010D` or `222414`.
- **Ready:** Vehicle awake state where telemetry can be meaningful even at zero
  speed.
- **Rollup:** A cached summary row or JSON projection derived from raw samples.
- **SOC:** State of charge, usually a percent of usable battery energy.
- **SOP:** State of power, a limit/availability concept distinct from SOC.
- **Stale age:** How long it has been since a displayed value was actually
  refreshed from the adapter.
- **Trip materializer:** Pure Kotlin logic that turns raw location and telemetry
  samples into trip rows for the dashboard.
- **WAL:** SQLite write-ahead log. It keeps writes and dashboard reads concurrent.
