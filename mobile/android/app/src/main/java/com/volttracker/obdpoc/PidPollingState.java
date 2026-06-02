package com.volttracker.obdpoc;

import com.volttracker.obdpoc.PidSchedule.Header;
import com.volttracker.obdpoc.PidSchedule.PidSpec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/** Owns live-poll PID scheduling, batching, carry-forward raw values, and stale timers. */
final class PidPollingState {

    // Carry-forward age ceiling. A PID that stops responding keeps serving its last raw value via
    // lastRawByCommand indefinitely, so the dashboard would render a frozen number forever (its own
    // *StaleMs rendering is advisory and can be ignored by other consumers). Past this ceiling we
    // drop the carried value so lastRaw(command) returns null and the sample reads "--" instead.
    // The ceiling sits comfortably above the slowest scheduled cadence — Tier 3 PIDs poll every 10
    // cycles (~17s at the live-poll rate) — so a value that is merely slow but still responding is
    // never dropped; only a truly stalled command ages out.
    static final long CARRY_FORWARD_MAX_AGE_MS = 30_000L;

    private final ObdService service;
    private final ObdPollingEngine engine;
    private int cycleNum;
    private boolean mode01BatchSupported;
    private final Map<String, String> lastRawByCommand = new HashMap<>();
    private final Map<String, Long> lastPolledAtMsByCommand = new HashMap<>();
    // When each command's lastRaw was last set. Kept in lockstep with lastRawByCommand writes;
    // gates
    // carry-forward expiry independently of lastPolledAtMsByCommand (which the dashboard reads).
    private final Map<String, Long> lastRawSetAtMsByCommand = new HashMap<>();
    // Clock seam so tests can drive carry-forward expiry deterministically without sleeping.
    private Clock clock = System::currentTimeMillis;

    /** Time source, overridable in tests. */
    interface Clock {
        long nowMs();
    }

    void setClockForTesting(Clock clock) {
        this.clock = clock;
    }

    PidPollingState(ObdService service, ObdPollingEngine engine) {
        this.service = service;
        this.engine = engine;
    }

    void reset() {
        cycleNum = 0;
        mode01BatchSupported = false;
        lastRawByCommand.clear();
        lastPolledAtMsByCommand.clear();
        lastRawSetAtMsByCommand.clear();
    }

    boolean isInitialCycle() {
        return cycleNum == 0;
    }

    List<PidSpec> dueForCurrentCycle() {
        return isInitialCycle() ? PidSchedule.SPECS : PidSchedule.dueOnCycle(cycleNum);
    }

    void advanceCycle() {
        cycleNum += 1;
    }

    void setMode01BatchSupported(boolean supported) {
        mode01BatchSupported = supported;
    }

    String lastRaw(String command) {
        Long setAtMs = lastRawSetAtMsByCommand.get(command);
        if (setAtMs != null && clock.nowMs() - setAtMs > CARRY_FORWARD_MAX_AGE_MS) {
            // The carried value has aged past the ceiling — a command that stopped responding.
            // Drop it so the sample reads "--" rather than a frozen number. Cleared eagerly so
            // repeated reads don't keep re-checking a permanently stale entry.
            lastRawByCommand.remove(command);
            lastRawSetAtMsByCommand.remove(command);
            return null;
        }
        return lastRawByCommand.get(command);
    }

    private void putLastRaw(String command, String response, long nowMs) {
        lastRawByCommand.put(command, response);
        lastRawSetAtMsByCommand.put(command, nowMs);
    }

    void putStaleMsIfTracked(JSONObject sample, String key, String command, long now)
            throws JSONException {
        Long polledAtMs = lastPolledAtMsByCommand.get(command);
        if (polledAtMs != null) {
            sample.put(key, Math.max(0L, now - polledAtMs));
        }
    }

    /**
     * Issues every PID read due on this cycle, grouped by ATSH header so we don't pay an
     * unnecessary header switch for headers that have nothing due.
     */
    void runScheduledPolls(List<PidSpec> due, StringBuilder rawThisCycle) throws IOException {
        if (due.isEmpty()) {
            return;
        }
        boolean batchedTier1 = tryBatchTier1Mode01(due, rawThisCycle);
        boolean switchedHeader = false;
        Header lastHeaderSet = Header.BROADCAST;
        for (Header header : Header.values()) {
            List<PidSpec> headerSpecs = filterByHeader(due, header);
            if (headerSpecs.isEmpty()) {
                continue;
            }
            if (header != Header.BROADCAST && header != lastHeaderSet) {
                engine.sendCommand(header.atCommand, 1500);
                switchedHeader = true;
                lastHeaderSet = header;
            }
            // Generalized Mode-01 batching: any same-header Mode-01 PIDs due together on this cycle
            // (beyond the Tier-1 set handled above) can share a single multi-PID request, saving
            // round-trips. Tier-1 keeps its dedicated probe-gated path — these are the *other*
            // tiers' Mode-01 PIDs that happen to coincide on a cycle (e.g. SOC + coolant on cycle
            // 10). Falls back to per-PID on this adapter if the batched reply is short.
            List<PidSpec> extraBatch =
                    mode01BatchSupported
                            ? batchableMode01(headerSpecs, batchedTier1, header)
                            : java.util.Collections.emptyList();
            boolean batchedExtra =
                    extraBatch.size() >= 2 && tryBatchMode01Group(extraBatch, rawThisCycle);
            for (PidSpec spec : headerSpecs) {
                if (batchedTier1
                        && header == Header.BROADCAST
                        && PidSchedule.MODE_01_BATCH_COMMANDS.contains(spec.command)) {
                    continue;
                }
                if (batchedExtra && extraBatch.contains(spec)) {
                    continue;
                }
                String response = engine.sendRecoverableCommand(spec.command, 1500);
                appendRawTo(rawThisCycle, spec.command, response);
                long now = clock.nowMs();
                putLastRaw(spec.command, response, now);
                lastPolledAtMsByCommand.put(spec.command, now);
            }
        }
        if (switchedHeader) {
            engine.sendCommand(PidSchedule.RESTORE_BROADCAST_HEADER_COMMAND, 1500);
        }
    }

    private boolean tryBatchTier1Mode01(List<PidSpec> due, StringBuilder rawThisCycle)
            throws IOException {
        if (!mode01BatchSupported) {
            return false;
        }
        for (String batchCommand : PidSchedule.MODE_01_BATCH_COMMANDS) {
            if (!commandDueOnBroadcast(due, batchCommand)) {
                return false;
            }
        }
        String batched = ObdProtocol.buildMode01MultiCommand(PidSchedule.MODE_01_BATCH_PIDS_HEX);
        String response = engine.sendRecoverableCommand(batched, 1500);
        if (!ObdProtocol.responseContainsAllMode01Pids(
                response, PidSchedule.MODE_01_BATCH_PIDS_HEX)) {
            mode01BatchSupported = false;
            service.recorder.logEvent(
                    "mode01_batch_disabled",
                    "reason",
                    "incomplete_response",
                    "response",
                    ObdProtocol.summarize(response));
            return false;
        }
        long now = clock.nowMs();
        appendRawTo(rawThisCycle, batched, response);
        for (String command : PidSchedule.MODE_01_BATCH_COMMANDS) {
            putLastRaw(command, response, now);
            lastPolledAtMsByCommand.put(command, now);
        }
        return true;
    }

    /**
     * Selects the Mode-01 PIDs in {@code headerSpecs} eligible for ad-hoc same-cycle batching: a
     * 4-char {@code 01XX} command on the broadcast header that is NOT already covered by the Tier-1
     * batch (when that batch fired). Mode-01 PIDs reply with self-describing {@code 41XX} frames,
     * so the engine can assemble several into one request and let each parser pick out its own
     * marker. Non-broadcast headers (HV pack, Mode-22) are never batched here — those use mode-22
     * framing.
     */
    private static List<PidSpec> batchableMode01(
            List<PidSpec> headerSpecs, boolean batchedTier1, Header header) {
        if (header != Header.BROADCAST) {
            return java.util.Collections.emptyList();
        }
        List<PidSpec> out = new ArrayList<>();
        for (PidSpec spec : headerSpecs) {
            if (!isMode01Command(spec.command)) {
                continue;
            }
            if (batchedTier1 && PidSchedule.MODE_01_BATCH_COMMANDS.contains(spec.command)) {
                // Already served by the Tier-1 batch this cycle — don't request it twice.
                continue;
            }
            out.add(spec);
        }
        return out;
    }

    private static boolean isMode01Command(String command) {
        return command != null && command.length() == 4 && command.startsWith("01");
    }

    /**
     * Batches an arbitrary group of same-header Mode-01 PIDs into one multi-PID request. Returns
     * true and records each command's carry-forward value on success; returns false (so the caller
     * polls them per-PID) if the adapter's reply is missing any requested frame. Unlike the Tier-1
     * path this does NOT flip {@code mode01BatchSupported} — a short reply here is treated as a
     * per-cycle fallback for this group, not a permanent adapter capability verdict.
     */
    private boolean tryBatchMode01Group(List<PidSpec> group, StringBuilder rawThisCycle)
            throws IOException {
        List<String> pidHex = new ArrayList<>(group.size());
        for (PidSpec spec : group) {
            pidHex.add(spec.command.substring(2));
        }
        String batched = ObdProtocol.buildMode01MultiCommand(pidHex);
        String response = engine.sendRecoverableCommand(batched, 1500);
        if (!ObdProtocol.responseContainsAllMode01Pids(response, pidHex)) {
            return false;
        }
        long now = clock.nowMs();
        appendRawTo(rawThisCycle, batched, response);
        for (PidSpec spec : group) {
            putLastRaw(spec.command, response, now);
            lastPolledAtMsByCommand.put(spec.command, now);
        }
        return true;
    }

    static boolean wasPolledThisCycle(List<PidSpec> due, String command) {
        for (PidSpec spec : due) {
            if (command.equals(spec.command)) {
                return true;
            }
        }
        return false;
    }

    static String boundedRawTranscript(StringBuilder rawThisCycle) {
        String raw = rawThisCycle == null ? "" : rawThisCycle.toString().trim();
        if (raw.length() <= ObdPollingEngine.RAW_TRANSCRIPT_MAX_CHARS) {
            return raw;
        }
        int omitted = raw.length() - ObdPollingEngine.RAW_TRANSCRIPT_MAX_CHARS;
        return raw.substring(0, ObdPollingEngine.RAW_TRANSCRIPT_MAX_CHARS)
                + "... [truncated "
                + omitted
                + " chars]";
    }

    private static boolean commandDueOnBroadcast(List<PidSpec> due, String command) {
        for (PidSpec spec : due) {
            if (spec.header == Header.BROADCAST && command.equals(spec.command)) {
                return true;
            }
        }
        return false;
    }

    private static List<PidSpec> filterByHeader(List<PidSpec> specs, Header header) {
        List<PidSpec> out = new ArrayList<>();
        for (PidSpec spec : specs) {
            if (spec.header == header) {
                out.add(spec);
            }
        }
        return out;
    }

    private static void appendRawTo(StringBuilder buf, String command, String response) {
        if (buf.length() > 0) {
            buf.append(' ');
        }
        buf.append('[').append(command).append("] ").append(ObdProtocol.summarize(response));
    }
}
