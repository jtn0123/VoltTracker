package com.volttracker.obdpoc;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;

/**
 * Centralizes the runtime-permission handling for {@link MainActivity}. Connecting only requires
 * the Bluetooth runtime permissions; location and notifications are optional feature permissions
 * and must not block the core OBD session.
 */
final class PermissionGate {

    static final int REQUEST_CODE = 4101;

    private final Activity activity;

    PermissionGate(Activity activity) {
        this.activity = activity;
    }

    /** Requests missing Bluetooth permissions. Returns true when connect can proceed now. */
    boolean ensureConnectPermissions() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (!granted(Manifest.permission.BLUETOOTH_SCAN)) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        }
        return requestMissing(missing);
    }

    /** Backward-compatible alias for tests and callers that name the connect-only behavior. */
    boolean ensureConnectionGranted() {
        return ensureConnectPermissions();
    }

    /** Requests optional feature permissions from the dashboard permissions button. */
    boolean ensureGranted() {
        List<String> missing = new ArrayList<>();
        addMissingConnectionPermissions(missing);
        addMissingLocationPermissions(missing);
        return requestMissing(missing);
    }

    private void addMissingConnectionPermissions(List<String> missing) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (!granted(Manifest.permission.BLUETOOTH_SCAN)) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private void addMissingLocationPermissions(List<String> missing) {
        if (hasLocation()) {
            return;
        }
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
    }

    private boolean requestMissing(List<String> missing) {
        if (!missing.isEmpty()) {
            activity.requestPermissions(missing.toArray(new String[0]), REQUEST_CODE);
            return false;
        }
        return true;
    }

    boolean hasBluetoothConnect() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || granted(Manifest.permission.BLUETOOTH_CONNECT);
    }

    boolean hasLocation() {
        return granted(Manifest.permission.ACCESS_FINE_LOCATION)
                || granted(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    boolean hasNotifications() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || granted(Manifest.permission.POST_NOTIFICATIONS);
    }

    private boolean granted(String permission) {
        return activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
}
