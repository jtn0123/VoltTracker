package com.volttracker.obdpoc

import org.json.JSONObject

/**
 * Service-side glue for the event-notification (M1) and auto-DTC-scan features. It owns the
 * per-session [EventNotificationDecider], reads the live telemetry / scan payloads the service is
 * already broadcasting, and posts the resulting notifications via [EventNotifier]. It also drives the
 * gated on-connect auto-scan ([AutoScanController] + [AutoDtcScanRunner]).
 *
 * Lives in the service layer (no WebView), so the architecture boundary holds. A fresh
 * [EventNotificationDecider] is built per session so charge accumulation / threshold arming start
 * clean each drive; the DTC baseline persists in [EventNotificationPrefs] across sessions.
 */
class EventNotificationCoordinator(
    private val prefs: EventNotificationPrefs,
    private val notifier: EventNotifier,
    private val autoScan: AutoScanController,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    // Reassigned by onSessionStart() on the MAIN thread (service startSession) while a draining task
    // from the previous session may still read it via onTelemetry/applyScanCodes on the executor
    // thread — so the reference must be @Volatile. The decider's internal accumulation/arming fields
    // stay executor-thread-only, so volatile on the reference is sufficient. (Report item B1.)
    @Volatile
    private var decider: EventNotificationDecider = newDecider()

    /** Resets per-session state. Call when a new logging session starts. */
    fun onSessionStart() {
        decider = newDecider()
        autoScan.resetForConnect()
    }

    /**
     * Feeds one broadcast telemetry payload. Live OBD samples drive the charge/threshold events; a
     * `source=="scan"` payload with a `dtcCodes` array drives the new-DTC diff. Returns the events it
     * posted, for tests/diagnostics.
     */
    fun onTelemetry(payload: JSONObject?): List<EventNotificationDecider.Event> {
        if (payload == null) {
            return emptyList()
        }
        if (payload.optString("source") == "scan" && payload.has("dtcCodes")) {
            return handleScanPayload(payload)
        }
        val sample = toSample(payload) ?: return emptyList()
        // Re-read prefs each evaluation so a toggle/threshold change mid-session takes effect on the
        // next sample, rather than being frozen at the decider's session-start snapshot. The scan path
        // refreshes separately in applyScanCodes, so it is handled before reaching here.
        decider.updateSettings(currentSettings())
        val events = decider.onSample(sample)
        for (event in events) {
            notifier.notify(event)
        }
        return events
    }

    /**
     * Runs the opt-in on-connect auto-scan when [AutoScanController] allows it: a generic Mode 03
     * read whose newly-seen codes feed the same new-DTC notification path. No-op when disabled or
     * throttled. Runs on the service IO thread (the engine's connect path).
     */
    fun maybeRunAutoDtcScan(engine: ObdPollingEngine?) {
        engine ?: return
        autoScan.onConnected {
            val scan = AutoDtcScanRunner(engine).readGenericDtcScan()
            if (scan.completed) {
                applyScanCodes(scan.codes)
            }
            scan.completed
        }
    }

    private fun handleScanPayload(payload: JSONObject): List<EventNotificationDecider.Event> {
        if (payload.has("dtcScanValid") && !payload.optBoolean("dtcScanValid", false)) {
            return emptyList()
        }
        val arr = payload.optJSONArray("dtcCodes") ?: return emptyList()
        // Pass raw strings through; Decider.normalizeCodes does the canonical trim + drop-empties.
        val codes = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            codes.add(arr.optString(i))
        }
        val event = applyScanCodes(codes)
        return if (event != null) listOf(event) else emptyList()
    }

    /**
     * Diffs [codes] against the persisted baseline, posts a new-DTC notification when warranted, and
     * persists the new baseline regardless. Returns the posted event (or null).
     */
    private fun applyScanCodes(codes: Collection<String>): EventNotificationDecider.Event.NewDtc? {
        // Auto-scan reaches the decider off the telemetry path (the connect thread), so refresh the
        // live newDtcEnabled toggle here too.
        decider.updateSettings(currentSettings())
        // Read the baseline flag BEFORE persisting (setLastScanDtcCodes establishes it): the first
        // scan ever silently establishes the baseline without a "new DTC" false alert.
        val result = decider.onScan(codes, prefs.lastScanDtcCodes(), prefs.hasDtcBaseline())
        prefs.setLastScanDtcCodes(result.allCodes)
        val event = result.event
        if (event != null) {
            notifier.notify(event)
        }
        return event
    }

    private fun toSample(payload: JSONObject): EventNotificationDecider.Sample? {
        if (payload.optString("source") != "obd") {
            return null
        }
        val capturedAt =
            payload.optLong("updatedAt", 0L).let { if (it > 0L) it else nowMs() }
        return EventNotificationDecider.Sample(
            capturedAtMs = capturedAt,
            packCurrentA = optDoubleOrNull(payload, "packCurrentA"),
            packVoltage = optDoubleOrNull(payload, "packVoltage"),
            speedKph = optDoubleOrNull(payload, "speedKph"),
            socPct = optDoubleOrNull(payload, "soc"),
            packTempC = optDoubleOrNull(payload, "batteryTemp"),
        )
    }

    private fun newDecider(): EventNotificationDecider = EventNotificationDecider(currentSettings())

    // Cached settings snapshot + the prefs version it was built from. Rebuilt only when the version
    // moves (a user toggle), so the steady-state hot path is one cheap volatile/getLong compare per
    // sample instead of 6 prefs getters + a Settings allocation at ~1 Hz. A mid-session toggle bumps
    // the version, so it still takes effect on the next sample. (Report item G2.)
    @Volatile
    private var cachedSettings: EventNotificationDecider.Settings? = null

    @Volatile
    private var cachedSettingsVersion: Long = Long.MIN_VALUE

    /**
     * Returns the live notification toggles/thresholds, rebuilding the snapshot from prefs only when
     * the settings version changed since the last call (cheap version read+compare otherwise).
     */
    private fun currentSettings(): EventNotificationDecider.Settings {
        val version = prefs.settingsVersion()
        val cached = cachedSettings
        if (cached != null && version == cachedSettingsVersion) {
            return cached
        }
        val rebuilt =
            EventNotificationDecider.Settings(
                chargeCompleteEnabled = prefs.chargeCompleteEnabled(),
                newDtcEnabled = prefs.newDtcEnabled(),
                lowSocEnabled = prefs.lowSocEnabled(),
                lowSocThresholdPct = prefs.lowSocThresholdPct(),
                highPackTempEnabled = prefs.highPackTempEnabled(),
                highPackTempThresholdC = prefs.highPackTempThresholdC(),
                targetSocPct = prefs.targetSocPct(),
            )
        cachedSettings = rebuilt
        cachedSettingsVersion = version
        return rebuilt
    }

    private companion object {
        fun optDoubleOrNull(
            payload: JSONObject,
            key: String,
        ): Double? {
            if (!payload.has(key) || payload.isNull(key)) {
                return null
            }
            val value = payload.optDouble(key, Double.NaN)
            return value.takeIf { it.isFinite() }
        }
    }
}
