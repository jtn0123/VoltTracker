package com.volttracker.obdpoc

import com.volttracker.obdpoc.ui.VoltRoute
import com.volttracker.obdpoc.ui.components.VoltTab
import java.util.Locale

/**
 * Where a debug launch opens, from `am start` extras, so emulator screenshots don't have to find
 * and tap on-screen elements (uiautomator stalls while the demo animates):
 *
 * `adb shell am start --activity-clear-top -n com.volttracker.obdpoc.debug/com.volttracker.obdpoc.ComposeDashboardActivity
 *   --es vt.tab trips --es vt.route settings --ez vt.demo true`
 *
 * Only debug builds read the extras (see ComposeDashboardActivity); scripts/local-emulator.sh
 * wraps them. Unknown names fall back to the normal start screen.
 */
data class DebugLaunch(
    val tab: VoltTab = VoltTab.DRIVE,
    val routes: List<VoltRoute> = emptyList(),
    val startDemo: Boolean = false,
) {
    companion object {
        const val EXTRA_TAB = "vt.tab"
        const val EXTRA_ROUTE = "vt.route"
        const val EXTRA_DEMO = "vt.demo"

        fun from(
            tab: String?,
            route: String?,
            demo: Boolean,
        ): DebugLaunch =
            DebugLaunch(
                tab = enumOrNull<VoltTab>(tab) ?: VoltTab.DRIVE,
                routes = listOfNotNull(enumOrNull<VoltRoute>(route)),
                startDemo = demo,
            )

        private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
            name?.trim()?.uppercase(Locale.US)?.let { wanted -> enumValues<E>().firstOrNull { it.name == wanted } }
    }
}
