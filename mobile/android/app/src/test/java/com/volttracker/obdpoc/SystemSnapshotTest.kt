package com.volttracker.obdpoc

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Verifies that [SystemSnapshot.collect] produces the expected diagnostic keys and resolves
 * `lastSuccessfulSessionMs` against [SessionSummaryStore]'s recent rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemSnapshotTest {
    private lateinit var context: Context
    private lateinit var summaryFile: File

    @Before
    @Throws(IOException::class)
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val tmp = Files.createTempDirectory("snapshot").toFile()
        summaryFile = File(File(tmp, "obd-logs"), "sessions-summary.jsonl")
    }

    @After
    fun tearDown() {
        SessionSummaryStore.resetForTests()
    }

    @Test
    fun collectIncludesExpectedKeys() {
        val snap = SystemSnapshot.collect(context, null)

        assertTrue("must include Android SDK level", snap.has("androidSdk"))
        assertTrue("must include Android release", snap.has("androidRelease"))
        assertTrue("must include device manufacturer", snap.has("deviceManufacturer"))
        assertTrue("must include device model", snap.has("deviceModel"))
        assertTrue("must include app version name", snap.has("appVersionName"))
        assertTrue("must include app version code", snap.has("appVersionCode"))
        assertTrue("must include BT adapter availability flag", snap.has("btAdapterAvailable"))
        assertTrue("must include process uptime", snap.has("processUptimeMs"))
    }

    @Test
    fun collectOmitsLastSuccessfulSessionMsWhenStoreIsNull() {
        val snap = SystemSnapshot.collect(context, null)
        assertFalse(
            "without a store, the last-successful key should be absent",
            snap.has("lastSuccessfulSessionMs"),
        )
    }

    @Test
    fun collectFillsLastSuccessfulFromStore() {
        val store = SessionSummaryStore(summaryFile)
        store.recordStart(1_000L, "A", "AA")
        store.recordEnd(2_000L, SessionSummary.OUTCOME_FAILED, 0, "INSTANT_DROP")
        store.recordStart(3_000L, "A", "AA")
        store.recordEnd(4_000L, SessionSummary.OUTCOME_SUCCESS, 7, null)
        store.recordStart(5_000L, "A", "AA")
        store.recordEnd(6_000L, SessionSummary.OUTCOME_ABORTED, 0, null)

        val snap = SystemSnapshot.collect(context, store)
        assertNotNull(snap)
        assertEquals(
            "should resolve to the most recent SUCCESS, not the latest session of any kind",
            4_000L,
            snap.optLong("lastSuccessfulSessionMs"),
        )
    }

    @Test
    fun collectReturnsZeroLastSuccessfulWhenNoSuccess() {
        val store = SessionSummaryStore(summaryFile)
        store.recordStart(1L, "A", "AA")
        store.recordEnd(2L, SessionSummary.OUTCOME_FAILED, 0, "BT_OFF")

        val snap = SystemSnapshot.collect(context, store)
        assertEquals(0L, snap.optLong("lastSuccessfulSessionMs"))
    }

    @Test
    fun collectWithNullContextDoesNotThrow() {
        val snap = SystemSnapshot.collect(null, null)
        assertNotNull(snap)
        // Android version info is from Build.* statics, so always populated.
        assertTrue(snap.has("androidSdk"))
        // App version comes from the context — absent when context is null.
        assertFalse(snap.has("appVersionName"))
    }

    @Test
    fun collectMarksBluetoothIdentityReadableWhenConnectPermissionIsGranted() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        BluetoothAdapter.getDefaultAdapter()?.let { shadowOf(it).setEnabled(true) }

        val snap = SystemSnapshot.collect(context, null)

        assertTrue(snap.getBoolean("btAdapterAvailable"))
        assertTrue(snap.getBoolean("btIdentityReadable"))
        assertTrue(snap.has("btEnabled"))
    }
}
