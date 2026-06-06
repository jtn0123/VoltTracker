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
    fun positiveResponseRecognizesMode22AndRejectsNegativeFrames() {
        assertTrue(EnhancedPidProfiles.isPositiveResponse("221154", "62115460"))
        assertFalse(EnhancedPidProfiles.isPositiveResponse("22119F01", "7F2212"))
        assertFalse(EnhancedPidProfiles.isPositiveResponse("221940", "NO DATA"))
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
        }
        assertTrue(EnhancedPidProfiles.forCategory("tpms").size >= 8)
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
