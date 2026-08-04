package com.volttracker.obdpoc.update

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.Executor

/**
 * Covers [UpdateManager.checkNow] — the decision half of the check — with an
 * injected fetcher. The download/install half is real I/O plus a system
 * intent, exercised on-device rather than faked here.
 */
@RunWith(AndroidJUnit4::class)
class UpdateManagerTest {
    private val direct = Executor { it.run() }

    private fun manager(
        running: Int,
        body: () -> String,
    ): UpdateManager =
        UpdateManager(
            context = ApplicationProvider.getApplicationContext(),
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
    fun asyncCheckDeliversTheResultOnTheMainExecutor() {
        var delivered: UpdateManager.CheckResult? = null
        manager(running = 34_000) { feed("v0.35.0") }.checkForUpdate { delivered = it }
        assertTrue(delivered is UpdateManager.CheckResult.UpdateAvailable)
    }

    @Test
    fun copyReportsMonotonicProgressAgainstAKnownTotal() {
        val mgr = manager(running = 1) { "[]" }
        val payload = ByteArray(200_000) { it.toByte() }
        val out = java.io.ByteArrayOutputStream()
        val seen = mutableListOf<Int>()

        mgr.copyWithProgress(java.io.ByteArrayInputStream(payload), out, payload.size.toLong(), seen::add)

        assertTrue(out.toByteArray().contentEquals(payload))
        assertEquals(99, seen.last())
        assertEquals(seen, seen.sorted()) // never goes backwards
    }

    @Test
    fun copyWithUnknownTotalStaysSilentButComplete() {
        val mgr = manager(running = 1) { "[]" }
        val payload = ByteArray(70_000) { 7 }
        val out = java.io.ByteArrayOutputStream()
        val seen = mutableListOf<Int>()

        mgr.copyWithProgress(java.io.ByteArrayInputStream(payload), out, -1L, seen::add)

        assertTrue(seen.isEmpty())
        assertEquals(payload.size, out.size())
    }
}
