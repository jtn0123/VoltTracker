package com.volttracker.obdpoc

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests [CompetingAppDetector.detect] — the filtering / ordering rules. Uses a stub subclass that
 * overrides [CompetingAppDetector.isPackageInstalled] so we don't need to plumb a real
 * [android.content.pm.PackageManager]. [ApplicationInfo] is a Parcelable data class so we run under
 * Robolectric to get a real Android runtime; the test stays pure-logic otherwise.
 */
@RunWith(RobolectricTestRunner::class)
// Project targetSdk is 36; Robolectric 4.x ships SDK 34 as its newest. Pin so CI doesn't try to
// load an SDK Robolectric doesn't have (which fails with UnsupportedOperationException in
// DefaultSdkProvider). All the other Robolectric tests in this module pin the same way.
@Config(sdk = [34])
class CompetingAppDetectorTest {
    @Test
    fun emptyInstalledListReturnsEmpty() {
        val d = withInstalled("com.volttracker.obdpoc", emptyList())
        assertTrue(d.detect().isEmpty())
    }

    @Test
    fun nullPackageManagerProductionPathReturnsEmpty() {
        val d = CompetingAppDetector(null, null, null, "com.volttracker.obdpoc")
        assertTrue(d.detect().isEmpty())
    }

    @Test
    fun detectsKnownObdAppFromAllowlist() {
        val d =
            withInstalled(
                "com.volttracker.obdpoc",
                listOf(
                    info("com.android.chrome"),
                    info("io.tripovan.voltage"),
                    info("com.google.android.youtube"),
                ),
            )
        val detected = d.detect()
        assertEquals(listOf("io.tripovan.voltage"), detected)
    }

    @Test
    fun doesNotDetectNonAllowlistedPackagesEvenIfTheirNamesSuggestObd() {
        // Non-allowlist packages stay invisible even when their names look OBD-flavored; production
        // probes only explicit <queries> packages instead of enumerating installed apps broadly.
        val d =
            withInstalled(
                "com.volttracker.obdpoc",
                listOf(
                    info("com.android.chrome"),
                    info("com.example.someobdhelper"),
                    info("net.fake.elm327scanner"),
                ),
            )
        assertTrue(d.detect().isEmpty())
    }

    @Test
    fun skipsOwnPackageEvenWhenItMatchesTheAllowlistPrefixCheck() {
        // The app's own package literally contains "obd" — must never appear in the CSV even
        // if a future allowlist entry collided with our package name.
        val d =
            withInstalled(
                "com.volttracker.obdpoc",
                listOf(info("com.volttracker.obdpoc"), info("io.tripovan.voltage")),
            )
        val detected = d.detect()
        assertEquals(listOf("io.tripovan.voltage"), detected)
        assertFalse(detected.contains("com.volttracker.obdpoc"))
    }

    @Test
    fun allowlistEntriesAreReturnedInDeclarationOrder() {
        val d =
            withInstalled(
                "com.volttracker.obdpoc",
                listOf(
                    info("com.example.obdhelper"), // not on allowlist — ignored
                    info("io.tripovan.voltage"),
                    info("org.prowl.torque"),
                ),
            )
        val detected = d.detect()
        assertEquals(listOf("io.tripovan.voltage", "org.prowl.torque"), detected)
    }

    @Test
    fun ignoresPackagesNotOnAllowlist() {
        val d =
            withInstalled(
                "com.volttracker.obdpoc",
                listOf(
                    info("com.android.chrome"),
                    info("com.google.android.gm"),
                    info("com.spotify.music"),
                ),
            )
        assertTrue(d.detect().isEmpty())
    }

    @Test
    fun allowlistMatchesAreUnique() {
        // Trivial de-duplication coverage: an unrelated package alongside an allowlist entry
        // returns exactly one detection.
        val installed = ArrayList<ApplicationInfo>()
        installed.add(info("io.tripovan.voltage"))
        installed.add(info("org.fake.has-obd-in-name"))
        val d = withInstalled("com.volttracker.obdpoc", installed)
        val detected = d.detect()
        assertEquals(listOf("io.tripovan.voltage"), detected)
    }

    @Test
    fun handlesNullPackageNamesGracefully() {
        val nullPkg = ApplicationInfo()
        nullPkg.packageName = null
        val d =
            withInstalled(
                "com.volttracker.obdpoc",
                listOf(nullPkg, info("io.tripovan.voltage")),
            )
        assertEquals(listOf("io.tripovan.voltage"), d.detect())
    }

    companion object {
        private fun info(pkg: String): ApplicationInfo {
            val a = ApplicationInfo()
            a.packageName = pkg
            return a
        }

        /**
         * Detector that doesn't talk to a real PackageManager — we hand it the installed list at
         * construction time.
         */
        private fun withInstalled(
            ownPackage: String,
            installed: List<ApplicationInfo>,
        ): CompetingAppDetector =
            object : CompetingAppDetector(null, null, null, ownPackage) {
                override fun isPackageInstalled(packageName: String): Boolean {
                    for (info in installed) {
                        if (packageName == info.packageName) {
                            return true
                        }
                    }
                    return false
                }
            }
    }
}
