package com.volttracker.obdpoc;

import static com.volttracker.obdpoc.ObdElmDecode.round1;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Emits the synthetic demo telemetry stream that runs in place of a real OBD adapter loop (e.g.
 * when the user picks "Demo telemetry" in the dashboard). Pulled out of {@link ObdPollingEngine} so
 * the live-poll path and the demo path can be read independently and each file stays under the
 * team's 500-LOC ceiling.
 *
 * <p>Owns no IO state of its own: the per-session counters and session-health JSON still live on
 * {@link ObdPollingEngine}, which exposes the small surface this class needs ({@link
 * ObdPollingEngine#incrementSampleCount()}, {@link ObdPollingEngine#supportedPidsSummary()}, and
 * {@link ObdPollingEngine#appendSessionHealth(JSONObject)}). Runs on the service IO thread, just
 * like the live loop.
 */
final class DemoPollingLoop {

    private final ObdService service;
    private final ObdPollingEngine engine;

    DemoPollingLoop(ObdService service, ObdPollingEngine engine) {
        this.service = service;
        this.engine = engine;
    }

    void run() {
        service.broadcastStatus(
                "connected", "Demo telemetry is running without an OBD adapter.", false);
        long start = System.currentTimeMillis();
        while (service.running.get()) {
            double t = (System.currentTimeMillis() - start) / 1000.0;
            JSONObject sample = new JSONObject();
            try {
                int sampleNumber = engine.incrementSampleCount();
                sample.put("source", "demo");
                sample.put("connected", true);
                sample.put("adapter", service.activeName);
                sample.put("sampleCount", sampleNumber);
                sample.put(
                        "sessionMs",
                        Math.max(0, System.currentTimeMillis() - service.sessionStartedAtMs));
                sample.put("supportedPids", engine.supportedPidsSummary());
                sample.put("vehicleState", "demo-preview");
                sample.put("speedKph", Math.max(0, Math.round(54 + 23 * Math.sin(t / 3.4))));
                sample.put("rpm", Math.round(1260 + 420 * Math.sin(t / 2.1)));
                sample.put("coolantC", Math.round(82 + 4 * Math.sin(t / 8.0)));
                sample.put("loadPct", Math.round(34 + 18 * Math.sin(t / 4.4)));
                sample.put("throttlePct", Math.round(18 + 14 * Math.sin(t / 2.7)));
                sample.put("voltage", round1(13.8 + 0.2 * Math.sin(t / 5.0)));
                sample.put("soc", Math.max(13.4, round1(77.8 - t * 0.01)));
                sample.put("batteryTemp", round1(24.0 + Math.sin(t / 8.0)));
                sample.put("powerKw", round1(16.0 + Math.sin(t / 2.2) * 12.0));
                sample.put("updatedAt", System.currentTimeMillis());
                engine.appendSessionHealth(sample);
                sample.put("raw", "demo");
            } catch (JSONException ignored) {
                // Local numeric values are safe.
            }
            service.broadcastTelemetry(sample);
            sleep(1000);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
