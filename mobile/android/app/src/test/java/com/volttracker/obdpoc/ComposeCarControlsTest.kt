package com.volttracker.obdpoc

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import com.volttracker.obdpoc.ui.live.LiveUiStateStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper

/**
 * The Car tab's controls: real commands only ever go to the host delegate (which owns the PIN,
 * confirmation and every gate); the demo confirms in its own dialog and never reaches it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeCarControlsTest {
    private val requests = mutableListOf<String?>()
    private val enables = mutableListOf<Boolean>()
    private var enabled = false
    private lateinit var publish: () -> Unit
    private val store = LiveUiStateStore { 0L }
    private lateinit var controls: ComposeCarControls

    private val fake =
        object : CarControlCommands {
            override fun getCarControlStateJson(): String =
                JSONObject().put("available", true).put("enabled", enabled).toString()

            override fun setCarControlsEnabled(enabled: Boolean) {
                enables += enabled
            }

            override fun requestCarControl(command: String?) {
                requests += command
            }

            override fun relockCarControls() = Unit
        }

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(android.R.style.Theme_DeviceDefault)
        controls =
            ComposeCarControls(activity, { null }, store, toast = {}) { callback ->
                publish = callback
                fake
            }
    }

    @Test
    fun aRealCommandGoesToTheHostDelegate() {
        controls.request("lock")
        assertEquals(listOf<String?>("lock"), requests)
    }

    @Test
    fun theDemoConfirmsInItsOwnDialogAndSendsNothing() {
        store.onStatus(JSONObject().put("state", "demo"))
        controls.request("locate")
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertTrue("nothing reaches the host", requests.isEmpty())
        assertEquals("locate", store.state.value.car.controls.lastCommand)
    }

    @Test
    fun turningControlsOnOrOffGoesThroughTheHostAndIsReadBack() {
        controls.setEnabled(true)
        assertEquals(listOf(true), enables)
        enabled = true
        publish()
        assertTrue(store.state.value.car.controls.enabled)
        controls.refresh()
        assertTrue(store.state.value.car.controls.enabled)
    }

    @Test
    fun anUnknownDemoCommandShowsNoDialog() {
        store.onStatus(JSONObject().put("state", "demo"))
        controls.request("self_destruct")
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertTrue(requests.isEmpty())
    }
}
