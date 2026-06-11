package com.volttracker.obdpoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancedPidProfilesTest {
    @Test
    fun findRequiresTheExpectedHeaderForEnhancedPids() {
        assertNotNull(EnhancedPidProfiles.find("ATSH7E0", "221154"))
        assertEquals(null, EnhancedPidProfiles.find("ATSH7E2", "221154"))
    }

    @Test
    fun findMatchesBareAndAtshHeaderSpellings() {
        // The catalog stores the full ELM command ("ATSH7E4") while SessionRecorder tracks
        // the bare CAN id ("7E4"); both spellings must resolve to the same profile.
        val viaAtsh = EnhancedPidProfiles.find("ATSH7E4", "224329")
        val viaBare = EnhancedPidProfiles.find("7E4", "224329")
        assertNotNull(viaAtsh)
        assertNotNull(viaBare)
        assertEquals(viaAtsh!!.key, viaBare!!.key)

        // A bare id must still respect header scoping — the wrong module stays null.
        assertEquals(null, EnhancedPidProfiles.find("7E2", "221154"))
    }

    @Test
    fun positiveResponseRecognizesMode22AndRejectsNegativeFrames() {
        assertTrue(EnhancedPidProfiles.isPositiveResponse("221154", "62115460"))
        assertFalse(EnhancedPidProfiles.isPositiveResponse("22119F01", "7F2212"))
        assertFalse(EnhancedPidProfiles.isPositiveResponse("221940", "NO DATA"))
    }

    @Test
    fun positiveResponseOnlyRejectsFramesThatStartWithNegativeMarker() {
        // 0x7F in the payload is data, not a refusal: with ATS0, 410D7F is 127 km/h.
        assertTrue(EnhancedPidProfiles.isPositiveResponse("010D", "410D7F"))
        // A frame starting 7F + echoed service id is a real UDS negative response.
        assertFalse(EnhancedPidProfiles.isPositiveResponse("221154", "7F2231"))
        assertFalse(EnhancedPidProfiles.isPositiveResponse("221154", "7F 22 31"))
        // 7F xx 78 = response pending: not a rejection when the real answer follows...
        assertTrue(EnhancedPidProfiles.isPositiveResponse("221154", "7F2278\r62115460"))
        // ...but a pending line with no answer is still not a positive response.
        assertFalse(EnhancedPidProfiles.isPositiveResponse("221154", "7F2278"))
    }

    @Test
    fun passiveCanOdometerIsCatalogedButNotTreatedAsAnObdPositive() {
        val profile = EnhancedPidProfiles.find("CAN:120", "CAN:120")
        assertNotNull(profile)
        assertEquals("passive", profile!!.pollLane)
        assertFalse(EnhancedPidProfiles.isPositiveResponse("CAN:120", "0012D590"))
    }

    @Test
    fun tpmsCategoryReturnsOnlyTpmsProfiles() {
        for (profile in EnhancedPidProfiles.forCategory("tpms")) {
            assertEquals("tpms", profile.category)
            assertEquals(EnhancedPidProfiles.STAGE_TIRES, profile.scanStage)
            assertEquals(EnhancedPidProfiles.STATUS_REJECTED, profile.validationStatus)
        }
        assertTrue(EnhancedPidProfiles.forCategory("tpms").size >= 8)
    }

    @Test
    fun sensorExpansionProfilesCoverNewHeadersAndStayDiagnosticOnlyUnlessConfirmed() {
        assertNotNull(EnhancedPidProfiles.find("ATSH7E6", "224501"))
        assertNotNull(EnhancedPidProfiles.find("ATSH7E7", "224181"))
        assertNotNull(EnhancedPidProfiles.find("ATSH7E4", "224329"))
        assertNotNull(EnhancedPidProfiles.find("", "0132"))

        val capacity = EnhancedPidProfiles.find("ATSH7E4", "2241A3")
        assertNotNull(capacity)
        assertEquals(EnhancedPidProfiles.STATUS_CONFIRMED, capacity!!.validationStatus)
        assertEquals("once_per_drive", capacity.pollLane)

        for (profile in EnhancedPidProfiles.forCategory("brake")) {
            assertEquals("diagnostic_only", profile.pollLane)
            assertEquals(EnhancedPidProfiles.STATUS_CANDIDATE, profile.validationStatus)
        }
    }

    @Test
    fun scanStagesSeparateSafeTiresAndExperimentalCandidates() {
        assertEquals(
            EnhancedPidProfiles.STAGE_TIRES,
            EnhancedPidProfiles.normalizeStage("unknown"),
        )
        assertTrue(EnhancedPidProfiles.forStage(EnhancedPidProfiles.STAGE_LOW_RISK).size >= 3)
        assertTrue(
            EnhancedPidProfiles.forStage(EnhancedPidProfiles.STAGE_EXPERIMENTAL).size >= 3,
        )
        for (
        profile in
        EnhancedPidProfiles.forStage(EnhancedPidProfiles.STAGE_EXPERIMENTAL)
        ) {
            assertEquals(EnhancedPidProfiles.STAGE_EXPERIMENTAL, profile.scanStage)
            assertTrue(profile.retryAfterMs > 0L)
        }
    }

    @Test
    fun catalogJsonExposesSignalMetadataForTheDashboard() {
        assertTrue(EnhancedPidProfiles.catalogJson().length() >= EnhancedPidProfiles.ALL.size)
        assertEquals(
            "engine oil temperature",
            EnhancedPidProfiles.catalogJson().getJSONObject(4).optString("name"),
        )
        assertEquals(
            "thermal",
            EnhancedPidProfiles.catalogJson().getJSONObject(4).optString("pollLane"),
        )
        assertEquals(
            "low-risk",
            EnhancedPidProfiles.catalogJson().getJSONObject(4).optString("scanStage"),
        )
        assertEquals(
            "low",
            EnhancedPidProfiles.catalogJson().getJSONObject(4).optString("risk"),
        )
    }
}
