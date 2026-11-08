package com.volttracker.obdpoc

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.volttracker.obdpoc.ui.VoltApp
import com.volttracker.obdpoc.ui.VoltAppActions
import com.volttracker.obdpoc.ui.VoltAppUiState
import com.volttracker.obdpoc.ui.VoltRoute
import com.volttracker.obdpoc.ui.charge.ChargeUiState
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.diag.DiagUiState
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.insights.InsightsUiState
import com.volttracker.obdpoc.ui.screenViewName
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import com.volttracker.obdpoc.ui.trips.TripsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pins `tools/benchmark-adb-target.sh`, which says what the no-install adb benchmarks launch, tap
 * and wait for, to the app. Nothing runs those benchmarks without a phone, so a rename on one side
 * (an application ID, the launcher, a StartupTrace mark, a nav label, a screen's view name) would
 * otherwise only show up as a phone session that waits for something that no longer exists.
 * `bash tools/benchmark-adb-target.sh --self-test` checks the script's own parsing.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class BenchmarkTargetContractTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val script by lazy { locate("tools/benchmark-adb-target.sh").readText() }

    @Test
    fun packagesAreTheBuildsApplicationIds() {
        val gradle = locate("app/build.gradle").readText()
        val applicationId = Regex("""(?m)^\s*applicationId = "([^"]+)"""").find(gradle)?.groupValues?.get(1)
        val debugSuffix = Regex("""(?m)^\s*applicationIdSuffix = "([^"]+)"""").find(gradle)?.groupValues?.get(1)

        assertEquals("VT_RELEASE_PACKAGE is the release applicationId", applicationId, value("VT_RELEASE_PACKAGE"))
        assertEquals("VT_DEBUG_PACKAGE adds the debug suffix", "$applicationId$debugSuffix", value("VT_DEBUG_PACKAGE"))
        val built = if (BuildConfig.DEBUG) value("VT_DEBUG_PACKAGE") else value("VT_RELEASE_PACKAGE")
        assertEquals("this unit-test variant's application ID", built, BuildConfig.APPLICATION_ID)
        val namespace = BuildConfig::class.java.`package`?.name
        assertEquals("class names resolve against the namespace", namespace, value("VT_CODE_PACKAGE"))
    }

    @Test
    fun launcherAndClassicAliasMatchTheManifests() {
        val main = manifest("app/src/main/AndroidManifest.xml")
        val launchers =
            main.elements("activity").filter { activity ->
                activity.elements("category").any { it.android("name") == "android.intent.category.LAUNCHER" }
            }
        assertEquals("one launcher activity", 1, launchers.size)
        assertEquals(
            "VT_LAUNCHER_CLASS is the manifest's launcher",
            ComposeDashboardActivity::class.java.name,
            value("VT_CODE_PACKAGE") + launchers.single().android("name"),
        )
        assertEquals(ComposeDashboardActivity::class.java.name, value("VT_LAUNCHER_CLASS"))

        // Classic is debug-only because MainActivity is not exported and only debug builds alias it.
        val classic = main.elements("activity").single { it.android("name") == ".MainActivity" }
        assertEquals("MainActivity stays unexported", "false", classic.android("exported"))
        val alias = ".${value("VT_CLASSIC_ALIAS")}"
        val releaseAliases = main.elements("activity-alias").map { it.android("name") }
        assertTrue("release builds have no classic alias", alias !in releaseAliases)
        val debugAliases = manifest("app/src/debug/AndroidManifest.xml").elements("activity-alias")
        val debugAlias = debugAliases.single { it.android("name") == alias }
        assertEquals("the debug alias is exported", "true", debugAlias.android("exported"))
        assertEquals("the debug alias opens MainActivity", ".MainActivity", debugAlias.android("targetActivity"))
    }

    @Test
    fun marksAndReadyProbesAreTheAppsOwn() {
        assertEquals(StartupTrace.TAG, value("VT_TRACE_TAG"))
        assertEquals(StartupTrace.COMPOSE_SCREEN, value("VT_COMPOSE_SCREEN_MARK"))
        assertEquals("a fresh start opens Drive", screenViewName(VoltTab.DRIVE, null), value("VT_NATIVE_START_VIEW"))
        assertEquals("the release ready probe is a nav button", VoltTab.TRIPS.label, value("VT_NATIVE_READY_DESC"))
        assertEquals(MainActivity.DASHBOARD_READY_DESCRIPTION, value("VT_CLASSIC_READY_TEXT"))
        val mainActivity = locate("app/src/main/kotlin/com/volttracker/obdpoc/MainActivity.kt").readText()
        assertTrue(
            "MainActivity must log the classic ready mark ${value("VT_CLASSIC_READY_MARK")}",
            mainActivity.contains("StartupTrace.mark(\"${value("VT_CLASSIC_READY_MARK")}\")"),
        )
    }

    @Test
    fun nativeTableIsTheNavBarTheGearAndTheirScreens() {
        val expected =
            VoltTab.entries.map { listOf(it.name.lowercase(), it.label, screenViewName(it, null)) } +
                listOf(listOf("settings", SETTINGS_GEAR, screenViewName(VoltTab.DRIVE, VoltRoute.SETTINGS)))
        assertEquals(expected, table("VT_NATIVE_TABS_TABLE"))
    }

    @Test
    fun classicTableIsTheTemplatesNavInOrder() {
        val template = locate("app/src/main/dashboard-src/index.template.html").readText()
        val navButtons = Regex("""data-nav="([a-z-]+)"""").findAll(template).map { it.groupValues[1] }.toList()
        assertEquals(navButtons, table("VT_CLASSIC_TABS_TABLE").map { it.first() })
    }

    /**
     * What a benchmark run does on a phone, in the real [VoltApp]: a fresh start shows Drive, then
     * one tap on the target's content description (exact, or with a badge after ", ") must log
     * that target's `compose_screen:<view>`.
     */
    @Test
    fun eachNativeTargetIsOneTapFromAFreshStart() {
        val shown = mutableListOf<String>()
        var launch by mutableIntStateOf(0)
        compose.setContent {
            key(launch) { VoltApp(DEMO, actions = VoltAppActions(onScreenShown = { shown += it })) }
        }
        compose.waitForIdle()
        for ((tab, desc, view) in table("VT_NATIVE_TABS_TABLE")) {
            shown.clear()
            launch++
            compose.waitForIdle()
            assertEquals("a fresh start logs the ready mark's view", listOf(value("VT_NATIVE_START_VIEW")), shown)
            compose.onAllNodes(benchmarkTarget(value("VT_NATIVE_READY_DESC"))).assertCountEquals(1)
            if (tab == "drive") continue
            val target = compose.onAllNodes(benchmarkTarget(desc))
            target.assertCountEquals(1)
            target.onFirst().performClick()
            compose.waitForIdle()
            assertEquals("tapping '$desc' shows $view", view, shown.last())
        }
    }

    private fun benchmarkTarget(desc: String) =
        SemanticsMatcher("content description '$desc' (or '$desc, …')") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any {
                it == desc || it.startsWith("$desc, ")
            }
        } and hasClickAction()

    private fun value(name: String): String =
        Regex("""(?m)^$name="([^"]*)"$""").find(script)?.groupValues?.get(1)
            ?: throw AssertionError("tools/benchmark-adb-target.sh must define $name=\"…\"")

    private fun table(name: String): List<List<String>> =
        Regex("""(?s)\n$name='\n(.*?)\n'""")
            .find(script)
            ?.groupValues
            ?.get(1)
            ?.lines()
            ?.filter { it.isNotBlank() }
            ?.map { it.trim().split(Regex("\\s+")) }
            ?: throw AssertionError("tools/benchmark-adb-target.sh must define the $name table")

    private fun manifest(relative: String): Element =
        DocumentBuilderFactory
            .newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(locate(relative))
            .documentElement

    private fun Element.elements(tag: String): List<Element> =
        getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun Element.android(attribute: String): String = getAttributeNS(ANDROID_NS, attribute)

    /** Resolves a path under `mobile/android` by walking up from the test working directory. */
    private fun locate(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) {
                return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate $relative searching upward from " + System.getProperty("user.dir"))
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        /** The app bar gear's content description (see VoltAppNavigationTest). */
        const val SETTINGS_GEAR = "Settings"

        /** Demo data everywhere, so Car carries its fault badge ("Car, has faults"). */
        val DEMO =
            VoltAppUiState(
                drive = DriveUiState.demo,
                charge = ChargeUiState.demo,
                trips = TripsUiState.demo,
                insights = InsightsUiState.demo,
                diag = DiagUiState.demo,
                settings = SettingsUiState.demo,
            )
    }
}
