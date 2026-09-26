package com.volttracker.obdpoc.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdatePromptTest {
    private val build =
        UpdateFeed.AvailableBuild(
            tag = "v0.37.0",
            title = "0.37.0",
            versionCode = 37_000,
            assetName = "volttracker-v0.37.0-release.apk",
            downloadUrl = "https://example.invalid/volttracker-v0.37.0-release.apk",
            sizeBytes = 1L,
            pageUrl = "https://example.invalid/releases/v0.37.0",
        )

    @Test
    fun offersOnlyANewerBuild() {
        assertEquals(build, UpdatePrompt.offeredBuild(UpdateManager.CheckResult.UpdateAvailable(build)))
        assertNull(UpdatePrompt.offeredBuild(UpdateManager.CheckResult.Unknown(build)))
        assertNull(UpdatePrompt.offeredBuild(UpdateManager.CheckResult.UpToDate))
        assertNull(UpdatePrompt.offeredBuild(UpdateManager.CheckResult.NoBuilds))
        assertNull(UpdatePrompt.offeredBuild(UpdateManager.CheckResult.Offline))
        assertNull(UpdatePrompt.offeredBuild(UpdateManager.CheckResult.Failed("boom")))
    }

    @Test
    fun mapsDownloadProgressToUserMessages() {
        assertEquals(UpdatePrompt.Progress.FAILED, UpdatePrompt.progress(-1))
        assertEquals(UpdatePrompt.Progress.NONE, UpdatePrompt.progress(0))
        assertEquals(UpdatePrompt.Progress.NONE, UpdatePrompt.progress(99))
        assertEquals(UpdatePrompt.Progress.INSTALLING, UpdatePrompt.progress(100))
    }
}
