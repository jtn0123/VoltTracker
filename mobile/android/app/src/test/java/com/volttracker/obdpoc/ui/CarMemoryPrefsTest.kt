package com.volttracker.obdpoc.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.volttracker.obdpoc.ui.car.CarMemory
import com.volttracker.obdpoc.ui.car.CarMemoryPrefs
import com.volttracker.obdpoc.ui.drive.TirePressures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Car tab's remembered tyres and oil life, kept in the shared prefs file between app runs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CarMemoryPrefsTest {
    private val prefs =
        ApplicationProvider
            .getApplicationContext<Context>()
            .getSharedPreferences("car-memory-test", Context.MODE_PRIVATE)

    @Test
    fun aNewInstallRemembersNothing() {
        assertEquals(CarMemory(), CarMemoryPrefs.read(prefs))
    }

    @Test
    fun tiresAndOilLifeComeBackAsTheyWereStored() {
        CarMemoryPrefs.write(prefs, CarMemory(TirePressures(38.04, 37.96, 36.5, 38.0), 1_000L, 69, 2_000L))
        val back = CarMemoryPrefs.read(prefs)
        assertEquals(TirePressures(38.0, 38.0, 36.5, 38.0), back.tires)
        assertEquals(1_000L, back.tiresAtMs)
        assertEquals(69, back.oilLifePct)
        assertEquals(2_000L, back.oilAtMs)
        assertTrue(prefs.all.keys.all { it.startsWith(CarMemoryPrefs.PREFIX) })
    }

    @Test
    fun nothingNewKeepsWhatWasStored() {
        CarMemoryPrefs.write(prefs, CarMemory(oilLifePct = 70, oilAtMs = 5L))
        // A session that heard nothing (or only the demo) remembers nothing new.
        CarMemoryPrefs.write(prefs, CarMemory())
        assertEquals(70, CarMemoryPrefs.read(prefs).oilLifePct)
        CarMemoryPrefs.write(prefs, CarMemory(oilLifePct = 68, oilAtMs = 9L))
        assertEquals(CarMemory(oilLifePct = 68, oilAtMs = 9L), CarMemoryPrefs.read(prefs))
    }

    @Test
    fun aDamagedTireEntryIsIgnored() {
        prefs
            .edit()
            .putString("car_memory_tires_psi", "38.0,abc")
            .putLong("car_memory_tires_at", 5L)
            .commit()
        val back = CarMemoryPrefs.read(prefs)
        assertNull(back.tires)
        assertEquals(0L, back.tiresAtMs)
    }
}
