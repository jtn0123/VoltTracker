package com.volttracker.obdpoc;

import com.volttracker.obdpoc.data.ObdLocalStore;

import java.util.Locale;

/**
 * Stateless decoding, classification and formatting helpers for the OBD/ELM layer.
 * Extracted from {@link ObdService} so the service file stays focused and these pure
 * functions are unit-testable on their own. {@code ObdService} static-imports this class.
 */
final class ObdElmDecode {

    private ObdElmDecode() {
    }

    static boolean hasElmPrompt(String response) {
        return response != null && response.indexOf('>') >= 0;
    }

    static String appendRaw(String raw, String command, String response) {
        return raw + command + ": " + summarizeForStorage(command, response) + "\n";
    }

    static String summarizeForStorage(String command, String response) {
        String summary = ObdProtocol.summarize(response);
        if (!isVinCommand(command)) {
            return summary;
        }
        if (summary.isEmpty()) {
            return "";
        }
        return "[VIN redacted; responseLength=" + summary.length() + "]";
    }

    private static boolean isVinCommand(String command) {
        return command != null && "0902".equals(command.trim().toUpperCase(Locale.US));
    }

    static String pidForCommand(String command) {
        if (command == null) {
            return "";
        }
        String clean = command.trim().toUpperCase(Locale.US);
        if (clean.startsWith("01") && clean.length() >= 4) {
            return clean.substring(2, 4);
        }
        if (clean.startsWith("22") && clean.length() >= 6) {
            return clean.substring(2);
        }
        if (clean.startsWith("09") && clean.length() >= 4) {
            return clean.substring(2, 4);
        }
        return "";
    }

    static String nameForCommand(String command) {
        if (command == null) {
            return "";
        }
        String clean = command.trim().toUpperCase(Locale.US);
        if ("ATRV".equals(clean)) {
            return "adapter voltage";
        }
        if ("010D".equals(clean)) {
            return "vehicle speed";
        }
        if ("010C".equals(clean)) {
            return "engine rpm";
        }
        if ("0105".equals(clean)) {
            return "coolant temperature";
        }
        if ("0104".equals(clean)) {
            return "engine load";
        }
        if ("0111".equals(clean)) {
            return "throttle position";
        }
        if ("0902".equals(clean)) {
            return "vin";
        }
        return "";
    }

    static void appendProbeLine(StringBuilder raw, String label, String value) {
        raw.append(label).append(": ").append(value == null ? "" : value).append('\n');
    }

    static String tail(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(value.length() - maxLength);
    }

    /** Exponential backoff between OBD reconnect attempts, capped at 30 s. */
    static long reconnectBackoffMs(int attempt) {
        if (attempt < 1) {
            return 0L;
        }
        long base = 2000L * (1L << Math.min(attempt - 1, 4));
        return Math.min(30000L, base);
    }

    static String classifyVehicleState(Float voltage, Integer speed, Float rpm, Integer load, boolean chargeTransitionHint) {
        boolean stationary = speed == null || speed == 0;
        boolean engineOff = rpm == null || rpm < 80;
        boolean dcDcActive = voltage != null && voltage >= 13.0f;
        boolean hasLoad = load != null && load > 0;
        if (stationary && engineOff && chargeTransitionHint) {
            return "plugged-or-charging";
        }
        if (stationary && engineOff && dcDcActive) {
            return "ready-parked";
        }
        if (stationary && engineOff) {
            return hasLoad ? "awake-parked" : "parked";
        }
        if (!engineOff) {
            return stationary ? "engine-idle" : "driving-gas";
        }
        return "driving-ev";
    }

    static String classifyVehicleStateConfidence(Float voltage, Integer speed, Float rpm, boolean chargeTransitionHint) {
        if (chargeTransitionHint) {
            return "inferred";
        }
        if (voltage != null && speed != null && rpm != null) {
            return "observed";
        }
        if (voltage != null || speed != null || rpm != null) {
            return "partial";
        }
        return "unknown";
    }

    static String finishStatusFor(String state) {
        if ("error".equals(state) || "blocked".equals(state)) {
            return ObdLocalStore.STATUS_ERROR;
        }
        if ("idle".equals(state)) {
            return ObdLocalStore.STATUS_DISCONNECTED;
        }
        return ObdLocalStore.STATUS_COMPLETE;
    }

    static String friendlyConnectionMessage(Exception ex) {
        String message = safeMessage(ex).toLowerCase(Locale.US);
        if (message.contains("socket might closed") || message.contains("timeout") || message.contains("read failed")) {
            return "Adapter serial channel did not open. Make sure the car is awake, close other OBD apps, then retry.";
        }
        if (message.contains("permission")) {
            return "Bluetooth permission is missing. Grant permissions, then retry.";
        }
        return "OBD connection failed: " + safeMessage(ex);
    }

    static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return ex.getClass().getSimpleName();
        }
        return message;
    }

    static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
