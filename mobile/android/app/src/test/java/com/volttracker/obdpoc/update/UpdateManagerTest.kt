package com.volttracker.obdpoc.update

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.volttracker.obdpoc.update.UpdateManager.InstallEvent
import com.volttracker.obdpoc.update.UpdateManager.InstallFailure
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Covers [UpdateManager]: the decision half of the check ([UpdateManager.checkNow], with an
 * injected fetcher) and the whole download → size check → signer check → installer path of
 * [UpdateManager.downloadAndInstall], through the connection and installer seams. The real
 * network and Android's install confirmation stay outside: the connection serves canned bytes
 * and the installer records what it was handed (see [ApkInstallerTest] for the real intent).
 */
@RunWith(AndroidJUnit4::class)
class UpdateManagerTest {
    private val direct = Executor { it.run() }
    private val app: Context = ApplicationProvider.getApplicationContext()

    private fun manager(
        running: Int,
        body: () -> String,
    ): UpdateManager =
        UpdateManager(
            context = app,
            executor = direct,
            mainThread = direct,
            fetcher = { body() },
            runningVersionCode = running,
            wantDebugVariant = false,
        )

    private fun feed(vararg tags: String): String =
        JSONArray()
            .apply {
                tags.forEach { tag ->
                    put(
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
                    )
                }
            }.toString()

    @Test
    fun newerPublishedBuildIsAnUpdate() {
        val result = manager(running = 34_000) { feed("v0.35.0") }.checkNow()
        assertTrue(result is UpdateManager.CheckResult.UpdateAvailable)
        assertEquals("v0.35.0", (result as UpdateManager.CheckResult.UpdateAvailable).build.tag)
    }

    @Test
    fun matchingBuildIsUpToDate() {
        assertEquals(
            UpdateManager.CheckResult.UpToDate,
            manager(running = 35_000) { feed("v0.35.0") }.checkNow(),
        )
    }

    @Test
    fun unparseableTagIsUnknownNotUpToDate() {
        val result = manager(running = 35_000) { feed("nightly-build") }.checkNow()
        assertTrue(result is UpdateManager.CheckResult.Unknown)
    }

    @Test
    fun emptyFeedMeansNoBuilds() {
        assertEquals(
            UpdateManager.CheckResult.NoBuilds,
            manager(running = 35_000) { "[]" }.checkNow(),
        )
    }

    @Test
    fun networkFailureIsOffline() {
        assertEquals(
            UpdateManager.CheckResult.Offline,
            manager(running = 35_000) { throw IOException("no route") }.checkNow(),
        )
    }

    @Test
    fun nonJsonBodyIsAFailure() {
        val result = manager(running = 35_000) { "<html>rate limited</html>" }.checkNow()
        assertTrue(result is UpdateManager.CheckResult.Failed)
    }

    @Test
    fun anUnexpectedFetchCrashIsAFailureNotAStuckCheck() {
        val result = manager(running = 35_000) { throw SecurityException("no network permission") }.checkNow()
        assertTrue(result is UpdateManager.CheckResult.Failed)
    }

    @Test
    fun asyncCheckDeliversTheResultOnTheMainExecutor() {
        var delivered: UpdateManager.CheckResult? = null
        manager(running = 34_000) { feed("v0.35.0") }.checkForUpdate { delivered = it }
        assertTrue(delivered is UpdateManager.CheckResult.UpdateAvailable)
    }

    // ---- downloadAndInstall: the network / file / verifier / installer path ----

    @Test
    fun aVerifiedDownloadOpensTheInstallerAsTheOnlyTerminalEvent() {
        val conn = FakeConnection(body = PAYLOAD)
        val verified = mutableListOf<File>()
        val installer = RecordingInstaller()
        val mgr =
            downloadManager(
                connection = { conn.at(it) },
                verify = {
                    verified += it
                    true
                },
                installer = installer,
            )

        val events = mgr.collect(build())

        assertEquals(InstallEvent.InstallerOpened, events.last())
        assertExactlyOneTerminal(events)
        val ticks = events.filterIsInstance<InstallEvent.Downloading>().map { it.percent }
        assertTrue("progress ticks arrive before the terminal event", ticks.isNotEmpty())
        assertEquals(ticks, ticks.sorted())
        assertTrue("100 is never a progress tick", ticks.all { it in 0..99 })
        assertEquals(1, installer.launches.size)
        assertArrayEquals(PAYLOAD, installer.launches.single())
        assertEquals(
            "the verifier checks the file in the updates cache dir",
            File(app.cacheDir, UpdateManager.UPDATES_DIR),
            verified.single().parentFile,
        )
        assertEquals("application/octet-stream", conn.acceptHeader)
        assertEquals(UpdateManager.USER_AGENT, conn.userAgentHeader)
        assertTrue("connection is always released", conn.disconnected)
    }

    @Test
    fun truncatedBytesFailAsIncompleteAndNeverReachTheVerifierOrInstaller() {
        val installer = RecordingInstaller()
        var verifierCalls = 0
        val conn = FakeConnection(body = PAYLOAD.copyOf(120_000), advertisedLength = PAYLOAD.size)
        val mgr =
            downloadManager(
                connection = { conn.at(it) },
                verify = {
                    verifierCalls++
                    true
                },
                installer = installer,
            )

        val events = mgr.collect(build())

        assertEquals(InstallEvent.Failed(InstallFailure.INCOMPLETE), events.last())
        assertExactlyOneTerminal(events)
        assertEquals(0, verifierCalls)
        assertTrue(installer.launches.isEmpty())
        assertNoApkLeftBehind()
    }

    @Test
    fun truncationIsCaughtFromTheReleaseSizeWhenContentLengthIsAbsent() {
        val installer = RecordingInstaller()
        val conn = FakeConnection(body = PAYLOAD.copyOf(1_000), advertisedLength = -1)
        val events = downloadManager(connection = { conn.at(it) }, installer = installer).collect(build())

        assertEquals(InstallEvent.Failed(InstallFailure.INCOMPLETE), events.last())
        assertTrue(installer.launches.isEmpty())
    }

    @Test
    fun aWrongSignerFailsAsNotThisAppAndDeletesTheFile() {
        val installer = RecordingInstaller()
        val conn = FakeConnection(body = PAYLOAD)
        val events =
            downloadManager(connection = { conn.at(it) }, verify = { false }, installer = installer)
                .collect(build())

        assertEquals(InstallEvent.Failed(InstallFailure.NOT_THIS_APP), events.last())
        assertExactlyOneTerminal(events)
        assertTrue(installer.launches.isEmpty())
        assertNoApkLeftBehind()
    }

    @Test
    fun anHttpErrorFailsAsServer() {
        val installer = RecordingInstaller()
        val conn = FakeConnection(status = HttpURLConnection.HTTP_NOT_FOUND)
        val events = downloadManager(connection = { conn.at(it) }, installer = installer).collect(build())

        assertEquals(listOf(InstallEvent.Failed(InstallFailure.SERVER)), events)
        assertTrue(installer.launches.isEmpty())
        assertTrue(conn.disconnected)
    }

    @Test
    fun aConnectionThatNeverAnswersFailsAsNetwork() {
        val conn = FakeConnection(failure = IOException("connect timed out"))
        val events = downloadManager(connection = { conn.at(it) }).collect(build())

        assertEquals(listOf(InstallEvent.Failed(InstallFailure.NETWORK)), events)
    }

    @Test
    fun aStreamThatBreaksMidDownloadFailsAsNetwork() {
        val conn = FakeConnection(body = PAYLOAD, breakAfter = 70_000)
        val events = downloadManager(connection = { conn.at(it) }).collect(build())

        assertEquals(InstallEvent.Failed(InstallFailure.NETWORK), events.last())
        assertExactlyOneTerminal(events)
        assertNoApkLeftBehind()
    }

    @Test
    fun aCleartextOrMalformedLinkIsRefusedBeforeConnecting() {
        var opened = 0
        val mgr =
            downloadManager(connection = {
                opened++
                FakeConnection(body = PAYLOAD).at(it)
            })

        assertEquals(
            listOf(InstallEvent.Failed(InstallFailure.SERVER)),
            mgr.collect(build(url = "http://example.invalid/volttracker-v0.40.0-release.apk")),
        )
        assertEquals(listOf(InstallEvent.Failed(InstallFailure.SERVER)), mgr.collect(build(url = "not a url")))
        assertEquals(0, opened)
    }

    @Test
    fun anUnwritableCacheFailsAsStorage() {
        // A regular file where the cache dir should be: the updates dir can never be created.
        val blocked = File(app.filesDir, "cache-is-a-file").apply { writeText("x") }
        val context =
            object : ContextWrapper(app) {
                override fun getCacheDir(): File = blocked
            }
        val events =
            downloadManager(connection = { FakeConnection(body = PAYLOAD).at(it) }, context = context)
                .collect(build())

        assertEquals(listOf(InstallEvent.Failed(InstallFailure.STORAGE)), events)
    }

    @Test
    fun anUnexpectedCrashInTheDownloadIsReportedNotThrown() {
        val events =
            downloadManager(connection = { throw IllegalStateException("stack bug") }).collect(build())

        assertEquals(listOf(InstallEvent.Failed(InstallFailure.UNEXPECTED)), events)
    }

    @Test
    fun aFailedInstallerLaunchIsTheTerminalFailure() {
        val installer = RecordingInstaller(opens = false)
        val mgr = downloadManager(connection = { FakeConnection(body = PAYLOAD).at(it) }, installer = installer)

        val events = mgr.collect(build())

        // The old code reported "100%" first and then swallowed the launch exception.
        assertEquals(InstallEvent.Failed(InstallFailure.INSTALLER), events.last())
        assertExactlyOneTerminal(events)
        assertFalse(events.contains(InstallEvent.InstallerOpened))
        assertEquals(1, installer.launches.size)
        // And the failure released the slot: the user can try again.
        assertEquals(InstallEvent.Failed(InstallFailure.INSTALLER), mgr.collect(build()).last())
        assertEquals(2, installer.launches.size)
    }

    @Test
    fun aRejectedJobReleasesBusyAndStillAnswers() {
        val flaky = rejectsOnceThenRuns()
        val installer = RecordingInstaller()
        val mgr =
            downloadManager(
                connection = { FakeConnection(body = PAYLOAD).at(it) },
                installer = installer,
                executor = flaky,
            )

        assertEquals(listOf(InstallEvent.Failed(InstallFailure.BUSY)), mgr.collect(build()))
        // Busy was reset, so the next request runs instead of being refused forever.
        assertEquals(InstallEvent.InstallerOpened, mgr.collect(build()).last())
        assertEquals(1, installer.launches.size)
    }

    @Test
    fun aRejectedCheckReleasesBusyAndStillAnswers() {
        val flaky = rejectsOnceThenRuns()
        val mgr =
            UpdateManager(
                context = app,
                executor = flaky,
                mainThread = direct,
                fetcher = { feed("v0.35.0") },
                runningVersionCode = 34_000,
                wantDebugVariant = false,
            )
        val results = mutableListOf<UpdateManager.CheckResult>()

        mgr.checkForUpdate(results::add)
        mgr.checkForUpdate(results::add)

        assertTrue(results[0] is UpdateManager.CheckResult.Failed)
        assertTrue(results[1] is UpdateManager.CheckResult.UpdateAvailable)
    }

    @Test
    fun aSecondRequestWhileOneRunsIsAnsweredBusyAndTheSlotFreesAfterwards() {
        val worker = QueuedExecutor()
        val installer = RecordingInstaller()
        val mgr =
            downloadManager(
                connection = { FakeConnection(body = PAYLOAD).at(it) },
                installer = installer,
                executor = worker,
            )
        val first = mutableListOf<InstallEvent>()
        mgr.downloadAndInstall(build(), first::add)

        assertEquals(listOf(InstallEvent.Failed(InstallFailure.BUSY)), mgr.collect(build()))
        val check = mutableListOf<UpdateManager.CheckResult>()
        mgr.checkForUpdate(check::add)
        assertTrue(
            "a check during a download is refused, not dropped",
            check.single() is UpdateManager.CheckResult.Failed,
        )

        worker.runAll()
        assertEquals(InstallEvent.InstallerOpened, first.last())

        mgr.downloadAndInstall(build(), first::add)
        worker.runAll()
        assertEquals(2, installer.launches.size)
    }

    @Test
    fun busyIsHeldUntilTheInstallerLaunchIsDecided() {
        val main = QueuedExecutor()
        val installer = RecordingInstaller()
        val mgr =
            UpdateManager(
                context = app,
                executor = direct,
                mainThread = main,
                fetcher = { "[]" },
                runningVersionCode = 1,
                wantDebugVariant = false,
                verifyApk = { true },
                openConnection = { FakeConnection(body = PAYLOAD).at(it) },
                installer = installer,
            )
        val first = mutableListOf<InstallEvent>()
        val second = mutableListOf<InstallEvent>()

        // The download finished on the worker; its installer launch is still queued on main.
        mgr.downloadAndInstall(build(), first::add)
        mgr.downloadAndInstall(build(), second::add)
        main.runAll()

        // The second tap did not start a new download that would wipe the file mid-launch.
        assertEquals(InstallEvent.InstallerOpened, first.last())
        assertEquals(listOf(InstallEvent.Failed(InstallFailure.BUSY)), second)
        assertEquals(1, installer.launches.size)
    }

    @Test
    fun aFatalErrorInTheDownloadStillFreesTheSlot() {
        var fatal = true
        val mgr =
            downloadManager(
                connection = { FakeConnection(body = PAYLOAD).at(it) },
                verify = {
                    if (fatal) {
                        fatal = false
                        throw OutOfMemoryError("simulated fatal error")
                    }
                    true
                },
            )

        assertThrows(OutOfMemoryError::class.java) { mgr.collect(build()) }
        assertEquals(InstallEvent.InstallerOpened, mgr.collect(build()).last())
    }

    // ---- ApkDownloader.copyWithProgress ----

    @Test
    fun copyReportsMonotonicProgressAgainstAKnownTotal() {
        val payload = ByteArray(200_000) { it.toByte() }
        val out = ByteArrayOutputStream()
        val seen = mutableListOf<Int>()

        ApkDownloader.copyWithProgress(ByteArrayInputStream(payload), out, payload.size.toLong(), seen::add)

        assertTrue(out.toByteArray().contentEquals(payload))
        assertEquals(99, seen.last())
        assertEquals(seen, seen.sorted()) // never goes backwards
    }

    @Test
    fun copyWithUnknownTotalStaysSilentButComplete() {
        val payload = ByteArray(70_000) { 7 }
        val out = ByteArrayOutputStream()
        val seen = mutableListOf<Int>()

        ApkDownloader.copyWithProgress(ByteArrayInputStream(payload), out, -1L, seen::add)

        assertTrue(seen.isEmpty())
        assertEquals(payload.size, out.size())
    }

    @Test
    fun copyLongerThanAdvertisedNeverReportsMoreThan99() {
        val seen = mutableListOf<Int>()
        ApkDownloader.copyWithProgress(
            ByteArrayInputStream(ByteArray(200_000)),
            ByteArrayOutputStream(),
            100_000,
            seen::add,
        )

        assertEquals(99, seen.max())
    }

    @Test
    fun aWriteFailureIsTaggedAsStorageNotNetwork() {
        val full =
            object : OutputStream() {
                override fun write(b: Int): Unit = throw IOException("ENOSPC")

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Unit = throw IOException("ENOSPC")
            }

        assertThrows(ApkDownloader.StorageWriteException::class.java) {
            ApkDownloader.copyWithProgress(ByteArrayInputStream(ByteArray(10)), full, 10) {}
        }
    }

    // ---- helpers ----

    private fun downloadManager(
        connection: (URL) -> HttpURLConnection,
        verify: (File) -> Boolean = { true },
        installer: ApkInstaller = RecordingInstaller(),
        executor: Executor = direct,
        context: Context = app,
    ): UpdateManager =
        UpdateManager(
            context = context,
            executor = executor,
            mainThread = direct,
            fetcher = { "[]" },
            runningVersionCode = 1,
            wantDebugVariant = false,
            verifyApk = verify,
            openConnection = connection,
            installer = installer,
        )

    private fun build(
        url: String = "https://example.invalid/download/volttracker-v0.40.0-release.apk",
    ): UpdateFeed.AvailableBuild =
        UpdateFeed.AvailableBuild(
            tag = "v0.40.0",
            title = "0.40.0",
            versionCode = 40_000,
            assetName = "volttracker-v0.40.0-release.apk",
            downloadUrl = url,
            sizeBytes = PAYLOAD.size.toLong(),
            pageUrl = "https://example.invalid/releases/v0.40.0",
        )

    /** An executor whose first job is rejected (as by a shut-down worker); later jobs run inline. */
    private fun rejectsOnceThenRuns(): Executor {
        var rejectNext = true
        return Executor { job ->
            if (rejectNext) {
                rejectNext = false
                throw RejectedExecutionException("worker shut down")
            }
            job.run()
        }
    }

    private fun UpdateManager.collect(build: UpdateFeed.AvailableBuild): List<InstallEvent> {
        val events = mutableListOf<InstallEvent>()
        downloadAndInstall(build, events::add)
        return events
    }

    private fun assertExactlyOneTerminal(events: List<InstallEvent>) {
        assertEquals("exactly one terminal event, and it is last", 1, events.count { it !is InstallEvent.Downloading })
        assertFalse(events.last() is InstallEvent.Downloading)
    }

    private fun assertNoApkLeftBehind() {
        val left = File(app.cacheDir, UpdateManager.UPDATES_DIR).listFiles().orEmpty()
        assertTrue("rejected download was deleted: ${left.map { it.name }}", left.isEmpty())
    }

    private companion object {
        val PAYLOAD = ByteArray(200_000) { (it * 31).toByte() }
    }
}
