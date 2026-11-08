package com.volttracker.obdpoc.update

import com.volttracker.obdpoc.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun progressTicksStaySilentAndTerminalEventsSpeak() {
        assertNull(UpdatePrompt.message(UpdateManager.InstallEvent.Downloading(0)))
        assertNull(UpdatePrompt.message(UpdateManager.InstallEvent.Downloading(99)))
        assertEquals(
            R.string.update_opening_installer,
            UpdatePrompt.message(UpdateManager.InstallEvent.InstallerOpened),
        )
        assertEquals(
            R.string.update_failed_installer,
            UpdatePrompt.message(UpdateManager.InstallEvent.Failed(UpdateManager.InstallFailure.INSTALLER)),
        )
    }

    @Test
    fun settingsShowsProgressUnderTheOfferThenTheOutcome() {
        val text = { id: Int -> "text#$id" }
        val downloading = UpdateManager.InstallEvent.Downloading(42)
        assertEquals("v0.37.0 is available", UpdatePrompt.statusLine(downloading, "v0.37.0", text))
        assertEquals(42, UpdatePrompt.percent(downloading))

        val opened = UpdateManager.InstallEvent.InstallerOpened
        assertEquals("text#${R.string.update_opening_installer}", UpdatePrompt.statusLine(opened, "v0.37.0", text))
        assertNull(UpdatePrompt.percent(opened))

        val failed = UpdateManager.InstallEvent.Failed(UpdateManager.InstallFailure.NETWORK)
        assertEquals("text#${R.string.update_failed_network}", UpdatePrompt.statusLine(failed, "v0.37.0", text))
        assertNull(UpdatePrompt.percent(failed))
    }

    @Test
    fun everyFailureHasItsOwnMessage() {
        val messages = UpdateManager.InstallFailure.entries.map(UpdatePrompt::failureMessage)
        // A shared message would hide which step failed, which is the point of classifying.
        assertEquals(messages.size, messages.toSet().size)
        assertEquals(
            R.string.update_failed_not_this_app,
            UpdatePrompt.failureMessage(UpdateManager.InstallFailure.NOT_THIS_APP),
        )
        assertEquals(R.string.update_failed_network, UpdatePrompt.failureMessage(UpdateManager.InstallFailure.NETWORK))
    }

    @Test
    fun onlyFailuresThatCouldClearAreRetryable() {
        assertFalse(UpdatePrompt.retryable(UpdateManager.InstallFailure.NOT_THIS_APP))
        assertFalse(UpdatePrompt.retryable(UpdateManager.InstallFailure.INSTALLER))
        listOf(
            UpdateManager.InstallFailure.BUSY,
            UpdateManager.InstallFailure.NETWORK,
            UpdateManager.InstallFailure.SERVER,
            UpdateManager.InstallFailure.INCOMPLETE,
            UpdateManager.InstallFailure.STORAGE,
            UpdateManager.InstallFailure.UNEXPECTED,
        ).forEach { assertTrue(it.name, UpdatePrompt.retryable(it)) }
    }
}
