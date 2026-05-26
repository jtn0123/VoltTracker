package com.volttracker.obdpoc;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Bucket 2 (A7): scans installed packages for other Bluetooth-OBD apps that could be holding the
 * adapter's RFCOMM socket open and preventing us from binding. The OS doesn't expose "who owns this
 * BT device", so the next-best signal is "what else is even capable of binding it" — the user is
 * then offered a force-stop button (Bucket 4a's C4) when a connect fails.
 *
 * <p>The detection logic is conservative: a tight allowlist of known OBD apps plus any package name
 * that contains "obd" or "elm327". False negatives are fine (the list is documentation, not
 * enforcement); false positives would push the user toward force-stopping an unrelated app.
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

    /** Substrings that flag a probable OBD-capable app even if it's not on the allowlist. */
    private static final String[] NAME_HINTS = {"obd", "elm327"};

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
     * {@link #refresh()}. Preserves insertion order: known packages first (in the allowlist order
     * they were seen), then hint-matched packages in install order.
     */
    List<String> detect() {
        Set<String> ordered = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        List<ApplicationInfo> installed = installedApplications();
        if (installed == null) {
            return Collections.emptyList();
        }
        // First pass: collect all installed package names (lower-cased) for the allowlist match.
        for (ApplicationInfo info : installed) {
            if (info == null || info.packageName == null) {
                continue;
            }
            seen.add(info.packageName.toLowerCase(Locale.US));
        }
        // Walk the allowlist in declaration order so the resulting CSV is stable.
        for (String known : KNOWN_OBD_PACKAGES) {
            if (known.equalsIgnoreCase(ownPackageName)) {
                continue;
            }
            if (seen.contains(known)) {
                ordered.add(known);
            }
        }
        // Second pass: name-hint match (anything we haven't already captured).
        for (ApplicationInfo info : installed) {
            if (info == null || info.packageName == null) {
                continue;
            }
            String pkg = info.packageName.toLowerCase(Locale.US);
            if (pkg.equalsIgnoreCase(ownPackageName)) {
                continue;
            }
            if (ordered.contains(pkg)) {
                continue;
            }
            if (containsHint(pkg)) {
                ordered.add(pkg);
            }
        }
        return new ArrayList<>(ordered);
    }

    /**
     * Test seam: returns the installed-application list. Production resolves via {@link
     * PackageManager#getInstalledApplications(int)}; tests override to inject a fixed list without
     * standing up a Robolectric environment for what is otherwise pure filtering logic.
     */
    List<ApplicationInfo> installedApplications() {
        if (packageManager == null) {
            return Collections.emptyList();
        }
        try {
            return packageManager.getInstalledApplications(0);
        } catch (RuntimeException ex) {
            // PackageManager.getInstalledApplications can throw on transient PM errors; treat
            // that as "nothing found" rather than letting it crash session start.
            return Collections.emptyList();
        }
    }

    private static boolean containsHint(String pkg) {
        for (String hint : NAME_HINTS) {
            if (pkg.contains(hint)) {
                return true;
            }
        }
        return false;
    }
}
