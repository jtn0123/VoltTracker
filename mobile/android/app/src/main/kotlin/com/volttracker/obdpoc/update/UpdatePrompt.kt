package com.volttracker.obdpoc.update

import androidx.annotation.StringRes
import com.volttracker.obdpoc.R
import com.volttracker.obdpoc.update.UpdateManager.InstallEvent
import com.volttracker.obdpoc.update.UpdateManager.InstallFailure

/**
 * Decisions behind the update UI — the launcher's one-shot "update available" dialog and the
 * Settings update section — split out of the Activities so they are unit-testable without one.
 * Both surfaces show the same message for the same outcome.
 */
internal object UpdatePrompt {
    /** The build to offer, or null when the check found nothing installable. */
    fun offeredBuild(result: UpdateManager.CheckResult): UpdateFeed.AvailableBuild? =
        (result as? UpdateManager.CheckResult.UpdateAvailable)?.build

    /** What to tell the user for [event], or null while bytes are still arriving. */
    @StringRes
    fun message(event: InstallEvent): Int? =
        when (event) {
            is InstallEvent.Downloading -> null
            InstallEvent.InstallerOpened -> R.string.update_opening_installer
            is InstallEvent.Failed -> failureMessage(event.reason)
        }

    /** The download percent to draw for [event], or null once the attempt has ended. */
    fun percent(event: InstallEvent): Int? = (event as? InstallEvent.Downloading)?.percent

    /**
     * The Settings status line for [event]. While bytes arrive it stays "<tag> is available";
     * an outcome replaces it. The caller keeps the tag on failure, so the "Update to …" button
     * comes back for a retry while this line says what went wrong.
     */
    fun statusLine(
        event: InstallEvent,
        tag: String,
        text: (Int) -> String,
    ): String = message(event)?.let(text) ?: "$tag is available"

    /** One message per failure, each saying what happened and what the user can do next. */
    @StringRes
    fun failureMessage(reason: InstallFailure): Int =
        when (reason) {
            InstallFailure.BUSY -> R.string.update_failed_busy
            InstallFailure.NETWORK -> R.string.update_failed_network
            InstallFailure.SERVER -> R.string.update_failed_server
            InstallFailure.INCOMPLETE -> R.string.update_failed_incomplete
            InstallFailure.NOT_THIS_APP -> R.string.update_failed_not_this_app
            InstallFailure.STORAGE -> R.string.update_failed_storage
            InstallFailure.INSTALLER -> R.string.update_failed_installer
            InstallFailure.UNEXPECTED -> R.string.update_failed_unexpected
        }

    /**
     * Whether retrying the same download could succeed. A wrong-key file or a missing
     * installer will fail the same way again, so those are not offered a retry.
     */
    fun retryable(reason: InstallFailure): Boolean =
        when (reason) {
            InstallFailure.NOT_THIS_APP, InstallFailure.INSTALLER -> false
            InstallFailure.BUSY,
            InstallFailure.NETWORK,
            InstallFailure.SERVER,
            InstallFailure.INCOMPLETE,
            InstallFailure.STORAGE,
            InstallFailure.UNEXPECTED,
            -> true
        }
}
