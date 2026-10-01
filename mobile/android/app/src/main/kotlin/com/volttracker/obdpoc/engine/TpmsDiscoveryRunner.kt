package com.volttracker.obdpoc.engine

import com.volttracker.obdpoc.EnhancedPidProfile
import com.volttracker.obdpoc.EnhancedPidProfiles
import com.volttracker.obdpoc.ObdElmDecode
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
            // Nodes outside 7E0-7E7 reply outside the adapter's automatic 7E8-7EF filter.
            val receiveFilter = RECEIVE_FILTERS[header]
            if (receiveFilter != null) {
                probeCommand(receiveFilter, 1800, raw)
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
            if (receiveFilter != null) {
                probeCommand(RESTORE_AUTO_RECEIVE, 1800, raw)
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
            store.signalLogs.hasRecentEnhancedCapability(
                adapterAddress,
                profile.header,
                profile.command,
                profile.retryAfterMs,
            )
        } catch (ex: RuntimeException) {
            service.recorder.logError("enhanced_capability_cache_read_failed", ex)
            false
        }
    }

    companion object {
        /**
         * Reply filters for headers outside 7E0-7E7: the BCM answers 0x241 on 0x641 (GM's +0x400,
         * like the 0x257 motor node); 0x751's reply ID is unknown, so accept any 7xx reply.
         */
        private val RECEIVE_FILTERS = mapOf("ATSH241" to "ATCRA641", "ATSH751" to "ATCRA7XX")
        private const val RESTORE_AUTO_RECEIVE = "ATAR"

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
