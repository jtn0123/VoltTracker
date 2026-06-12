package com.volttracker.obdpoc.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * [LocationTracker] backed by the platform [LocationManager].
 */
class LocationManagerTracker(
    context: Context,
) : LocationTracker {
    private val context: Context = context.applicationContext
    private val filter = LocationFilter()
    private var locationManager: LocationManager? = null
    private var listener: LocationTracker.Listener? = null

    /** True once requestLocationUpdates has been issued for the current start/stop cycle. */
    private var updatesRequested = false

    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdog = Runnable { onWatchdogTimeout() }

    @Volatile
    private var lastAccepted: FilteredLocation? = null

    private val locationListener =
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                handleFix(location)
            }

            override fun onProviderDisabled(provider: String) {
            }

            override fun onProviderEnabled(provider: String) {
            }

            @Deprecated("Deprecated platform callback still required on older API levels.")
            override fun onStatusChanged(
                provider: String,
                status: Int,
                extras: Bundle,
            ) {
            }
        }

    @SuppressLint("MissingPermission")
    override fun start(listener: LocationTracker.Listener) {
        this.listener = listener
        filter.reset()
        lastAccepted = null
        updatesRequested = false
        if (!hasLocationPermission()) {
            // Keep the listener parked: resumeUpdatesIfPermitted() begins delivery if the user
            // grants location while this session is still running.
            Log.w(TAG, "location permission missing; GPS updates deferred until granted")
            return
        }
        requestUpdates()
    }

    override fun resumeUpdatesIfPermitted(): Boolean {
        if (listener == null || updatesRequested || !hasLocationPermission()) {
            return false
        }
        requestUpdates()
        return updatesRequested
    }

    private fun requestUpdates() {
        locationManager = context.getSystemService(LocationManager::class.java)
        val manager = locationManager ?: return
        val gps = requestProvider(manager, LocationManager.GPS_PROVIDER, 1000L)
        val network = requestProvider(manager, LocationManager.NETWORK_PROVIDER, 5000L)
        // Only mark updates active when at least one provider actually subscribed;
        // otherwise resumeUpdatesIfPermitted() would be suppressed forever after a
        // total failure even though nothing is delivering fixes.
        updatesRequested = gps || network
        if (updatesRequested) {
            scheduleWatchdog()
        }
    }

    private fun scheduleWatchdog() {
        watchdogHandler.removeCallbacks(watchdog)
        watchdogHandler.postDelayed(watchdog, WATCHDOG_TIMEOUT_MS)
    }

    /**
     * No fix arrived within [WATCHDOG_TIMEOUT_MS] of subscribing (or of the last fix). A hung
     * provider would otherwise stall GPS for the whole session with no signal, so tear the
     * subscription down and re-issue it; the re-request re-arms this watchdog, so a still-dead
     * provider keeps retrying at the timeout cadence.
     */
    private fun onWatchdogTimeout() {
        if (listener == null || !updatesRequested) {
            return
        }
        Log.w(TAG, "no location fix in ${WATCHDOG_TIMEOUT_MS / 1000}s; re-subscribing providers")
        locationManager?.let { manager ->
            try {
                manager.removeUpdates(locationListener)
            } catch (ex: SecurityException) {
                Log.w(TAG, "location permission revoked before watchdog could re-subscribe", ex)
            }
        }
        updatesRequested = false
        if (hasLocationPermission()) {
            requestUpdates()
        }
    }

    /** Returns true when the provider subscription was actually issued. */
    @SuppressLint("MissingPermission")
    private fun requestProvider(
        manager: LocationManager,
        provider: String,
        minIntervalMs: Long,
    ): Boolean =
        try {
            manager.requestLocationUpdates(provider, minIntervalMs, 0f, locationListener)
            true
        } catch (ex: IllegalArgumentException) {
            Log.w(TAG, "location provider $provider unavailable; skipping it", ex)
            false
        } catch (ex: SecurityException) {
            Log.w(TAG, "location permission denied while requesting $provider updates", ex)
            false
        }

    override fun stop() {
        watchdogHandler.removeCallbacks(watchdog)
        val manager = locationManager
        if (manager != null) {
            try {
                manager.removeUpdates(locationListener)
            } catch (ex: SecurityException) {
                Log.w(TAG, "location permission revoked before updates could be removed", ex)
            }
            locationManager = null
        }
        updatesRequested = false
        listener = null
    }

    override fun getLastLocation(): FilteredLocation? = lastAccepted

    private fun handleFix(location: Location) {
        // Any fix — even one the filter rejects — proves the providers are alive; push the
        // hung-provider watchdog out rather than re-subscribing under a healthy stream.
        if (updatesRequested) {
            scheduleWatchdog()
        }
        val now = System.currentTimeMillis()
        val accuracy = if (location.hasAccuracy()) location.accuracy else -1f
        val fixTime = location.time
        val decision =
            filter.evaluate(
                location.latitude,
                location.longitude,
                accuracy,
                fixTime,
                location.provider,
                now,
            )
        if (decision != LocationFilter.Decision.ACCEPT) {
            return
        }
        val effectiveFix = if (fixTime > 0L) fixTime else now
        val accepted =
            FilteredLocation(
                round6(location.latitude),
                round6(location.longitude),
                if (location.hasAccuracy()) round1(location.accuracy.toDouble()) else null,
                if (location.hasAltitude()) round1(location.altitude) else null,
                if (location.hasSpeed()) round1(location.speed.toDouble()) else null,
                if (location.hasBearing()) round1(location.bearing.toDouble()) else null,
                effectiveFix,
                maxOf(0L, now - effectiveFix),
                if (location.elapsedRealtimeNanos != 0L) location.elapsedRealtimeNanos else null,
                location.provider,
            )
        lastAccepted = accepted
        listener?.onLocation(accepted)
    }

    private fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "LocationTracker"

        /** How long the tracker waits for any fix before re-subscribing a hung provider. */
        internal const val WATCHDOG_TIMEOUT_MS = 60_000L

        private fun round6(value: Double): Double = Math.round(value * 1_000_000.0) / 1_000_000.0

        private fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0
    }
}
