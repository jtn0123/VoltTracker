package com.volttracker.obdpoc;

import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Activity;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityPermissionTest {

    @Test
    public void freshLaunchRequestsLocationRuntimePermissions() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).create();
        try {
            Activity activity = controller.get();
            ShadowActivity.PermissionsRequest request =
                    shadowOf(activity).getLastRequestedPermission();

            assertTrue("fresh launch should request runtime permissions", request != null);
            List<String> permissions = Arrays.asList(request.requestedPermissions);
            assertTrue(permissions.contains(Manifest.permission.ACCESS_FINE_LOCATION));
            assertTrue(permissions.contains(Manifest.permission.ACCESS_COARSE_LOCATION));
        } finally {
            controller.destroy();
        }
    }
}
