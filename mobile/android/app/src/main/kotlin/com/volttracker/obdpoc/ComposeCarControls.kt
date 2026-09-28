package com.volttracker.obdpoc

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.util.Log
import com.volttracker.obdpoc.ui.live.LiveUiStateStore
import org.json.JSONObject

/**
 * The Car tab's car controls. Real commands go through the same [CarControlHostDelegate] the
 * classic dashboard uses: its native opt-in (warning + PIN), per-command confirmation and PIN, and
 * live-session check, then the engine's own gate (OBDLink, parked, fresh data, rate limit). Nothing
 * here can skip any of it.
 *
 * The demo never reaches the delegate: like the classic dashboard, it asks in its own dialog and
 * records a simulated result, and nothing is sent to a car.
 */
class ComposeCarControls(
    private val activity: Activity,
    prefs: () -> SharedPreferences?,
    private val store: LiveUiStateStore,
    toast: (String) -> Unit,
    /** Builds the host delegate around the callback it runs after each change (a seam for tests). */
    delegateFactory: (publishAppState: () -> Unit) -> CarControlCommands = { publish ->
        CarControlHostDelegate(activity = activity, prefs = prefs, publishAppState = publish, toast = toast)
    },
) {
    private val delegate: CarControlCommands = delegateFactory { refresh() }

    /** Re-reads the opt-in and PIN lockout into the store (on resume, and after every change). */
    fun refresh() {
        store.onCarControlState(JSONObject(delegate.getCarControlStateJson()))
    }

    fun request(command: String) {
        if (store.state.value.settings.demoActive) {
            confirmDemo(command)
        } else {
            delegate.requestCarControl(command)
        }
    }

    fun setEnabled(enabled: Boolean) {
        delegate.setCarControlsEnabled(enabled)
        refresh()
    }

    private fun confirmDemo(command: String) {
        val label = CarCommand.fromWireName(command)?.label ?: return
        try {
            AlertDialog
                .Builder(activity)
                .setTitle("$label?")
                .setMessage(DEMO_MESSAGE)
                .setPositiveButton(label) { _, _ -> store.onDemoCarControl(command) }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        } catch (ex: RuntimeException) {
            // The activity can be finishing between the tap and the dialog.
            Log.w(AppPrefs.LOG_TAG, "demo car-control dialog failed", ex)
        }
    }

    private companion object {
        const val DEMO_MESSAGE = "Demo / Testing: this is simulated. Nothing is sent to a car."
    }
}
