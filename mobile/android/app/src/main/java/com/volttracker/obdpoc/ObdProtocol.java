package com.volttracker.obdpoc;

import java.util.Locale;

final class ObdProtocol {
    private ObdProtocol() {
    }

    static Integer parseSpeedKph(String response) {
        int[] bytes = mode01Bytes(response, "0D", 1);
        if (bytes == null || bytes[0] == 0xFF) {
            return null;
        }
        return bytes[0];
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
}
