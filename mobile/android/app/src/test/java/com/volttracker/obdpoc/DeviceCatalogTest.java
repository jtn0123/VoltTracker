package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Tests the OBD-adapter name heuristic in {@link DeviceCatalog}. */
@RunWith(RobolectricTestRunner.class)
// Pin the Robolectric SDK like the rest of the suite: the app's targetSdk is 36,
// which Robolectric only runs under Java 21, but CI builds on Java 17. SDK 34
// runs on 17 and exercises the same code path.
@Config(sdk = 34)
public class DeviceCatalogTest {

    @Test
    public void isLikelyObdNameMatchesKnownAdapterKeywords() {
        assertTrue(DeviceCatalog.isLikelyObdName("OBDII"));
        assertTrue(DeviceCatalog.isLikelyObdName("ELM327 v1.5"));
        assertTrue(DeviceCatalog.isLikelyObdName("OBDLink MX+"));
        assertTrue(DeviceCatalog.isLikelyObdName("Veepeak"));
        assertTrue(DeviceCatalog.isLikelyObdName("vLinker FD"));
        assertTrue(DeviceCatalog.isLikelyObdName("Carista"));
        assertTrue(DeviceCatalog.isLikelyObdName("Bluetooth Scanner"));
    }

    @Test
    public void isLikelyObdNameIsCaseInsensitive() {
        assertTrue(DeviceCatalog.isLikelyObdName("obdlink mx+"));
        assertTrue(DeviceCatalog.isLikelyObdName("Elm327"));
    }

    @Test
    public void isLikelyObdNameRejectsUnrelatedDevices() {
        assertFalse(DeviceCatalog.isLikelyObdName("AirPods Pro"));
        assertFalse(DeviceCatalog.isLikelyObdName("Galaxy Buds"));
        assertFalse(DeviceCatalog.isLikelyObdName(""));
        assertFalse(DeviceCatalog.isLikelyObdName(null));
    }

    @Test
    public void rememberRejectsInvalidBluetoothAddress() {
        Context context = RuntimeEnvironment.getApplication();
        DeviceCatalog catalog =
                new DeviceCatalog(context, context.getSharedPreferences("device-catalog-test", 0));
        context.getSharedPreferences("device-catalog-test", 0).edit().clear().commit();

        assertEquals("", catalog.remember("not-a-mac", "Bad adapter"));
        assertEquals("", catalog.lastAddress());
    }
}
