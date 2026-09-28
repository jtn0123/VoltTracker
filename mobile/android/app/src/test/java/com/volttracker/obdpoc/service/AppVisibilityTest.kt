package com.volttracker.obdpoc.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the in-process visibility signal that replaced the APP_FOREGROUND/BACKGROUND start commands. */
class AppVisibilityTest {
    @After
    fun tearDown() {
        AppVisibility.resetForTest()
    }

    @Test
    fun defaultsToForegroundSoAFreshProcessMatchesTheOldServiceDefault() {
        assertTrue(AppVisibility.isForeground)
    }

    @Test
    fun reportUpdatesTheStateAndNotifiesEveryListenerInOrder() {
        val seen = mutableListOf<String>()
        AppVisibility.addListener { seen += "a:$it" }
        AppVisibility.addListener { seen += "b:$it" }

        AppVisibility.report(false)
        assertFalse(AppVisibility.isForeground)
        AppVisibility.report(true)

        assertTrue(AppVisibility.isForeground)
        assertEquals(listOf("a:false", "b:false", "a:true", "b:true"), seen)
    }

    @Test
    fun aListenerAddedTwiceIsNotifiedOnceAndStopsAfterRemoval() {
        val seen = mutableListOf<Boolean>()
        val listener = AppVisibility.Listener { seen += it }
        AppVisibility.addListener(listener)
        AppVisibility.addListener(listener)

        AppVisibility.report(false)
        AppVisibility.removeListener(listener)
        AppVisibility.report(true)

        assertEquals(listOf(false), seen)
    }
}
