package com.volttracker.obdpoc.ui

import android.content.res.Configuration
import com.volttracker.obdpoc.ui.drive.landscapeGaugeWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DriveLandscapeTest {
    private fun config(
        w: Int,
        h: Int,
    ) = Configuration().apply {
        screenWidthDp = w
        screenHeightDp = h
    }

    @Test
    fun portraitAndNarrowWindowsKeepTheFullWidthRing() {
        assertNull(landscapeGaugeWidth(config(412, 900)))
        assertNull(landscapeGaugeWidth(config(560, 400)))
    }

    @Test
    fun landscapeSizesTheRingToTheHeightLeftUnderTheAppBar() {
        // 415dp tall phone sideways: 265dp of ring height, at the ring's 380 × 350 aspect.
        assertEquals(265 * 380f / 350f, landscapeGaugeWidth(config(900, 415))!!.value, 0.01f)
        // Very short windows still get a usable ring.
        assertEquals(200 * 380f / 350f, landscapeGaugeWidth(config(700, 300))!!.value, 0.01f)
    }
}
