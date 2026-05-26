package com.volttracker.obdpoc;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One-line summary of a recorded session — owned by Bucket 3's {@code SessionSummaryStore},
 * appended to {@code files/obd-logs/sessions-summary.jsonl} on session end, and read by Bucket 4b
 * via {@link VoltBridge#getRecentSessions(int)} to render the "last connected" badge and the
 * adapter-health pill.
 *
 * <p>The on-disk JSON shape is exactly {@link #toJson()} — field renames are a wire break for the
 * dashboard. Add new fields with sensible defaults so old summary lines still parse.
 */
public final class SessionSummary {

    /** Outcome strings written to the rollup file. */
    public static final String OUTCOME_SUCCESS = "success";

    public static final String OUTCOME_FAILED = "failed";
    public static final String OUTCOME_ABORTED = "aborted";

    public final long startMs;
    public final long endMs;
    public final String outcome;
    public final int sampleCount;
    public final String adapter;
    public final String adapterAddress;

    /** Nullable. Non-null only when {@link #outcome} is {@link #OUTCOME_FAILED}. */
    public final String failureClass;

    public SessionSummary(
            long startMs,
            long endMs,
            String outcome,
            int sampleCount,
            String adapter,
            String adapterAddress,
            String failureClass) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.outcome = outcome == null ? "" : outcome;
        this.sampleCount = sampleCount;
        this.adapter = adapter == null ? "" : adapter;
        this.adapterAddress = adapterAddress == null ? "" : adapterAddress;
        this.failureClass = failureClass;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("startMs", startMs);
            o.put("endMs", endMs);
            o.put("outcome", outcome);
            o.put("sampleCount", sampleCount);
            o.put("adapter", adapter);
            o.put("adapterAddress", adapterAddress);
            if (failureClass != null) {
                o.put("failureClass", failureClass);
            }
        } catch (JSONException ignored) {
            // Local values are safe.
        }
        return o;
    }

    public static SessionSummary fromJson(JSONObject o) {
        if (o == null) {
            return null;
        }
        String fc = o.has("failureClass") ? o.optString("failureClass", null) : null;
        return new SessionSummary(
                o.optLong("startMs"),
                o.optLong("endMs"),
                o.optString("outcome"),
                o.optInt("sampleCount"),
                o.optString("adapter"),
                o.optString("adapterAddress"),
                fc);
    }
}
