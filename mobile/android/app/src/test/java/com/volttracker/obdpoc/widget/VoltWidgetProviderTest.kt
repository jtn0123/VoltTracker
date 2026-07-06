package com.volttracker.obdpoc.widget

import android.content.Context
import android.view.View
import android.widget.TextView
import com.volttracker.obdpoc.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Covers the RemoteViews binding half of report item D1: that [VoltWidgetProvider.buildViews] binds
 * the SOC / status / freshness text the pure [WidgetStateFormatter] decides (mapped through the real
 * `strings.xml`), so a swapped field or wrong resource id renders a blank/stale widget. Robolectric
 * 4.16.1 ships no `ShadowRemoteViews`, so the binding is verified by inflating the RemoteViews into a
 * real view tree (`apply`) and reading the bound [TextView]s — the same text the widget shows
 * on-screen. [onUpdate] is exercised end-to-end via [org.robolectric.shadows.ShadowAppWidgetManager].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoltWidgetProviderTest {
    private lateinit var context: Context
    private val nowMs = System.currentTimeMillis()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    /** Inflates a RemoteViews into a real view tree so the bound text is readable. */
    private fun bind(snapshot: WidgetSnapshot): RenderedWidget {
        val views = VoltWidgetProvider.buildViews(context, snapshot)
        val rendered = views.apply(context, null)
        return RenderedWidget(
            soc = textOf(rendered, R.id.widget_soc),
            status = textOf(rendered, R.id.widget_status),
            updated = textOf(rendered, R.id.widget_updated),
            contentDescription = rendered.contentDescription?.toString() ?: "",
        )
    }

    private fun textOf(
        root: View,
        id: Int,
    ): String = (root.findViewById<TextView>(id)).text?.toString() ?: ""

    @Test
    fun buildViewsBindsSocStatusAndFreshnessForAConnectedCharge() {
        val snapshot =
            WidgetSnapshot(
                socPct = 73,
                charging = true,
                connected = true,
                vehicleState = "charging",
                updatedAtMs = nowMs,
                lastSampleAtMs = nowMs,
            )
        val rendered = bind(snapshot)

        assertEquals("SOC binds the formatter's numeric percent", "73%", rendered.soc)
        assertEquals(
            "a charging snapshot binds the charging status string",
            context.getString(R.string.widget_status_charging),
            rendered.status,
        )
        assertEquals(
            "a just-arrived sample binds the just-now freshness string",
            context.getString(R.string.widget_updated_just_now),
            rendered.updated,
        )
    }

    @Test
    fun buildViewsBindsConnectedStatusWhenNotCharging() {
        val snapshot =
            WidgetSnapshot(
                socPct = 50,
                charging = false,
                connected = true,
                vehicleState = "driving_ev",
                updatedAtMs = nowMs,
                lastSampleAtMs = nowMs,
            )
        val rendered = bind(snapshot)

        assertEquals("50%", rendered.soc)
        assertEquals(context.getString(R.string.widget_status_connected), rendered.status)
    }

    @Test
    fun buildViewsBindsDisconnectedStatusWhenLinkDown() {
        val snapshot =
            WidgetSnapshot(
                socPct = 40,
                charging = false,
                connected = false,
                vehicleState = "parked",
                updatedAtMs = nowMs,
                lastSampleAtMs = nowMs,
            )
        assertEquals(context.getString(R.string.widget_status_disconnected), bind(snapshot).status)
    }

    @Test
    fun buildViewsBindsEmptyStateForNeverWrittenSnapshot() {
        val rendered = bind(WidgetSnapshot.EMPTY)

        assertEquals("an unknown SOC binds the em-dash placeholder", "—", rendered.soc)
        assertEquals(context.getString(R.string.widget_status_no_data), rendered.status)
        assertEquals("the no-data state has no freshness line", "", rendered.updated)
    }

    @Test
    fun buildViewsMarksAStaleSnapshotInTheFreshnessLine() {
        val staleAgeMs = WidgetStateFormatter.STALE_AFTER_MS + 5L * 60L * 1000L
        val snapshot =
            WidgetSnapshot(
                socPct = 60,
                charging = false,
                connected = true,
                vehicleState = "parked",
                updatedAtMs = nowMs - staleAgeMs,
                lastSampleAtMs = nowMs - staleAgeMs,
            )
        val updated = bind(snapshot).updated

        assertTrue("a stale snapshot's freshness line carries the (stale) marker", updated.contains("stale"))
        assertTrue("a >15m-old sample reports minutes elapsed", updated.contains("m ago"))
    }

    @Test
    fun onUpdateBindsTheStoredSnapshotIntoAPlacedWidget() {
        // Persist a snapshot the provider will read out-of-process, then place a widget — which drives
        // VoltWidgetProvider.onUpdate end-to-end through the real AppWidgetManager.
        WidgetSnapshotStore(context).writeIfChanged(
            WidgetSnapshot(
                socPct = 81,
                charging = true,
                connected = true,
                vehicleState = "charging",
                updatedAtMs = System.currentTimeMillis(),
                lastSampleAtMs = System.currentTimeMillis(),
            ),
        )

        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val shadow = shadowOf(manager)
        val widgetId = shadow.createWidget(VoltWidgetProvider::class.java, R.layout.widget_volt)

        val view = shadow.getViewFor(widgetId)
        assertEquals("81%", textOf(view, R.id.widget_soc))
        assertEquals(
            context.getString(R.string.widget_status_charging),
            textOf(view, R.id.widget_status),
        )
    }

    @Test
    fun contentDescriptionForEmptyStateOmitsEmDashAndTrailingClause() {
        val description = bind(WidgetSnapshot.EMPTY).contentDescription

        assertFalse(
            "the unknown-SOC content description must not speak the em-dash placeholder",
            description.contains("—"),
        )
        assertEquals(
            "no dangling trailing clause/whitespace when there is no freshness line",
            description,
            description.trim(),
        )
        assertTrue(
            "the empty-state description leads with the widget title",
            description.startsWith(context.getString(R.string.widget_title)),
        )
        assertTrue(
            "the empty-state description conveys the no-data status",
            description.contains(context.getString(R.string.widget_status_no_data)),
        )
    }

    @Test
    fun contentDescriptionForAConnectedChargeSpeaksSocStatusAndFreshness() {
        val snapshot =
            WidgetSnapshot(
                socPct = 73,
                charging = true,
                connected = true,
                vehicleState = "charging",
                updatedAtMs = nowMs,
                lastSampleAtMs = nowMs,
            )
        val description = bind(snapshot).contentDescription

        assertTrue("the known-SOC description speaks the percent", description.contains("73%"))
        assertTrue(description.contains(context.getString(R.string.widget_status_charging)))
        assertTrue(description.contains(context.getString(R.string.widget_updated_just_now)))
    }

    private data class RenderedWidget(
        val soc: String,
        val status: String,
        val updated: String,
        val contentDescription: String,
    )
}
