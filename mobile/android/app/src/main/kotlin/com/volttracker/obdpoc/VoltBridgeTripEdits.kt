package com.volttracker.obdpoc

import android.util.Log
import com.volttracker.obdpoc.data.ObdTripLabels
import org.json.JSONObject

/**
 * Trip-edit bridge implementation: hide ("not a trip") / restore, user labels, and favorites.
 * Every edit marshals to the background, persists through the local store, then refreshes the
 * dashboard's storage summary. [VoltBridge] owns the `@JavascriptInterface` wrappers; this class
 * is plumbing only.
 */
internal class VoltBridgeTripEdits(
    private val activity: DashboardHost,
) {
    fun markTripNotTrip(routeKey: String?) {
        val cleanRouteKey = bridgeSafe(routeKey, BRIDGE_MAX_LABEL_LEN)
        if (cleanRouteKey.isEmpty()) {
            activity.runOnUiThread {
                activity.publishStatus("blocked", "Choose a stored map trip to mark as not a trip.", true)
            }
            return
        }
        activity.confirmBridgeAction(
            "Mark as not a trip?",
            "This hides the selected route from Maps and Trips. Raw samples stay on the phone for diagnostics and backups.",
            "Mark not trip",
        ) {
            markTripNotTripConfirmed(cleanRouteKey)
        }
    }

    fun restoreTrip(routeKey: String?) {
        val cleanRouteKey = bridgeSafe(routeKey, BRIDGE_MAX_LABEL_LEN)
        if (cleanRouteKey.isEmpty()) {
            activity.runOnUiThread {
                activity.publishActionConfirmation("blocked", "Choose a hidden trip to restore.", true)
            }
            return
        }
        activity.runOnBackground {
            val changed =
                try {
                    activity.localStore?.setTripHidden(cleanRouteKey, false) == true
                } catch (ex: RuntimeException) {
                    Log.w(AppPrefs.LOG_TAG, "restoreTrip failed", ex)
                    false
                }
            activity.runOnUiThread {
                activity.publishStorageSummary()
                activity.publishActionConfirmation(
                    if (changed) "ready" else "blocked",
                    if (changed) "Drive restored." else "That drive could not be restored.",
                    !changed,
                )
            }
        }
    }

    /**
     * Sets (or clears, when [label] is blank) the user label for the trip identified by [routeKey]
     * (M4). Marshals to the background, persists, then refreshes the dashboard's trip data so the
     * label shows immediately. Mirrors [markTripNotTrip]'s threading without the confirm dialog —
     * naming a trip is reversible and low-stakes.
     */
    fun setTripLabel(
        routeKey: String?,
        label: String?,
    ) {
        val cleanRouteKey = bridgeSafe(routeKey, BRIDGE_MAX_LABEL_LEN)
        if (cleanRouteKey.isEmpty()) {
            activity.runOnUiThread {
                activity.publishStatus("blocked", "Choose a stored trip to rename.", true)
            }
            return
        }
        // The label cap is owned by the data layer (ObdTripLabels re-truncates to the same length on
        // store), so reference that single constant rather than a second, larger bridge cap that the
        // storage would silently clip — keeping one cap for the trip-label path.
        val cleanLabel = bridgeSafe(label, ObdTripLabels.MAX_LABEL_LEN)
        activity.runOnBackground {
            val changed =
                try {
                    activity.localStore?.setTripLabel(cleanRouteKey, cleanLabel) == true
                } catch (ex: RuntimeException) {
                    Log.w(AppPrefs.LOG_TAG, "setTripLabel failed", ex)
                    false
                }
            activity.runOnUiThread {
                activity.publishStorageSummary()
                activity.publishActionConfirmation(
                    if (changed) "ready" else "blocked",
                    when {
                        !changed -> "That trip could not be renamed."
                        cleanLabel.isEmpty() -> "Trip label cleared."
                        else -> "Trip renamed."
                    },
                    !changed,
                )
            }
        }
    }

    /**
     * Sets or clears the user "favorite" flag for the trip identified by [routeKey] (M4 favorites
     * half). Marshals to the background, persists as a route-key-keyed status event (no schema
     * change), then refreshes the dashboard so the star state shows immediately. Mirrors
     * [setTripLabel]'s threading without a confirm dialog — favoriting is reversible and low-stakes.
     */
    fun setTripFavorite(
        routeKey: String?,
        favorite: Boolean,
    ) {
        val cleanRouteKey = bridgeSafe(routeKey, BRIDGE_MAX_LABEL_LEN)
        if (cleanRouteKey.isEmpty()) {
            activity.runOnUiThread {
                activity.publishStatus("blocked", "Choose a stored trip to favorite.", true)
            }
            return
        }
        activity.runOnBackground {
            val changed =
                try {
                    activity.localStore?.setTripFavorite(cleanRouteKey, favorite) == true
                } catch (ex: RuntimeException) {
                    Log.w(AppPrefs.LOG_TAG, "setTripFavorite failed", ex)
                    false
                }
            activity.runOnUiThread {
                activity.publishStorageSummary()
                activity.publishActionConfirmation(
                    if (changed) "ready" else "blocked",
                    when {
                        !changed -> "That trip could not be updated."
                        favorite -> "Trip added to favorites."
                        else -> "Trip removed from favorites."
                    },
                    !changed,
                )
            }
        }
    }

    private fun markTripNotTripConfirmed(routeKey: String) {
        activity.runOnBackground {
            val changed =
                try {
                    activity.localStore?.setTripHidden(routeKey, true) == true
                } catch (ex: RuntimeException) {
                    Log.w(AppPrefs.LOG_TAG, "markTripNotTrip failed", ex)
                    false
                }
            activity.runOnUiThread {
                activity.publishStorageSummary()
                if (changed) {
                    activity.publishDashboardPayload(
                        "showTripUndo",
                        JSONObject().put("routeKey", routeKey).toString(),
                    )
                }
                activity.publishActionConfirmation(
                    if (changed) "ready" else "blocked",
                    if (changed) {
                        "Trip marked as not a trip. Raw data was kept."
                    } else {
                        "That map row could not be marked as not a trip."
                    },
                    !changed,
                )
            }
        }
    }
}
