package com.volttracker.obdpoc.service

/** How a session holds the process: a typed foreground service, a plain started service, or not at all. */
internal sealed interface ForegroundPlan {
    /** Enter the foreground with [serviceType] (null below Android Q, where startForeground is untyped). */
    data class Foreground(
        val serviceType: Int?,
    ) : ForegroundPlan

    /**
     * Run as a plain started service with no foreground notification. The demo stream touches no
     * car, adapter, Bluetooth or GPS, so none of the manifest's FGS types (connectedDevice,
     * location) honestly describes it — and on Android 14+ both require runtime permissions a
     * fresh install does not have, so demanding one made "Start demo" fail after `pm clear`.
     */
    data object Background : ForegroundPlan

    /**
     * A real adapter session on Android 14+ without any Nearby devices permission: startForeground
     * with the connectedDevice type would throw a SecurityException, so refuse up front with a
     * message the user can act on instead.
     */
    data object MissingNearbyDevicesPermission : ForegroundPlan
}

/**
 * Picks the foreground-service treatment for a session from its mode and the permissions granted
 * right now. Pure (no Android calls) so plain JVM tests can pin every branch; the service and the
 * activities that launch it both consult it so `startForegroundService` is only used for sessions
 * that will actually call `startForeground`.
 */
internal object ForegroundServicePolicy {
    /** [android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE] (API 29). */
    const val TYPE_CONNECTED_DEVICE = 0x10

    /** [android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION] (API 29). */
    const val TYPE_LOCATION = 0x08

    private const val SDK_Q = 29

    // Android 14 (U) is where the platform started validating FGS-type prerequisites.
    private const val SDK_UPSIDE_DOWN_CAKE = 34

    private const val MODE_DEMO = "demo"

    /** True when [action] starts a session that enters the foreground (everything but the demo). */
    fun launchesInForeground(action: String?): Boolean = action != ObdService.ACTION_DEMO

    /**
     * [hasNearbyDevicesPermission] is BLUETOOTH_CONNECT or BLUETOOTH_SCAN (either satisfies the
     * connectedDevice type); both are implicitly granted below Android 12.
     */
    fun plan(
        mode: String,
        sdkInt: Int,
        hasNearbyDevicesPermission: Boolean,
        hasLocationPermission: Boolean,
    ): ForegroundPlan =
        when {
            mode == MODE_DEMO -> ForegroundPlan.Background
            sdkInt >= SDK_UPSIDE_DOWN_CAKE && !hasNearbyDevicesPermission ->
                ForegroundPlan.MissingNearbyDevicesPermission
            sdkInt < SDK_Q -> ForegroundPlan.Foreground(null)
            else -> ForegroundPlan.Foreground(serviceType(hasLocationPermission))
        }

    /** The typed-FGS mask for a real session: connectedDevice, plus location once GPS is permitted. */
    fun serviceType(hasLocationPermission: Boolean): Int =
        if (hasLocationPermission) TYPE_CONNECTED_DEVICE or TYPE_LOCATION else TYPE_CONNECTED_DEVICE
}
