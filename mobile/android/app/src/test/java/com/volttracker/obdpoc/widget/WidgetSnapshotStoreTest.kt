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
            WidgetSnapshot(72, charging = true, connected = true, vehicleState = "charging", updatedAtMs = 123L)
        assertTrue(store.writeIfChanged(written))

        val reloaded = WidgetSnapshotStore(prefs).read()
        assertEquals(72, reloaded.socPct)
        assertTrue(reloaded.charging)
        assertTrue(reloaded.connected)
        assertEquals("charging", reloaded.vehicleState)
        assertEquals(123L, reloaded.updatedAtMs)
        assertTrue(reloaded.hasData())
    }

    @Test
    fun writeIfChangedDebouncesIdenticalDisplayFields() {
        val first =
            WidgetSnapshot(50, charging = false, connected = true, vehicleState = "driving_ev", updatedAtMs = 1_000L)
        assertTrue("first write happens", store.writeIfChanged(first))

        // Same display fields, only the timestamp moved — must NOT write again.
        val sameButLater = first.copy(updatedAtMs = 2_000L)
        assertFalse("timestamp-only change is debounced", store.writeIfChanged(sameButLater))

        // The stored timestamp stays at the first value because the second write was skipped.
        assertEquals(1_000L, store.read().updatedAtMs)
    }

    @Test
    fun writeIfChangedWritesWhenSocChanges() {
        val first = WidgetSnapshot(50, charging = false, connected = true, vehicleState = "", updatedAtMs = 1_000L)
        assertTrue(store.writeIfChanged(first))

        val socChanged = WidgetSnapshot(49, charging = false, connected = true, vehicleState = "", updatedAtMs = 2_000L)
        assertTrue(store.writeIfChanged(socChanged))
        assertEquals(49, store.read().socPct)
        assertEquals(2_000L, store.read().updatedAtMs)
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
