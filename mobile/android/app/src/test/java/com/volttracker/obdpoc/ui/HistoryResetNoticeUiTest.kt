package com.volttracker.obdpoc.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.volttracker.obdpoc.ui.components.historyResetDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** The "saved history was reset" notice shows on the native pages and can be dismissed. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class HistoryResetNoticeUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theNoticeShowsAndDismissReportsBack() {
        var dismissed = 0
        compose.setContent {
            VoltApp(
                VoltAppUiState(historyResetAtMs = 1_700_000_000_000L),
                actions = VoltAppActions(onDismissHistoryReset = { dismissed++ }),
            )
        }

        compose.onNodeWithText("Saved history was reset").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()

        assertEquals(1, dismissed)
    }

    @Test
    fun theDetailSaysWhenAndHowToGetTheDrivesBack() {
        val dates = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

        val detail = historyResetDetail(1_700_000_000_000L, dates)

        assertTrue(detail, detail.startsWith("On 2023-11-14 "))
        assertTrue(detail, detail.contains("kept the damaged copy"))
        assertTrue(detail, detail.contains("restore a backup"))
    }
}
