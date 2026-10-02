package com.volttracker.obdpoc.engine

import com.volttracker.obdpoc.FailureClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class VehicleOffDisconnectTest {
    @Test
    fun unknownOrMissingSampleStateKeepsTheLastKnownState() {
        // Field pattern: the car is parked, its modules go quiet one by one, and the final samples
        // before the link drops classify as "unknown". The end-of-session decision must still see "parked".
        var known = ""
        for (state in listOf("driving_ev", "ready", "parked", "unknown", "unknown", "")) {
            known = VehicleOffDisconnect.stickyKnownState(known, state)
        }
        assertEquals("parked", known)
        assertEquals("parked", VehicleOffDisconnect.stickyKnownState("parked", null))
        assertEquals("driving_gas", VehicleOffDisconnect.stickyKnownState("parked", "driving_gas"))
        assertEquals("", VehicleOffDisconnect.stickyKnownState("", "unknown"))
    }

    @Test
    fun connectTimeoutAfterParkedEndsAsVehicleOff() {
        assertTrue(
            VehicleOffDisconnect.endsAsVehicleOff(
                FailureClass.CONNECT_TIMEOUT,
                IOException("read timed out"),
                true,
                "parked",
            ),
        )
    }

    @Test
    fun silentBusAfterParkedOrPluggedEndsAsVehicleOff() {
        // Adapter wakes and answers AT commands, but the sleeping car never answers 0100. The classifier
        // buckets that as UNKNOWN; the dedicated exception type still marks it as a benign end.
        val silent = VehicleBusSilentException("Adapter did not answer the standard OBD PID probe.")
        for (state in listOf("parked", "plugged", "charging")) {
            assertTrue(state, VehicleOffDisconnect.endsAsVehicleOff(FailureClass.UNKNOWN, silent, true, state))
        }
    }

    @Test
    fun realFaultsStayErrors() {
        val silent = VehicleBusSilentException("Adapter did not answer the standard OBD PID probe.")
        assertFalse(
            "a silent bus mid-drive is a real problem",
            VehicleOffDisconnect.endsAsVehicleOff(FailureClass.UNKNOWN, silent, true, "driving_ev"),
        )
        assertFalse(
            "a silent bus on a session that never connected is a real connect failure",
            VehicleOffDisconnect.endsAsVehicleOff(FailureClass.UNKNOWN, silent, false, "parked"),
        )
        assertFalse(
            "other unclassified failures while parked keep the error path",
            VehicleOffDisconnect.endsAsVehicleOff(FailureClass.UNKNOWN, IOException("boom"), true, "parked"),
        )
        assertFalse(
            "a lost bond is never a vehicle-off end",
            VehicleOffDisconnect.endsAsVehicleOff(FailureClass.BOND_LOST, IOException("not bonded"), true, "parked"),
        )
        assertFalse(
            "no known state means we cannot call it benign",
            VehicleOffDisconnect.endsAsVehicleOff(FailureClass.CONNECT_TIMEOUT, null, true, ""),
        )
    }

    @Test
    fun silentBusExceptionIsAnIoExceptionWithItsMessage() {
        val ex: IOException = VehicleBusSilentException("quiet")
        assertEquals("quiet", ex.message)
    }
}
