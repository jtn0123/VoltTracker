package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.drive.Gen2Status
import com.volttracker.obdpoc.ui.live.Gen2StatusMapper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Gen2StatusMapperTest {
    @Test
    fun readsEachStatusKey() {
        val status =
            Gen2StatusMapper.map(
                JSONObject()
                    .put("engineStartReason", "Battery charge low; Hood open")
                    .put("auxTransPumpPct", 19.6)
                    .put("peCoolantPumpPct", 40)
                    .put("batteryConditioning", "Active heating")
                    .put("aux12vMaintenanceCharge", "Active")
                    .put("chargeState", "Charging")
                    .put("chargeSystemStatus", "Ready")
                    .put("chargeEnableCheck", "Passed")
                    .put("chargeControlCheck", "Failed")
                    .put("chargeStartTime", "Tue 23:00")
                    .put("chargeCompleteTime", "Wed 04:30")
                    .put("chargeStartTime120v", "Tue 22:00")
                    .put("chargeCompleteTime120v", "Wed 09:00")
                    .put("chargeRecord2", "Full charge")
                    .put("acPowerW", 1100)
                    .put("cabinHeatPowerW", 0)
                    .put("remoteStartSecondsLeft", 590)
                    .put("remoteStartsLeft", 2),
            )
        assertEquals("Battery charge low; Hood open", status.engineStartReason)
        assertEquals("Battery charge low", status.firstStartReason)
        assertEquals(20, status.auxTransPumpPct)
        assertEquals(40, status.peCoolantPumpPct)
        assertEquals("Active heating", status.batteryConditioning)
        assertEquals("Active", status.maintenance12v)
        assertEquals("Charging", status.chargeState)
        assertEquals("Ready", status.chargeSystem)
        assertEquals("Passed", status.chargeEnableCheck)
        assertEquals("Failed", status.chargeControlCheck)
        assertEquals("Tue 23:00", status.chargeStart)
        assertEquals("Wed 04:30", status.chargeComplete)
        assertEquals("Tue 22:00", status.chargeStart120v)
        assertEquals("Wed 09:00", status.chargeComplete120v)
        assertEquals(listOf(null, "Full charge", null, null, null), status.chargeRecords)
        assertEquals(1100, status.acPowerW)
        assertEquals(0, status.cabinHeatPowerW)
        assertEquals(590, status.remoteStartSecondsLeft)
        assertEquals(2, status.remoteStartsLeft)
    }

    @Test
    fun anAbsentOrBlankKeyIsNotReported() {
        assertEquals(Gen2Status(), Gen2StatusMapper.map(JSONObject()))
        val blank = Gen2StatusMapper.map(JSONObject().put("chargeState", "").put("acPowerW", JSONObject.NULL))
        assertNull(blank.chargeState)
        assertNull(blank.acPowerW)
    }

    @Test
    fun noStartReasonIsNotAReason() {
        assertNull(Gen2Status(engineStartReason = "None").firstStartReason)
        assertNull(Gen2Status().firstStartReason)
        assertEquals("Remote start", Gen2Status(engineStartReason = "Remote start").firstStartReason)
    }
}
