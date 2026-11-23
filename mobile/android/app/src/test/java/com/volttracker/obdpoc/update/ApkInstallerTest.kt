package com.volttracker.obdpoc.update

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * [SystemApkInstaller] up to the hand-off: the intent it fires and what it reports when
 * Android cannot open the installer. The install confirmation itself is the system's and
 * stays a manual on-device step.
 */
@RunWith(AndroidJUnit4::class)
class ApkInstallerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun resetFileProviderCache() {
        // FileProvider caches a PathStrategy keyed to the first cacheDir it saw; one cached by an
        // earlier test points at a stale root and makes getUriForFile throw (the same reset as
        // TripExportShareIntentTest).
        try {
            val field = FileProvider::class.java.getDeclaredField("sCache")
            field.isAccessible = true
            (field.get(null) as? MutableMap<*, *>)?.clear()
        } catch (_: ReflectiveOperationException) {
            // Renamed in a future androidx: these tests may turn order-sensitive again, and the
            // happy-path assertion will say so rather than this setup crashing.
        }
    }

    private fun downloadedApk(): File =
        File(File(app.cacheDir, UpdateManager.UPDATES_DIR).apply { mkdirs() }, "volttracker-v0.40.0-release.apk")
            .apply { writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04)) }

    @Test
    fun launchesTheInstallSheetWithAContentUriAndAReadGrant() {
        assertTrue(SystemApkInstaller(app).launch(downloadedApk()))

        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(SystemApkInstaller.APK_MIME, intent.type)
        assertEquals("content", intent.data?.scheme)
        assertEquals("${app.packageName}.fileprovider", intent.data?.authority)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun aMissingInstallerIsReportedAsAFailedLaunch() {
        var attempted: Intent? = null
        val noInstaller =
            object : ContextWrapper(app) {
                override fun startActivity(intent: Intent?) {
                    attempted = intent
                    throw ActivityNotFoundException("no package installer")
                }
            }

        assertFalse(SystemApkInstaller(noInstaller).launch(downloadedApk()))
        // The URI was built; the failure is Android having nothing to open it with.
        assertEquals(Intent.ACTION_VIEW, attempted?.action)
    }

    @Test
    fun aFileOutsideTheSharedUpdatesDirIsAFailedLaunchNotACrash() {
        val stray = File(app.filesDir, "stray.apk").apply { writeText("x") }

        assertFalse(SystemApkInstaller(app).launch(stray))
        assertNull(shadowOf(app).nextStartedActivity)
    }
}
