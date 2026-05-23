package com.volttracker.obdpoc.location;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;

/**
 * {@link LocationTracker} backed by the platform {@link LocationManager}. Every raw fix runs
 * through {@link LocationFilter}; only accepted fixes reach the listener and {@link
 * #getLastLocation()}. Because fixes arrive on the provider's own callbacks (~1 Hz), route logging
 * no longer depends on the OBD poll cadence.
 */
public final class LocationManagerTracker implements LocationTracker {

    private final Context context;
    private final LocationFilter filter = new LocationFilter();
    private LocationManager locationManager;
    private Listener listener;
    private volatile FilteredLocation lastAccepted;

    private final LocationListener locationListener =
            new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (location != null) {
                        handleFix(location);
                    }
                }

                @Override
                public void onProviderDisabled(String provider) {}

                @Override
                public void onProviderEnabled(String provider) {}

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {
                    // Deprecated but still required on older API levels.
                }
            };

    public LocationManagerTracker(Context context) {
        this.context = context.getApplicationContext();
    }

    @SuppressLint("MissingPermission")
    @Override
    public void start(Listener listener) {
        this.listener = listener;
        // The tracker instance is reused across sessions; clear any state carried over
        // from the previous one so a new session's first fixes are not jump-checked
        // against a stale point from minutes ago.
        filter.reset();
        lastAccepted = null;
        if (!hasLocationPermission()) {
            return;
        }
        locationManager = context.getSystemService(LocationManager.class);
        if (locationManager == null) {
            return;
        }
        // Both providers feed the same filter; the filter prefers GPS and drops coarse
        // network fixes while GPS is fresh, so network only fills true GPS gaps.
        requestProvider(LocationManager.GPS_PROVIDER, 1000L);
        requestProvider(LocationManager.NETWORK_PROVIDER, 5000L);
    }

    @SuppressLint("MissingPermission")
    private void requestProvider(String provider, long minIntervalMs) {
        try {
            locationManager.requestLocationUpdates(provider, minIntervalMs, 0f, locationListener);
        } catch (IllegalArgumentException | SecurityException ignored) {
            // Provider absent on this device, or permission revoked mid-flight.
        }
    }

    @Override
    public void stop() {
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (SecurityException ignored) {
            }
            locationManager = null;
        }
        listener = null;
    }

    @Override
    public FilteredLocation getLastLocation() {
        return lastAccepted;
    }

    private void handleFix(Location location) {
        long now = System.currentTimeMillis();
        float accuracy = location.hasAccuracy() ? location.getAccuracy() : -1f;
        long fixTime = location.getTime();
        LocationFilter.Decision decision =
                filter.evaluate(
                        location.getLatitude(),
                        location.getLongitude(),
                        accuracy,
                        fixTime,
                        location.getProvider(),
                        now);
        if (decision != LocationFilter.Decision.ACCEPT) {
            return;
        }
        long effectiveFix = fixTime > 0L ? fixTime : now;
        FilteredLocation accepted =
                new FilteredLocation(
                        round6(location.getLatitude()),
                        round6(location.getLongitude()),
                        location.hasAccuracy()
                                ? Double.valueOf(round1(location.getAccuracy()))
                                : null,
                        location.hasAltitude()
                                ? Double.valueOf(round1(location.getAltitude()))
                                : null,
                        location.hasSpeed() ? Double.valueOf(round1(location.getSpeed())) : null,
                        location.hasBearing()
                                ? Double.valueOf(round1(location.getBearing()))
                                : null,
                        effectiveFix,
                        Math.max(0L, now - effectiveFix),
                        location.getElapsedRealtimeNanos() != 0L
                                ? location.getElapsedRealtimeNanos()
                                : null,
                        location.getProvider());
        lastAccepted = accepted;
        Listener current = listener;
        if (current != null) {
            current.onLocation(accepted);
        }
    }

    private boolean hasLocationPermission() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
