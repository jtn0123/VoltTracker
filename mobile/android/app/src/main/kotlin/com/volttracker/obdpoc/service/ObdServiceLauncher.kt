package com.volttracker.obdpoc.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Starts [ObdService] the way its session will run: `startForegroundService` obliges the service to
 * call `startForeground` within seconds, and Android crashes the app if the service stops first, so
 * only sessions that will enter the foreground may use it ([ForegroundServicePolicy.launchesInForeground]).
 * Everything else is a plain start: the demo (see [ForegroundPlan.Background]) and a real session the
 * service will refuse for missing Nearby devices permission. Callers are visible screens, where
 * `startService` is allowed.
 */
internal object ObdServiceLauncher {
    /** Throws what the platform throws (background-start restrictions); callers report it. */
    fun start(
        context: Context,
        intent: Intent,
    ) {
        // The SDK_INT check re-proves what launchesInForeground already requires, so lint can verify
        // startForegroundService's API 26 requirement locally.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            ForegroundServicePolicy.launchesInForeground(
                intent.action,
                Build.VERSION.SDK_INT,
                hasNearbyDevicesPermission(context),
            )
        ) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /** BLUETOOTH_CONNECT or BLUETOOTH_SCAN, as [ObdService] checks; both are implicit below Android 12. */
    private fun hasNearbyDevicesPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
}
