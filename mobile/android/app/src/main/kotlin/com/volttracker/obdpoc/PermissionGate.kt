package com.volttracker.obdpoc

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build

/**
 * Centralizes the runtime-permission handling for [MainActivity]. Connecting only requires the
 * Bluetooth runtime permissions; location and notifications are optional feature permissions and
 * must not block the core OBD session.
 */
class PermissionGate(
    private val activity: Activity,
    private val requestPermissions: (Array<String>) -> Unit,
) {
    /** Requests missing Bluetooth permissions. Returns true when connect can proceed now. */
    fun ensureConnectPermissions(): Boolean {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) {
                missing += Manifest.permission.BLUETOOTH_CONNECT
            }
            if (!granted(Manifest.permission.BLUETOOTH_SCAN)) {
                missing += Manifest.permission.BLUETOOTH_SCAN
            }
        }
        return requestMissing(missing)
    }

    /** Backward-compatible alias for tests and callers that name the connect-only behavior. */
    fun ensureConnectionGranted(): Boolean = ensureConnectPermissions()

    /** Requests optional feature permissions from the dashboard permissions button. */
    fun ensureGranted(): Boolean {
        val missing = mutableListOf<String>()
        addMissingConnectionPermissions(missing)
        addMissingLocationPermissions(missing)
        return requestMissing(missing)
    }

    private fun addMissingConnectionPermissions(missing: MutableList<String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) {
                missing += Manifest.permission.BLUETOOTH_CONNECT
            }
            if (!granted(Manifest.permission.BLUETOOTH_SCAN)) {
                missing += Manifest.permission.BLUETOOTH_SCAN
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !granted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
    }

    private fun addMissingLocationPermissions(missing: MutableList<String>) {
        if (hasLocation()) {
            return
        }
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            missing += Manifest.permission.ACCESS_COARSE_LOCATION
        }
    }

    private fun requestMissing(missing: List<String>): Boolean {
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray())
            return false
        }
        return true
    }

    fun hasBluetoothConnect(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            granted(Manifest.permission.BLUETOOTH_CONNECT)

    fun hasLocation(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(Manifest.permission.POST_NOTIFICATIONS)

    private fun granted(permission: String): Boolean = activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
