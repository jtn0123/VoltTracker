package com.volttracker.obdpoc

import com.volttracker.obdpoc.data.ObdLocalStore
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.LinkedHashMap

/**
 * Runs small read-only enhanced discovery passes.
 */
class TpmsDiscoveryRunner(
    private val service: EngineHost,
    private val engine: ObdPollingEngine,
) {
    @Throws(IOException::class)
    fun run(
        adapterAddress: String,
        requestedStage: String?,
    ) {
        val stage = EnhancedPidProfiles.normalizeStage(requestedStage)
        service.broadcastStatus(
            "scanning",
            "Running ${stageLabel(stage)} Detail Probe. No DTC or freeze-frame reads.",
            false,
        )
        service.updateNotification("Detail Probe (${stageLabel(stage)}) on ${service.activeName}")

        val raw = StringBuilder()
        ObdElmDecode.appendProbeLine(raw, "adapter", service.activeName)
        ObdElmDecode.appendProbeLine(raw, "stage", stage)
        probeCommand("ATI", 1800, raw)
        probeCommand("ATDP", 1800, raw)
        probeCommand("ATDPN", 1800, raw)
        probeCommand("ATRV", 1800, raw)

        runProfileGroup(
            "$stage-discovery",
            EnhancedPidProfiles.forStage(stage),
            adapterAddress,
            raw,
        )
        appendPassiveTargets(raw)
        probeCommand("ATSH7DF", 1800, raw)

        val sample = JSONObject()
        try {
            sample.put("source", "detail-probe")
            sample.put("scanStage", stage)
            sample.put("connected", true)
            sample.put("adapter", service.activeName)
            sample.put("updatedAt", System.currentTimeMillis())
            sample.put("raw", ObdElmDecode.tail(raw.toString(), 7200))
        } catch (ignored: JSONException) {
            // Local values are safe.
        }
        service.broadcastTelemetry(sample)
        service.broadcastStatus(
            "scan-complete",
            "Detail Probe (${stageLabel(stage)}) complete. Bring the phone back for the log.",
            false,
        )
        service.updateNotification("Detail Probe (${stageLabel(stage)}) complete for ${service.activeName}")
    }

    @Throws(IOException::class)
    private fun probeCommand(
        command: String,
        timeoutMs: Long,
        raw: StringBuilder,
    ): String {
        val response = engine.sendRecoverableCommand(command, timeoutMs)
        ObdElmDecode.appendProbeLine(raw, command, ObdElmDecode.summarizeForStorage(command, response))
        return response
    }

    @Throws(IOException::class)
    private fun runProfileGroup(
        label: String,
        profiles: List<EnhancedPidProfile>,
        adapterAddress: String,
        raw: StringBuilder,
    ) {
        val plannedByHeader = plannedExecutableCounts(profiles, adapterAddress)
        for ((header, count) in plannedByHeader) {
            if (count <= 0) {
                continue
            }
            ObdElmDecode.appendProbeLine(
                raw,
                label,
                (if (header.isEmpty()) "standard-header" else header) + " profile-driven probes",
            )
            if (header.isNotEmpty()) {
                probeCommand(header, 1800, raw)
            }
            for (profile in profiles) {
                if (header != profile.header || isPassive(profile)) {
                    continue
                }
                if (shouldSkip(profile, adapterAddress)) {
                    ObdElmDecode.appendProbeLine(
                        raw,
                        profile.command,
                        "skipped cached unsupported; profile=${profile.key}",
                    )
                    continue
                }
                probeCommand(profile.command, 4200, raw)
            }
        }
    }

    private fun plannedExecutableCounts(
        profiles: List<EnhancedPidProfile>,
        adapterAddress: String,
    ): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        for (profile in profiles) {
            if (isPassive(profile) || shouldSkip(profile, adapterAddress)) {
                continue
            }
            val current = counts[profile.header]
            counts[profile.header] = (current ?: 0) + 1
        }
        return counts
    }

    private fun appendPassiveTargets(raw: StringBuilder) {
        for (profile in EnhancedPidProfiles.passiveProfiles()) {
            ObdElmDecode.appendProbeLine(
                raw,
                "passive-target",
                "${profile.key} ${profile.header} deferred; requires a dedicated short CAN monitor path",
            )
        }
    }

    private fun shouldSkip(
        profile: EnhancedPidProfile,
        adapterAddress: String,
    ): Boolean {
        if (EnhancedPidProfiles.STATUS_REJECTED == profile.validationStatus) {
            return true
        }
        if (EnhancedPidProfiles.STATUS_CONFIRMED == profile.validationStatus) {
            return false
        }
        val store: ObdLocalStore = service.localStore ?: return false
        return try {
            store.hasRecentEnhancedCapability(adapterAddress, profile.header, profile.command, profile.retryAfterMs)
        } catch (ex: RuntimeException) {
            service.recorder.logError("enhanced_capability_cache_read_failed", ex)
            false
        }
    }

    companion object {
        private fun isPassive(profile: EnhancedPidProfile): Boolean = profile.pollLane == "passive"

        private fun stageLabel(stage: String): String =
            when (stage) {
                EnhancedPidProfiles.STAGE_PASSIVE -> "passive"
                EnhancedPidProfiles.STAGE_LOW_RISK -> "low-risk"
                EnhancedPidProfiles.STAGE_EXPERIMENTAL -> "experimental"
                else -> "tires"
            }
    }
}
