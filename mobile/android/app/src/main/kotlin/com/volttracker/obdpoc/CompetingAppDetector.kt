package com.volttracker.obdpoc

import android.content.pm.PackageManager
import android.util.Log
import java.util.LinkedHashSet

/**
 * Scans installed packages for other Bluetooth-OBD apps that could be holding the adapter's RFCOMM
 * socket open and preventing us from binding. The OS doesn't expose "who owns this BT device", so
 * the next-best signal is "what else is even capable of binding it" -- the user is then offered a
 * force-stop button when a connect fails.
 *
 * The detection logic is conservative: a tight allowlist of known OBD apps plus package-name
 * hints from test-injected/package-manager-visible entries. False negatives are fine (the list is
 * documentation, not enforcement); false positives would push the user toward force-stopping an
 * unrelated app.
 */
open class CompetingAppDetector(
    private val packageManager: PackageManager?,
    private val service: ObdService?,
    private val recorder: SessionRecorder?,
    ownPackageName: String?,
) {
    private val ownPackageName = ownPackageName ?: ""

    /**
     * Enumerates installed apps, picks out the ones likely to claim the OBD adapter, logs the
     * `competing_apps_detected` event, and pushes the CSV onto the service so it auto-merges into
     * subsequent status broadcasts. Safe to call repeatedly -- each call refreshes both the logged
     * event and the service field.
     *
     * Returns the detected list mainly so tests can assert the result without snooping the service.
     */
    fun refresh(): List<String> {
        val detected = detect()
        val csv = detected.joinToString(",")
        service?.setCompetingApps(csv)
        recorder?.logEvent(
            "competing_apps_detected",
            "count",
            detected.size.toString(),
            "packages",
            csv,
        )
        return detected
    }

    /**
     * Pure detection step -- visible to tests so we can assert filtering without the side effects of
     * [refresh]. Returns packages from [KNOWN_OBD_PACKAGES] that are installed on the device, in
     * declaration order so the CSV is stable.
     */
    fun detect(): List<String> {
        val ordered = LinkedHashSet<String>()
        for (known in KNOWN_OBD_PACKAGES) {
            if (known.equals(ownPackageName, ignoreCase = true)) {
                continue
            }
            if (isPackageInstalled(known)) {
                ordered.add(known)
            }
        }
        return ArrayList(ordered)
    }

    /**
     * Test seam: production probes only the packages declared in [KNOWN_OBD_PACKAGES]. This avoids
     * broad installed-app enumeration, which Android package visibility restricts and lint flags for
     * user-privacy reasons.
     */
    open fun isPackageInstalled(packageName: String): Boolean {
        val pm = packageManager ?: return false
        return try {
            pm.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "Competing-app package probe failed for $packageName", ex)
            false
        }
    }

    companion object {
        /**
         * Curated allowlist of packages known to claim the SPP UUID. Add to this list when a user
         * reports a new culprit. Lower-cased for comparison.
         */
        @JvmField
        val KNOWN_OBD_PACKAGES: List<String> =
            listOf(
                "io.tripovan.voltage",
                "com.gretio.obd",
                "org.prowl.torque",
                "org.prowl.torquefree",
                "org.prowl.torqueusb",
                "com.pnn.obdcardoctor_full",
                "com.pnn.obdcardoctor",
                "com.ovz.carscanner",
                "com.outils.obd2",
            )
    }
}
