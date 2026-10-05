package com.volttracker.obdpoc.ui.components

import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * App-wide display preferences every screen honours without threading them through each UI
 * state: Settings → Quiet live data, the phone's "remove animations" and 24-hour clock settings,
 * and whether the Demo / Testing stream is what's on screen.
 */
data class VoltPrefs(
    /** Settings → Quiet live data: no spoken announcements for mode and phase changes. */
    val quietLiveData: Boolean = true,
    /** The phone's animator duration scale is 0 ("Remove animations"): nothing loops. */
    val reduceMotion: Boolean = false,
    /** The phone's 24-hour clock setting: "23:08" instead of "11:08 PM". */
    val clock24h: Boolean = false,
    /** The Demo / Testing stream is running: every header says so. */
    val demo: Boolean = false,
)

val LocalVoltPrefs = compositionLocalOf { VoltPrefs() }

/** Re-reads the phone's motion and clock settings when this host resumes. */
@Composable
fun rememberSystemPrefs(
    quietLiveData: Boolean,
    demo: Boolean,
): VoltPrefs {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val read = {
        val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        VoltPrefs(reduceMotion = scale == 0f, clock24h = DateFormat.is24HourFormat(context))
    }
    var system by
        remember(context) {
            mutableStateOf(read())
        }
    DisposableEffect(owner, context) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) system = read()
            }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return remember(system, quietLiveData, demo) { system.copy(quietLiveData = quietLiveData, demo = demo) }
}

/**
 * Marks a mode or phase change for TalkBack to announce politely, unless Quiet live data is on.
 * The value itself still reads on focus either way.
 */
fun Modifier.announceChanges(prefs: VoltPrefs): Modifier =
    if (prefs.quietLiveData) this else semantics { liveRegion = LiveRegionMode.Polite }
