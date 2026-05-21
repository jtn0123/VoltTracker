package com.volttracker.obdpoc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Tests the OBD-adapter name heuristic in {@link DeviceCatalog}. */
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
}
