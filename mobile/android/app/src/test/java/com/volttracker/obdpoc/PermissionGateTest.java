package com.volttracker.obdpoc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Activity;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PermissionGateTest {

    @Test
    public void connectionPermissionsDoNotRequireLocation() {
        Activity activity = activityWithConnectionPermissions();

        assertTrue(new PermissionGate(activity).ensureConnectionGranted());
        assertTrue(Shadows.shadowOf(activity).getLastRequestedPermission() == null);
    }

    @Test
    public void fullPermissionRequestStillIncludesLocation() {
        Activity activity = activityWithConnectionPermissions();

        assertFalse(new PermissionGate(activity).ensureGranted());
        ShadowActivity.PermissionsRequest request =
                Shadows.shadowOf(activity).getLastRequestedPermission();
        assertArrayEquals(
                new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                },
                request.requestedPermissions);
    }

    private static Activity activityWithConnectionPermissions() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Shadows.shadowOf(activity)
                .grantPermissions(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.POST_NOTIFICATIONS);
        return activity;
    }
}
