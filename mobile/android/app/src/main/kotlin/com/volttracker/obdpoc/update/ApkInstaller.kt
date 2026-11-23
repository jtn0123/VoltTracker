package com.volttracker.obdpoc.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.volttracker.obdpoc.AppPrefs
import java.io.File

/**
 * Opens Android's install sheet for an APK that already passed [ApkSignerCheck]. Returns true
 * once the sheet is launched and false when it could not be opened, so a launch failure is
 * reported to the user instead of being lost. Confirming the install stays the user's step.
 */
internal fun interface ApkInstaller {
    fun launch(apk: File): Boolean
}

/**
 * Hands the APK to the package installer as a `content://` URI from the app's FileProvider —
 * bare `file://` URIs have been refused since Android 7, and the read grant travels with the
 * intent flag. The install itself belongs to the system from here.
 */
internal class SystemApkInstaller(
    private val context: Context,
) : ApkInstaller {
    override fun launch(apk: File): Boolean =
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            val intent =
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (ex: RuntimeException) {
            // ActivityNotFoundException (no installer for the APK type, e.g. a locked-down
            // profile), SecurityException, or IllegalArgumentException from FileProvider.
            Log.w(AppPrefs.LOG_TAG, "installer launch failed", ex)
            false
        }

    internal companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
