package com.volttracker.obdpoc.update

import android.app.Activity
import android.app.AlertDialog
import android.util.Log
import android.widget.Toast
import com.volttracker.obdpoc.AppPrefs
import com.volttracker.obdpoc.R

/**
 * How the classic dashboard tells the user an update did not install. While the screen is in
 * front it shows a dialog, with "Try again" when a retry could help; Android 12+ cuts text
 * toasts to two lines, which is too short for these messages. If the user has left the screen,
 * a toast still says it.
 */
internal object UpdateFailureNotice {
    fun show(
        activity: Activity,
        inFront: Boolean,
        reason: UpdateManager.InstallFailure,
        onRetry: () -> Unit,
    ) {
        val message = UpdatePrompt.failureMessage(reason)
        if (!inFront || activity.isFinishing || activity.isDestroyed) {
            toast(activity, message)
            return
        }
        try {
            val dialog =
                AlertDialog
                    .Builder(activity)
                    .setTitle(R.string.update_failed_title)
                    .setMessage(message)
            if (UpdatePrompt.retryable(reason)) {
                dialog
                    .setPositiveButton(R.string.update_failed_retry) { _, _ -> onRetry() }
                    .setNegativeButton(R.string.update_failed_close, null)
            } else {
                dialog.setPositiveButton(R.string.update_failed_close, null)
            }
            dialog.show()
        } catch (ex: RuntimeException) {
            Log.w(AppPrefs.LOG_TAG, "update failure dialog failed", ex)
            toast(activity, message)
        }
    }

    private fun toast(
        activity: Activity,
        message: Int,
    ) {
        Toast.makeText(activity.applicationContext, message, Toast.LENGTH_LONG).show()
    }
}
