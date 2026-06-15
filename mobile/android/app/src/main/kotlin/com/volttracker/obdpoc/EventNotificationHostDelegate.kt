package com.volttracker.obdpoc

/**
 * Backs the [EventNotificationCommands] host seam for [MainActivity]: the six toggle entry points
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
) : EventNotificationCommands {
    override fun getEventNotificationStateJson(): String =
        prefs()?.stateJson()
            ?: MainActivityUtils
                .errorPayload("event_notifications_unavailable", "Notification settings are not ready.")
                .toString()

    override fun setChargeCompleteNotifyFromBridge(enabled: Boolean) = update { it.setChargeCompleteEnabled(enabled) }

    override fun setNewDtcNotifyFromBridge(enabled: Boolean) = update { it.setNewDtcEnabled(enabled) }

    override fun setLowSocNotifyFromBridge(
        enabled: Boolean,
        thresholdPct: Double,
    ) = update {
        it.setLowSocEnabled(enabled)
        it.setLowSocThresholdPct(thresholdPct)
    }

    override fun setHighPackTempNotifyFromBridge(
        enabled: Boolean,
        thresholdC: Double,
    ) = update {
        it.setHighPackTempEnabled(enabled)
        it.setHighPackTempThresholdC(thresholdC)
    }

    override fun setAutoScanOnConnectFromBridge(enabled: Boolean) = update { it.setAutoScanOnConnectEnabled(enabled) }

    private inline fun update(mutate: (EventNotificationPrefs) -> Unit) {
        val current = prefs()
        if (current == null) {
            publishStatus("blocked", notReadyMessage(), true)
            return
        }
        mutate(current)
        publishAppState()
    }
}
