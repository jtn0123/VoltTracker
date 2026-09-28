package com.volttracker.obdpoc.widget

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Pins [WidgetSnapshotStore] defaults, round-trip persistence, and the change-debounced write. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetSnapshotStoreTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var store: WidgetSnapshotStore

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("widget-store-test", Context.MODE_PRIVATE)
        prefs.edit { clear() }
        store = WidgetSnapshotStore(prefs)
    }

    @Test
    fun readReturnsEmptyWhenNothingPersisted() {
        val snapshot = store.read()
        assertEquals(WidgetSnapshot.EMPTY, snapshot)
        assertFalse(snapshot.hasData())
    }

    @Test
    fun writeThenReadRoundTrips() {
        val written =
            WidgetSnapshot(
                72,
                charging = true,
                connected = true,
                vehicleState = "charging",
                updatedAtMs = 123L,
                lastSampleAtMs = 456L,
            )
        assertTrue(store.writeIfChanged(written))

        val reloaded = WidgetSnapshotStore(prefs).read()
        assertEquals(72, reloaded.socPct)
        assertTrue(reloaded.charging)
        assertTrue(reloaded.connected)
        assertEquals("charging", reloaded.vehicleState)
        assertEquals(123L, reloaded.updatedAtMs)
        assertEquals(456L, reloaded.lastSampleAtMs)
        assertTrue(reloaded.hasData())
    }

    @Test
    fun writeIfChangedDebouncesIdenticalDisplayFields() {
        val first =
            WidgetSnapshot(
                50,
                charging = false,
                connected = true,
                vehicleState = "driving_ev",
                updatedAtMs = 1_000L,
                lastSampleAtMs = 1_000L,
            )
        assertTrue("first write happens", store.writeIfChanged(first))

        // Same display fields, a fresh sample arrives later — must NOT request a redraw.
        val sameButLater = first.copy(updatedAtMs = 2_000L, lastSampleAtMs = 2_000L)
        assertFalse("identical display fields are debounced (no redraw)", store.writeIfChanged(sameButLater))

        // The display CHANGE time stays at the first value (no display change happened) ...
        assertEquals(1_000L, store.read().updatedAtMs)
    }

    @Test
    fun identicalSampleBumpsFreshnessAndRequestsAPeriodicFreshnessRedraw() {
        // B7: a steady (flat-but-live) sample must keep the freshness ticking even though the display
        // is unchanged and no redraw is requested, so the widget is not wrongly flagged stale.
        val first =
            WidgetSnapshot(
                50,
                charging = true,
                connected = true,
                vehicleState = "charging",
                updatedAtMs = 1_000L,
                lastSampleAtMs = 1_000L,
            )
        assertTrue(store.writeIfChanged(first))

        val laterIdenticalSample = first.copy(updatedAtMs = 2_000L, lastSampleAtMs = 900_000L)
        assertTrue("freshness text is redrawn after the refresh interval", store.writeIfChanged(laterIdenticalSample))

        val reloaded = store.read()
        assertEquals("change time stays put (no display change)", 1_000L, reloaded.updatedAtMs)
        assertEquals("freshness advances to the latest sample", 900_000L, reloaded.lastSampleAtMs)
    }

    @Test
    fun identicalSampleWithinFreshnessIntervalDoesNotRedraw() {
        val first =
            WidgetSnapshot(
                50,
                charging = true,
                connected = true,
                vehicleState = "charging",
                updatedAtMs = 1_000L,
                lastSampleAtMs = 1_000L,
            )
        assertTrue(store.writeIfChanged(first))

        assertFalse(
            "the 1 Hz telemetry stream must not redraw the widget every sample",
            store.writeIfChanged(first.copy(updatedAtMs = 30_000L, lastSampleAtMs = 30_000L)),
        )
    }

    @Test
    fun socChangeIsHeldInMemoryAndPersistedOnTheNextThrottleWindow() {
        val first = WidgetSnapshot(50, charging = false, connected = true, vehicleState = "", updatedAtMs = 1_000L)
        assertTrue(store.writeIfChanged(first))

        val socChanged = WidgetSnapshot(49, charging = false, connected = true, vehicleState = "", updatedAtMs = 2_000L)
        assertFalse("an SOC tick inside the throttle window is not written", store.writeIfChanged(socChanged))
        assertEquals("the in-memory snapshot has the new SOC", 49, store.read().socPct)
        assertEquals("disk still holds the last persisted SOC", 50, store.readPersisted().socPct)

        val nextWindow = socChanged.copy(updatedAtMs = 31_000L, lastSampleAtMs = 31_000L)
        assertTrue(
            "the held SOC change is persisted and redrawn once the window opens",
            store.writeIfChanged(nextWindow),
        )
        val persisted = WidgetSnapshotStore(prefs).read()
        assertEquals(49, persisted.socPct)
        assertEquals("the change time is when the SOC changed", 2_000L, persisted.updatedAtMs)
        assertEquals("the freshness clock carries the latest sample", 31_000L, persisted.lastSampleAtMs)
    }

    @Test
    fun aOneHertzStreamWritesPrefsAtMostOncePerThrottleWindow() {
        // The share-sheet ANR: every apply() rewrites volt_obd_prefs.xml and service starts / activity
        // stops wait for it. A 10-minute 1 Hz stream (SOC drifting every sample) must not write per sample.
        var writes = 0
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == "widget_snapshot_last_sample_at") writes++
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        val samples = 600
        for (i in 0 until samples) {
            val at = 1_000L + i * 1_000L
            store.writeIfChanged(
                WidgetSnapshot(
                    socPct = 80 - (i % 7),
                    charging = false,
                    connected = true,
                    vehicleState = "driving_ev",
                    updatedAtMs = at,
                    lastSampleAtMs = at,
                ),
            )
        }
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

        val window = WidgetSnapshotStore.MIN_PERSIST_INTERVAL_MS / 1_000L
        assertTrue("expected about ${samples / window} writes, got $writes", writes <= samples / window + 1)
        assertTrue("the stream must still reach disk periodically, got $writes", writes >= samples / window - 1)
    }

    @Test
    fun connectionDropIsPersistedImmediatelyWithTheLatestHeldSoc() {
        val first = WidgetSnapshot(50, charging = false, connected = true, vehicleState = "", updatedAtMs = 1_000L)
        assertTrue(store.writeIfChanged(first))
        assertFalse(store.writeIfChanged(first.copy(socPct = 47, updatedAtMs = 5_000L, lastSampleAtMs = 5_000L)))

        // Session end: the disconnect status must flush the held SOC along with the connection flag.
        assertTrue(store.writeIfChanged(store.read().copy(connected = false, updatedAtMs = 6_000L)))
        val persisted = store.readPersisted()
        assertFalse(persisted.connected)
        assertEquals(47, persisted.socPct)
        assertEquals("a status write never advances the sample clock", 5_000L, persisted.lastSampleAtMs)
    }

    @Test
    fun aWallClockStepBackwardsDoesNotStallPersistence() {
        val first = WidgetSnapshot(50, charging = false, connected = true, vehicleState = "", updatedAtMs = 100_000L)
        assertTrue(store.writeIfChanged(first))

        val afterClockReset = first.copy(socPct = 48, updatedAtMs = 5_000L, lastSampleAtMs = 5_000L)
        assertTrue(store.writeIfChanged(afterClockReset))
        assertEquals(48, store.readPersisted().socPct)
    }

    @Test
    fun aZeroIntervalStorePersistsEveryDisplayChange() {
        val unthrottled = WidgetSnapshotStore(prefs, minPersistIntervalMs = 0L)
        val first = WidgetSnapshot(50, charging = false, connected = true, vehicleState = "", updatedAtMs = 1_000L)
        assertTrue(unthrottled.writeIfChanged(first))
        assertTrue(unthrottled.writeIfChanged(first.copy(socPct = 49, updatedAtMs = 2_000L)))
        assertEquals(49, unthrottled.readPersisted().socPct)
    }

    @Test
    fun writeIfChangedWritesWhenChargingChanges() {
        val first = WidgetSnapshot(50, charging = false, connected = true, vehicleState = "", updatedAtMs = 1_000L)
        assertTrue(store.writeIfChanged(first))
        val chargingChanged = first.copy(charging = true, updatedAtMs = 2_000L)
        assertTrue(store.writeIfChanged(chargingChanged))
        assertTrue(store.read().charging)
    }

    @Test
    fun readRecoversFromTypeCorruptedKey() {
        // Simulate a key written with the wrong type; read() must fall back, not throw.
        prefs.edit {
            putString("widget_snapshot_updated_at", "not-a-long")
            putInt("widget_snapshot_soc", 60)
        }
        val snapshot = store.read()
        assertEquals(WidgetSnapshot.EMPTY, snapshot)
    }
}
