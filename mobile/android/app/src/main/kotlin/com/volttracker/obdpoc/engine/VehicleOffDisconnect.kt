package com.volttracker.obdpoc.engine

import com.volttracker.obdpoc.FailureClass
import com.volttracker.obdpoc.classify.VehicleState
import java.io.IOException

/**
 * The adapter's ELM327 link is up but the vehicle bus never answered the standard `0100` PID probe.
 * With the car off, that is simply the car being asleep, not an adapter fault. A dedicated type lets
 * the end-of-session logic tell it apart from other failures without adding a new [FailureClass]
 * (the connection classifier still buckets it by message, as before).
 */
class VehicleBusSilentException(
    message: String,
) : IOException(message)

/**
 * Pure decisions for telling "the adapter went to sleep with the car" apart from a real link fault
 * when the reconnect budget runs out.
 */
internal object VehicleOffDisconnect {
    private val UNKNOWN_STATE = VehicleState.UNKNOWN.asPayloadKey()

    /**
     * The last *known* vehicle state. An `unknown` (or missing) sample state never overwrites a known
     * one: as the car powers down its modules stop answering in turn, so the final samples before the
     * link drops often classify as `unknown` even though the car was just seen parked.
     */
    @JvmStatic
    fun stickyKnownState(
        previous: String,
        sampleState: String?,
    ): String = if (sampleState.isNullOrEmpty() || sampleState == UNKNOWN_STATE) previous else sampleState

    /**
     * A reconnect-exhausted drop ends cleanly (drive saved, no red error) when the link had connected,
     * the car was last known parked/plugged/charging, and the failure looks like the car being gone:
     * an RFCOMM connect timeout (adapter powered down) or a silent vehicle bus (adapter awake, car
     * asleep).
     */
    @JvmStatic
    fun endsAsVehicleOff(
        failureClass: FailureClass,
        ex: IOException?,
        everConnected: Boolean,
        lastKnownState: String,
    ): Boolean =
        (failureClass == FailureClass.CONNECT_TIMEOUT || ex is VehicleBusSilentException) &&
            ObdPollingEngine.isVehicleOffDisconnect(everConnected, lastKnownState)
}
