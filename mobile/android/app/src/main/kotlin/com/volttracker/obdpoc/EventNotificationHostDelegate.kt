package com.volttracker.obdpoc

/**
 * Backs the [EventNotificationCommands] host seam for [MainActivity]: the toggle entry points
 * forward here so their bodies live off the Activity. It only reads/writes the native-owned
 * [EventNotificationPrefs] and republishes app state; the notifications themselves are posted
 * service-side from the shared prefs file.
 *
 * Collaborators are lambdas because the prefs are not constructed until the Activity's `onCreate`;
 * until then [prefs] returns null and a flip reports the not-ready status, mirroring how the
 * auto-connect seam degrades before its controller exists.
 */
class EventNotificationHostDelegate(
    private val prefs: () -> EventNotificationPrefs?,
    private val publishStatus: (String, String, Boolean) -> Unit,
    private val publishAppState: () -> Unit,
    private val notReadyMessage: () -> String,
    private val hasNotificationPermission: () -> Boolean = { true },
    private val ensureNotificationPermission: () -> Unit = {},
) : EventNotificationCommands {
    override fun getEventNotificationStateJson(): String =
        prefs()?.let {
            org.json
                .JSONObject(it.stateJson())
                .put("notificationsGranted", hasNotificationPermission())
                .toString()
        } ?: MainActivityUtils
            .errorPayload("event_notifications_unavailable", "Notification settings are not ready.")
            .toString()

    override fun setChargeCompleteEnabled(enabled: Boolean) = update(enabled) { it.setChargeCompleteEnabled(enabled) }

    override fun setNewDtcEnabled(enabled: Boolean) = update(enabled) { it.setNewDtcEnabled(enabled) }

    override fun setLowSocEnabled(
        enabled: Boolean,
        thresholdPct: Double,
    ) = update(enabled) {
        it.setLowSocEnabled(enabled)
        it.setLowSocThresholdPct(thresholdPct)
    }

    override fun setHighPackTempEnabled(
        enabled: Boolean,
        thresholdC: Double,
    ) = update(enabled) {
        it.setHighPackTempEnabled(enabled)
        it.setHighPackTempThresholdC(thresholdC)
    }

    override fun setChargeTargetSoc(targetPct: Double) = update(false) { it.setTargetSocPct(targetPct) }

    override fun setAutoScanOnConnectEnabled(enabled: Boolean) =
        update(false) { it.setAutoScanOnConnectEnabled(enabled) }

    override fun setMaintenanceDueEnabled(enabled: Boolean) = update(enabled) { it.setMaintenanceDueEnabled(enabled) }

    private inline fun update(
        requestsNotification: Boolean,
        mutate: (EventNotificationPrefs) -> Unit,
    ) {
        val current = prefs()
        if (current == null) {
            publishStatus("blocked", notReadyMessage(), true)
            return
        }
        mutate(current)
        if (requestsNotification && !hasNotificationPermission()) {
            ensureNotificationPermission()
        }
        publishAppState()
    }
}
