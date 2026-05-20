package com.volttracker.obdpoc;

import java.util.Locale;

final class ObdProtocol {
    private ObdProtocol() {
    }

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

    static Float parseVoltage(String response) {
        if (response == null) {
            return null;
        }
        String cleaned = response.replace(">", "")
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
        return response.replace("\r", " ")
                .replace("\n", " ")
                .replace(">", "")
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
            return coolant == null ? null : value("coolant temperature", coolant.doubleValue(), "deg C", 0);
        }
        if ("0104".equals(cleanCommand)) {
            Integer load = parseEngineLoadPct(response);
            return load == null ? null : value("engine load", load.doubleValue(), "%", 0);
        }
        if ("0111".equals(cleanCommand)) {
            Integer throttle = parseThrottlePct(response);
            return throttle == null ? null : value("throttle position", throttle.doubleValue(), "%", 0);
        }
        if ("2243AF1".equals(cleanCommand)) {
            Double soc = parseVoltRawSoc(response, cleanCommand);
            return soc == null ? null : value("raw hv soc", soc, "%", 1);
        }
        if ("228334".equals(cleanCommand)) {
            Double soc = parseVoltDisplayedSoc(response, cleanCommand);
            return soc == null ? null : value("displayed soc", soc, "%", 1);
        }
        if ("2241A31".equals(cleanCommand)) {
            Double capacity = parseVoltCapacityAh(response, cleanCommand);
            return capacity == null ? null : value("battery capacity", capacity, "Ah", 1);
        }
        if ("2234B2".equals(cleanCommand)) {
            Double odometer = parseVoltOdometer(response, cleanCommand);
            return odometer == null ? null : value("odometer", odometer, "mi", 0);
        }
        if (isVoltCellVoltageCommand(cleanCommand)) {
            Double cellVoltage = parseVoltCellVoltage(response, cleanCommand);
            return cellVoltage == null ? null : value("cell voltage", cellVoltage, "V", 4);
        }
        return null;
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

    private static Double parseVoltRawSoc(String response, String command) {
        int[] payload = mode22Payload(response, command);
        Integer raw = lastWord(payload);
        return raw == null ? null : raw * 100.0 / 65535.0;
    }

    private static Double parseVoltDisplayedSoc(String response, String command) {
        int[] payload = mode22Payload(response, command);
        Integer raw = lastByte(payload);
        return raw == null ? null : raw * 100.0 / 255.0;
    }

    private static Double parseVoltCapacityAh(String response, String command) {
        int[] payload = mode22Payload(response, command);
        Integer raw = lastWord(payload);
        return raw == null ? null : raw / 10.0;
    }

    private static Double parseVoltOdometer(String response, String command) {
        int[] payload = mode22Payload(response, command);
        Long raw = lastDword(payload);
        return raw == null ? null : raw / 64.0;
    }

    private static Double parseVoltCellVoltage(String response, String command) {
        int[] payload = mode22Payload(response, command);
        Integer raw = lastWord(payload);
        return raw == null ? null : raw * 5.0 / 65535.0;
    }

    private static boolean isVoltCellVoltageCommand(String command) {
        if (command == null || !command.startsWith("224")) {
            return false;
        }
        String body = command.substring(2);
        String pid = body.length() >= 4 ? body.substring(0, 4) : body;
        try {
            int value = Integer.parseInt(pid, 16);
            return value >= 0x4181 && value <= 0x4240;
        } catch (NumberFormatException ex) {
            return false;
        }
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

    private static Integer lastByte(int[] bytes) {
        if (bytes == null || bytes.length < 1) {
            return null;
        }
        return bytes[bytes.length - 1];
    }

    private static Integer lastWord(int[] bytes) {
        if (bytes == null || bytes.length < 2) {
            return null;
        }
        return bytes[bytes.length - 2] * 256 + bytes[bytes.length - 1];
    }

    private static Long lastDword(int[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return null;
        }
        int offset = bytes.length - 4;
        return ((long) bytes[offset] << 24)
                + ((long) bytes[offset + 1] << 16)
                + ((long) bytes[offset + 2] << 8)
                + bytes[offset + 3];
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
