package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Gen2ScanCandidatesTest {
    @Test
    fun everyCandidateIsInTheCatalogOnItsModuleAndScanOnly() {
        assertEquals(12, Gen2ScanCandidates.COMMANDS.size)
        for (command in Gen2ScanCandidates.COMMANDS) {
            val header = if (command.startsWith("2290")) "ATSH241" else "ATSH7E4"
            val profile = EnhancedPidProfiles.find(header, command)
            assertNotNull("$command on $header", profile)
            assertEquals(EnhancedPidProfiles.STATUS_CANDIDATE, profile!!.validationStatus)
            assertEquals("diagnostic_only", profile.pollLane)
            assertNull("$command is not polled live until the car proves it", PidSchedule.specFor(command))
        }
    }

    @Test
    fun everyCandidateHasADecoderAndALabel() {
        for (command in Gen2ScanCandidates.COMMANDS) {
            val decoded = ObdProtocol.parseKnownValue(command, "62${command.substring(2)}00000000")
            assertNotNull("$command decodes", decoded)
            assertEquals(decoded!!.name, ObdElmDecode.nameForCommand(command.lowercase()))
        }
    }
}
