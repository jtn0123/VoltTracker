package com.volttracker.obdpoc;

import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Pure-Java helpers extracted from {@link MainActivity} so the Activity stays small and the helpers
 * can be unit-tested without any Android-framework stubs.
 */
final class MainActivityUtils {

    private MainActivityUtils() {}

    /** Returns a parsed object, or an empty one for {@code null}, blank, or malformed input. */
    static JSONObject parseJson(String json) {
        try {
            return json == null || json.trim().isEmpty() ? new JSONObject() : new JSONObject(json);
        } catch (JSONException ex) {
            return new JSONObject();
        }
    }

    /** Returns the dashboard-facing shape for native read failures. */
    static JSONObject errorPayload(String error, String message) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("ok", false);
            payload.put("error", error == null ? "unknown_error" : error);
            payload.put("message", message == null ? "" : message);
        } catch (JSONException ignored) {
            // JSONObject with static keys should not fail; keep the helper total anyway.
        }
        return payload;
    }

    /** True if {@code state} names a session phase where the OBD link is being driven. */
    static boolean isConnectedState(String state) {
        String clean = state == null ? "" : state.toLowerCase(Locale.US);
        return clean.equals("connected")
                || clean.equals("connecting")
                || clean.equals("initializing")
                || clean.equals("scanning")
                || clean.equals("scan-complete")
                || clean.equals("demo");
    }

    /** Returns the first non-blank argument, or {@code ""} if every argument is blank/null. */
    static String coalesce(String first, String second, String third) {
        if (first != null && !first.trim().isEmpty()) {
            return first;
        }
        if (second != null && !second.trim().isEmpty()) {
            return second;
        }
        if (third != null && !third.trim().isEmpty()) {
            return third;
        }
        return "";
    }

    /** Returns the last 5 chars of a MAC-like address prefixed with "...", or "" for shorts. */
    static String redactAddress(String address) {
        if (address == null || address.length() < 5) {
            return "";
        }
        return "..." + address.substring(address.length() - 5);
    }
}
