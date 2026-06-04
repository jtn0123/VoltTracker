package com.volttracker.obdpoc;

import com.volttracker.obdpoc.data.ObdLocalStore;
import java.util.Locale;

/**
 * Stateless decoding, classification and formatting helpers for the OBD/ELM layer. Extracted from
 * {@link ObdService} so the service file stays focused and these pure functions are unit-testable
 * on their own. {@code ObdService} static-imports this class.
 */
final class ObdElmDecode {

    private ObdElmDecode() {}

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
        if ("010F".equals(clean)) {
            return "intake air temperature";
        }
        if ("0104".equals(clean)) {
            return "engine load";
        }
        if ("0111".equals(clean)) {
            return "throttle position";
        }
        if ("0149".equals(clean)) {
            return "accelerator pedal position";
        }
        if ("0142".equals(clean)) {
            return "control module voltage";
        }
        if ("011F".equals(clean)) {
            return "engine run time";
        }
        if ("01A6".equals(clean)) {
            return "odometer";
        }
        if ("012F".equals(clean)) {
            return "fuel level";
        }
        if ("015C".equals(clean)) {
            return "engine oil temperature";
        }
        if ("22119F".equals(clean) || "22119F01".equals(clean)) {
            return "engine oil life";
        }
        if ("221154".equals(clean)) {
            return "engine oil temperature";
        }
        if ("22203F".equals(clean)) {
            return "engine torque";
        }
        if ("015B".equals(clean)) {
            return "state of charge";
        }
        if ("222429".equals(clean)) {
            return "hv pack voltage";
        }
        if ("222414".equals(clean)) {
            return "hv pack current";
        }
        if ("222883".equals(clean)) {
            return "motor A current";
        }
        if ("222884".equals(clean)) {
            return "motor B current";
        }
        if ("222885".equals(clean)) {
            return "motor A voltage";
        }
        if ("222886".equals(clean)) {
            return "motor B voltage";
        }
        if ("222487".equals(clean)) {
            return "ev distance this cycle";
        }
        if ("222889".equals(clean)) {
            return "prndl state";
        }
        if ("221940".equals(clean) || "22194001".equals(clean)) {
            return "transmission temperature";
        }
        if ("22434F".equals(clean)) {
            return "hv battery temperature";
        }
        if ("224368".equals(clean)) {
            return "charger ac voltage";
        }
        if ("224369".equals(clean)) {
            return "charger ac current";
        }
        if ("22436B".equals(clean)) {
            return "charger hv voltage";
        }
        if ("22436C".equals(clean)) {
            return "charger hv current";
        }
        if ("224373".equals(clean)) {
            return "charging mode";
        }
        if ("22437D".equals(clean)) {
            return "last charge energy";
        }
        if ("2243A5".equals(clean)) {
            return "hv battery charge count";
        }
        if ("2243AF".equals(clean)) {
            return "hv battery raw soc";
        }
        if ("224531".equals(clean)) {
            return "charging level";
        }
        if ("228334".equals(clean)) {
            return "hv battery displayed soc";
        }
        if ("2241B2".equals(clean)) {
            return "battery coolant pump rpm";
        }
        if ("2241B4".equals(clean)) {
            return "battery coolant valve";
        }
        if ("2241B6".equals(clean)) {
            return "battery heater power";
        }
        if ("22801E".equals(clean)) {
            return "outside air temperature raw";
        }
        if ("22801F".equals(clean)) {
            return "outside air temperature filtered";
        }
        if ("22248E".equals(clean)) {
            return "candidate tire pressure front-left";
        }
        if ("22248F".equals(clean)) {
            return "candidate tire pressure front-right";
        }
        if ("222490".equals(clean)) {
            return "candidate tire pressure rear-right";
        }
        if ("222491".equals(clean)) {
            return "candidate tire pressure rear-left";
        }
        if ("22C901".equals(clean)) {
            return "candidate tire pressures";
        }
        if ("22C902".equals(clean)) {
            return "candidate tire temperatures";
        }
        if ("224051".equals(clean)
                || "224052".equals(clean)
                || "224053".equals(clean)
                || "224054".equals(clean)) {
            return "candidate tire receiver slot " + clean.substring(clean.length() - 1);
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

    /**
     * Backoff before retrying the initial connect, before any link was ever established. Quicker
     * and gentler than {@link #reconnectBackoffMs}: a first-attempt RFCOMM glitch retries almost
     * immediately, and a genuinely asleep car exhausts its tries fast instead of stalling the user
     * through ~90 s of exponential backoff.
     */
    static long initialConnectBackoffMs(int attempt) {
        if (attempt < 1) {
            return 0L;
        }
        return Math.min(3000L, 500L * attempt);
    }

    static String finishStatusFor(String state) {
        if ("error".equals(state) || "blocked".equals(state)) {
            return ObdLocalStore.STATUS_ERROR;
        }
        // "complete" means the session actually reached the adapter. A teardown while
        // still "connecting"/"initializing" — e.g. the user retried before the link
        // came up — never connected, so it falls through to "disconnected" below.
        if ("connected".equals(state)
                || "scanning".equals(state)
                || "scan-complete".equals(state)) {
            return ObdLocalStore.STATUS_COMPLETE;
        }
        return ObdLocalStore.STATUS_DISCONNECTED;
    }

    static String friendlyConnectionMessage(Exception ex) {
        String message = safeMessage(ex).toLowerCase(Locale.US);
        if (message.contains("socket might closed")
                || message.contains("timeout")
                || message.contains("read failed")) {
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
