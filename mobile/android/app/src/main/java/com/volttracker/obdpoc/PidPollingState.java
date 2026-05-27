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

    private final ObdService service;
    private final ObdPollingEngine engine;
    private int cycleNum;
    private boolean mode01BatchSupported;
    private final Map<String, String> lastRawByCommand = new HashMap<>();
    private final Map<String, Long> lastPolledAtMsByCommand = new HashMap<>();

    PidPollingState(ObdService service, ObdPollingEngine engine) {
        this.service = service;
        this.engine = engine;
    }

    void reset() {
        cycleNum = 0;
        mode01BatchSupported = false;
        lastRawByCommand.clear();
        lastPolledAtMsByCommand.clear();
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
        return lastRawByCommand.get(command);
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
            for (PidSpec spec : headerSpecs) {
                if (batchedTier1
                        && header == Header.BROADCAST
                        && PidSchedule.MODE_01_BATCH_COMMANDS.contains(spec.command)) {
                    continue;
                }
                String response = engine.sendRecoverableCommand(spec.command, 1500);
                appendRawTo(rawThisCycle, spec.command, response);
                lastRawByCommand.put(spec.command, response);
                lastPolledAtMsByCommand.put(spec.command, System.currentTimeMillis());
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
        long now = System.currentTimeMillis();
        appendRawTo(rawThisCycle, batched, response);
        for (String command : PidSchedule.MODE_01_BATCH_COMMANDS) {
            lastRawByCommand.put(command, response);
            lastPolledAtMsByCommand.put(command, now);
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
