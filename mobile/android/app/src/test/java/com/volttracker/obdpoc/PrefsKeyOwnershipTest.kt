package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the [PrefsKeyOwnership] invariant for the single shared [AppPrefs.FILE] prefs file (report
 * item A4): the subsystem namespaces are disjoint, and every real key in use belongs to exactly one
 * owner. This turns "two features picked the same key on the shared file" into a failing unit test
 * rather than a silent runtime read/clobber.
 */
class PrefsKeyOwnershipTest {
    @Test
    fun ownerPrefixesAreDisjointNoneIsAPrefixOfAnother() {
        val labelled =
            PrefsKeyOwnership.OWNERS.flatMap { owner -> owner.prefixes.map { it to owner.name } }
        for ((prefixA, ownerA) in labelled) {
            for ((prefixB, ownerB) in labelled) {
                if (ownerA == ownerB && prefixA == prefixB) {
                    continue
                }
                assertFalse(
                    "prefix \"$prefixA\" ($ownerA) overlaps \"$prefixB\" ($ownerB) — shared-file namespaces must be disjoint",
                    prefixA.startsWith(prefixB) || prefixB.startsWith(prefixA),
                )
            }
        }
    }

    @Test
    fun everyRealKeyInUseBelongsToExactlyOneOwner() {
        // The actual keys persisted to the shared file. Widget keys are private to WidgetSnapshotStore,
        // so they are spelled out here; the notification + auto-connect keys reference their constants.
        val widgetKeys =
            listOf(
                "widget_snapshot_soc",
                "widget_snapshot_charging",
                "widget_snapshot_connected",
                "widget_snapshot_vehicle_state",
                "widget_snapshot_updated_at",
                "widget_snapshot_last_sample_at",
            )
        val notificationKeys =
            listOf(
                EventNotificationPrefs.PREF_CHARGE_COMPLETE_ENABLED,
                EventNotificationPrefs.PREF_NEW_DTC_ENABLED,
                EventNotificationPrefs.PREF_LOW_SOC_ENABLED,
                EventNotificationPrefs.PREF_LOW_SOC_THRESHOLD,
                EventNotificationPrefs.PREF_HIGH_TEMP_ENABLED,
                EventNotificationPrefs.PREF_HIGH_TEMP_THRESHOLD,
                EventNotificationPrefs.PREF_TARGET_SOC,
                EventNotificationPrefs.PREF_AUTO_SCAN_ENABLED,
                EventNotificationPrefs.PREF_LAST_AUTO_SCAN_MS,
                EventNotificationPrefs.PREF_LAST_SCAN_DTCS,
                EventNotificationPrefs.PREF_DTC_BASELINE_SET,
                EventNotificationPrefs.PREF_SETTINGS_VERSION,
            )
        val otherKeys =
            listOf(
                AutoConnectController.PREF_AUTO_CONNECT_ENABLED,
                "raw_retention_days",
            )

        val allKeys = widgetKeys + notificationKeys + otherKeys

        // No two distinct keys are equal (the obvious collision).
        assertEquals("all shared-file keys must be unique", allKeys.size, allKeys.toSet().size)

        // Each key maps to exactly one owner via its prefix.
        for (key in allKeys) {
            val owners =
                PrefsKeyOwnership.OWNERS.filter { owner -> owner.prefixes.any { key.startsWith(it) } }
            assertEquals(
                "key \"$key\" must belong to exactly one owner (got ${owners.map { it.name }})",
                1,
                owners.size,
            )
        }
    }

    @Test
    fun notificationAndAutoScanKeysStayInTheirDeclaredNamespaces() {
        // Guards the specific report concern: the auto-SCAN keys (auto_scan_*) must not drift into the
        // auto-CONNECT namespace (auto_connect_*), and vice-versa.
        assertTrue(EventNotificationPrefs.PREF_AUTO_SCAN_ENABLED.startsWith("auto_scan_"))
        assertTrue(EventNotificationPrefs.PREF_LAST_AUTO_SCAN_MS.startsWith("auto_scan_"))
        assertTrue(AutoConnectController.PREF_AUTO_CONNECT_ENABLED.startsWith("auto_connect_"))
        assertFalse(AutoConnectController.PREF_AUTO_CONNECT_ENABLED.startsWith("auto_scan_"))
    }
}
