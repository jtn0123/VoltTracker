package com.volttracker.obdpoc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
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

    @Test
    public void fullPermissionRequestDoesNotReAskFineWhenCoarseIsGranted() {
        Activity activity = activityWithConnectionPermissions();
        Shadows.shadowOf(activity).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION);

        assertTrue(new PermissionGate(activity).ensureGranted());
        assertTrue(Shadows.shadowOf(activity).getLastRequestedPermission() == null);
    }

    @Test
    public void permissionResultMentionsLocationWhenOnlyBluetoothWasGranted() {
        HarnessActivity activity = Robolectric.buildActivity(HarnessActivity.class).create().get();
        Shadows.shadowOf(activity)
                .grantPermissions(
                        Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN);

        activity.onRequestPermissionsResult(
                PermissionGate.REQUEST_CODE,
                new String[] {Manifest.permission.BLUETOOTH_CONNECT},
                new int[] {android.content.pm.PackageManager.PERMISSION_GRANTED});

        assertEquals("ready", activity.lastState);
        assertTrue(activity.lastDetail.contains("Location is still off"));
    }

    @Test
    public void permissionResultMentionsNotificationsWhenBluetoothAndLocationWereGranted() {
        HarnessActivity activity = Robolectric.buildActivity(HarnessActivity.class).create().get();
        Shadows.shadowOf(activity)
                .grantPermissions(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION);

        activity.onRequestPermissionsResult(
                PermissionGate.REQUEST_CODE,
                new String[] {Manifest.permission.BLUETOOTH_CONNECT},
                new int[] {android.content.pm.PackageManager.PERMISSION_GRANTED});

        assertEquals("ready", activity.lastState);
        assertTrue(activity.lastDetail.contains("Notifications are still off"));
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

    public static class HarnessActivity extends MainActivity {
        String lastState;
        String lastDetail;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            permissionGate = new PermissionGate(this);
        }

        @Override
        public void publishDeviceList() {}

        @Override
        public void publishStatus(String state, String detail, boolean blocked) {
            lastState = state;
            lastDetail = detail;
        }
    }
}
