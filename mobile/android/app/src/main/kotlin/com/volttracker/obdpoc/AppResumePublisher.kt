package com.volttracker.obdpoc

/**
 * Foreground-entry publish lane for the dashboard. MainActivity still owns Android lifecycle calls;
 * this class keeps the resume-time dashboard refresh, visibility notify, auto-connect trigger, and
 * maintenance-due check from living inline in the Activity.
 */
internal class AppResumePublisher(
    private val publishDeviceList: () -> Unit,
    private val publishStorageSummary: () -> Unit,
    private val publishAppState: () -> Unit,
    private val reportAppVisible: () -> Unit,
    private val maybeAutoConnectOnResume: () -> Unit,
    private val scheduleStartupMaintenance: () -> Unit,
    private val submitBackground: (Runnable) -> Unit,
    private val checkMaintenanceDue: () -> Unit,
    private val logMaintenanceFailure: (RuntimeException) -> Unit,
) {
    fun onResume(dashboardReady: Boolean) {
        if (dashboardReady) {
            publishDeviceList()
            publishStorageSummary()
            publishAppState()
            scheduleStartupMaintenance()
        } else {
            StartupTrace.mark("resume_dashboard_publish_skipped_not_ready")
        }
        reportAppVisible()
        maybeAutoConnectOnResume()
        submitBackground(
            Runnable {
                try {
                    checkMaintenanceDue()
                } catch (ex: RuntimeException) {
                    logMaintenanceFailure(ex)
                }
            },
        )
    }
}
