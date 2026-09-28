package com.volttracker.obdpoc

import android.util.Log
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.ui.charge.CHARGE_HISTORY_LIMIT
import com.volttracker.obdpoc.ui.charge.ChargeHistory
import com.volttracker.obdpoc.ui.live.LiveUiStateStore
import com.volttracker.obdpoc.ui.trips.TRIP_HISTORY_LIMIT
import com.volttracker.obdpoc.ui.trips.TripHistory
import com.volttracker.obdpoc.ui.trips.TripMode
import com.volttracker.obdpoc.ui.trips.TripRoute
import com.volttracker.obdpoc.ui.trips.mode
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads the Compose tabs' history — logged charges, logged drives, the selected drive's route —
 * off the main thread, each on a short-lived store of its own (the data tools' store is only
 * open while one runs), and hands the results to [store] on the main thread. A read is skipped
 * while a backup or restore holds the database, or while the same read is still running.
 */
internal class ComposeHistoryLoader(
    private val executor: ExecutorService,
    private val store: LiveUiStateStore,
    private val post: (Runnable) -> Unit,
    openStore: () -> ObdLocalStore,
) {
    /** Seams so tests can serve canned rows without a database. */
    var chargeReader: () -> JSONArray = {
        openStore().use { it.projections().chargeSessionsForExport(CHARGE_HISTORY_LIMIT) }
    }
    var tripsReader: () -> JSONArray = { openStore().use { it.getTripsJson(TRIP_HISTORY_LIMIT) } }
    var routeReader: (String) -> Pair<JSONObject, JSONArray> = { key ->
        openStore().use { it.routes.getTripRouteJson(key) to it.routes.getTripDriveModesJson(key) }
    }

    private val chargesInFlight = AtomicBoolean(false)
    private val tripsInFlight = AtomicBoolean(false)
    private val routeInFlight = AtomicBoolean(false)

    fun loadCharges() {
        read(chargesInFlight, "charge history", chargeReader) { store.onChargeHistory(ChargeHistory.parse(it)) }
    }

    /** The drive list, then the selected drive's route. */
    fun loadTrips() {
        read(tripsInFlight, "trip history", tripsReader) {
            store.onTripHistory(TripHistory.parse(it))
            loadRoute()
        }
    }

    /**
     * The selected drive's route, EV / gas marked from its classified samples. A failed read
     * leaves an empty route (the map says so) rather than retrying forever; a drive picked while
     * this one was reading is read next.
     */
    fun loadRoute() {
        val key = store.tripRouteToRead() ?: return
        val fallbackGas =
            store.state.value.trips.trips
                .firstOrNull { it.routeKey == key }
                ?.mode == TripMode.GAS
        read(
            routeInFlight,
            "trip route",
            { routeReader(key) },
            onFailure = {
                store.onTripRoute(TripRoute(key, emptyList()))
                loadRoute()
            },
        ) { (route, modes) ->
            store.onTripRoute(TripHistory.route(key, route, modes, fallbackGas))
            loadRoute()
        }
    }

    private fun <T : Any> read(
        inFlight: AtomicBoolean,
        what: String,
        reader: () -> T,
        onFailure: () -> Unit = {},
        onRead: (T) -> Unit,
    ) {
        if (DatabaseOperationLease.isHeld() || !inFlight.compareAndSet(false, true)) return
        try {
            executor.execute {
                val result =
                    try {
                        reader()
                    } catch (ex: RuntimeException) {
                        Log.w(AppPrefs.LOG_TAG, "$what read failed", ex)
                        null
                    } finally {
                        inFlight.set(false)
                    }
                post(Runnable { if (result != null) onRead(result) else onFailure() })
            }
        } catch (ex: RejectedExecutionException) {
            Log.w(AppPrefs.LOG_TAG, "$what read not started", ex)
            inFlight.set(false)
        }
    }
}
