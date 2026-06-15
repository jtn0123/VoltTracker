# PID Validation Notes - 2026-06-03

See also `enhanced-discovery-tracker.md` for the running detailed-signal ledger that should be
updated after each Scan or Detail Probe.

## Confirmed In App

These fields are either standard OBD-II PIDs, parsed by existing tests, or backed by Volt-specific
OBDb test cases and diagnostic scan probes.

- Speed: `010D`
- RPM: `010C`
- Coolant temperature: `0105`
- Engine load: `0104`
- Accelerator pedal: `0149`
- State of charge: `015B`
- Control module voltage: `0142`
- Engine run time: `011F`
- Fuel level: `012F`
- Engine oil temperature: `015C`
- HV pack voltage/current: `222429`, `222414`
- HV battery temperature: `22434F`
- Charger AC voltage/current: `224368`, `224369`
- Charger HV voltage/current: `22436B`, `22436C`
- Charging mode/level: `224373`, `224531`
- HV battery raw/displayed SOC: `2243AF`, `228334`
- HV battery charge count: `2243A5`

`224373` was corrected from "charger HV power" to charging mode after checking OBDb test cases.
Charger power should be derived from `22436B * 22436C / 1000`, not read from `224373`.

## Derived From Existing Data

These do not need new PIDs:

- Pack power: `222429 * 222414 / 1000`
- Drive/regen direction: sign of pack current or pack power
- Drive kWh, regen kWh, and net kWh: integrate signed `powerKw`
- Rolling efficiency: combine integrated `powerKw` with GPS/trip distance
- SOC rate: slope of `soc`/`2243AF` over time
- Poll-loop health: command durations and sample cadence already flow through command logs and
  sample timestamps

## Real-Car Scan - 2026-06-03

Scan session `session-1780532623362-scan.jsonl` on the user's Volt confirmed the app can collect
and persist parsed values for the added battery/charger PIDs.

- Car state during scan: READY, stationary, ICE running (`010C` = `410C14B4`), coolant 56 C,
  fuel about 67%, HV battery temp 32 C, SOC about 14%.
- The pack-current sign showed current flowing into the HV pack (`222414` = `622414FE87`) while
  charger output was zero (`22436B` = `62436B02A0`, `22436C` = `62436C0000`), so this looked like
  engine/generator charging rather than plug charging.
- Confirmed useful responses: `0142`, `011F`, `012F`, `22434F`, `22436B`, `22436C`, `224373`,
  `22437D`, `2243A5`, `2243AF`, `224531`, `222429`, `222414`.
- `228334` responded with `62833400`, but that value did not match the known SOC, so keep it as
  scan-only until another capture proves it useful.

Normal live telemetry now promotes the confirmed slow-context fields on deep polling lanes:

- `controlModuleVoltage`, `engineRunTimeSec`, `fuelLevelPct`
- `hvBatteryRawSoc`, `hvBatteryChargeCount`, `lastChargeEnergyWh`
- `chargingMode`, `chargingLevel`, `chargerHvVoltage`, `chargerHvCurrent`, `chargerPowerKw`

## Tire Pressure Discovery

Tire pressure is not yet live-tracked. The diagnostic scan now probes candidate Chevrolet/Volt
tire-pressure commands without adding them to the live poll loop:

- Header `7E0`: `22248E`, `22248F`, `222490`, `222491`, `22C901`, `22C902`
- Header `760`: `224051`, `224052`, `224053`, `224054`

OBDb marks the `7E0` tire-pressure candidates unsupported in the 2018 Volt test data, so the next
validation step is a real diagnostic scan on the user's car. If any candidate returns a positive
`62...` response with plausible pressure/temperature bytes, add a parser and only then consider
slow-lane live tracking.

The real-car scan rejected the current tire-pressure candidates:

- Header `7E0` candidates returned `7F2231` (`request out of range` for service 22).
- Header `760` candidates returned `NO DATA`.

Do not promote tire pressure to live telemetry until a different header/PID set returns positive
frames.

## Field-Log Validation - 2026-06-14 (cell voltage scale + parse-failure noise)

Source: a full day of on-device session logs pulled from the user's Gen2 Volt (OBDLink MX+),
covering 2026-06-13 drives and scans. Analysis of the raw `command`/`telemetry` records:

- **Min/max cell voltage (`224329`, `22432B`) were never decoding.** Across 3,642 telemetry rows,
  `minCellVoltage`/`maxCellVoltage`/`cellBalanceMv` were present **0** times. Every read is a 2-byte
  word in `0x1644`-`0x1A90` (5700-6800). The old Bolt-derived `5/65535` scale mapped those to
  ~0.44 V, which the `1.5-4.5 V` sanity bound rejected on every sample, so the cell-balance view was
  always empty. A real 0-5 V cell over the full 16-bit range would answer ~`0xBD6F` (3.7 V); this
  BECM uses under 10% of the range, i.e. a different scale.
  - **Fix:** scale `224329`/`22432B` at `1/1600` V/count. Validated against a pack-voltage/96 anchor
    (N=118 rows where pack voltage and both cell words were present): 95-97% of reads land in
    3.0-4.2 V and the implied imbalance is a sane tens-of-mV. Cell imbalance (max-min) is
    near-invariant to small scale error, so this is safe for the cell-balance feature even if the
    exact GM count is off by a percent. The average (`22C218`) and individual cell probes stay on the
    legacy scale - we have no field anchor for them and the probe DIDs answer at a different
    magnitude (`2241B2` = `6241B208E5`).
- **`pid_parse_failed` was crying wolf on valid sentinels.** It fired for `22432A`/`22432C` (cell
  number) returning `00`, for `22203F` returning `62203F00`, and (redundantly) for `010D` = `410DFF`.
  These are recognized "no reading / idle" answers, not malformed frames.
  - **Fix:** `ObdProtocol.isBenignSentinelResponse` treats an all-zero Mode 22 payload and the `010D`
    0xFF speed sentinel as benign; `LiveSampleReader.reportParseFailures` skips them. Genuinely
    unmodeled non-zero frames still surface as parse failures.

### Still needs an on-car capture

- **Engine torque (`22203F`)** answers with a **single** data byte on this Volt (217/319 reads were
  `62203F00`, 74 were NO DATA, the rest 1-byte non-zero like `62203F17`), never the 2-byte word the
  community sheet documents as `((256*A)+B)/4`. `engineTorqueNm` therefore never decodes. The 1-byte
  scale is unknown and unvalidatable from logs alone (engine is off in EV mode for almost all reads).
  Capture a session with the ICE running to validate a scale, or drop `22203F` from the live poll set.
