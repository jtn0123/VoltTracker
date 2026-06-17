package com.volttracker.obdpoc

import android.webkit.JavascriptInterface

open class VoltBridgeNotifications(
    private val activity: DashboardHost,
) {
    @JavascriptInterface
    fun getEventNotificationState(): String = activity.eventNotifications().getEventNotificationStateJson()

    @JavascriptInterface
    fun setChargeCompleteNotify(enabled: Boolean) {
        activity.runOnUiThread { activity.eventNotifications().setChargeCompleteEnabled(enabled) }
    }

    @JavascriptInterface
    fun setNewDtcNotify(enabled: Boolean) {
        activity.runOnUiThread { activity.eventNotifications().setNewDtcEnabled(enabled) }
    }

    @JavascriptInterface
    fun setLowSocNotify(
        enabled: Boolean,
        thresholdPct: Double,
    ) {
        activity.runOnUiThread { activity.eventNotifications().setLowSocEnabled(enabled, thresholdPct) }
    }

    @JavascriptInterface
    fun setHighPackTempNotify(
        enabled: Boolean,
        thresholdC: Double,
    ) {
        activity.runOnUiThread { activity.eventNotifications().setHighPackTempEnabled(enabled, thresholdC) }
    }

    @JavascriptInterface
    fun setChargeTargetSoc(targetPct: Double) {
        activity.runOnUiThread { activity.eventNotifications().setChargeTargetSoc(targetPct) }
    }

    @JavascriptInterface
    fun setAutoScanOnConnect(enabled: Boolean) {
        activity.runOnUiThread { activity.eventNotifications().setAutoScanOnConnectEnabled(enabled) }
    }

    @JavascriptInterface
    fun setMaintenanceDueNotify(enabled: Boolean) {
        activity.runOnUiThread { activity.eventNotifications().setMaintenanceDueEnabled(enabled) }
    }
}
