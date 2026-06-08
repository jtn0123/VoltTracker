package com.volttracker.obdpoc

import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal class VoltBridgeDiagnostics(
    private val activity: DashboardHost,
) {
    fun clearVehicleDtcCodes() {
        val device = activity.requireDeviceCatalog().getLastOrCandidateDevice()
        val address = VoltBridge.safe(device.optString("address", ""), VoltBridge.MAX_ADDRESS_LEN)
        val name = VoltBridge.safe(device.optString("name", ""), VoltBridge.MAX_NAME_LEN)
        activity.runOnUiThread {
            if (!VoltBridge.validBluetoothAddress(address)) {
                activity.publishStatus("blocked", "No remembered adapter yet. Connect once to save it.", true)
                return@runOnUiThread
            }
            activity.rememberDevice(address, name)
            activity.startObdService(ObdService.ACTION_CLEAR_DTC, address, name)
        }
    }

    fun openExternalSearch(dtc: String?) {
        val code = VoltBridge.safe(dtc, VoltBridge.MAX_DTC_LEN)
        if (code.isEmpty()) return
        activity.runOnUiThread {
            try {
                val query = URLEncoder.encode(code + " Chevy Volt DTC", StandardCharsets.UTF_8.name())
                val uri = "https://www.google.com/search?q=$query".toUri()
                val intent = Intent(Intent.ACTION_VIEW, uri)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
            } catch (ex: Exception) {
                Log.w(MainActivity.TAG, "openExternalSearch failed", ex)
                activity.publishStatus("blocked", "Could not open the browser for DTC lookup.", true)
            }
        }
    }

    fun forceStopPackage(packageName: String?): Boolean {
        val pkg = VoltBridge.safe(packageName, VoltBridge.MAX_NAME_LEN)
        if (pkg.isEmpty()) {
            return false
        }
        return activity.forceStopPackageFromBridge(pkg)
    }

    fun getRecentSessions(n: Int): String = activity.getRecentSessionsJson(n)

    fun shareDiagnostics() {
        activity.runOnUiThread(activity::shareDiagnosticsFromBridge)
    }

    fun startTestConnection() {
        activity.runOnUiThread(activity::startTestConnectionFromBridge)
    }

    fun scheduleAdapterReadyNotify(mins: Int) {
        val clamped = maxOf(1, minOf(30, mins))
        activity.runOnUiThread {
            activity.scheduleAdapterReadyNotifyFromBridge(clamped)
        }
    }

    fun cancelAdapterReadyNotify() {
        activity.runOnUiThread(activity::cancelAdapterReadyNotifyFromBridge)
    }
}
