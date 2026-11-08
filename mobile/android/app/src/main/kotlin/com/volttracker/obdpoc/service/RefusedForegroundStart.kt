package com.volttracker.obdpoc.service

import android.os.Build
import android.util.Log
import com.volttracker.obdpoc.AppPrefs

/**
 * Keeps the startForegroundService contract for a session start [ObdService] refuses before the
 * session begins (a database operation holds the lease). [ObdServiceLauncher] used
 * startForegroundService when that session would enter the foreground, and Android crashes an app
 * whose foreground-launched service stops before calling startForeground. So the service enters
 * the foreground as the session would and leaves it at once, with a notification Android 12+ holds
 * back long enough that it never shows ([ObdNotifications.buildRefusal]).
 */
internal object RefusedForegroundStart {
    /**
     * Calls [enterAndLeave] with the session's foreground type when a start of [action] owes
     * startForeground. A service already in the foreground owes nothing: Android dropped the
     * obligation when the start arrived. A platform refusal is logged and dropped, because the
     * service stops either way.
     */
    fun settle(
        action: String?,
        alreadyInForeground: Boolean,
        hasNearbyDevicesPermission: Boolean,
        hasLocationPermission: Boolean,
        sdkInt: Int = Build.VERSION.SDK_INT,
        enterAndLeave: (serviceType: Int?) -> Unit,
    ) {
        if (alreadyInForeground ||
            !ForegroundServicePolicy.launchesInForeground(action, sdkInt, hasNearbyDevicesPermission)
        ) {
            return
        }
        val plan =
            ForegroundServicePolicy.planForAction(
                action,
                sdkInt,
                hasNearbyDevicesPermission,
                hasLocationPermission,
            )
        if (plan !is ForegroundPlan.Foreground) return
        try {
            enterAndLeave(plan.serviceType)
        } catch (ex: SecurityException) {
            Log.w(AppPrefs.LOG_TAG, "Refused start: foreground entry refused", ex)
        } catch (ex: IllegalStateException) {
            // API 31+ ForegroundServiceStartNotAllowedException is an IllegalStateException.
            Log.w(AppPrefs.LOG_TAG, "Refused start: foreground entry refused", ex)
        }
    }
}
