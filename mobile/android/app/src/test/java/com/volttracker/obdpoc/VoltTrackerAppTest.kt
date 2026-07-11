package com.volttracker.obdpoc

import android.os.StrictMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = VoltTrackerApp::class)
class VoltTrackerAppTest {
    @Test
    fun appBootstrapsWithStrictModeArmedInDebug() {
        val app = RuntimeEnvironment.getApplication()
        assertTrue(app is VoltTrackerApp)
        // Unit tests run the debug variant, so onCreate must have armed a non-LAX
        // thread policy. (StrictMode.ThreadPolicy.LAX is the unarmed default.)
        assertNotEquals(
            StrictMode.ThreadPolicy.LAX.toString(),
            StrictMode.getThreadPolicy().toString(),
        )
        assertNotEquals(
            StrictMode.VmPolicy.LAX.toString(),
            StrictMode.getVmPolicy().toString(),
        )
    }

    @Test
    fun appInstallsTheUncaughtCrashCapture() {
        // onCreate already ran (Robolectric builds the manifest Application before each test).
        // Compared by class name because the JVM-global default handler may have been installed
        // by another Robolectric sandbox's copy of the class.
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        assertNotNull("VoltTrackerApp.onCreate must install crash capture (report item B4)", handler)
        assertEquals(UncaughtCrashLogger::class.java.name, handler!!.javaClass.name)
    }
}
