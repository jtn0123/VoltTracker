package com.volttracker.obdpoc

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CarControlAuthTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var settings: CarControlSettings

    @Before
    fun setUp() {
        prefs =
            ApplicationProvider.getApplicationContext<Context>().getSharedPreferences(
                AppPrefs.FILE,
                Context.MODE_PRIVATE,
            )
        prefs.edit().clear().commit()
        settings = CarControlSettings { prefs }
        CarControlAuth.resetForTest()
    }

    @After
    fun tearDown() {
        CarControlAuth.resetForTest()
    }

    @Test
    fun offByDefault() {
        assertFalse(settings.isEnabled())
        assertFalse(CarControlSettings { null }.isEnabled())
        assertFalse(CarControlSettings { null }.enable("1234"))
        assertFalse(CarControlSettings { null }.verifyPin("1234"))
    }

    @Test
    fun enableStoresASaltedHashNeverThePin() {
        assertTrue(settings.enable("135790"))
        assertTrue(settings.isEnabled())
        val stored = prefs.all
        assertTrue(stored.values.none { it.toString().contains("135790") })
        val hash = prefs.getString(CarControlSettings.KEY_PIN_HASH, null)!!
        assertEquals(64, hash.length)
        assertEquals(32, prefs.getString(CarControlSettings.KEY_PIN_SALT, null)!!.length)
        assertTrue(stored.keys.all { it.startsWith(CarControlSettings.PREFIX) })
        // Same PIN, new salt, different hash.
        assertTrue(settings.enable("135790"))
        assertNotEquals(hash, prefs.getString(CarControlSettings.KEY_PIN_HASH, null))
    }

    @Test
    fun verifyPin() {
        settings.enable("2468")
        assertTrue(settings.verifyPin("2468"))
        assertFalse(settings.verifyPin("2469"))
        assertFalse(settings.verifyPin("24680"))
        assertFalse(settings.verifyPin(""))
    }

    @Test
    fun corruptSaltFailsClosed() {
        settings.enable("2468")
        prefs.edit().putString(CarControlSettings.KEY_PIN_SALT, "zz").commit()
        assertFalse(settings.verifyPin("2468"))
        prefs.edit().putString(CarControlSettings.KEY_PIN_SALT, "abc").commit()
        assertFalse(settings.verifyPin("2468"))
    }

    @Test
    fun enabledFlagWithoutHashIsOff() {
        prefs.edit().putBoolean(CarControlSettings.KEY_ENABLED, true).commit()
        assertFalse(settings.isEnabled())
    }

    @Test
    fun pinMustBeFourToEightDigits() {
        for (bad in listOf("123", "123456789", "12a4", "", " 1234", "１２３４")) {
            assertFalse(bad, CarControlSettings.isValidPin(bad))
            assertFalse(bad, settings.enable(bad))
        }
        assertFalse(CarControlSettings.isValidPin(null))
        assertTrue(CarControlSettings.isValidPin("0000"))
        assertTrue(CarControlSettings.isValidPin("12345678"))
        assertFalse(settings.isEnabled())
    }

    @Test
    fun disableWipesThePin() {
        settings.enable("2468")
        settings.disable()
        assertFalse(settings.isEnabled())
        assertFalse(settings.verifyPin("2468"))
        assertFalse(prefs.contains(CarControlSettings.KEY_PIN_HASH))
        assertFalse(prefs.contains(CarControlSettings.KEY_PIN_SALT))
    }

    @Test
    fun correctPinOpensAFiveMinuteWindow() {
        assertFalse(CarControlAuth.isUnlocked(0L))
        assertTrue(CarControlAuth.recordPinAttempt(true, 1_000L))
        assertTrue(CarControlAuth.isUnlocked(1_000L + CarControlAuth.UNLOCK_WINDOW_MS - 1))
        assertEquals(CarControlAuth.UNLOCK_WINDOW_MS - 1, CarControlAuth.unlockedRemainingMs(1_001L))
        assertFalse(CarControlAuth.isUnlocked(1_000L + CarControlAuth.UNLOCK_WINDOW_MS))
        assertEquals(0L, CarControlAuth.unlockedRemainingMs(1_000L + CarControlAuth.UNLOCK_WINDOW_MS + 5))
        CarControlAuth.recordPinAttempt(true, 2_000L)
        CarControlAuth.relock()
        assertFalse(CarControlAuth.isUnlocked(2_001L))
    }

    @Test
    fun wrongPinsLockOut() {
        repeat(CarControlAuth.MAX_PIN_FAILURES - 1) { assertFalse(CarControlAuth.recordPinAttempt(false, 0L)) }
        assertFalse(CarControlAuth.isPinLockedOut(0L))
        CarControlAuth.recordPinAttempt(false, 0L)
        assertTrue(CarControlAuth.isPinLockedOut(0L))
        assertFalse(CarControlAuth.isPinLockedOut(CarControlAuth.PIN_LOCKOUT_MS))
        assertFalse(CarControlAuth.isUnlocked(0L))
    }

    @Test
    fun confirmationIsOneShotPerCommandWithinItsTtl() {
        CarControlAuth.confirm(CarCommand.LOCK, 0L)
        assertTrue(CarControlAuth.consume(CarCommand.LOCK, 10L))
        assertFalse("no replay", CarControlAuth.consume(CarCommand.LOCK, 11L))

        CarControlAuth.confirm(CarCommand.LOCK, 0L)
        assertFalse("other command", CarControlAuth.consume(CarCommand.UNLOCK, 1L))
        assertFalse("mismatch consumed the grant", CarControlAuth.consume(CarCommand.LOCK, 2L))

        CarControlAuth.confirm(CarCommand.LOCK, 0L)
        assertFalse("expired", CarControlAuth.consume(CarCommand.LOCK, CarControlAuth.CONFIRMATION_TTL_MS + 1))

        CarControlAuth.confirm(CarCommand.LOCK, 100L)
        assertFalse("clock went backwards", CarControlAuth.consume(CarCommand.LOCK, 99L))

        CarControlAuth.confirm(CarCommand.LOCK, 0L)
        CarControlAuth.relock()
        assertFalse("relock drops the grant", CarControlAuth.consume(CarCommand.LOCK, 1L))
    }
}
