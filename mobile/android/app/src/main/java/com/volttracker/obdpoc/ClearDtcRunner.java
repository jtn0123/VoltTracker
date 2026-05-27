package com.volttracker.obdpoc;

import android.util.Log;
import java.io.IOException;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Runs the OBD-II Mode 04 (clear/reset diagnostic trouble codes) sequence on behalf of {@link
 * ObdPollingEngine}. Mode 04 is a one-shot write: send {@code "04"}, read the reply, parse one of:
 *
 * <ul>
 *   <li><b>44</b> - positive response, DTCs cleared
 *   <li><b>7F 04 &lt;NRC&gt;</b> - negative response; common NRCs:
 *       <ul>
 *         <li><b>22</b> - conditions not correct (engine running, gear engaged, etc.)
 *         <li><b>11</b> - service not supported by the vehicle
 *         <li><b>12</b> - sub-function not supported
 *       </ul>
 * </ul>
 *
 * <p>Runs on the service IO thread. Command IO is dispatched back through {@link
 * ObdPollingEngine#sendRecoverableCommand(String, long)} so the service's {@code ioLock} and the
 * per-command logging continue to apply.
 */
final class ClearDtcRunner {

    private final ObdService service;
    private final ObdPollingEngine engine;

    ClearDtcRunner(ObdService service, ObdPollingEngine engine) {
        this.service = service;
        this.engine = engine;
    }

    void run() throws IOException {
        service.broadcastStatus("clearing-codes", "Sending OBD-II Mode 04 (clear DTCs)...", false);
        service.updateNotification("Clearing codes on " + service.activeName);

        String response = engine.sendRecoverableCommand("04", 6000);
        String compact = compactResponse(response);
        OBDLog.event(
                "ClearDtcRunner",
                "mode04_reply",
                java.util.Map.of("raw", safeForLog(response), "compact", compact));

        String nrc = extractNegativeResponseCode(compact);
        if (!nrc.isEmpty()) {
            String detail = describeFailure(nrc);
            service.broadcastStatus("error", detail, true);
            service.updateNotification("Clear-codes failed on " + service.activeName);
            broadcastResult(false, nrc, compact);
            return;
        }

        if (hasPositiveMode04Response(compact)) {
            service.broadcastStatus(
                    "codes-cleared",
                    "Vehicle DTCs cleared. The check-engine light will go out once the PCM"
                            + " confirms - emissions readiness monitors are now reset.",
                    false);
            service.updateNotification("DTCs cleared on " + service.activeName);
            broadcastResult(true, "ok", compact);
            return;
        }

        String detail = describeFailure("");
        service.broadcastStatus("error", detail, true);
        service.updateNotification("Clear-codes failed on " + service.activeName);
        broadcastResult(false, "", compact);
    }

    private void broadcastResult(boolean ok, String code, String raw) {
        JSONObject sample = new JSONObject();
        try {
            sample.put("source", "clear-dtc");
            sample.put("connected", true);
            sample.put("adapter", service.activeName);
            sample.put("updatedAt", System.currentTimeMillis());
            sample.put("clearDtcOk", ok);
            sample.put("clearDtcCode", code);
            sample.put("raw", safeForLog(raw));
        } catch (JSONException ex) {
            Log.w(MainActivity.TAG, "clear-dtc telemetry encode failed", ex);
        }
        service.broadcastTelemetry(sample);
    }

    static String compactResponse(String raw) {
        if (raw == null) return "";
        return raw.replace('\r', ' ')
                .replace('\n', ' ')
                .replace('>', ' ')
                .trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    static String extractNegativeResponseCode(String compact) {
        // 7F 04 XX (with or without spaces) - extract XX.
        String normalized = compact == null ? "" : compact.replaceAll("[^0-9A-F]", "");
        int idx = normalized.indexOf("7F04");
        if (idx < 0 || idx + 6 > normalized.length()) return "";
        return normalized.substring(idx + 4, idx + 6);
    }

    static boolean hasPositiveMode04Response(String compact) {
        if (compact == null || compact.isEmpty()) {
            return false;
        }
        for (String token : compact.split("[^0-9A-F]+")) {
            if ("44".equals(token)) {
                return true;
            }
        }
        return false;
    }

    private static String describeFailure(String nrc) {
        switch (nrc) {
            case "22":
                return "Vehicle rejected Mode 04: conditions not correct. Park, ignition on,"
                        + " engine off, then try again.";
            case "11":
                return "Vehicle does not support Mode 04 (clear codes) over OBD-II.";
            case "12":
                return "Vehicle rejected Mode 04 sub-function.";
            case "13":
                return "Vehicle rejected Mode 04: invalid message length.";
            case "33":
                return "Vehicle rejected Mode 04: security access denied.";
            case "7F":
            case "":
                return "No usable reply from the vehicle for Mode 04. Re-check the adapter and"
                        + " try again.";
            default:
                return "Vehicle rejected Mode 04 (NRC 0x" + nrc + "). Codes were NOT cleared.";
        }
    }

    private static String safeForLog(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        return trimmed.length() <= 256 ? trimmed : trimmed.substring(0, 256);
    }
}
