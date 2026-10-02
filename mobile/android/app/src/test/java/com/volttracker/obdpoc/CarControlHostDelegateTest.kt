package com.volttracker.obdpoc

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.os.Looper
import android.widget.EditText
import android.widget.LinearLayout
import com.volttracker.obdpoc.service.ObdService
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CarControlHostDelegateTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var activity: Activity
    private lateinit var prefs: SharedPreferences
    private lateinit var delegate: CarControlHostDelegate
    private val toasts = mutableListOf<String>()
    private val dispatched = mutableListOf<CarCommand>()
    private var appStatePublishes = 0
    private var live = true
    private var now = 1_000_000L

    @Before
    fun setUp() {
        CarControlAuth.resetForTest()
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        activity = controller.get()
        prefs = activity.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        ShadowAlertDialog.reset()
        delegate =
            CarControlHostDelegate(
                activity = activity,
                prefs = { prefs },
                publishAppState = { appStatePublishes += 1 },
                toast = { toasts.add(it) },
                hasLiveSession = { live },
                dispatch = { dispatched.add(it) },
                clock = { now },
            )
    }

    @After
    fun tearDown() {
        CarControlAuth.resetForTest()
        controller.destroy()
    }

    private fun drain() = shadowOf(Looper.getMainLooper()).idle()

    private fun latestDialog(): AlertDialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog

    private fun pinFields(dialog: AlertDialog): List<EditText> {
        val column = shadowOf(dialog).view as LinearLayout
        return (0 until column.childCount).map { column.getChildAt(it) as EditText }
    }

    private fun click(
        dialog: AlertDialog,
        which: Int = DialogInterface.BUTTON_POSITIVE,
    ) {
        dialog.getButton(which).performClick()
        drain()
    }

    private fun state(): JSONObject = JSONObject(delegate.getCarControlStateJson())

    private fun enableWith(
        pin: String,
        repeat: String = pin,
    ) {
        delegate.setCarControlsEnabled(true)
        drain()
        val dialog = latestDialog()
        val (first, second) = pinFields(dialog)
        first.setText(pin)
        second.setText(repeat)
        click(dialog)
    }

    @Test
    fun offByDefault() {
        val state = state()
        assertTrue(state.getBoolean("available"))
        assertFalse(state.getBoolean("enabled"))
        assertFalse(state.getBoolean("unlocked"))
        assertEquals(0L, state.getLong("unlockedRemainingMs"))
        assertFalse(state.getBoolean("pinLockedOut"))
        assertFalse(JSONObject(CarControlCommands.UNAVAILABLE.getCarControlStateJson()).getBoolean("available"))
    }

    @Test
    fun enablingShowsTheWarningAndNeedsMatchingPins() {
        delegate.setCarControlsEnabled(true)
        drain()
        val dialog = latestDialog()
        assertEquals(CarControlHostDelegate.ENABLE_WARNING, shadowOf(dialog).message.toString())
        assertTrue(shadowOf(dialog).title.toString().contains("experimental"))
        click(dialog, DialogInterface.BUTTON_NEGATIVE)
        assertFalse(state().getBoolean("enabled"))

        enableWith("1234", "4321")
        assertFalse(state().getBoolean("enabled"))
        assertTrue(toasts.last().contains("did not match"))

        enableWith("12")
        assertFalse(state().getBoolean("enabled"))
        assertTrue(toasts.last().contains("4 to 8 digits"))

        enableWith("2468")
        assertTrue(state().getBoolean("enabled"))
        assertEquals("Car controls turned on", toasts.last())
        assertTrue(prefs.all.values.none { it.toString().contains("2468") })
        assertEquals(1, appStatePublishes)
    }

    @Test
    fun enablingWhenAlreadyOnShowsNothing() {
        enableWith("2468")
        ShadowAlertDialog.reset()
        delegate.setCarControlsEnabled(true)
        drain()
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
    }

    @Test
    fun disablingIsImmediateAndRelocks() {
        enableWith("2468")
        CarControlAuth.recordPinAttempt(true, now)
        delegate.setCarControlsEnabled(false)
        drain()
        assertFalse(state().getBoolean("enabled"))
        assertFalse(state().getBoolean("unlocked"))
        assertEquals("Car controls turned off", toasts.last())
    }

    @Test
    fun commandNeedsPinThenConfirmsOnceAndDispatches() {
        enableWith("2468")
        delegate.requestCarControl("unlock")
        drain()
        val dialog = latestDialog()
        assertEquals("Unlock the doors?", shadowOf(dialog).title.toString())
        assertTrue(shadowOf(dialog).message.toString().contains("doors will unlock"))
        assertEquals("Unlock the doors", dialog.getButton(DialogInterface.BUTTON_POSITIVE).text.toString())
        pinFields(dialog).single().setText("2468")
        click(dialog)
        assertEquals(listOf(CarCommand.UNLOCK), dispatched)
        assertTrue(CarControlAuth.consume(CarCommand.UNLOCK, now))
        assertTrue(state().getBoolean("unlocked"))
        assertEquals("Sending: Unlock the doors", toasts.last())
    }

    @Test
    fun withinTheUnlockWindowOnlyTheConfirmationIsShown() {
        enableWith("2468")
        CarControlAuth.recordPinAttempt(true, now)
        delegate.requestCarControl("flash")
        drain()
        val dialog = latestDialog()
        assertNull(shadowOf(dialog).view)
        click(dialog)
        assertEquals(listOf(CarCommand.FLASH_LIGHTS), dispatched)
    }

    @Test
    fun cancelSendsNothing() {
        enableWith("2468")
        CarControlAuth.recordPinAttempt(true, now)
        delegate.requestCarControl("remote_start")
        drain()
        val dialog = latestDialog()
        assertTrue(shadowOf(dialog).message.toString().contains("ventilated"))
        click(dialog, DialogInterface.BUTTON_NEGATIVE)
        assertTrue(dispatched.isEmpty())
        assertFalse(CarControlAuth.consume(CarCommand.REMOTE_START, now))
    }

    @Test
    fun wrongPinSendsNothingAndFiveLockOut() {
        enableWith("2468")
        repeat(CarControlAuth.MAX_PIN_FAILURES) {
            delegate.requestCarControl("lock")
            drain()
            val dialog = latestDialog()
            pinFields(dialog).single().setText("1111")
            click(dialog)
        }
        assertTrue(dispatched.isEmpty())
        assertTrue(toasts.last().contains("Locked for 5 minutes"))
        assertTrue(state().getBoolean("pinLockedOut"))
        ShadowAlertDialog.reset()
        delegate.requestCarControl("lock")
        drain()
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertTrue(toasts.last().contains("Too many wrong PINs"))
    }

    @Test
    fun unlockWindowExpiringWhileTheDialogIsOpenSendsNothing() {
        enableWith("2468")
        CarControlAuth.recordPinAttempt(true, now)
        delegate.requestCarControl("lock")
        drain()
        now += CarControlAuth.UNLOCK_WINDOW_MS
        click(latestDialog())
        assertTrue(dispatched.isEmpty())
        assertTrue(toasts.last().contains("Enter the PIN again"))
    }

    @Test
    fun refusesWithoutOptInOrLiveSessionOrForUnknownNames() {
        delegate.requestCarControl("lock")
        drain()
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertTrue(toasts.last().contains("Settings"))

        enableWith("2468")
        live = false
        ShadowAlertDialog.reset()
        delegate.requestCarControl("lock")
        drain()
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertTrue(toasts.last().contains("Connect to the car first"))

        live = true
        val before = toasts.size
        delegate.requestCarControl("engine_start")
        delegate.requestCarControl(null)
        drain()
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(before, toasts.size)
    }

    @Test
    fun relockEndsTheWindow() {
        CarControlAuth.recordPinAttempt(true, now)
        delegate.relockCarControls()
        assertFalse(state().getBoolean("unlocked"))
        assertEquals(1, appStatePublishes)
    }

    @Test
    fun everyCommandHasANamedWarning() {
        val warnings = CarCommand.entries.map { CarControlHostDelegate.commandWarning(it) }
        assertEquals(warnings.size, warnings.toSet().size)
        assertTrue(warnings.all { it.contains("not yet verified on your car") })
    }

    @Test
    fun defaultDispatchSendsTheCarControlIntent() {
        enableWith("2468")
        val real =
            CarControlHostDelegate(
                activity = activity,
                prefs = { prefs },
                publishAppState = {},
                toast = {},
                hasLiveSession = { true },
                clock = { now },
            )
        CarControlAuth.recordPinAttempt(true, now)
        real.requestCarControl("locate")
        drain()
        click(latestDialog())
        val intent = shadowOf(activity).nextStartedService
        assertEquals(ObdService.ACTION_CAR_CONTROL, intent.action)
        assertEquals("locate", intent.getStringExtra(ObdService.EXTRA_CAR_COMMAND))
    }
}
