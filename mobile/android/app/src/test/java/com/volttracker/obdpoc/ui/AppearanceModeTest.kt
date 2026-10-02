package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.theme.AppearanceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceModeTest {
    @Test
    fun everyChoiceHasItsOwnHint() {
        assertEquals(
            AppearanceMode.entries.size,
            AppearanceMode.entries
                .map { it.hint }
                .toSet()
                .size,
        )
    }

    @Test
    fun onlySystemSaysItFollowsThePhone() {
        assertTrue(AppearanceMode.SYSTEM.hint.startsWith("Follows your phone"))
        assertFalse(AppearanceMode.DARK.hint.startsWith("Follows"))
        assertTrue(AppearanceMode.DARK.hint.contains("dark"))
        assertTrue(AppearanceMode.LIGHT.hint.contains("light"))
    }
}
