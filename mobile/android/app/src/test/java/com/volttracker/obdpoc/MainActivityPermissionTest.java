package com.volttracker.obdpoc;

import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
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
    public void freshLaunchDoesNotRequestRuntimePermissions() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).create();
        try {
            Activity activity = controller.get();
            ShadowActivity.PermissionsRequest request =
                    shadowOf(activity).getLastRequestedPermission();

            assertNull("fresh launch should let the dashboard explain permissions first", request);
        } finally {
            controller.destroy();
        }
    }
}
