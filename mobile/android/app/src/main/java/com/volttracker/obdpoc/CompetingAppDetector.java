package com.volttracker.obdpoc;

import android.content.pm.PackageManager;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Scans installed packages for other Bluetooth-OBD apps that could be holding the adapter's RFCOMM
 * socket open and preventing us from binding. The OS doesn't expose "who owns this BT device", so
 * the next-best signal is "what else is even capable of binding it" — the user is then offered a
 * force-stop button when a connect fails.
 *
 * <p>The detection logic is conservative: a tight allowlist of known OBD apps plus package-name
 * hints from test-injected/package-manager-visible entries. False negatives are fine (the list is
 * documentation, not enforcement); false positives would push the user toward force-stopping an
 * unrelated app.
 *
 * <p>Constructor takes a {@link PackageManager} so unit tests can hand in a stub list of {@link
 * ApplicationInfo}s rather than instantiating the real Android package store.
 */
class CompetingAppDetector {

    /**
     * Curated allowlist of packages known to claim the SPP UUID. Add to this list when a user
     * reports a new culprit. Lower-cased for comparison.
     */
    static final List<String> KNOWN_OBD_PACKAGES =
            Collections.unmodifiableList(
                    Arrays.asList(
                            "io.tripovan.voltage",
                            "com.gretio.obd",
                            "org.prowl.torque",
                            "org.prowl.torquefree",
                            "org.prowl.torqueusb",
                            "com.pnn.obdcardoctor_full",
                            "com.pnn.obdcardoctor",
                            "com.ovz.carscanner",
                            "com.outils.obd2"));

    private final PackageManager packageManager;
    private final ObdService service;
    private final SessionRecorder recorder;
    private final String ownPackageName;

    CompetingAppDetector(
            PackageManager packageManager,
            ObdService service,
            SessionRecorder recorder,
            String ownPackageName) {
        this.packageManager = packageManager;
        this.service = service;
        this.recorder = recorder;
        this.ownPackageName = ownPackageName == null ? "" : ownPackageName;
    }

    /**
     * Enumerates installed apps, picks out the ones likely to claim the OBD adapter, logs the
     * {@code competing_apps_detected} event, and pushes the CSV onto the service so it auto-merges
     * into subsequent status broadcasts. Safe to call repeatedly — each call refreshes both the
     * logged event and the service field.
     *
     * <p>Returns the detected list (also written to {@link ObdService#setCompetingApps(String)})
     * mainly so tests can assert the result without snooping the service.
     */
    List<String> refresh() {
        List<String> detected = detect();
        String csv = String.join(",", detected);
        if (service != null) {
            service.setCompetingApps(csv);
        }
        if (recorder != null) {
            recorder.logEvent(
                    "competing_apps_detected",
                    "count",
                    String.valueOf(detected.size()),
                    "packages",
                    csv);
        }
        return detected;
    }

    /**
     * Pure detection step — visible to tests so we can assert filtering without the side effects of
     * {@link #refresh()}. Returns packages from {@link #KNOWN_OBD_PACKAGES} that are installed on
     * the device, in declaration order so the CSV is stable.
     *
     * <p>An earlier version of this method also ran a second pass that scanned every installed app
     * for {@code "obd"}/{@code "elm327"} substring matches. On API 30+ that pass was dead: without
     * {@link android.Manifest.permission#QUERY_ALL_PACKAGES} (Play-Store-restricted), {@link
     * PackageManager#getInstalledApplications(int)} only returns packages the manifest's {@code
     * <queries>} block lists — which is the same set as {@link #KNOWN_OBD_PACKAGES}, so the second
     * pass could never find anything the first pass had missed. Adding a new package to the
     * detector therefore also requires adding a matching {@code <package>} entry in the manifest.
     */
    List<String> detect() {
        Set<String> ordered = new LinkedHashSet<>();
        for (String known : KNOWN_OBD_PACKAGES) {
            if (known.equalsIgnoreCase(ownPackageName)) {
                continue;
            }
            if (isPackageInstalled(known)) {
                ordered.add(known);
            }
        }
        return new java.util.ArrayList<>(ordered);
    }

    /**
     * Test seam: production probes only the packages declared in {@link #KNOWN_OBD_PACKAGES}. This
     * avoids broad installed-app enumeration, which Android package visibility restricts and lint
     * flags for user-privacy reasons.
     */
    boolean isPackageInstalled(String packageName) {
        if (packageManager == null) {
            return false;
        }
        try {
            packageManager.getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ex) {
            return false;
        } catch (RuntimeException ex) {
            // PackageManager can throw on transient PM errors; treat that as "not installed"
            // rather than letting it crash session start.
            return false;
        }
    }
}
