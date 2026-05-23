package com.volttracker.obdpoc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class ObdProtocol {
    private ObdProtocol() {}

    static final class ParsedPidValue {
        final String name;
        final String valueText;
        final Double valueNumeric;
        final String unit;

        ParsedPidValue(String name, String valueText, Double valueNumeric, String unit) {
            this.name = name == null ? "" : name;
            this.valueText = valueText == null ? "" : valueText;
            this.valueNumeric = valueNumeric;
            this.unit = unit == null ? "" : unit;
        }
    }

    static final class DiagnosticTroubleCode {
        final String code;
        final String status;
        final String statusLabel;
        final String moduleKey;
        final String moduleName;
        final String header;
        final String rawResponse;

        DiagnosticTroubleCode(
                String code,
                String status,
                String statusLabel,
                String moduleKey,
                String moduleName,
                String header,
                String rawResponse) {
            this.code = code == null ? "" : code;
            this.status = status == null ? "" : status;
            this.statusLabel = statusLabel == null ? "" : statusLabel;
            this.moduleKey = moduleKey == null ? "" : moduleKey;
            this.moduleName = moduleName == null ? "" : moduleName;
            this.header = header == null ? "" : header;
            this.rawResponse = rawResponse == null ? "" : rawResponse;
        }
    }

    static Integer parseSpeedKph(String response) {
        int[] bytes = mode01Bytes(response, "0D", 1);
        if (bytes == null || bytes[0] == 0xFF) {
            return null;
        }
        return bytes[0];
    }

    static boolean hasMaxSpeedSentinel(String response) {
        int[] bytes = mode01Bytes(response, "0D", 1);
        return bytes != null && bytes[0] == 0xFF;
    }

    static Float parseRpm(String response) {
        int[] bytes = mode01Bytes(response, "0C", 2);
        if (bytes == null) {
            return null;
        }
        return ((bytes[0] * 256f) + bytes[1]) / 4f;
    }

    static Integer parseCoolantC(String response) {
        int[] bytes = mode01Bytes(response, "05", 1);
        return bytes == null ? null : bytes[0] - 40;
    }

    static Integer parseEngineLoadPct(String response) {
        int[] bytes = mode01Bytes(response, "04", 1);
        return bytes == null ? null : Math.round(bytes[0] * 100f / 255f);
    }

    static Integer parseThrottlePct(String response) {
        int[] bytes = mode01Bytes(response, "11", 1);
        return bytes == null ? null : Math.round(bytes[0] * 100f / 255f);
    }

    static Integer parseStateOfChargePct(String response) {
        int[] bytes = mode01Bytes(response, "5B", 1);
        return bytes == null ? null : Math.round(bytes[0] * 100f / 255f);
    }

    static Float parseVoltage(String response) {
        if (response == null) {
            return null;
        }
        String cleaned =
                response.replace(">", "")
                        .replace("\r", "")
                        .replace("\n", "")
                        .trim()
                        .toUpperCase(Locale.US);
        int end = cleaned.indexOf('V');
        if (end < 0) {
            return null;
        }
        int start = end - 1;
        while (start >= 0) {
            char c = cleaned.charAt(start);
            if ((c >= '0' && c <= '9') || c == '.') {
                start--;
            } else {
                break;
            }
        }
        try {
            return Float.parseFloat(cleaned.substring(start + 1, end));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static String summarize(String response) {
        if (response == null) {
            return "";
        }
        return response.replace("\r", " ").replace("\n", " ").replace(">", "").trim();
    }

    /**
     * Cleans an 0100-style capability response for storage in the supported-PID field. {@link
     * #summarize} only strips line endings and the prompt; while the ELM327 auto-detects the
     * protocol it also emits the "SEARCHING..." status word inline — and glued straight onto — the
     * first {@code 4100} frame. That token is noise here, so strip it and collapse the whitespace,
     * leaving only the capability frames.
     */
    static String cleanSupportedPids(String response) {
        return summarize(response)
                .replaceAll("(?i)SEARCHING\\.*", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static ParsedPidValue parseKnownValue(String command, String response) {
        String cleanCommand = command == null ? "" : command.trim().toUpperCase(Locale.US);
        if ("ATRV".equals(cleanCommand)) {
            Float voltage = parseVoltage(response);
            return voltage == null ? null : value("adapter voltage", voltage.doubleValue(), "V", 1);
        }
        if ("010D".equals(cleanCommand)) {
            Integer speed = parseSpeedKph(response);
            return speed == null ? null : value("vehicle speed", speed.doubleValue(), "km/h", 0);
        }
        if ("010C".equals(cleanCommand)) {
            Float rpm = parseRpm(response);
            return rpm == null ? null : value("engine rpm", rpm.doubleValue(), "rpm", 0);
        }
        if ("0105".equals(cleanCommand)) {
            Integer coolant = parseCoolantC(response);
            return coolant == null
                    ? null
                    : value("coolant temperature", coolant.doubleValue(), "deg C", 0);
        }
        if ("0104".equals(cleanCommand)) {
            Integer load = parseEngineLoadPct(response);
            return load == null ? null : value("engine load", load.doubleValue(), "%", 0);
        }
        if ("0111".equals(cleanCommand)) {
            Integer throttle = parseThrottlePct(response);
            return throttle == null
                    ? null
                    : value("throttle position", throttle.doubleValue(), "%", 0);
        }
        if ("015B".equals(cleanCommand)) {
            Integer soc = parseStateOfChargePct(response);
            return soc == null ? null : value("state of charge", soc.doubleValue(), "%", 0);
        }
        if ("222429".equals(cleanCommand)) {
            Double voltage = voltWordValue(response, cleanCommand, 64.0, true);
            return voltage == null ? null : value("hv pack voltage", voltage, "V", 1);
        }
        if ("222414".equals(cleanCommand)) {
            Double current = voltWordValue(response, cleanCommand, 20.0, true);
            return current == null ? null : value("hv pack current", current, "A", 2);
        }
        if ("22434F".equals(cleanCommand)) {
            Double temp = voltByteValue(response, cleanCommand, 1.0, -40.0);
            return temp == null ? null : value("hv battery temperature", temp, "deg C", 0);
        }
        if ("224368".equals(cleanCommand)) {
            Double voltage = voltByteValue(response, cleanCommand, 2.0, 0.0);
            return voltage == null ? null : value("charger ac voltage", voltage, "V", 0);
        }
        if ("224369".equals(cleanCommand)) {
            Double current = voltByteValue(response, cleanCommand, 0.2, 0.0);
            return current == null ? null : value("charger ac current", current, "A", 1);
        }
        if ("22436B".equals(cleanCommand)) {
            Double voltage = voltWordValue(response, cleanCommand, 2.0, true);
            return voltage == null ? null : value("charger hv voltage", voltage, "V", 1);
        }
        if ("22436C".equals(cleanCommand)) {
            Double current = voltWordValue(response, cleanCommand, 20.0, true);
            return current == null ? null : value("charger hv current", current, "A", 2);
        }
        if ("224373".equals(cleanCommand)) {
            Double power = voltWordValue(response, cleanCommand, 1.0, true);
            return power == null ? null : value("charger hv power", power, "W", 0);
        }
        if ("22437D".equals(cleanCommand)) {
            // Community formula is (A*256+B)*10 Wh; dividing by 0.1 applies the x10 scale.
            Double energy = voltWordValue(response, cleanCommand, 0.1, false);
            return energy == null ? null : value("last charge energy", energy, "Wh", 0);
        }
        return null;
    }

    static List<DiagnosticTroubleCode> parseDiagnosticTroubleCodes(
            String command, String response, String header) {
        List<DiagnosticTroubleCode> codes = new ArrayList<>();
        String marker = positiveDtcMarker(command);
        if (marker == null || response == null) {
            return codes;
        }
        String cleanHeader = cleanHeader(header);
        String moduleKey =
                cleanHeader.isEmpty() || "7DF".equals(cleanHeader)
                        ? "generic-obd"
                        : "header-" + cleanHeader;
        String moduleName =
                "generic-obd".equals(moduleKey)
                        ? "ECM / powertrain (generic OBD-II)"
                        : "OBD module header " + cleanHeader;
        String status = dtcStatusForCommand(command);
        String statusLabel = dtcStatusLabel(status);
        String raw = summarize(response);
        Set<String> seen = new HashSet<>();
        boolean collectingMultiFrame = false;
        for (String line : response.split("[\\r\\n]+")) {
            String hex = line.toUpperCase(Locale.US).replaceAll("[^0-9A-F]", "");
            int index = hex.indexOf(marker);
            if (index < 0 && collectingMultiFrame) {
                String continuation = continuationPayload(hex);
                if (!continuation.isEmpty()) {
                    parseDiagnosticPayload(
                            continuation,
                            status,
                            statusLabel,
                            moduleKey,
                            moduleName,
                            cleanHeader,
                            raw,
                            seen,
                            codes);
                }
                continue;
            }
            while (index >= 0) {
                String payload = hex.substring(index + marker.length());
                parseDiagnosticPayload(
                        payload,
                        status,
                        statusLabel,
                        moduleKey,
                        moduleName,
                        cleanHeader,
                        raw,
                        seen,
                        codes);
                collectingMultiFrame = true;
                index = hex.indexOf(marker, index + marker.length());
            }
        }
        if (codes.isEmpty()) {
            String hex = response.toUpperCase(Locale.US).replaceAll("[^0-9A-F]", "");
            int index = hex.indexOf(marker);
            if (index >= 0) {
                parseDiagnosticPayload(
                        hex.substring(index + marker.length()),
                        status,
                        statusLabel,
                        moduleKey,
                        moduleName,
                        cleanHeader,
                        raw,
                        seen,
                        codes);
            }
        }
        return codes;
    }

    /**
     * HV pack power in kW from the Volt mode-22 pack-voltage (222429) and pack-current (222414)
     * responses. Discharge current is decoded as positive, so discharge power is positive. Returns
     * null if either frame is missing or malformed.
     */
    static Double parsePackPowerKw(String voltageResponse, String currentResponse) {
        ParsedPidValue voltage = parseKnownValue("222429", voltageResponse);
        ParsedPidValue current = parseKnownValue("222414", currentResponse);
        if (voltage == null
                || voltage.valueNumeric == null
                || current == null
                || current.valueNumeric == null) {
            return null;
        }
        return voltage.valueNumeric * current.valueNumeric / 1000.0;
    }

    private static int[] mode01Bytes(String response, String pid, int expectedBytes) {
        if (response == null) {
            return null;
        }
        String hex = response.toUpperCase(Locale.US).replaceAll("[^0-9A-F]", "");
        String marker = "41" + pid.toUpperCase(Locale.US);
        int index = hex.indexOf(marker);
        if (index < 0) {
            return null;
        }
        int dataStart = index + marker.length();
        if (hex.length() < dataStart + expectedBytes * 2) {
            return null;
        }
        int[] bytes = new int[expectedBytes];
        for (int i = 0; i < expectedBytes; i++) {
            int offset = dataStart + i * 2;
            bytes[i] = Integer.parseInt(hex.substring(offset, offset + 2), 16);
        }
        return bytes;
    }

    private static String positiveDtcMarker(String command) {
        String cleanCommand = command == null ? "" : command.trim().toUpperCase(Locale.US);
        if ("03".equals(cleanCommand)) {
            return "43";
        }
        if ("07".equals(cleanCommand)) {
            return "47";
        }
        if ("0A".equals(cleanCommand)) {
            return "4A";
        }
        if ("0202".equals(cleanCommand)) {
            return "4202";
        }
        return null;
    }

    private static String dtcStatusForCommand(String command) {
        String cleanCommand = command == null ? "" : command.trim().toUpperCase(Locale.US);
        if ("07".equals(cleanCommand)) {
            return "pending";
        }
        if ("0A".equals(cleanCommand)) {
            return "permanent";
        }
        if ("0202".equals(cleanCommand)) {
            return "freeze-frame";
        }
        return "stored";
    }

    private static String dtcStatusLabel(String status) {
        if ("pending".equals(status)) {
            return "Pending";
        }
        if ("permanent".equals(status)) {
            return "Permanent";
        }
        if ("freeze-frame".equals(status)) {
            return "Freeze frame";
        }
        return "Stored/current";
    }

    private static String cleanHeader(String header) {
        return header == null
                ? ""
                : header.trim().toUpperCase(Locale.US).replaceAll("[^0-9A-F]", "");
    }

    private static String continuationPayload(String hex) {
        if (hex == null || hex.length() < 4) {
            return "";
        }
        int pciOffset = isLikelyExtendedCanFrame(hex) ? 8 : isLikelyStandardCanFrame(hex) ? 3 : 0;
        if (hex.length() < pciOffset + 2) {
            return "";
        }
        try {
            int firstDataByte = Integer.parseInt(hex.substring(pciOffset, pciOffset + 2), 16);
            if ((firstDataByte & 0xF0) != 0x20) {
                return "";
            }
        } catch (NumberFormatException ignored) {
            return "";
        }
        if (hex.length() >= 10 && hex.startsWith("18")) {
            return hex.substring(10);
        }
        if (hex.length() >= 5 && hex.startsWith("7")) {
            return hex.substring(5);
        }
        if (hex.length() >= 2) {
            try {
                int firstByte = Integer.parseInt(hex.substring(0, 2), 16);
                if (firstByte >= 0x21 && firstByte <= 0x2F) {
                    return hex.substring(2);
                }
            } catch (NumberFormatException ignored) {
                return "";
            }
        }
        return "";
    }

    private static boolean isLikelyExtendedCanFrame(String hex) {
        return hex.length() >= 10 && hex.startsWith("18");
    }

    private static boolean isLikelyStandardCanFrame(String hex) {
        return hex.length() >= 5 && hex.startsWith("7");
    }

    private static void parseDiagnosticPayload(
            String payload,
            String status,
            String statusLabel,
            String moduleKey,
            String moduleName,
            String header,
            String rawResponse,
            Set<String> seen,
            List<DiagnosticTroubleCode> output) {
        if (payload == null) {
            return;
        }
        int limit = payload.length() - (payload.length() % 4);
        for (int i = 0; i + 3 < limit; i += 4) {
            int first;
            int second;
            try {
                first = Integer.parseInt(payload.substring(i, i + 2), 16);
                second = Integer.parseInt(payload.substring(i + 2, i + 4), 16);
            } catch (NumberFormatException ex) {
                return;
            }
            if (first == 0 && second == 0) {
                continue;
            }
            String code = decodeDtc(first, second);
            String key = moduleKey + "|" + status + "|" + code;
            if (seen.add(key)) {
                output.add(
                        new DiagnosticTroubleCode(
                                code,
                                status,
                                statusLabel,
                                moduleKey,
                                moduleName,
                                header,
                                rawResponse));
            }
        }
    }

    private static String decodeDtc(int first, int second) {
        char family = "PCBU".charAt((first >> 6) & 0x03);
        int digit1 = (first >> 4) & 0x03;
        int digit2 = first & 0x0F;
        int digit3 = (second >> 4) & 0x0F;
        int digit4 = second & 0x0F;
        return String.format(Locale.US, "%c%d%X%X%X", family, digit1, digit2, digit3, digit4);
    }

    // First data byte after the mode-22 positive-response marker, scaled: byte * scale + offset.
    private static Double voltByteValue(
            String response, String command, double scale, double offset) {
        int[] payload = mode22Payload(response, command);
        if (payload == null || payload.length < 1) {
            return null;
        }
        return payload[0] * scale + offset;
    }

    // First 16-bit word after the mode-22 marker, divided by divisor. When signed is true the
    // word is read as two's-complement so discharge/charge keep their sign.
    private static Double voltWordValue(
            String response, String command, double divisor, boolean signed) {
        int[] payload = mode22Payload(response, command);
        if (payload == null || payload.length < 2) {
            return null;
        }
        int word = payload[0] * 256 + payload[1];
        if (signed && word > 0x7FFF) {
            word -= 0x10000;
        }
        return word / divisor;
    }

    private static int[] mode22Payload(String response, String command) {
        if (response == null || command == null) {
            return null;
        }
        String cleanCommand = command.trim().toUpperCase(Locale.US).replaceAll("[^0-9A-F]", "");
        if (!cleanCommand.startsWith("22") || cleanCommand.length() < 6) {
            return null;
        }
        String body = cleanCommand.substring(2);
        String marker = "62" + body;
        String hex = response.toUpperCase(Locale.US).replaceAll("[^0-9A-F]", "");
        int index = hex.indexOf(marker);
        if (index < 0 && body.length() > 4) {
            marker = "62" + body.substring(0, 4);
            index = hex.indexOf(marker);
        }
        if (index < 0) {
            return null;
        }
        int dataStart = index + marker.length();
        int availableChars = hex.length() - dataStart;
        if (availableChars < 2) {
            return new int[0];
        }
        int count = availableChars / 2;
        int[] bytes = new int[count];
        for (int i = 0; i < count; i++) {
            int offset = dataStart + i * 2;
            bytes[i] = Integer.parseInt(hex.substring(offset, offset + 2), 16);
        }
        return bytes;
    }

    private static ParsedPidValue value(String name, Double value, String unit, int decimals) {
        if (value == null) {
            return null;
        }
        return new ParsedPidValue(name, format(value, decimals), value, unit);
    }

    private static String format(double value, int decimals) {
        return String.format(Locale.US, "%." + decimals + "f", value);
    }
}
