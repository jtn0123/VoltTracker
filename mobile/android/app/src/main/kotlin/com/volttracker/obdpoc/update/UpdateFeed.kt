package com.volttracker.obdpoc.update

import org.json.JSONArray
import java.util.Locale

/**
 * Which build is the newest one published on GitHub Releases, and is it newer
 * than this one? Pure parsing and comparison — the network call lives in
 * [UpdateManager]; the pieces that decide *which* build and *whether it is
 * newer* are here where plain JVM tests can hold them still.
 *
 * The release pipeline this reads is the repo's own:
 * - python-semantic-release tags `v<major.minor.patch>` on every bumpable merge;
 * - attach-release-apks.yml uploads `volttracker-v<semver>-release.apk` and
 *   `volttracker-v<semver>-debug.apk`, both signed with the same stable key;
 * - `versionCode = major*1_000_000 + minor*1_000 + patch` (app/build.gradle),
 *   so the upgrade counter is derivable from the tag alone.
 *
 * APK attachment is a manual dispatch, so the newest *tag* may carry no APK —
 * [pickBuild] walks the list (GitHub returns newest-first) to the first
 * non-draft release that actually has one.
 */
object UpdateFeed {
    /** One installable build discovered on the releases feed. */
    data class AvailableBuild(
        val tag: String,
        val title: String,
        /** Android's upgrade counter, derived from the tag; null if unparseable. */
        val versionCode: Int?,
        val assetName: String,
        val downloadUrl: String,
        val sizeBytes: Long,
        val pageUrl: String,
    )

    enum class Comparison { NEWER, CURRENT, OLDER, UNKNOWN }

    /**
     * `v0.35.0` → 35_000, mirroring build.gradle's derivation exactly. The
     * same 0..999 bounds apply: outside them the formula would collide, so
     * the answer is "unknown", not a wrong number.
     */
    fun versionCodeFromTag(tag: String): Int? {
        val match = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$").find(tag.trim()) ?: return null
        val (major, minor, patch) = match.destructured.toList().map { it.toIntOrNull() ?: return null }
        // major is bounded too: 2148+ would overflow Int (and Android caps
        // versionCode at 2.1 billion) — a wrong number is worse than UNKNOWN.
        if (major > 2_000 || minor > 999 || patch > 999) return null
        return major * 1_000_000 + minor * 1_000 + patch
    }

    /**
     * Reduces a remote asset name to a single safe filename — no separators,
     * no traversal components — before it is ever used as a [java.io.File].
     */
    fun safeAssetFileName(name: String): String =
        name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .takeIf { it.isNotBlank() && it != "." && it != ".." }
            ?: "update.apk"

    /**
     * The newest published release that has an APK for this build variant
     * (debug installs update from the debug asset, release from release —
     * both share the signing key, but like-for-like keeps `adb run-as`
     * working on field builds). Falls back to any `.apk` on that release so
     * a hand-uploaded asset still counts. Drafts are invisible to the phone
     * and skipped; pre-releases are not, matching how ad-hoc builds publish.
     */
    fun pickBuild(
        releases: JSONArray,
        wantDebugVariant: Boolean,
    ): AvailableBuild? {
        val wantSuffix = if (wantDebugVariant) "-debug.apk" else "-release.apk"
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.optBoolean("draft", false)) continue
            val assets = release.optJSONArray("assets") ?: continue
            var fallback: Triple<String, String, Long>? = null
            var match: Triple<String, String, Long>? = null
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                val name = asset.optString("name", "")
                val url = asset.optString("browser_download_url", "")
                if (url.isBlank() || !name.lowercase(Locale.US).endsWith(".apk")) continue
                val entry = Triple(name, url, asset.optLong("size", 0L))
                if (name.lowercase(Locale.US).endsWith(wantSuffix)) match = match ?: entry
                fallback = fallback ?: entry
            }
            val chosen = match ?: fallback ?: continue
            val tag = release.optString("tag_name", "")
            return AvailableBuild(
                tag = tag,
                title = release.optString("name", "").ifBlank { tag },
                versionCode = versionCodeFromTag(tag),
                assetName = chosen.first,
                downloadUrl = chosen.second,
                sizeBytes = chosen.third,
                pageUrl = release.optString("html_url", ""),
            )
        }
        return null
    }

    /**
     * UNKNOWN is a real answer the UI must show as one: if either side has no
     * version code — a dev build, or a hand-named tag — whether an update
     * exists is genuinely not known, and "up to date" would be a confident
     * falsehood.
     */
    fun compare(
        runningVersionCode: Int?,
        availableVersionCode: Int?,
    ): Comparison =
        when {
            runningVersionCode == null || availableVersionCode == null -> Comparison.UNKNOWN
            availableVersionCode > runningVersionCode -> Comparison.NEWER
            availableVersionCode == runningVersionCode -> Comparison.CURRENT
            else -> Comparison.OLDER
        }
}
