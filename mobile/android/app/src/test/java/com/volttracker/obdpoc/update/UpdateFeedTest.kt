package com.volttracker.obdpoc.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateFeedTest {
    // --- versionCodeFromTag --------------------------------------------------

    @Test
    fun tagParsingMirrorsTheGradleFormula() {
        assertEquals(35_000, UpdateFeed.versionCodeFromTag("v0.35.0"))
        assertEquals(1_002_003, UpdateFeed.versionCodeFromTag("1.2.3"))
        assertEquals(999_999, UpdateFeed.versionCodeFromTag("v0.999.999"))
    }

    @Test
    fun malformedTagsYieldNull() {
        assertNull(UpdateFeed.versionCodeFromTag("v1.2"))
        assertNull(UpdateFeed.versionCodeFromTag("release-1"))
        assertNull(UpdateFeed.versionCodeFromTag("v0.1000.0")) // out of gradle's bounds
        assertNull(UpdateFeed.versionCodeFromTag("v0.0.1000"))
        assertNull(UpdateFeed.versionCodeFromTag("v2148.0.0")) // would overflow Int
        assertNull(UpdateFeed.versionCodeFromTag(""))
    }

    @Test
    fun remoteAssetNamesAreReducedToSafeFilenames() {
        assertEquals("volttracker-v1.apk", UpdateFeed.safeAssetFileName("volttracker-v1.apk"))
        assertEquals("evil.apk", UpdateFeed.safeAssetFileName("../../evil.apk"))
        assertEquals("evil.apk", UpdateFeed.safeAssetFileName("a/b\\evil.apk"))
        assertEquals("update.apk", UpdateFeed.safeAssetFileName(".."))
        assertEquals("update.apk", UpdateFeed.safeAssetFileName(""))
    }

    // --- pickBuild -----------------------------------------------------------

    private fun release(
        tag: String,
        draft: Boolean = false,
        vararg assetNames: String,
    ): JSONObject =
        JSONObject()
            .put("tag_name", tag)
            .put("name", "Release $tag")
            .put("html_url", "https://github.com/jtn0123/VoltTracker/releases/tag/$tag")
            .put("draft", draft)
            .put(
                "assets",
                JSONArray().apply {
                    assetNames.forEach { name ->
                        put(
                            JSONObject()
                                .put("name", name)
                                .put("size", 20_000_000L)
                                .put("browser_download_url", "https://github.com/dl/$name"),
                        )
                    }
                },
            )

    @Test
    fun newestReleaseWithAnApkWins() {
        val releases =
            JSONArray()
                .put(release("v0.36.0")) // newest, but no APK attached yet
                .put(release("v0.35.0", false, "volttracker-v0.35.0-release.apk", "volttracker-v0.35.0-debug.apk"))
                .put(release("v0.34.0", false, "volttracker-v0.34.0-release.apk"))

        val build = UpdateFeed.pickBuild(releases, wantDebugVariant = false)!!
        assertEquals("v0.35.0", build.tag)
        assertEquals("volttracker-v0.35.0-release.apk", build.assetName)
        assertEquals(35_000, build.versionCode)
    }

    @Test
    fun debugInstallsPreferTheDebugAsset() {
        val releases =
            JSONArray()
                .put(release("v0.35.0", false, "volttracker-v0.35.0-release.apk", "volttracker-v0.35.0-debug.apk"))

        val build = UpdateFeed.pickBuild(releases, wantDebugVariant = true)!!
        assertEquals("volttracker-v0.35.0-debug.apk", build.assetName)
    }

    @Test
    fun missingVariantFallsBackToAnyApk() {
        val releases = JSONArray().put(release("v0.35.0", false, "volttracker-hand-upload.apk"))
        val build = UpdateFeed.pickBuild(releases, wantDebugVariant = true)!!

        assertEquals("volttracker-hand-upload.apk", build.assetName)
        // The tag still parses even when the asset name doesn't.
        assertEquals(35_000, build.versionCode)
    }

    @Test
    fun draftsAndApklessReleasesAreSkipped() {
        val releases =
            JSONArray()
                .put(release("v0.37.0", true, "volttracker-v0.37.0-release.apk")) // draft
                .put(release("v0.36.0")) // no assets
        assertNull(UpdateFeed.pickBuild(releases, wantDebugVariant = false))
    }

    @Test
    fun assetlessAndDuplicateAssetReleasesResolve() {
        val releases =
            JSONArray()
                .put(JSONObject().put("tag_name", "v0.37.0").put("draft", false)) // no assets key at all
                .put(
                    release(
                        "v0.35.0",
                        false,
                        // Two matching APKs: the first one wins, the second exercises
                        // the already-matched path.
                        "volttracker-v0.35.0-release.apk",
                        "volttracker-v0.35.0b-release.apk",
                    ),
                )

        val build = UpdateFeed.pickBuild(releases, wantDebugVariant = false)!!
        assertEquals("volttracker-v0.35.0-release.apk", build.assetName)
    }

    @Test
    fun malformedFeedEntriesAreSkippedNotFatal() {
        val messy =
            JSONArray()
                .put("not an object")
                .put(
                    JSONObject() // no draft key, blank name, junk assets alongside a real one
                        .put("tag_name", "v0.35.0")
                        .put("name", "")
                        .put(
                            "assets",
                            JSONArray()
                                .put("not an object either")
                                .put(JSONObject().put("name", "notes.txt").put("browser_download_url", "https://x/y"))
                                .put(JSONObject().put("name", "urlless.apk"))
                                .put(
                                    JSONObject()
                                        .put("name", "volttracker-v0.35.0-release.apk")
                                        .put("size", 5L)
                                        .put("browser_download_url", "https://x/good"),
                                ),
                        ),
                )

        val build = UpdateFeed.pickBuild(messy, wantDebugVariant = false)!!
        assertEquals("v0.35.0", build.tag)
        assertEquals("v0.35.0", build.title) // blank release name falls back to the tag
        assertEquals("https://x/good", build.downloadUrl)
    }

    // --- compare -------------------------------------------------------------

    @Test
    fun comparisonHandlesAllFourAnswers() {
        assertEquals(UpdateFeed.Comparison.NEWER, UpdateFeed.compare(34_000, 35_000))
        assertEquals(UpdateFeed.Comparison.CURRENT, UpdateFeed.compare(35_000, 35_000))
        assertEquals(UpdateFeed.Comparison.OLDER, UpdateFeed.compare(36_000, 35_000))
        assertEquals(UpdateFeed.Comparison.UNKNOWN, UpdateFeed.compare(null, 35_000))
        assertEquals(UpdateFeed.Comparison.UNKNOWN, UpdateFeed.compare(35_000, null))
    }
}
