package com.volttracker.obdpoc

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * Battery-safe auto-connect policy for the last remembered OBD adapter.
 *
 * This never starts Bluetooth discovery. It only reacts to app foregrounding or
 * platform Bluetooth broadcasts and rate-limits actual RFCOMM connect attempts.
 */
class AutoConnectController(
    private val prefs: SharedPreferences,
    private val catalog: DeviceCatalog,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private var lastAttemptMs = 0L

    fun isEnabled(): Boolean = prefs.getBoolean(PREF_AUTO_CONNECT_ENABLED, DEFAULT_AUTO_CONNECT_ENABLED)

    fun setEnabled(enabled: Boolean) {
        prefs.edit {
            putBoolean(PREF_AUTO_CONNECT_ENABLED, enabled)
        }
    }

    fun stateJson(): String {
        val payload = JSONObject()
        try {
            val now = nowMs()
            payload.put("enabled", isEnabled())
            payload.put("lastAddress", catalog.lastAddress())
            payload.put("lastName", catalog.lastName())
            payload.put("available", VoltBridge.validBluetoothAddress(catalog.lastAddress()))
            payload.put("cooldownRemainingMs", cooldownRemainingMs(now))
        } catch (_: JSONException) {
            // Local values are safe.
        }
        return payload.toString()
    }

    fun maybeConnect(
        trigger: String,
        observedAddress: String?,
        bluetoothReady: Boolean,
        loggingActive: Boolean,
        startConnect: (String, String) -> Unit,
        publishStatus: (String, String, Boolean) -> Unit,
    ): Boolean {
        if (!isEnabled() || loggingActive || !bluetoothReady) {
            return false
        }
        val address = catalog.lastAddress().trim()
        if (!VoltBridge.validBluetoothAddress(address)) {
            return false
        }
        val observed = observedAddress?.trim().orEmpty()
        if (observed.isNotEmpty() && !address.equals(observed, ignoreCase = true)) {
            return false
        }
        val now = nowMs()
        if (cooldownRemainingMs(now) > 0L) {
            return false
        }
        lastAttemptMs = now
        val name = catalog.lastName().trim()
        publishStatus("connecting", statusDetail(trigger, name, address), false)
        startConnect(address, name)
        return true
    }

    private fun cooldownRemainingMs(now: Long): Long {
        if (lastAttemptMs <= 0L) {
            return 0L
        }
        return maxOf(0L, AUTO_CONNECT_COOLDOWN_MS - maxOf(0L, now - lastAttemptMs))
    }

    private fun statusDetail(
        trigger: String,
        name: String,
        address: String,
    ): String {
        val label = if (name.isNotEmpty()) name else MainActivityUtils.redactAddress(address)
        return when (trigger.lowercase(Locale.US)) {
            TRIGGER_BLUETOOTH_CONNECTED -> "OBD adapter detected - auto-connecting to $label."
            TRIGGER_BLUETOOTH_ON -> "Bluetooth is on - auto-connecting to $label."
            TRIGGER_USER_ENABLED -> "Auto-connect enabled - connecting to $label."
            else -> "Auto-connecting to $label."
        }
    }

    companion object {
        const val PREF_AUTO_CONNECT_ENABLED = "auto_connect_enabled"
        const val DEFAULT_AUTO_CONNECT_ENABLED = true
        const val TRIGGER_APP_RESUME = "app_resume"
        const val TRIGGER_DASHBOARD_READY = "dashboard_ready"
        const val TRIGGER_BLUETOOTH_CONNECTED = "bluetooth_connected"
        const val TRIGGER_BLUETOOTH_ON = "bluetooth_on"
        const val TRIGGER_USER_ENABLED = "user_enabled"
        private const val AUTO_CONNECT_COOLDOWN_MS = 90_000L
    }
}
