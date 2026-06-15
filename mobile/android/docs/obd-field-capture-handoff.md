# OBD Field-Capture Handoff

Purpose: a self-contained note so a future session (human or AI) can pick up the open OBD
decoder questions that need **fresh data from the car** — they can't be settled from the
logs we already have. Hand this whole file to the next session along with a new log pull.

Context: findings here came from a day of on-device session logs off the user's Gen2 Chevy
Volt (adapter: OBDLink MX+ 54242), analyzed 2026-06-14. The decoder fixes that *could* be
validated from existing logs are already done (see `pid-validation-2026-06-03.md` →
"Field-Log Validation - 2026-06-14"). What's below still needs a capture.

## How to pull a fresh log set from the phone

The app build is debuggable, so no root is needed.

1. On the phone: Settings → Developer options → **Wireless debugging** → note the IP and the
   **current port** (the port rotates each time wireless debugging restarts).
2. On the dev machine:
   ```
   adb connect <ip>:<port>          # retry once if it says "No route to host"
   adb devices -l                   # confirm the SM-S928U1 shows as "device"
   ```
3. Pull the logs (private app storage, via run-as). zsh: use an array, not a bare for-var.
   ```
   P=com.volttracker.obdpoc; D=/tmp/volt-logs; mkdir -p $D
   adb exec-out run-as $P sh -c 'cd files/obd-logs && tar c .' | tar x -C $D
   adb exec-out run-as $P cat files/app-log/app.log > $D/app.log
   ```
   Key files: `obd-logs/session-<ms>-<mode>.jsonl` (mode = obd | scan | tpms-scan |
   detail-probe), `obd-logs/sessions-summary.jsonl` (one rollup line/session), `app-log/app.log`.

JSONL record types: `command` (raw ELM cmd+response), `telemetry` (decoded snapshot — each
`raw` field carries that cycle's `[cmd] response …` transcript), `event`, `status`,
`system_snapshot`, `error`. The raw on-device JSONL is **not** redacted (GPS + partial VIN) —
treat pulled copies as sensitive.

## Open item 1 — engine torque `22203F` (1-byte scale unknown)

This Volt answers `22203F` with a **single** data byte (e.g. `62203F00`, `62203F17`), not the
2-byte word the community sheet models as `((256*A)+B)/4` (0–200 Nm). 217/319 reads were
`62203F00` and 74 were NO DATA, because the **engine is off in EV mode** for almost every read,
so the byte is ~0 and the real scale can't be inferred.

What to capture: **drive a stretch with the gas engine actually running** (let the range
extender kick in — low battery, or "Hold"/"Mountain" mode), then pull the logs.

How to validate from the new capture:
```
# extract 22203F responses while the engine ran
grep -ho '\[22203F\] 62203F[0-9A-F]*' /tmp/volt-logs/*-obd.jsonl | sort | uniq -c | sort -rn
```
- Confirm it's still 1 byte under load. If the byte tracks engine effort (idle small → accel
  large), find the scale: e.g. if peak ≈ the engine's max torque (~150–170 Nm on the 1.5 L),
  the byte is likely `Nm` directly or `Nm/4`. Cross-check against `010C` RPM and `0104` load in
  the same rows.
- Then in `ObdProtocol.parseKnownValueLegacy`, change `22203F` from `voltWordValue(…4.0…)` to a
  1-byte decode with the validated scale, add a test in `ObdProtocolTest`, update this doc +
  `pid-validation-2026-06-03.md`.
- If it stays mostly 0/NO DATA even with the engine running, **drop `22203F`** from the live
  poll set (`PidSchedule`/`ObdProbes.VOLT_7E0_PROBES`) instead — it's not earning its bus slot.

## Open item 2 — cell-number PIDs `22432A` / `22432C` semantics suspect

Labeled "minimum/maximum cell number" but in 39/81 paired field rows the decoded "min #" was
**greater than** the "max #" — a ~50/50 split, which is impossible if they're the min/max-voltage
cell indices. The 1-byte decode (range 1–96) is fine; the **meaning** is probably wrong.

How to investigate from a new capture (ideally during charge or heavy load, when one cell
clearly leads):
```
# pair each cell-number read with the min/max cell voltage words in the same telemetry row
python3 - <<'PY'
import json,glob,re
mn=re.compile(r'\[22432A\] 62432A([0-9A-F]{2})'); mx=re.compile(r'\[22432C\] 62432C([0-9A-F]{2})')
vlo=re.compile(r'\[224329\] 624329([0-9A-F]{4})'); vhi=re.compile(r'\[22432B\] 62432B([0-9A-F]{4})')
for fn in glob.glob('/tmp/volt-logs/*-obd.jsonl'):
  for line in open(fn):
    try:o=json.loads(line)
    except:continue
    if o.get('type')!='telemetry':continue
    r=o['payload'].get('raw','')
    a,b,lo,hi=mn.search(r),mx.search(r),vlo.search(r),vhi.search(r)
    if a and b and lo and hi:
      print(int(a.group(1),16),int(b.group(1),16),int(lo.group(1),16)/1600,int(hi.group(1),16)/1600)
PY
```
- If `22432A`'s index doesn't correlate with which cell is actually lowest, these DIDs are
  something else (module index, balancing target, temperature cell, …). Re-label or demote them
  in `EnhancedPidProfiles` + `ObdProtocol`, and stop deriving min/max-cell from them.

## Reference

- Decoders: `app/src/main/kotlin/com/volttracker/obdpoc/ObdProtocol.kt`
  (`parseKnownValueLegacy`, `cellVoltageValue`, `isBenignSentinelResponse`).
- Live sample build + parse-failure diagnostic: `LiveSampleReader.kt`.
- PID list/scales catalogue: `EnhancedPidProfiles.kt`, `PidSchedule.kt`, `ObdProbes.kt`.
- Validation history: `pid-validation-2026-06-03.md`, `volt-pid-research-2026-05-20.md`,
  `volt-pids-community-sheet.csv`.
