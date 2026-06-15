package com.volttracker.obdpoc

/**
 * Single source of truth for how the one shared [AppPrefs.FILE] SharedPreferences file is partitioned
 * between subsystems. Several independent features multiplex that file by an unenforced key-prefix
 * convention (the widget snapshot, the event-notification settings, auto-scan bookkeeping, and the
 * auto-connect toggle); a collision would let one feature silently read/clobber another's value (the
 * defensive ClassCastException swallow in `WidgetSnapshotStore.read()` is evidence the risk is real).
 *
 * Declaring the owners here — and pinning their disjointness in `PrefsKeyOwnershipTest` — documents
 * the ownership in one place and turns "two features picked the same key" from a runtime data bug
 * into a failing unit test. (Report item A4.)
 */
object PrefsKeyOwnership {
    /** A subsystem's slice of the shared prefs file: a label and the key prefix(es) it owns. */
    data class Owner(
        val name: String,
        val prefixes: List<String>,
    )

    val OWNERS: List<Owner> =
        listOf(
            Owner("widget-snapshot", listOf("widget_snapshot_")),
            // The event-notification settings + scan bookkeeping (notify_*) plus the on-connect
            // auto-scan throttle/toggle (auto_scan_*).
            Owner("event-notifications", listOf("notify_", "auto_scan_")),
            Owner("auto-connect", listOf("auto_connect_")),
            // Bare activity-owned keys with no shared prefix (kept explicit so the test catches a
            // future bare key that accidentally collides with one of the prefixed namespaces).
            Owner("activity-misc", listOf("raw_retention_days")),
        )
}
