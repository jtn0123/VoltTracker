package com.volttracker.obdpoc

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Pins [EventNotificationPrefs] defaults, clamping, DTC-set round-trip, and the state JSON. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventNotificationPrefsTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var subject: EventNotificationPrefs

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("event-prefs-test", Context.MODE_PRIVATE)
        prefs.edit { clear() }
        subject = EventNotificationPrefs(prefs)
    }

    @Test
    fun defaultsMatchSpecChargeAndDtcOnThresholdsOff() {
        assertTrue("charge-complete defaults on", subject.chargeCompleteEnabled())
        assertTrue("new-DTC defaults on", subject.newDtcEnabled())
        assertFalse("low-SOC defaults off", subject.lowSocEnabled())
        assertFalse("high-temp defaults off", subject.highPackTempEnabled())
        assertFalse("auto-scan defaults off", subject.autoScanOnConnectEnabled())
        assertEquals(20.0, subject.lowSocThresholdPct(), 0.001)
        assertEquals(45.0, subject.highPackTempThresholdC(), 0.001)
        assertEquals("target SOC defaults to a full charge", 100.0, subject.targetSocPct(), 0.001)
    }

    @Test
    fun settersPersist() {
        subject.setChargeCompleteEnabled(false)
        subject.setNewDtcEnabled(false)
        subject.setLowSocEnabled(true)
        subject.setLowSocThresholdPct(15.0)
        subject.setHighPackTempEnabled(true)
        subject.setHighPackTempThresholdC(50.0)
        subject.setTargetSocPct(80.0)
        subject.setAutoScanOnConnectEnabled(true)

        val reloaded = EventNotificationPrefs(prefs)
        assertFalse(reloaded.chargeCompleteEnabled())
        assertFalse(reloaded.newDtcEnabled())
        assertTrue(reloaded.lowSocEnabled())
        assertEquals(15.0, reloaded.lowSocThresholdPct(), 0.001)
        assertTrue(reloaded.highPackTempEnabled())
        assertEquals(50.0, reloaded.highPackTempThresholdC(), 0.001)
        assertEquals(80.0, reloaded.targetSocPct(), 0.001)
        assertTrue(reloaded.autoScanOnConnectEnabled())
    }

    @Test
    fun thresholdsAreClampedToSaneRanges() {
        subject.setLowSocThresholdPct(500.0)
        assertEquals(99.0, subject.lowSocThresholdPct(), 0.001)
        subject.setLowSocThresholdPct(-5.0)
        assertEquals(1.0, subject.lowSocThresholdPct(), 0.001)

        subject.setHighPackTempThresholdC(999.0)
        assertEquals(80.0, subject.highPackTempThresholdC(), 0.001)
        subject.setHighPackTempThresholdC(0.0)
        assertEquals(20.0, subject.highPackTempThresholdC(), 0.001)

        // Target SOC is clamped to [50, 100].
        subject.setTargetSocPct(200.0)
        assertEquals(100.0, subject.targetSocPct(), 0.001)
        subject.setTargetSocPct(10.0)
        assertEquals(50.0, subject.targetSocPct(), 0.001)
    }

    @Test
    fun lastScanDtcCodesRoundTripDedupedAndUpperCased() {
        subject.setLastScanDtcCodes(listOf("  p0aa6 ", "P1FFF", "p0aa6"))
        val codes = subject.lastScanDtcCodes()
        assertEquals(setOf("P0AA6", "P1FFF"), codes)
    }

    @Test
    fun lastScanDtcCodesDefaultsEmpty() {
        assertTrue(subject.lastScanDtcCodes().isEmpty())
    }

    @Test
    fun dtcBaselineFlagIsUnsetUntilFirstScanThenSet() {
        assertFalse("no baseline before any scan persists", subject.hasDtcBaseline())
        // Even persisting an EMPTY scan establishes the baseline (a clean car has a real, empty
        // baseline), so a later newly-appearing code is treated as new rather than a first-scan blip.
        subject.setLastScanDtcCodes(emptyList())
        assertTrue("baseline set once a scan persists", EventNotificationPrefs(prefs).hasDtcBaseline())
    }

    @Test
    fun lastAutoScanTimestampRoundTrips() {
        assertEquals(0L, subject.lastAutoScanAtMs())
        subject.setLastAutoScanAtMs(123_456L)
        assertEquals(123_456L, EventNotificationPrefs(prefs).lastAutoScanAtMs())
    }

    @Test
    fun settingsVersionAdvancesOnEverySettingChangeButNotOnScanBookkeeping() {
        val start = subject.settingsVersion()
        subject.setChargeCompleteEnabled(false)
        val afterOneToggle = subject.settingsVersion()
        assertTrue("a settings change advances the version", afterOneToggle > start)

        subject.setLowSocThresholdPct(15.0)
        assertTrue("a threshold change advances it again", subject.settingsVersion() > afterOneToggle)

        // Scan/throttle bookkeeping is NOT part of the cached Settings snapshot, so it must not churn
        // the version (which would defeat the coordinator's per-sample cache).
        val beforeBookkeeping = subject.settingsVersion()
        subject.setLastScanDtcCodes(listOf("P0AA6"))
        subject.setLastAutoScanAtMs(123L)
        assertEquals("scan bookkeeping leaves the version alone", beforeBookkeeping, subject.settingsVersion())
    }

    @Test
    fun settingsVersionIsVisibleAcrossInstancesOverTheSameFile() {
        // Production holds two EventNotificationPrefs (Activity toggles vs service coordinator) over
        // the same prefs file; a toggle via one must be observable as a version bump via the other.
        val reader = EventNotificationPrefs(prefs)
        val before = reader.settingsVersion()
        subject.setHighPackTempEnabled(true)
        assertTrue("cross-instance toggle bumps the version", reader.settingsVersion() > before)
    }

    @Test
    fun stateJsonReflectsCurrentSettings() {
        subject.setLowSocEnabled(true)
        subject.setLowSocThresholdPct(25.0)
        val json = JSONObject(subject.stateJson())
        assertTrue(json.getBoolean("chargeComplete"))
        assertTrue(json.getBoolean("newDtc"))
        assertTrue(json.getBoolean("lowSoc"))
        assertEquals(25.0, json.getDouble("lowSocThresholdPct"), 0.001)
        assertFalse(json.getBoolean("highPackTemp"))
        assertEquals(100.0, json.getDouble("targetSocPct"), 0.001)
        assertFalse(json.getBoolean("autoScanOnConnect"))
    }

    @Test
    fun targetSocSetterAdvancesTheSettingsVersion() {
        val before = subject.settingsVersion()
        subject.setTargetSocPct(80.0)
        assertTrue("a target-SOC change advances the version", subject.settingsVersion() > before)
    }
}
