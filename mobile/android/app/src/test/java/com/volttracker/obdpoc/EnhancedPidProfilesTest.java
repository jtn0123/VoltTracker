package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EnhancedPidProfilesTest {

    @Test
    public void findRequiresTheExpectedHeaderForEnhancedPids() {
        assertNotNull(EnhancedPidProfiles.find("ATSH7E0", "221154"));
        assertEquals(null, EnhancedPidProfiles.find("ATSH7E2", "221154"));
    }

    @Test
    public void positiveResponseRecognizesMode22AndRejectsNegativeFrames() {
        assertTrue(EnhancedPidProfiles.isPositiveResponse("221154", "62115460"));
        assertFalse(EnhancedPidProfiles.isPositiveResponse("22119F01", "7F2212"));
        assertFalse(EnhancedPidProfiles.isPositiveResponse("221940", "NO DATA"));
    }

    @Test
    public void passiveCanOdometerIsCatalogedButNotTreatedAsAnObdPositive() {
        EnhancedPidProfile profile = EnhancedPidProfiles.find("CAN:120", "CAN:120");
        assertNotNull(profile);
        assertEquals("passive", profile.pollLane);
        assertFalse(EnhancedPidProfiles.isPositiveResponse("CAN:120", "0012D590"));
    }

    @Test
    public void tpmsCategoryReturnsOnlyTpmsProfiles() {
        for (EnhancedPidProfile profile : EnhancedPidProfiles.forCategory("tpms")) {
            assertEquals("tpms", profile.category);
            assertEquals(EnhancedPidProfiles.STAGE_TIRES, profile.scanStage);
        }
        assertTrue(EnhancedPidProfiles.forCategory("tpms").size() >= 8);
    }

    @Test
    public void scanStagesSeparateSafeTiresAndExperimentalCandidates() {
        assertEquals(
                EnhancedPidProfiles.STAGE_TIRES, EnhancedPidProfiles.normalizeStage("unknown"));
        assertTrue(EnhancedPidProfiles.forStage(EnhancedPidProfiles.STAGE_LOW_RISK).size() >= 3);
        assertTrue(
                EnhancedPidProfiles.forStage(EnhancedPidProfiles.STAGE_EXPERIMENTAL).size() >= 3);
        for (EnhancedPidProfile profile :
                EnhancedPidProfiles.forStage(EnhancedPidProfiles.STAGE_EXPERIMENTAL)) {
            assertEquals(EnhancedPidProfiles.STAGE_EXPERIMENTAL, profile.scanStage);
            assertTrue(profile.retryAfterMs > 0L);
        }
    }

    @Test
    public void catalogJsonExposesSignalMetadataForTheDashboard() throws Exception {
        assertTrue(EnhancedPidProfiles.catalogJson().length() >= EnhancedPidProfiles.ALL.size());
        assertEquals(
                "engine oil temperature",
                EnhancedPidProfiles.catalogJson().getJSONObject(4).optString("name"));
        assertEquals(
                "thermal",
                EnhancedPidProfiles.catalogJson().getJSONObject(4).optString("pollLane"));
        assertEquals(
                "low-risk",
                EnhancedPidProfiles.catalogJson().getJSONObject(4).optString("scanStage"));
        assertEquals("low", EnhancedPidProfiles.catalogJson().getJSONObject(4).optString("risk"));
    }
}
