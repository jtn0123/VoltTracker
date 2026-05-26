package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.ApplicationInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Tests {@link CompetingAppDetector#detect()} — the filtering / ordering rules. Uses a stub
 * subclass that overrides {@link CompetingAppDetector#installedApplications()} so we don't need to
 * plumb a real {@link android.content.pm.PackageManager}. {@code ApplicationInfo} is a Parcelable
 * data class so we run under Robolectric to get a real Android runtime; the test stays pure-logic
 * otherwise.
 */
@RunWith(RobolectricTestRunner.class)
// Project targetSdk is 36; Robolectric 4.x ships SDK 34 as its newest. Pin so CI doesn't try to
// load an SDK Robolectric doesn't have (which fails with UnsupportedOperationException in
// DefaultSdkProvider). All the other Robolectric tests in this module pin the same way.
@Config(sdk = 34)
public class CompetingAppDetectorTest {

    private static ApplicationInfo info(String pkg) {
        ApplicationInfo a = new ApplicationInfo();
        a.packageName = pkg;
        return a;
    }

    /**
     * Detector that doesn't talk to a real PackageManager — we hand it the installed list at
     * construction time.
     */
    private static CompetingAppDetector withInstalled(
            String ownPackage, List<ApplicationInfo> installed) {
        return new CompetingAppDetector(null, null, null, ownPackage) {
            @Override
            List<ApplicationInfo> installedApplications() {
                return installed;
            }
        };
    }

    @Test
    public void emptyInstalledListReturnsEmpty() {
        CompetingAppDetector d = withInstalled("com.volttracker.obdpoc", Collections.emptyList());
        assertTrue(d.detect().isEmpty());
    }

    @Test
    public void nullPackageManagerProductionPathReturnsEmpty() {
        CompetingAppDetector d =
                new CompetingAppDetector(null, null, null, "com.volttracker.obdpoc");
        assertTrue(d.detect().isEmpty());
    }

    @Test
    public void detectsKnownObdAppFromAllowlist() {
        CompetingAppDetector d =
                withInstalled(
                        "com.volttracker.obdpoc",
                        Arrays.asList(
                                info("com.android.chrome"),
                                info("io.tripovan.voltage"),
                                info("com.google.android.youtube")));
        List<String> detected = d.detect();
        assertEquals(Collections.singletonList("io.tripovan.voltage"), detected);
    }

    @Test
    public void doesNotDetectNonAllowlistedPackagesEvenIfTheirNamesSuggestObd() {
        // Earlier versions ran a substring-match second pass for "obd"/"elm327"; that pass was
        // dead on Android 11+ (getInstalledApplications only returns packages the manifest
        // <queries> block lists) and has been removed. Non-allowlist packages stay invisible
        // even when their names look OBD-flavored.
        CompetingAppDetector d =
                withInstalled(
                        "com.volttracker.obdpoc",
                        Arrays.asList(
                                info("com.android.chrome"),
                                info("com.example.someobdhelper"),
                                info("net.fake.elm327scanner")));
        assertTrue(d.detect().isEmpty());
    }

    @Test
    public void skipsOwnPackageEvenWhenItMatchesTheAllowlistPrefixCheck() {
        // The app's own package literally contains "obd" — must never appear in the CSV even
        // if a future allowlist entry collided with our package name.
        CompetingAppDetector d =
                withInstalled(
                        "com.volttracker.obdpoc",
                        Arrays.asList(info("com.volttracker.obdpoc"), info("io.tripovan.voltage")));
        List<String> detected = d.detect();
        assertEquals(Collections.singletonList("io.tripovan.voltage"), detected);
        assertFalse(detected.contains("com.volttracker.obdpoc"));
    }

    @Test
    public void allowlistEntriesAreReturnedInDeclarationOrder() {
        CompetingAppDetector d =
                withInstalled(
                        "com.volttracker.obdpoc",
                        Arrays.asList(
                                info("com.example.obdhelper"), // not on allowlist — ignored
                                info("io.tripovan.voltage"),
                                info("org.prowl.torque")));
        List<String> detected = d.detect();
        assertEquals(Arrays.asList("io.tripovan.voltage", "org.prowl.torque"), detected);
    }

    @Test
    public void ignoresPackagesNotOnAllowlist() {
        CompetingAppDetector d =
                withInstalled(
                        "com.volttracker.obdpoc",
                        Arrays.asList(
                                info("com.android.chrome"),
                                info("com.google.android.gm"),
                                info("com.spotify.music")));
        assertTrue(d.detect().isEmpty());
    }

    @Test
    public void allowlistMatchesAreUnique() {
        // Trivial de-duplication coverage: an unrelated package alongside an allowlist entry
        // returns exactly one detection.
        List<ApplicationInfo> installed = new ArrayList<>();
        installed.add(info("io.tripovan.voltage"));
        installed.add(info("org.fake.has-obd-in-name"));
        CompetingAppDetector d = withInstalled("com.volttracker.obdpoc", installed);
        List<String> detected = d.detect();
        assertEquals(Collections.singletonList("io.tripovan.voltage"), detected);
    }

    @Test
    public void handlesNullPackageNamesGracefully() {
        ApplicationInfo nullPkg = new ApplicationInfo();
        nullPkg.packageName = null;
        CompetingAppDetector d =
                withInstalled(
                        "com.volttracker.obdpoc",
                        Arrays.asList(nullPkg, info("io.tripovan.voltage")));
        assertEquals(Collections.singletonList("io.tripovan.voltage"), d.detect());
    }
}
