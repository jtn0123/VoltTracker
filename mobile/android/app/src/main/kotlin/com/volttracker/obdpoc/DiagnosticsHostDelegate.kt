package com.volttracker.obdpoc

/**
 * Backs the [DiagnosticsCommands] host seam for [MainActivity]: the troubleshooter forwarders and
 * the setup-guide re-open live here so their bodies stay off the Activity surface, mirroring
 * [EventNotificationHostDelegate] (the A1/I3 growth-by-override fix).
 *
 * Collaborators are lambdas because the [TroubleshooterBridge] and the setup-guide controller are
 * not constructed until the Activity's `onCreate` has run; each command re-reads them at call time
 * so the delegate never caches a stale collaborator across a restore.
 */
class DiagnosticsHostDelegate(
    private val troubleshooter: () -> TroubleshooterBridge,
    private val openSetupGuide: () -> Unit,
) : DiagnosticsCommands {
    override fun forceStopPackageFromBridge(packageName: String?): Boolean =
        troubleshooter().forceStopPackage(packageName)

    override fun cancelRetryFromBridge() {
        troubleshooter().cancelRetry()
    }

    override fun openBluetoothSettingsFromBridge() {
        troubleshooter().openBluetoothSettings()
    }

    override fun getRecentSessionsJson(n: Int): String = troubleshooter().getRecentSessionsJson(n)

    override fun shareDiagnosticsFromBridge() {
        troubleshooter().shareDiagnostics()
    }

    override fun shareDiagnosticsDigestFromBridge() {
        troubleshooter().shareDiagnosticsDigest()
    }

    override fun startTestConnectionFromBridge() {
        troubleshooter().startTestConnection()
    }

    override fun scheduleAdapterReadyNotifyFromBridge(mins: Int) {
        troubleshooter().scheduleAdapterReadyNotify(mins)
    }

    override fun cancelAdapterReadyNotifyFromBridge() {
        troubleshooter().cancelAdapterReadyNotify()
    }

    override fun openSetupGuideFromBridge() = openSetupGuide()
}
