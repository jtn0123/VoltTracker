package com.volttracker.obdpoc

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedDisplayPrefsTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var shared: SharedDisplayPrefs

    @Before
    fun setUp() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        shared = SharedDisplayPrefs(prefs)
    }

    @Test
    fun defaultsMatchWhatTheWebViewAndServiceUseWhenNothingIsStored() {
        assertEquals(SharedDisplayPrefs.Units.IMPERIAL, shared.units())
        assertEquals(0.0, shared.pricePerKwh(), 0.0)
        assertEquals(0.0, shared.publicPricePerKwh(), 0.0)
        assertNull(shared.mpg())
        assertEquals(0.0, shared.gasPricePerGal(), 0.0)
        assertEquals(100.0, shared.chargeTargetSoc(), 0.0)
        assertEquals(1.0, shared.fontScale(), 0.0)
        assertFalse(shared.highContrast())
        assertTrue(shared.quietTelemetry())
        assertEquals("{}", shared.snapshotJson())
    }

    @Test
    fun typedSettersRoundTripAndLandInTheSnapshot() {
        shared.setUnits(SharedDisplayPrefs.Units.METRIC)
        shared.setPricePerKwh(0.14)
        shared.setPublicPricePerKwh(0.42)
        shared.setMpg(31.0)
        shared.setGasPricePerGal(4.29)
        shared.setFontScale(1.25)
        shared.setHighContrast(true)
        shared.setQuietTelemetry(false)
        assertEquals(SharedDisplayPrefs.Units.METRIC, shared.units())
        assertEquals(0.14, shared.pricePerKwh(), 0.0)
        assertEquals(0.42, shared.publicPricePerKwh(), 0.0)
        assertEquals(31.0, shared.mpg())
        assertEquals(4.29, shared.gasPricePerGal(), 0.0)
        assertEquals(1.25, shared.fontScale(), 0.0)
        assertTrue(shared.highContrast())
        assertFalse(shared.quietTelemetry())
        val snapshot = JSONObject(shared.snapshotJson())
        assertEquals("metric", snapshot.getString("units"))
        assertEquals(0.14, snapshot.getDouble("pricePerKwh"), 0.0)
        assertEquals(false, snapshot.getBoolean("quietTelemetry"))
        assertFalse("charge target was never set", snapshot.has("chargeTargetSoc"))
    }

    @Test
    fun chargeTargetIsTheNotificationTargetNotACopy() {
        assertTrue(shared.set("chargeTargetSoc", "80"))
        assertEquals(80.0, EventNotificationPrefs(prefs).targetSocPct(), 0.0)
        assertFalse(prefs.contains(SharedDisplayPrefs.PREFIX + "chargeTargetSoc"))
        EventNotificationPrefs(prefs).setTargetSocPct(90.0)
        assertEquals(90.0, JSONObject(shared.snapshotJson()).getDouble("chargeTargetSoc"), 0.0)
    }

    @Test
    fun numbersAreClampedToTheWebViewInputRanges() {
        shared.set("pricePerKwh", "25")
        assertEquals(2.0, shared.pricePerKwh(), 0.0)
        shared.set("gasPricePerGal", "-3")
        assertEquals(0.0, shared.gasPricePerGal(), 0.0)
        shared.set("mpg", "2")
        assertEquals(5.0, shared.mpg())
        shared.set("mpg", "900")
        assertEquals(150.0, shared.mpg())
        shared.set("mpg", "0")
        assertNull("0 clears the comparison car", shared.mpg())
        shared.set("chargeTargetSoc", "20")
        assertEquals(50.0, shared.chargeTargetSoc(), 0.0)
        shared.set("fontScale", "3")
        assertEquals(1.5, shared.fontScale(), 0.0)
    }

    @Test
    fun unknownKeysAndWrongTypesAreRejectedWithoutWriting() {
        assertFalse(shared.set("mapLayer", "\"satellite\""))
        assertFalse(shared.set("units", "\"furlongs\""))
        assertFalse(shared.set("units", "3"))
        assertFalse(shared.set("highContrast", "\"yes\""))
        assertFalse(shared.set("pricePerKwh", "\"cheap\""))
        assertFalse(shared.set("pricePerKwh", "not json {"))
        assertFalse(shared.set("pricePerKwh", null))
        assertFalse(shared.set("pricePerKwh", "null"))
        assertEquals("{}", shared.snapshotJson())
    }

    @Test
    fun applyAllCopiesOnlyTheSharedKeysFromABackup() {
        shared.applyAll(
            JSONObject()
                .put("units", "metric")
                .put("pricePerKwh", 0.2)
                .put("highContrast", true)
                .put("mapLayer", "satellite"),
        )
        shared.applyAll(null)
        val snapshot = JSONObject(shared.snapshotJson())
        assertEquals(setOf("units", "pricePerKwh", "highContrast"), snapshot.keys().asSequence().toSet())
    }

    @Test
    fun aCorruptStoredValueReadsAsTheDefault() {
        prefs.edit().putString(SharedDisplayPrefs.PREFIX + "pricePerKwh", "{oops").commit()
        prefs.edit().putString(SharedDisplayPrefs.PREFIX + "units", "7").commit()
        assertEquals(0.0, shared.pricePerKwh(), 0.0)
        assertEquals(SharedDisplayPrefs.Units.IMPERIAL, shared.units())
        assertFalse(JSONObject(shared.snapshotJson()).has("pricePerKwh"))
    }

    @Test
    fun bridgeReadsAndWritesThroughTheHostStore() {
        val bridge = VoltBridgeSharedPrefs(host { shared })
        assertTrue(bridge.setSharedPref("pricePerKwh", "0.16"))
        assertFalse(bridge.setSharedPref(null, "0.16"))
        assertEquals(0.16, JSONObject(bridge.getSharedPrefs()).getDouble("pricePerKwh"), 0.0)
    }

    @Test
    fun bridgeAnswersSafelyBeforeThePrefsExist() {
        val bridge = VoltBridgeSharedPrefs(host { null })
        assertEquals("{}", bridge.getSharedPrefs())
        assertFalse(bridge.setSharedPref("pricePerKwh", "0.16"))
    }

    /** A [DashboardHost] that only answers [DashboardHost.sharedDisplayPrefs]. */
    private fun host(store: () -> SharedDisplayPrefs?): DashboardHost =
        Proxy.newProxyInstance(
            DashboardHost::class.java.classLoader,
            arrayOf(DashboardHost::class.java),
        ) { _, method, _ ->
            check(method.name == "sharedDisplayPrefs") { "unexpected host call ${method.name}" }
            store()
        } as DashboardHost
}
