# OBD field-log findings — 2026-06-16

On-device log analysis of Justin's Chevy Volt (gen-2, 1.5L) + OBDLink MX+ 54242,
pulled from the phone (`SM-S928U1`, Android 16) on 2026-06-16. App build on device:
**`0.17.0-6bc0d18` (versionCode 72)** — this predates PR #227, so the logs are a clean
"what still hurts" sample.

Sessions analysed (today): the 18:49 drive (`session-1781654704158`, 104 min, 1881
samples — the most recent), the 06:55 drive (42 min), the 15:05 drive (24 min), three
short sessions, the 14:41 DTC scan, and the `sessions-summary.jsonl` rollup (29 sessions).

## Headline: PR #227 is validated by the newest drive

Splitting the 18:49 drive into its **driving phase** (first 30.4 min) vs the **parked
tail** (plugged in at 31 min, churned to 104 min):

| Metric | Driving phase | Parked tail |
|---|---|---|
| NO-DATA on core PIDs (010D/010C/0149/222414…) | **0.0%** | ~95% |
| Null rate, every mapped signal | **0.0%** | ~40% |
| `sample_gap` events | 0 | 293 |
| Outcome | (good data) | `connect_timeout` ✗ (bogus) |

Every bit of waste — the 40% aggregate NO-DATA, all 293 sample-gaps, the bogus
`connect_timeout` — is in the 73-minute parked tail that #227 ends at ~34 min. **While
actually driving, data quality is flawless.** Same pattern in the 06:55 drive (ended
`reconnect_exhausted` after parking). Nothing to fix in the driving data itself.

**Shipped, pending a phone-build update (do not redo):**
- Parked/asleep-car clean stop + reclassify (no more bogus `connect_timeout`). — #227
- Per-session negative-PID cache retiring dead PIDs after 3 NO-DATAs. — #227

## Backlog — what's still fixable / improvable

| ID | Item | Evidence | Effort | Value |
|----|------|----------|--------|-------|
| ~~**L1**~~ ✅ | Pin OBD protocol (ATSP6 first) to kill the 4.8 s connect search — **DONE 2026-06-17** | `0100` returned `SEARCHING…` at **4.74–4.80 s on every session** (5/5) | S | High |
| **L2** | One-tap force-stop competing OBD app at connect | `io.tripovan.voltage` + `org.prowl.torque` detected in **every** session; genuine low-sample connect failures (0/332/482) correlate | M | High |
| **L3** | Decode-or-drop `engineTorqueNm` (`22203F`) | PID answers 0% NO-DATA while driving but is **100% null**; 1-byte gen-2 response vs 2-byte community formula | S–M | Med |
| **L4** | Investigate ~10.8 s Bluetooth socket-open latency | `socket_open_attempt`→`socket_open_result` = ~10.8 s; dominates the 17–25 s time-to-first-data | M–L | Med |
| **L5** | Trim no-prompt recovery cost | ELM327 v1.4b omits `>`; recovery cost **27.2 s across the 104-min session** (16 cmds) | M | Low–Med |

### L1 — Pin the protocol; stop re-searching every connect  ✅ DONE 2026-06-17
**Evidence:** `0100` (capability probe) returned `SEARCHING... 41000000000` and cost
**4739–4802 ms on all 5 OBD sessions today**. That was a fixed ~4.8 s tax on every connect.
**Cause:** `ObdPollingEngine.initializeElm327` sent `ATSP0` (auto) *then* `0100`, triggering
the search; `ATSP6` (ISO 15765-4 CAN 11-bit/500k — "the only protocol this car speaks", per
`ObdProtocol.kt:935`) existed only as a no-prompt fallback.
**Shipped fix:** init now sends `ATSP6` *first*, then `0100`, validated by a new
`protocolProbeAnswered()` that requires a real `4100` reply (not a `NO DATA`-with-prompt).
If the pin doesn't answer, it logs `protocol_probe_pinned_miss` and falls back to `ATSP0`
auto-search → retry `0100` (label `0100_atsp0_fallback`), so a different adapter/car still
auto-detects. Tests: `pinnedProtocolFastPathSkipsTheAutoSearch`,
`pinnedProtocolMissFallsBackToAutoSearch`; TPMS-scan protocol assertion updated. All gates
green. Expected ~4.5 s faster connect.

### L2 — Force-stop the competing OBD app at connect
**Evidence:** every session's status carries `competingApps:
"io.tripovan.voltage,org.prowl.torque"`. The genuine connect failures (low sample counts
0/332/482, distinct from the parked-tail false failures) are consistent with a rival app
holding the single RFCOMM/SPP channel.
**Status:** infrastructure likely already present — `CompetingAppDetector` +
`VoltBridge.forceStopPackage` are referenced in `docs/connect-hardening-buckets.md`
(Bucket 2 + Bucket 4a). **Verify** what's wired in current Kotlin, then surface a one-tap
"Close Torque / Voltage so VoltTracker can connect" affordance when a competing app is
detected and a connect fails. Bigger than the others (UX + reconnect flow), but it's the
top remaining *reliability* lever for true connect failures.

### L3 — Engine torque: read but discarded (decode-or-drop)
**Evidence:** `22203F` answers at **0% NO-DATA while driving** but `engineTorqueNm` is
**100% null**, so the drive view shows a permanently-blank gauge. The reply is a **single
byte** (`62203F00`…`62203F20`) that tracks engine load cleanly (nonzero only with the
engine running; `0x20`=32 at 3389 rpm peak, `0x09`=9 light load, `0x00` engine-off).
**Cause:** [`ObdProtocol.kt:285`](../mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/ObdProtocol.kt#L285)
routes it through `voltWordValue(...,4.0,...)`, which bails at `payload.size < 2`.
**Community formula** is `((256·A)+B)/4 → 0–200 Nm` — a **two-byte** formula from gen-1
(2011–2015) PID lists; it can't apply to a gen-2 one-byte reply (forcing it gives ~8 Nm
at peak, implausibly low). No validated gen-2 single-byte scale exists online.
**Decision path:**
1. Cross-check Torque Pro on the same car (it's already installed) with `((256*A)+B)/4`.
   Sane reading ⇒ the car gives Torque 2 bytes ⇒ we have a request/flow-control quirk to
   fix (then the formula just works). Blank/~8 Nm ⇒ one-byte reality confirmed.
2. If one byte is confirmed: either **drop `22203F`** from the live poll (removes the blank
   gauge, reclaims ~7 s/session) or decode the byte as an explicitly *approximate /
   relative* load index (not labelled Nm).
**Also:** `pid_parse_failed` still fires on nonzero torque bytes (`62203F0B`) — the benign
-sentinel suppression only covers the all-zero `62203F00`. Whatever we choose for L3 should
also silence this log noise.

### L4 — Bluetooth socket-open latency (~10.8 s)
**Evidence:** time-to-first-data is **17–25 s** every session (39 s for the 14:41 scan).
Breakdown of the 25.5 s on the 18:49 drive: **socket open ~10.8 s**
(`socket_open_attempt`→`wake_nudge`/`socket_open_result`) + ELM init ~1 s + protocol
search 4.8 s (see L1) + VIN probe & first cycle ~5 s. The socket-open chunk is the single
largest and is unexplained.
**Fix:** instrument the RFCOMM `connect()` path (insecure vs secure socket, SDP lookup,
the `wake_nudge` timing) to find where ~10.8 s goes; likely a first-attempt timeout/retry
or the adapter waking from low-power. Harder — needs on-device experimentation; risk of
regressing connection reliability, so measure before changing.

### L5 — No-prompt recovery cost
**Evidence:** `command_no_prompt_recovery` + `elm_escape_sent` fire 16× in the 104-min
session, and those commands cost **27.2 s of extra latency** (6.0 s / 4 cmds in the 42-min
drive). The ELM327 v1.4b intermittently omits the `>` prompt; recovery works but is slow.
**Fix:** review the recovery timeout/escape sequence for a faster path (e.g. shorter
inter-byte read deadline before declaring "no prompt", or an adapter-specific quirk flag).
Lowest priority — it's latency, not data loss.

## Confirmed healthy — no action
- **DTC scan (14:41):** clean. Mode 03/07/0A all return `4300`/`4700`/`4A00` (zero codes)
  across 5 ECUs, 0 errors, no freeze frames. The occasional `CAN ERROR` on a mode-03 retry
  is a single transient, not a broken read.
- **Driving-phase data quality:** every mapped signal populated, 0% null (see headline).
- **Command latency:** p50 77 ms, p90 460 ms, p99 538 ms; only the `0100` search (L1) and
  the dead-PID timeouts (fixed by #227) exceed 1 s.
- **`speed_rejected: charge_transition_hint`:** intentional — `010D`=`FF` (255) is rejected
  by design (`parseSpeedKph`), not a bug.
- **Short sessions (50/9 samples):** clean intentional stops (`app_backgrounded`,
  `gps_stopped`, `session_end`), not failures.

## Method / repro
Logs pulled via `adb -s <dev> exec-out run-as com.volttracker.obdpoc cat
files/obd-logs/<file>` (wireless debugging). On-device files are **unredacted** (VIN/GPS)
— treat as sensitive and delete local copies after analysis. Per-session parsing: record
types `command` (with `durationMs`/`response`/`gotPrompt`), `telemetry` (per-signal values
+ `*StaleMs` + `vehicleState`), `event`, `status`, `error`. See also
[[volttracker-obd-reliability]] memory note for the running failure-pattern catalogue.
