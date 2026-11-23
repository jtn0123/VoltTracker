package com.volttracker.obdpoc.update

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Looper
import android.view.View
import com.volttracker.obdpoc.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast

/** What the classic dashboard shows when an update fails, for each way it can be delivered. */
@RunWith(RobolectricTestRunner::class)
class UpdateFailureNoticeTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var activity: Activity
    private var retries = 0

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        activity = controller.get()
        ShadowAlertDialog.reset()
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    private fun show(
        reason: UpdateManager.InstallFailure,
        inFront: Boolean = true,
    ) {
        UpdateFailureNotice.show(activity, inFront, reason) { retries++ }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun latestDialog(): AlertDialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog

    @Test
    fun aRetryableFailureExplainsItselfAndOffersTryAgain() {
        show(UpdateManager.InstallFailure.NETWORK)

        val dialog = latestDialog()
        assertTrue(dialog.isShowing)
        assertEquals(activity.getString(R.string.update_failed_title), shadowOf(dialog).title)
        assertEquals(activity.getString(R.string.update_failed_network), shadowOf(dialog).message)
        val retry = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        assertEquals(activity.getString(R.string.update_failed_retry), retry.text)
        assertEquals(View.VISIBLE, dialog.getButton(DialogInterface.BUTTON_NEGATIVE).visibility)

        retry.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, retries)
    }

    @Test
    fun aFailureThatWouldRepeatOffersOnlyClose() {
        show(UpdateManager.InstallFailure.INSTALLER)

        val dialog = latestDialog()
        assertEquals(activity.getString(R.string.update_failed_installer), shadowOf(dialog).message)
        assertEquals(
            activity.getString(R.string.update_failed_close),
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).text,
        )
        assertEquals(View.GONE, dialog.getButton(DialogInterface.BUTTON_NEGATIVE).visibility)
    }

    @Test
    fun aScreenTheUserLeftGetsAToastInstead() {
        show(UpdateManager.InstallFailure.NOT_THIS_APP, inFront = false)

        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(activity.getString(R.string.update_failed_not_this_app), ShadowToast.getTextOfLatestToast())
        assertEquals(0, retries)
    }

    @Test
    fun aFinishingScreenGetsAToastInstead() {
        activity.finish()
        show(UpdateManager.InstallFailure.STORAGE)

        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(activity.getString(R.string.update_failed_storage), ShadowToast.getTextOfLatestToast())
    }
}
