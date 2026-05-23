package com.volttracker.obdpoc;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;

/**
 * Centralizes the runtime-permission handling for {@link MainActivity}: requesting the Bluetooth,
 * location, and notification permissions the OBD service needs and reporting which are currently
 * granted. Extracted so the SDK-version branching lives in one place.
 */
final class PermissionGate {

    static final int REQUEST_CODE = 4101;

    private final Activity activity;

    PermissionGate(Activity activity) {
        this.activity = activity;
    }

    /** Requests any not-yet-granted permissions. Returns true when nothing was missing. */
    boolean ensureGranted() {
        List<String> missing = new ArrayList<>();
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
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
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
