package com.volttracker.obdpoc.update

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class UpdateCoordinatorTest {
    private val direct = Executor { it.run() }

    private fun feedWith(tag: String): String =
        JSONArray()
            .put(
                JSONObject()
                    .put("tag_name", tag)
                    .put("draft", false)
                    .put(
                        "assets",
                        JSONArray().put(
                            JSONObject()
                                .put("name", "volttracker-$tag-release.apk")
                                .put("size", 1L)
                                .put("browser_download_url", "https://example.invalid/$tag"),
                        ),
                    ),
            ).toString()

    private fun coordinator(
        running: Int,
        body: () -> String,
    ): UpdateCoordinator =
        UpdateCoordinator {
            UpdateManager(
                context = ApplicationProvider.getApplicationContext(),
                executor = direct,
                mainThread = direct,
                fetcher = { body() },
                runningVersionCode = running,
                wantDebugVariant = false,
            )
        }

    @Test
    fun autoCheckRunsExactlyOncePerCoordinator() {
        var calls = 0
        val coord =
            coordinator(running = 34_000) {
                calls++
                feedWith("v0.35.0")
            }

        assertTrue(coord.autoCheckOnce { })
        assertFalse(coord.autoCheckOnce { })
        assertEquals(1, calls)
    }

    @Test
    fun lastResultAndAvailableBuildSurviveForRepublishing() {
        val coord = coordinator(running = 34_000) { feedWith("v0.35.0") }
        coord.check { }

        // A recreated Activity reads these instead of re-checking.
        assertTrue(coord.lastResult is UpdateManager.CheckResult.UpdateAvailable)
        assertEquals("v0.35.0", coord.availableBuild()?.tag)
    }

    @Test
    fun upToDateResultOffersNoBuild() {
        val coord = coordinator(running = 35_000) { feedWith("v0.35.0") }
        coord.check { }

        assertNotNull(coord.lastResult)
        assertNull(coord.availableBuild())
    }

    @Test
    fun installUsesTheBuildTheCallerShowedEvenAfterALaterCheckReplacedIt() {
        var online = true
        val installer = RecordingInstaller()
        val coord =
            UpdateCoordinator {
                UpdateManager(
                    context = ApplicationProvider.getApplicationContext(),
                    executor = direct,
                    mainThread = direct,
                    fetcher = { if (online) feedWith("v0.35.0") else throw IOException("offline") },
                    runningVersionCode = 34_000,
                    wantDebugVariant = false,
                    verifyApk = { true },
                    openConnection = { FakeConnection(body = ByteArray(4_096) { 1 }).at(it) },
                    installer = installer,
                )
            }
        coord.check { }
        val offered = checkNotNull(coord.availableBuild())
        online = false
        coord.check { }
        assertNull("a later Offline result replaced the offer", coord.availableBuild())

        val events = mutableListOf<UpdateManager.InstallEvent>()
        coord.downloadAndInstall(offered, events::add)

        // The old API re-read lastResult here and returned without any callback.
        assertEquals(UpdateManager.InstallEvent.InstallerOpened, events.last())
        assertEquals(1, installer.launches.size)
    }
}
