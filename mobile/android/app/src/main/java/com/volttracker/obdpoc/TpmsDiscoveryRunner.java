package com.volttracker.obdpoc;

import static com.volttracker.obdpoc.ObdElmDecode.appendProbeLine;
import static com.volttracker.obdpoc.ObdElmDecode.summarizeForStorage;
import static com.volttracker.obdpoc.ObdElmDecode.tail;

import com.volttracker.obdpoc.data.ObdLocalStore;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Runs small read-only enhanced discovery passes. This deliberately avoids the full diagnostic
 * scan's protocol sweep, DTC reads, and freeze-frame requests so field tests can probe candidates
 * without waking unrelated modules more than necessary.
 */
final class TpmsDiscoveryRunner {

    private final ObdService service;
    private final ObdPollingEngine engine;

    TpmsDiscoveryRunner(ObdService service, ObdPollingEngine engine) {
        this.service = service;
        this.engine = engine;
    }

    void run(String adapterAddress, String requestedStage) throws IOException {
        String stage = EnhancedPidProfiles.normalizeStage(requestedStage);
        service.broadcastStatus(
                "scanning",
                "Running " + stageLabel(stage) + " Detail Probe. No DTC or freeze-frame reads.",
                false);
        service.updateNotification(
                "Detail Probe (" + stageLabel(stage) + ") on " + service.activeName);

        StringBuilder raw = new StringBuilder();
        appendProbeLine(raw, "adapter", service.activeName);
        appendProbeLine(raw, "stage", stage);
        probeCommand("ATI", 1800, raw);
        probeCommand("ATDP", 1800, raw);
        probeCommand("ATDPN", 1800, raw);
        probeCommand("ATRV", 1800, raw);

        runProfileGroup(
                stage + "-discovery", EnhancedPidProfiles.forStage(stage), adapterAddress, raw);
        appendPassiveTargets(raw);
        probeCommand("ATSH7DF", 1800, raw);

        JSONObject sample = new JSONObject();
        try {
            sample.put("source", "detail-probe");
            sample.put("scanStage", stage);
            sample.put("connected", true);
            sample.put("adapter", service.activeName);
            sample.put("updatedAt", System.currentTimeMillis());
            sample.put("raw", tail(raw.toString(), 7200));
        } catch (JSONException ignored) {
            // Local values are safe.
        }
        service.broadcastTelemetry(sample);
        service.broadcastStatus(
                "scan-complete",
                "Detail Probe ("
                        + stageLabel(stage)
                        + ") complete. Bring the phone back for the log.",
                false);
        service.updateNotification(
                "Detail Probe (" + stageLabel(stage) + ") complete for " + service.activeName);
    }

    private String probeCommand(String command, long timeoutMs, StringBuilder raw)
            throws IOException {
        String response = engine.sendRecoverableCommand(command, timeoutMs);
        appendProbeLine(raw, command, summarizeForStorage(command, response));
        return response;
    }

    private void runProfileGroup(
            String label,
            List<EnhancedPidProfile> profiles,
            String adapterAddress,
            StringBuilder raw)
            throws IOException {
        Map<String, Integer> plannedByHeader = plannedExecutableCounts(profiles, adapterAddress);
        for (Map.Entry<String, Integer> entry : plannedByHeader.entrySet()) {
            if (entry.getValue() <= 0) {
                continue;
            }
            String header = entry.getKey();
            appendProbeLine(
                    raw,
                    label,
                    (header.isEmpty() ? "standard-header" : header) + " profile-driven probes");
            if (!header.isEmpty()) {
                probeCommand(header, 1800, raw);
            }
            for (EnhancedPidProfile profile : profiles) {
                if (!header.equals(profile.header) || isPassive(profile)) {
                    continue;
                }
                if (shouldSkip(profile, adapterAddress)) {
                    appendProbeLine(
                            raw,
                            profile.command,
                            "skipped cached unsupported; profile=" + profile.key);
                    continue;
                }
                probeCommand(profile.command, 4200, raw);
            }
        }
    }

    private Map<String, Integer> plannedExecutableCounts(
            List<EnhancedPidProfile> profiles, String adapterAddress) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (EnhancedPidProfile profile : profiles) {
            if (isPassive(profile) || shouldSkip(profile, adapterAddress)) {
                continue;
            }
            Integer current = counts.get(profile.header);
            counts.put(profile.header, (current == null ? 0 : current) + 1);
        }
        return counts;
    }

    private void appendPassiveTargets(StringBuilder raw) {
        for (EnhancedPidProfile profile : EnhancedPidProfiles.passiveProfiles()) {
            appendProbeLine(
                    raw,
                    "passive-target",
                    profile.key
                            + " "
                            + profile.header
                            + " deferred; requires a dedicated short CAN monitor path");
        }
    }

    private boolean shouldSkip(EnhancedPidProfile profile, String adapterAddress) {
        if (EnhancedPidProfiles.STATUS_CONFIRMED.equals(profile.validationStatus)) {
            return false;
        }
        ObdLocalStore store = service.localStore;
        if (store == null) {
            return false;
        }
        try {
            return store.hasRecentEnhancedCapability(
                    adapterAddress, profile.header, profile.command, profile.retryAfterMs);
        } catch (RuntimeException ex) {
            service.recorder.logError("enhanced_capability_cache_read_failed", ex);
            return false;
        }
    }

    private static boolean isPassive(EnhancedPidProfile profile) {
        return "passive".equals(profile.pollLane);
    }

    private static String stageLabel(String stage) {
        if (EnhancedPidProfiles.STAGE_PASSIVE.equals(stage)) return "passive";
        if (EnhancedPidProfiles.STAGE_LOW_RISK.equals(stage)) return "low-risk";
        if (EnhancedPidProfiles.STAGE_EXPERIMENTAL.equals(stage)) return "experimental";
        return "tires";
    }
}
