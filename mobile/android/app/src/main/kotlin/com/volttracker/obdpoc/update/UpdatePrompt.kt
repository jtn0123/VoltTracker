package com.volttracker.obdpoc.update

/**
 * Decisions behind the launcher's one-shot "update available" dialog, split out of
 * [com.volttracker.obdpoc.MainActivity] so they are unit-testable without an Activity.
 */
internal object UpdatePrompt {
    /** What the user should be told for a download progress tick. */
    enum class Progress { NONE, FAILED, INSTALLING }

    /** The build to offer, or null when the check found nothing installable. */
    fun offeredBuild(result: UpdateManager.CheckResult): UpdateFeed.AvailableBuild? =
        (result as? UpdateManager.CheckResult.UpdateAvailable)?.build

    /** [UpdateManager] reports -1 for a failed download and 100 once the installer takes over. */
    fun progress(percent: Int): Progress =
        when {
            percent < 0 -> Progress.FAILED
            percent >= 100 -> Progress.INSTALLING
            else -> Progress.NONE
        }
}
