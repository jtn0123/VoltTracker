package com.volttracker.obdpoc.widget

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

/**
 * Covers the read -> merge -> writeIfChanged orchestration in [WidgetUpdater] (report item D1) over a
 * real [WidgetSnapshotStore], the surface where a swapped/dropped field silently renders a wrong
 * widget. Pins the field-merge contract (telemetry supplies SOC/charging/state, status supplies the
 * connection flag, each preserving the other), the change-debounced redraw, and the crash-swallowing
 * of a null/garbage payload.
 *
 * The redraw side effect (`requestRefresh`) is a no-op here — no widget is placed in the test runtime,
 * so [VoltWidgetProvider.refreshAll] short-circuits — which lets the merge/persist logic be asserted
 * purely through what lands in the store. [VoltWidgetProviderTest] covers the RemoteViews binding.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetUpdaterTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var store: WidgetSnapshotStore
    private lateinit var updater: WidgetUpdater

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("widget-updater-test", Context.MODE_PRIVATE)
        prefs.edit { clear() }
        store = WidgetSnapshotStore(prefs)
        updater = WidgetUpdater(context, store)
    }

    @Test
    fun telemetryThenStatusPreservesSocWhileUpdatingConnection() {
        // A telemetry sample establishes SOC + charging while connected.
        updater.onTelemetry(
            JSONObject()
                .put("soc", 64.0)
                .put("vehicleState", "charging")
                .put("connected", true),
        )
        val afterTelemetry = store.read()
        assertEquals(64, afterTelemetry.socPct)
        assertTrue(afterTelemetry.charging)
        assertTrue(afterTelemetry.connected)

        // A status-only update flips the connection flag but must NOT clobber the merged SOC/state.
        updater.onStatus("idle")
        val afterStatus = store.read()
        assertEquals("status must preserve the last telemetry SOC", 64, afterStatus.socPct)
        assertEquals("status must preserve the last vehicle state", "charging", afterStatus.vehicleState)
        assertTrue("the charging flag derived from telemetry survives", afterStatus.charging)
        assertFalse("an idle status state marks the link disconnected", afterStatus.connected)
    }

    @Test
    fun statusOnlyUpdatePreservesLastSocWhenNoTelemetryYet() {
        // Seed a known SOC via telemetry.
        updater.onTelemetry(JSONObject().put("soc", 88.0).put("vehicleState", "driving_ev"))
        assertEquals(88, store.read().socPct)

        // A connected status arriving with no SOC of its own keeps the last known SOC.
        updater.onStatus("connected")
        val merged = store.read()
        assertEquals("a status update never blanks the SOC", 88, merged.socPct)
        assertTrue(merged.connected)
    }

    @Test
    fun roundsSocRatherThanTruncating() {
        // 82.9 must round to 83 (matching the dashboard), not truncate to 82 (the old optInt bug, B5).
        updater.onTelemetry(JSONObject().put("soc", 82.9).put("connected", true))
        assertEquals(83, store.read().socPct)
    }

    @Test
    fun identicalSampleDoesNotRedrawButStillBumpsFreshness() {
        updater.onTelemetry(JSONObject().put("soc", 50.0).put("vehicleState", "charging").put("connected", true))
        val firstChangeAt = store.read().updatedAtMs
        val firstSampleAt = store.read().lastSampleAtMs

        // Sleep so the second sample's wall-clock "now" is strictly later than the first.
        sleepAtLeast1Ms()

        // An identical-display sample arrives: the change time must stay put (debounced redraw) ...
        updater.onTelemetry(JSONObject().put("soc", 50.0).put("vehicleState", "charging").put("connected", true))
        val afterIdentical = store.read()
        assertEquals(
            "an identical display must not bump the change time (no redraw)",
            firstChangeAt,
            afterIdentical.updatedAtMs,
        )
        // ... but the freshness sample time must advance so a flat-but-live charge isn't flagged stale (B7).
        assertTrue(
            "an identical sample still advances the freshness timestamp",
            afterIdentical.lastSampleAtMs > firstSampleAt,
        )
    }

    @Test
    fun changedSocBumpsTheChangeTimeAndRedraws() {
        updater.onTelemetry(JSONObject().put("soc", 50.0).put("connected", true))
        val firstChangeAt = store.read().updatedAtMs
        sleepAtLeast1Ms()

        updater.onTelemetry(JSONObject().put("soc", 49.0).put("connected", true))
        val after = store.read()
        assertEquals("a changed SOC is persisted", 49, after.socPct)
        assertTrue("a display change advances the change time", after.updatedAtMs > firstChangeAt)
    }

    @Test
    fun nullTelemetryPayloadIsSwallowedAndLeavesStoreUntouched() {
        updater.onTelemetry(JSONObject().put("soc", 70.0).put("connected", true))
        val before = store.read()

        // A null payload must be a no-op, never a throw, and must not disturb the stored snapshot.
        updater.onTelemetry(null)
        assertEquals(before, store.read())
    }

    @Test
    fun garbagePayloadIsSwallowedWithoutCorruptingTheSnapshot() {
        updater.onTelemetry(JSONObject().put("soc", 70.0).put("vehicleState", "driving_ev").put("connected", true))
        val before = store.read()

        // A non-numeric SOC and a non-boolean "connected" (the shape a corrupt/garbage bridge payload
        // takes) must not throw and must not blank the last good SOC: optDouble yields NaN, which
        // roundSoc maps back to the previously stored value. Parsed from a raw string because org.json
        // rejects a literal Double.NaN at put() time.
        updater.onTelemetry(JSONObject("""{"soc":"not-a-number","connected":"not-a-bool"}"""))
        val after = store.read()
        assertEquals("a garbage SOC keeps the last good value, never blanks it", before.socPct, after.socPct)
        assertTrue("the snapshot is still intact after a garbage sample", after.hasData())
    }

    @Test
    fun nullStatusStateIsSwallowedAndMarksDisconnected() {
        updater.onTelemetry(JSONObject().put("soc", 42.0).put("connected", true))

        // A null status string must not throw; isConnectedState(null) is false, so it marks the link down
        // while preserving the SOC.
        updater.onStatus(null)
        val after = store.read()
        assertEquals(42, after.socPct)
        assertFalse(after.connected)
    }

    @Test
    fun statusUpdateNeverAdvancesFreshnessPastLastTelemetrySample() {
        // Telemetry establishes a real last-sample time (the freshness/stale reference).
        updater.onTelemetry(JSONObject().put("soc", 60.0).put("vehicleState", "charging").put("connected", true))
        val sampleAt = store.read().lastSampleAtMs
        sleepAtLeast1Ms()

        // Link drops mid-charge: the status update flips the connection flag, but freshness must stay
        // pinned to the last real telemetry sample. Bumping it here would reset a frozen SOC to "just
        // now" and stop it ever aging out to stale (Rank 8).
        updater.onStatus("idle")
        val afterDisconnect = store.read()
        assertFalse("an idle status marks the link down", afterDisconnect.connected)
        assertEquals(
            "a status transition must not advance the freshness timestamp",
            sampleAt,
            afterDisconnect.lastSampleAtMs,
        )

        // A subsequent stream of "connected" pings must likewise leave freshness untouched.
        sleepAtLeast1Ms()
        updater.onStatus("connected")
        assertEquals(
            "repeated status pings must not keep resetting freshness",
            sampleAt,
            store.read().lastSampleAtMs,
        )
    }

    private fun sleepAtLeast1Ms() {
        val start = System.currentTimeMillis()
        do {
            Thread.sleep(2)
        } while (System.currentTimeMillis() <= start)
    }
}
