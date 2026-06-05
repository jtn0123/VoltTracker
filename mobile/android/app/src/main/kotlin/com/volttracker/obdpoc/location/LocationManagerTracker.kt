package com.volttracker.obdpoc.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle

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
        if (!hasLocationPermission()) {
            return
        }
        locationManager = context.getSystemService(LocationManager::class.java)
        val manager = locationManager ?: return
        requestProvider(manager, LocationManager.GPS_PROVIDER, 1000L)
        requestProvider(manager, LocationManager.NETWORK_PROVIDER, 5000L)
    }

    @SuppressLint("MissingPermission")
    private fun requestProvider(
        manager: LocationManager,
        provider: String,
        minIntervalMs: Long,
    ) {
        try {
            manager.requestLocationUpdates(provider, minIntervalMs, 0f, locationListener)
        } catch (ignored: IllegalArgumentException) {
        } catch (ignored: SecurityException) {
        }
    }

    override fun stop() {
        val manager = locationManager
        if (manager != null) {
            try {
                manager.removeUpdates(locationListener)
            } catch (ignored: SecurityException) {
            }
            locationManager = null
        }
        listener = null
    }

    override fun getLastLocation(): FilteredLocation? = lastAccepted

    private fun handleFix(location: Location) {
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
        private fun round6(value: Double): Double = Math.round(value * 1_000_000.0) / 1_000_000.0

        private fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0
    }
}
