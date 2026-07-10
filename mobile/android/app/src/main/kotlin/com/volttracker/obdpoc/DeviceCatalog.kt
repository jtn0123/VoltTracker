package com.volttracker.obdpoc

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * Owns paired-adapter discovery, OBD-likelihood heuristics, and the remembered-device history
 * persisted in [SharedPreferences]. Extracted from [MainActivity] so the Bluetooth/preferences
 * plumbing is isolated and the name heuristics stay unit-testable.
 */
class DeviceCatalog(
    private val context: Context,
    private val prefs: SharedPreferences,
) {
    fun lastAddress(): String = prefs.getString(PREF_LAST_ADDRESS, "") ?: ""

    fun lastName(): String = prefs.getString(PREF_LAST_NAME, "") ?: ""

    fun hasBluetoothConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun getBondedDevicesJson(): String {
        val devices = JSONArray()
        val adapter = BluetoothAdapters.get(context)
        if (adapter == null || !hasBluetoothConnectPermission()) {
            return devices.toString()
        }

        val bonded = bondedDevicesOrEmpty(adapter)
        val sorted = bonded.toMutableList()
        sorted.sortWith { left, right ->
            val leftIsCandidate = isLikelyObdDevice(left)
            val rightIsCandidate = isLikelyObdDevice(right)
            when {
                leftIsCandidate != rightIsCandidate -> if (leftIsCandidate) -1 else 1
                else ->
                    safeName(left).lowercase(Locale.US).compareTo(safeName(right).lowercase(Locale.US))
            }
        }

        for (device in sorted) {
            val item = JSONObject()
            try {
                item.put("name", safeName(device))
                item.put("address", safeAddress(device))
                item.put("type", safeType(device))
                item.put("bondState", safeBondState(device))
                item.put("obdCandidate", isLikelyObdDevice(device))
                devices.put(item)
            } catch (_: JSONException) {
                // Skip malformed device entries. Android-provided addresses should be valid.
            } catch (ex: SecurityException) {
                Log.w(TAG, "Bluetooth device entry became unreadable", ex)
                // Skip entries that become unreadable while iterating bonded devices.
            }
        }
        return devices.toString()
    }

    @SuppressLint("MissingPermission")
    private fun getLikelyObdCandidates(): JSONArray {
        val candidates = JSONArray()
        val adapter = BluetoothAdapters.get(context)
        if (adapter == null || !hasBluetoothConnectPermission()) {
            return candidates
        }

        val sorted = bondedDevicesOrEmpty(adapter).toMutableList()
        sorted.sortWith { left, right ->
            safeName(left).lowercase(Locale.US).compareTo(safeName(right).lowercase(Locale.US))
        }
        for (device in sorted) {
            if (!isLikelyObdDevice(device)) {
                continue
            }
            val item = JSONObject()
            try {
                item.put("address", safeAddress(device))
                item.put("name", safeName(device))
                item.put("lastSeen", 0)
                item.put("connectCount", 0)
                item.put("candidate", true)
                candidates.put(item)
            } catch (_: JSONException) {
                // Skip malformed device entries.
            } catch (ex: SecurityException) {
                Log.w(TAG, "Bluetooth candidate became unreadable", ex)
                // Skip entries that become unreadable while iterating bonded devices.
            }
        }
        return candidates
    }

    @SuppressLint("MissingPermission")
    private fun bondedDevicesOrEmpty(adapter: BluetoothAdapter): Set<BluetoothDevice> =
        try {
            // Android's platform type is declared non-null to Kotlin, but the Bluetooth service
            // can transiently return null while it restarts. Treat that state like an empty scan
            // so synchronous JavaScript bridge calls never inherit a framework NPE.
            adapter.bondedDevices ?: emptySet()
        } catch (ex: SecurityException) {
            Log.w(TAG, "Bluetooth bonded-device read denied", ex)
            emptySet()
        }

    /**
     * Persists `address`/`name` as the last adapter and folds it into the remembered-device history.
     * Returns the trimmed address, or `""` when blank.
     */
    fun remember(
        address: String?,
        name: String?,
    ): String {
        if (address == null || address.trim().isEmpty()) {
            return ""
        }
        val cleanAddress = address.trim()
        if (!BluetoothAdapter.checkBluetoothAddress(cleanAddress)) {
            return ""
        }
        val cleanName = name?.trim() ?: ""
        val history = updatedDeviceHistory(cleanAddress, cleanName)
        prefs.edit {
            putString(PREF_LAST_ADDRESS, cleanAddress)
            putString(PREF_LAST_NAME, cleanName)
            putString(PREF_DEVICE_HISTORY, history.toString())
        }
        return cleanAddress
    }

    fun getLastDeviceJson(): String = getLastOrCandidateDevice().toString()

    fun getLastOrCandidateDevice(): JSONObject {
        val payload = JSONObject()
        val address = prefs.getString(PREF_LAST_ADDRESS, "")
        val name = prefs.getString(PREF_LAST_NAME, "")
        if (address == null || address.trim().isEmpty()) {
            val candidate = getLikelyObdCandidates().optJSONObject(0)
            if (candidate != null) {
                return candidate
            }
        }
        try {
            payload.put("address", address ?: "")
            payload.put("name", name ?: "")
        } catch (_: JSONException) {
            // Preference strings are local.
        }
        return payload
    }

    fun getDeviceHistoryJson(): String {
        var history = parseDeviceHistory()
        if (history.length() == 0) {
            val address = prefs.getString(PREF_LAST_ADDRESS, "")
            if (address != null && address.trim().isNotEmpty()) {
                val item = JSONObject()
                try {
                    item.put("address", address.trim())
                    item.put("name", prefs.getString(PREF_LAST_NAME, ""))
                    item.put("lastSeen", System.currentTimeMillis())
                    item.put("connectCount", 1)
                    history.put(item)
                } catch (_: JSONException) {
                    // Preference strings are local.
                }
            } else {
                history = getLikelyObdCandidates()
            }
        }
        return history.toString()
    }

    private fun parseDeviceHistory(): JSONArray {
        val stored = prefs.getString(PREF_DEVICE_HISTORY, "[]")
        return try {
            JSONArray(stored ?: "[]")
        } catch (_: JSONException) {
            JSONArray()
        }
    }

    private fun updatedDeviceHistory(
        address: String,
        name: String,
    ): JSONArray {
        val current = parseDeviceHistory()
        val next = JSONArray()
        val remembered = JSONObject()
        val now = System.currentTimeMillis()
        var connectCount = 1
        var firstSeen = now
        var cleanName = name

        for (i in 0 until current.length()) {
            val item = current.optJSONObject(i) ?: continue
            if (address.equals(item.optString("address", ""), ignoreCase = true)) {
                connectCount = item.optInt("connectCount", 0) + 1
                firstSeen = item.optLong("firstSeen", now)
                if (cleanName.isEmpty()) {
                    cleanName = item.optString("name", "")
                }
            }
        }

        try {
            remembered.put("address", address)
            remembered.put("name", cleanName)
            remembered.put("firstSeen", firstSeen)
            remembered.put("lastSeen", now)
            remembered.put("connectCount", connectCount)
            next.put(remembered)

            var i = 0
            while (i < current.length() && next.length() < MAX_DEVICE_HISTORY) {
                val item = current.optJSONObject(i)
                if (item == null || address.equals(item.optString("address", ""), ignoreCase = true)) {
                    i += 1
                    continue
                }
                next.put(item)
                i += 1
            }
        } catch (_: JSONException) {
            // Local values are safe.
        }
        return next
    }

    companion object {
        const val PREF_LAST_ADDRESS = "last_address"
        const val PREF_LAST_NAME = "last_name"
        private const val PREF_DEVICE_HISTORY = "device_history"
        private const val MAX_DEVICE_HISTORY = 8
        private const val TAG = "VoltTracker"

        @SuppressLint("MissingPermission")
        private fun safeAddress(device: BluetoothDevice): String =
            try {
                device.address ?: ""
            } catch (ex: SecurityException) {
                Log.w(TAG, "Bluetooth address read denied", ex)
                ""
            }

        @SuppressLint("MissingPermission")
        private fun safeType(device: BluetoothDevice): Int =
            try {
                device.type
            } catch (ex: SecurityException) {
                Log.w(TAG, "Bluetooth device type read denied", ex)
                BluetoothDevice.DEVICE_TYPE_UNKNOWN
            }

        @SuppressLint("MissingPermission")
        private fun safeBondState(device: BluetoothDevice): Int =
            try {
                device.bondState
            } catch (ex: SecurityException) {
                Log.w(TAG, "Bluetooth bond-state read denied", ex)
                BluetoothDevice.BOND_NONE
            }

        @SuppressLint("MissingPermission")
        private fun safeName(device: BluetoothDevice): String {
            val name =
                try {
                    device.name
                } catch (ex: SecurityException) {
                    Log.w(TAG, "Bluetooth name read denied", ex)
                    return "OBD adapter"
                }
            if (name == null || name.trim().isEmpty()) {
                return "OBD adapter"
            }
            return name.trim()
        }

        @SuppressLint("MissingPermission")
        private fun isLikelyObdDevice(device: BluetoothDevice): Boolean = isLikelyObdName(safeName(device))

        @JvmStatic
        fun isLikelyObdName(name: String?): Boolean {
            val lower = name?.lowercase(Locale.US) ?: ""
            return lower.contains("obd") ||
                lower.contains("elm") ||
                lower.contains("vlink") ||
                lower.contains("veepeak") ||
                lower.contains("obdlink") ||
                lower.contains("mx+") ||
                lower.contains("carista") ||
                lower.contains("scanner")
        }
    }
}
