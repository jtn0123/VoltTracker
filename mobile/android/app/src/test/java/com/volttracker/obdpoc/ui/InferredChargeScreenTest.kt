package com.volttracker.obdpoc.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.volttracker.obdpoc.ui.charge.ChargeProvenance
import com.volttracker.obdpoc.ui.charge.ChargeUiState
import com.volttracker.obdpoc.ui.charge.ESTIMATE_NOTE
import com.volttracker.obdpoc.ui.components.VoltTab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/** An inferred charge reads as an estimate on the Charge tab, on its receipt, and in what it shares. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class InferredChargeScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val shared = mutableListOf<Pair<String, String>>()

    private val originalZone = TimeZone.getDefault()

    // The Charge demo's clock is Apr 30, 9:42 PM Pacific, which is already May 1 in UTC. The
    // month line counts charges in the demo's month, so pin the zone it was written in: on a
    // UTC machine (CI) the month would be May and the inferred April charge wouldn't count.
    @Before fun demoZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
    }

    @After fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    private fun show(
        charge: ChargeUiState,
        routes: List<VoltRoute> = emptyList(),
        open: String? = null,
    ) {
        compose.setContent {
            VoltApp(
                VoltAppUiState(charge = charge),
                initialTab = VoltTab.CHARGE,
                initialRoutes = routes,
                actions = VoltAppActions(onShareText = { subject, text -> shared += subject to text }),
                initialCharge = open,
            )
        }
    }

    @Test
    fun theChargeTabLabelsAnInferredChargeAndItsFiguresAsEstimates() {
        show(InferredChargeFixture.state)
        // The unplugged hero and its figures describe the newest charge: the inferred one.
        compose.onNodeWithText("Estimated · 22% → 86% · 9.0 kWh").assertIsDisplayed()
        compose.onNodeWithText("EST. ADDED").assertIsDisplayed()
        compose.onNodeWithText("EST. COST").assertIsDisplayed()
        // Its row in Recent sessions, and the month total that counts it.
        compose.onNodeWithText("Estimated · 22% → 86%").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("9.0 kWh, estimated").assertIsDisplayed()
        compose.onNodeWithText("$1.08 est.").assertIsDisplayed()
        compose.onNodeWithContentDescription("$1.08, estimated").assertIsDisplayed()
        compose.onNodeWithText("includes 1 estimated", substring = true).assertIsDisplayed()
        // The measured rows below keep their plain figures.
        compose.onNodeWithText("Level 2 · 24% → 91%").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("$1.42").assertIsDisplayed()
    }

    @Test
    fun aMeasuredLastChargeKeepsThePlainFigures() {
        show(ChargeUiState.demo.copy(charging = false))
        compose.onNodeWithText("ADDED").assertIsDisplayed()
        compose.onNodeWithText("COST").assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasText("est.", substring = true)).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasText("estimated", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun tappingAnInferredChargeOpensAnEstimatedReceiptThatSharesAsOne() {
        show(InferredChargeFixture.state)
        compose.onNodeWithText("Estimated · 22% → 86%").performScrollTo().performClick()
        compose.onNodeWithText("Estimated charge").assertIsDisplayed()
        compose.onNodeWithText(ESTIMATE_NOTE).assertIsDisplayed()
        compose.onNodeWithText("EST. ADDED").assertIsDisplayed()
        compose.onNodeWithText("EST. COST").assertIsDisplayed()
        for (line in listOf(
            "Observation window, 10 hr",
            "Energy added, 9.0 kWh, estimated",
            "Total, $1.08, estimated",
        )) {
            compose.onNodeWithContentDescription(line, substring = true).performScrollTo().assertIsDisplayed()
        }
        // An inferred charge wasn't plugged in for the whole window, so neither figure applies.
        for (absent in listOf("Plugged in", "Average power")) {
            val nodes = compose.onAllNodes(hasContentDescription(absent, substring = true)).fetchSemanticsNodes()
            assertTrue(absent, nodes.isEmpty())
        }
        compose.onNodeWithText("Share").performScrollTo().performClick()
        val (subject, text) = shared.single()
        assertEquals("Estimated charge", subject)
        assertTrue(text.contains(ESTIMATE_NOTE))
        assertTrue(text.contains("Energy added: 9.0 kWh (est.)"))
        assertTrue(text.contains("Total: $1.08 (est.)"))
        assertFalse(text.contains("Plugged in"))
    }

    @Test
    fun twoChargesThatStartTogetherEachOpenTheirOwnReceipt() {
        val inferred = InferredChargeFixture.inferred
        val measured = InferredChargeFixture.measured.copy(startedAtMs = inferred.startedAtMs, id = "77")
        val charge = InferredChargeFixture.state.copy(sessions = listOf(inferred, measured))
        assertEquals(ChargeProvenance.OBSERVED, measured.provenance)

        show(charge, listOf(VoltRoute.CHARGE), open = "77")
        compose.onNodeWithText("Level 2 charge").assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasText(ESTIMATE_NOTE)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theInferredReceiptOpensByItsStoreId() {
        show(InferredChargeFixture.state, listOf(VoltRoute.CHARGE), open = InferredChargeFixture.id)
        compose.onNodeWithText("Estimated charge").assertIsDisplayed()
        compose.onNodeWithContentDescription("Battery from 22% to 86%").assertIsDisplayed()
    }
}
