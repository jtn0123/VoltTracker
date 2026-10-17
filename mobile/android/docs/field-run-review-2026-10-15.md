# October 15 field run: VoltTracker compared with Voltage

Reviewed October 16, 2026. Target: the owner's 2017 Volt and OBDLink MX+.

The new reads are largely answering. The most useful next work is fixing tire-source
selection, stopping unnecessary tire hunts, and correcting shutdown detection. After that,
make Health populate sooner and finish the parked/charging checks before adding controls.

Follow-up: F1–F6 are now implemented on `fix/field-run-polish`, with source-selection,
charger-state, startup, voltage, recorded-history, CSV and native UI regression tests. The
findings below describe the captured build before those changes. See
[the current parity checklist](gen2-parity.md#follow-up-fixes-and-recorded-signal-history) for
the resulting behavior. New on-car acceptance remains outstanding.

## Evidence and boundaries

- Phone: SM-S928U1, `com.volttracker.obdpoc.debug`, `0.65.0-dev`, installed October 14 at
  21:38 local time. Its installed APK SHA-256 matches the existing `gen2-more` debug artifact:
  `2607a3e419f392209ee901c14e44d20e58dea8b6c033c273c9e0f24a1b2fa4ef`.
- Source reviewed: `feat/gen2-more-readings`, `15e04140a`, including PRs #179, #180, #181.
  All three PRs were still open when checked. The primary workspace is on an older branch;
  this review uses the installed stack, not that checkout.
- Main capture: `session-1792110731434-obd.jsonl`, October 15, 17:32:11–18:01:18 PDT.
  901 telemetry records, 8,339 command records, 29:07 total including reconnect attempts
  after shutdown. 848 samples were recorded with the app in the background.
- Also pulled the preceding 1:59 OBD session and 23-second scan, the session summaries,
  latest-file pointer, and app log. The scan is a basic protocol/PID-support/DTC scan;
  it is **not** a Detailed signals scan.
- Comparison: the locally decoded Voltage **2.2.3** reference in `~/VoltTracker-re/spec/`.
  This is not a comparison with a newly downloaded release or a simultaneous Voltage drive.
- Raw captures and checksums are private under `~/VoltTracker-re/captures/2026-10-16/`.
  JSONL line numbers below refer to the main capture. No route, VIN, or vehicle odometer is
  reproduced here.
- The initial evidence review was documentation-only: it changed no production code,
  simulator evidence tags or installed app. The F1–F6 follow-up described above adds the
  fixes and regression tests separately.

## Fixes and polish, in order

### F1 — Old tire broadcasts overwrite newer direct readings

**Confirmed bug; high value; small-to-medium change.**

At 24:39 into the session, all four direct BCM replies succeeded. The published sample
immediately afterward still used old SW-CAN readings:

| Wheel | Fresh BCM reading, kPa | Published sample, kPa |
|---|---:|---:|
| Front left | 247.5 | 232 |
| Rear left | 250.25 | 232 |
| Front right | 253.0 | 232 |
| Rear right | 250.25 | 228 |

The sample's tire age was 1,236.732 seconds, despite the just-completed direct reads.
Proof: command lines 9003–9006 and telemetry line 9013.

`LiveSampleReader.appendBcmTires()` supplies the direct values, then
`ObdPollingEngine.pollLiveSamples()` calls `appendSwcanReadings()`. `SwcanReadings.appendTo()`
unconditionally writes its held tire values and age over the same keys. Unlike the comment
in `appendBcmTires()`, there is no timestamp comparison. The tire group is held for the session.

**Change:** choose the newest valid source per wheel, and calculate the reported age from
the chosen values. Preserve the broadcast fallback when a direct read fails.

**Regression evidence to add:** old broadcast plus fresh BCM must retain BCM values; newer
broadcast must win; partial/invalid results must not overwrite a valid newer wheel. Exercise
the real sample assembly order, not just either decoder separately.

### F2 — Tire hunts continue after all four direct reads succeed

**Confirmed unnecessary work; high value; small-to-medium change.**

All four direct tires were published at +11.32 s (line 72). The listener still ran ten
`tire_hunt` windows before hearing the broadcast at +243.28 s (line 1320). Those windows
consumed **48.872 seconds**, in addition to the startup window. The first 250 seconds
contained eleven telemetry gaps longer than six seconds; the maximum was 14.621 s during
the startup window. Between +250 and +1500 s there were no gaps over six seconds.

`SwcanListenRunner.isTireHunting()` checks only `tiresHeard`, which is set by the broadcast
listener. It knows nothing about successful direct BCM reads.

**Change:** fresh, complete direct tire data should suppress the long tire-hunt mode. Keep
normal body-bus listening for doors, climate and other broadcasts, and resume a bounded
hunt if direct tires become unavailable. Separately shorten or interleave the long startup
capture when driving; suppressing the tire hunt alone does not remove that startup gap.

**Regression evidence to add:** complete fresh direct tires suppress hunts; incomplete or
expired direct tires retain the fallback; ordinary body listening still runs.

### F3 — Switching the car off is misclassified as plugged in

**Confirmed bug with owner confirmation; high value; medium change.**

The owner confirmed that the car was switched off without plugging in. The log changes to
`vehicleState=plugged` from +1544.09 s (line 9218), driven by sustained speed byte `FF`.
The charger still reports `chargeState=None`; at +1598.4 s that result is only 258 ms old.
AC input voltage/current are zero, and the final disconnect is labeled `ended_vehicle_off`.

`LiveSampleReader` treats repeated `010D=FF` as a plugged hint. It passes that hint to
`VehicleStateClassifier` without the newly added charger state, so `FF` during shutdown
becomes positive plug evidence. Voltage already uses the dedicated charger-state DID.

**Change:** use fresh charger state/input evidence to distinguish plugged, charging and
shutdown. An invalid speed sentinel alone must not assert plugged. Use unknown/offline
when the available evidence cannot distinguish states.

**Regression evidence to add:** this shutdown sequence stays out of plugged/charging;
genuine Waiting/Charging/Complete states still work; a moving regenerative-braking sample
does not become charging; stale or absent charger data does not invent a plug connection.

### F4 — Health takes nearly seven minutes to populate

**Measured usability gap; high value; medium change.**

| Reading | First appearance after session start |
|---|---:|
| First live sample | 6.09 s |
| Direct tires | 11.32 s |
| Displayed SOC | 17.57 s |
| Gear | 42.54 s |
| 12 V capacity | 2:03.52 |
| Charge records | 3:52.38 |
| HV capacity, resistance and insulation | **6:46.89** |

Health first receives capacity and insulation at line 2340. `PidSchedule` places the
capacity/resistance/insulation block at phase 210 of 240 cycles: a slow repeat interval also
delays the initial read. Gear is also too slow for a driver's immediate P/D/L confirmation.
Voltage has a separate connect-time read list instead of waiting for every repeat phase.

**Change:** prime key health values once soon after the first live sample, interleaving them
with hot driving reads. Then retain the slow trend schedule. Poll gear earlier and more often.
Proposed acceptance targets: gear within 10 s, core health within 30 s, with no new long gaps.
These are targets, not measured performance of an implemented fix.

### F5 — An implausible controller voltage is accepted during shutdown

**Observed data-quality issue; medium value; small-to-medium change.**

The shutdown batch includes a controller's `0012` voltage word, i.e. 0.018 V; the published
sample rounds it to **0.02 V** at +1572.88 s (line 9291). Other responding controllers in
that batch report roughly 12.4 V, the adapter about 12.1 V, and the body-bus value about 12.8 V.
This does not establish that the physical 12 V battery collapsed. The bytes are decoded as
specified; validity and responder selection are the problem to address.

**Change:** handle unavailable/implausible controller readings as invalid and prefer a
plausible fresh module response. Use module-scoped supply reads where appropriate, as
Voltage does. Keep adapter, controller and battery-sensor voltages labeled separately.
Do not apply Voltage's empirical BCM `0.976` correction to the adapter or broadcasts.

### F6 — Make the new signals useful after the drive

**Product gap; medium-to-high value; medium change.**

Voltage's Live Monitoring rows have individual charts and a recorded-readings CSV export.
VoltTracker's native Live signals screen presents the new values as text. The full telemetry
JSON is saved by `ObdStoreWriter`, but the route CSV's fixed columns do not expose EGR,
conditioning, remote-start reasons or the other new diagnostic readings.

**Change:** add selected-signal history and diagnostic CSV export from the recorded telemetry,
with units, timestamps and freshness. Start with EGR temperatures, battery coolant, A/C
power and 12 V readings. This can be developed from the captured run without new car writes.

## What answered on this car

**35 `(header, command)` pairs tagged GUESS in the current virtual-Volt catalog returned
positive replies in the main run.** This confirms request support and reply shape, not every
possible state or the physical meaning of every bit. Repeated telemetry samples carry values
forward; they are not hundreds of independent ECU responses.

| Area | Requests | Independent positive replies per request | Observed result |
|---|---|---|---|
| Transmission | 7E1 `221940`, `222C05` | 19 | Temperature 64–78°C; auxiliary pump 0–51% |
| Engine start reason | 7E1 `222439` | 37/38 attempts | None / Maintenance mode; event emitted on six nonzero transitions |
| EGR | 7E0 `22002C`, `223040`, `223041` | 18 | Command 0–44%; cooler 1 76–255°C, cooler 2 71–106°C |
| Thermal | 7E4 `22435A`, `224357`, `2243DE`, `2241A4` | 18–19 | Pump 50–3,850 rpm; PE pump 55–56%; Off / Active cooling; coolant 26–33°C |
| Insulation | 7E4 `2243A6` | 3 | 2,900 kΩ |
| 12 V maintenance | 7E4 `22441E` | 19 | Off only; active flag still unexercised |
| Charger state | 7E4 `2243AE` | 38 | None only; no actual plug-in in this run |
| Charger checks | 7E4 `224532`, `224530`, `22452F` | 19 | Ready/Not ready, Passed, Passed/Failed; shutdown context matters |
| Charger times | 7E4 `2283E1`, `2283E2`, `228375`, `228377` | 18 | Valid weekday/time layouts; times change while unplugged |
| Charger duty | 7E4 `22452D`, `22452E` | 19 | Input 0–10%, output 0–65%, even while unplugged; not proof of charging |
| Charge records | 7E4 `2243CB`–`2243CF` | 4 | All five decode identically: Full charge; Cell voltage at max |
| Tires | BCM `225008`, `225007`, `225006`, `225005` | 19 | All four answer; final sample arbitration is broken (F1) |
| Remote-start reads | BCM `228537`, `229025`, `22900D` | 19 | 0 s remaining, 2 starts left, Ignition switch |
| 12 V capacity | BCM `22CB31` | 4 | Complete multi-frame replies; 61.8–61.9 Ah in the long run, 62.0 Ah in the short run |

Other useful checks: HV capacity read 44.9 Ah; A/C power varied from 0 to 2,843 W; cabin
heater power stayed zero. Gear read L while driving and P at the end. D/R/N were not exercised.
The EGR temperature contrast suggests different thermal locations, but does not establish
which physical side each sensor measures. A capacity number alone is not a complete battery test.

Positive raw layouts should become synthetic-value REAL fixtures in a follow-up, with
status variants not observed here remaining explicitly synthetic. Add the interleaved
multi-responder Mode 01 shutdown layout as a separate regression case.

## Remaining useful Voltage features for this 2017

| Priority | Addition | Current VoltTracker position | Next evidence/action |
|---|---|---|---|
| Next | Per-module 12 V supply comparison | Only 7E4 `220042` is a scan candidate | Probe reachable 2017 modules and compare fresh readings side by side |
| Next | Precise cabin/duct/target temperatures and HVAC mode | Passive climate data and an estimated cabin temperature exist; no `251` diagnostic reader for these DIDs | Read-only SW-CAN HVAC query on the MX+, compare with the car controls |
| Next | Charger pilot and input-current command | Decoded scan candidates `224533`, `224545`, `224546` | A plugged-in Detailed signals capture |
| Next | BCM 12 V current, voltage and SOC | Decoded candidates `2290FB`, `2290B1`, `2290F8`; current display uses broadcasts | Parked Detailed signals capture; compare sources before choosing scale |
| Later | Direct energy/fuel/distance counters | Decoded candidates `224470`, `2241AD`, `2241C5`, `2241C6`; some equivalent broadcast counters already exist | Determine reset semantics and benefit over existing counters |
| Later | Weak-cell load assessment | Cell snapshot/probe infrastructure exists; guided load test absent | Confirm the 96-cell read and assess synchronized load/spread evidence first |
| Later | Charge start/stop, 8/12 A, departure time, charge limit | Read side partly present; write controls absent | Separate implementation and on-car acceptance; SOC limit needs the reference's clock/schedule workaround |
| Later | Trunk release, engine hold, HVAC/preheat controls | Existing controls cover lock/unlock, windows and remote start/stop | Separate on-car acceptance for each added control |

The twelve scan candidates were not requested in these new logs. They are **untested here**,
not rejected by this car. Avoid duplicating existing passive data unless direct reads improve
freshness, reliability or precision.

VoltTracker retains advantages: background trip recording, route history, persistent
telemetry, passive body-bus listening, and several additional motor/odometer/maintenance
readings. Keep those while borrowing Voltage's useful dedicated status and module reads.

## Still needs an on-car check

1. Plugged-in Waiting → Charging → Complete and unplug transitions, with the car's schedule
   as the reference. No charging state other than None was observed here.
2. Charge-record order: all five records were identical, so newest/oldest order is unresolved.
3. Remote-start countdown under an actual remote start, and the maintenance-charge active bit.
4. D/R/N gear positions, tire wheel mapping against the car/gauge, and nonzero cabin heating.
5. One parked and one plugged-in Detailed signals scan for the twelve candidates.
6. The diagnostic-only `2240D4` current sign and the 96-cell probe; neither was requested here.
7. Existing and future car-control actions. No car-control command was exercised by this review.

The main recording stayed active in the background. Its six failed reconnect attempts occurred
after shutdown; they are not evidence of six mid-drive dropouts. The median telemetry interval
was 1.444 s and the 95th percentile 3.805 s. Those are measured whole-session values, not a
benchmark against Voltage.

## Follow-up: engine running in Park still looked like plug-in charging

After the phone update, the owner reported that waiting in Park with the engine running
still looked like charging. The earlier classifier fix did not remove the native UI's
independent `chargerPowerKw >= 0.5` shortcut. That shortcut could override a `ready`,
`parked` or driving classifier result and begin a fictitious live charge. The UI also kept
its previous charging phase when the next sample had no usable gear/speed evidence.

Voltage 2.2.3 keeps these concepts separate. Its `M4/h.java:E()` reads `2243AE` alongside
charger voltage/current; the charging UI in `T4/A.java` tests the dedicated state for `2`
(Charging). Its separate 12 V screen uses BCM battery current (`2290FB`), not that charger
state. A battery receiving energy from the engine or DC–DC converter is not evidence of
a plug-in charge.

The native UI now follows the charger-backed classifier and clears an unconfirmed charging
phase. Raw power remains a reading, not a charging-state override. The 12 V status text now
says **12 V system active**, so it cannot be mistaken for plug-in charging; this wording does
not assert the current direction of the still-unchecked SW-CAN 12 V current decode.

JVM and Compose regressions reproduce an engine-on/Park/charger-None sample with positive
charger power, driving with an old positive charger-power reading, and missing evidence
after a charge. A genuine confirmed charge still remains charging at zero reported power.
The engine-on/Park reproduction is **synthetic software evidence**, not a newly pulled car
capture: the October 15 recording had no engine-running-in-Park samples. Physical acceptance
of this follow-up is still needed.

## Follow-up: the reported charging message was a notification

The owner clarified that the message appeared in an Android notification. The event-alert
decider had another independent shortcut: stationary negative pack current armed a charge,
without consulting the charger-backed vehicle classification. An engine generating into
the pack in Park could therefore trigger **Charging stopped / Check the plug and charger**,
**Charge complete**, or **Charge target reached**, even while live telemetry said `ready`
and the dedicated charger state was None. The earlier native-screen fix did not cover this
notification path. The exact notification wording reported on the car is still unconfirmed.

Charge alerts now require `vehicleState == charging` as well as stationary current into
the pack. Missing or unknown classification cannot arm a charge from current alone. The
existing minimum sample count, end debounce, energy integration and alert preferences
remain in force. Low-SOC alerts still work while the engine runs in Park.

Robolectric regressions feed synthetic engine-on/Park telemetry through the real vehicle
classifier, coordinator and Android notification manager. Three negative cases failed
before this change: false target/completion, false interruption, and absent/unknown
classification. Positive controls still post all three genuine plug-in charge alert kinds.
These tests establish software behavior; a new on-car recording and notification check
remain necessary after installation.

## Next test drive with F1–F6

- Record normally, including time in the background. Note roughly when the adapter connects
  and whether tire readings and Health populate promptly. Gear within 10 seconds and core
  Health within 30 seconds remain on-car targets, not measurements from the new build.
- At the end, note the shutdown time and whether the car was plugged in. The next capture
  will check the charger state against that observation and compare tire-source ages,
  startup gaps and rejected controller-voltage replies with this baseline.
- Afterward, open **Car → Health → Live signals → Signal history & CSV**. A saved recording
  stays accessible with the adapter disconnected. Select the drive and a signal; CSV exports
  all retained telemetry rows in UTC with fixed units and source ages, excluding route and
  vehicle identity.
- A separate parked plug-in session can check Waiting, Charging, Complete and unplug
  transitions. Driving alone cannot validate those states or the remaining scan candidates.
