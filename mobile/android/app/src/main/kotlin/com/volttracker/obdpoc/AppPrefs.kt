package com.volttracker.obdpoc

/**
 * Neutral home for the two app-wide constants the Activity, the [ObdService], the widget package, and
 * [EventNotificationPrefs] all share, so none of them has to reach into [MainActivity] for them
 * (which created incidental coupling — a widget-only change forcing a `MainActivity` recompile, and
 * the prefs-file name authored in two layers). See report item A3.
 *
 * [FILE] is the single `MODE_PRIVATE` SharedPreferences file the app multiplexes by key prefix
 * (widget snapshot, notification settings, auto-connect — see [com.volttracker.obdpoc.PrefsKeyOwnership]).
 * [LOG_TAG] is the shared logcat tag. [MainActivity] keeps deprecated aliases that forward here so the
 * existing `MainActivity.PREFS`/`MainActivity.TAG` call sites stay compiling.
 */
object AppPrefs {
    /** The shared `MODE_PRIVATE` SharedPreferences filename. */
    const val FILE = "volt_obd_prefs"

    /** The shared logcat tag used across the app's components. */
    const val LOG_TAG = "VoltTracker"
}
