package com.volttracker.obdpoc.service

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ForegroundServicePolicyTest {
    private val realModes = listOf("obd", "scan", "tpms-scan", "clear-dtc")

    @Test
    fun demoNeverEntersTheForegroundWhateverIsGranted() {
        for (sdk in listOf(23, 29, 33, 34, 36)) {
            for (nearby in listOf(true, false)) {
                for (location in listOf(true, false)) {
                    assertEquals(
                        "demo sdk=$sdk nearby=$nearby location=$location",
                        ForegroundPlan.Background,
                        ForegroundServicePolicy.plan("demo", sdk, nearby, location),
                    )
                }
            }
        }
    }

    @Test
    fun realSessionsOnAndroid14WithoutNearbyDevicesAreRefusedUpFront() {
        for (mode in realModes) {
            assertEquals(
                mode,
                ForegroundPlan.MissingNearbyDevicesPermission,
                ForegroundServicePolicy.plan(
                    mode,
                    34,
                    hasNearbyDevicesPermission = false,
                    hasLocationPermission = true,
                ),
            )
        }
    }

    @Test
    fun belowAndroid14TheTypeIsNotValidatedSoTheSessionStillStarts() {
        assertEquals(
            ForegroundPlan.Foreground(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE),
            ForegroundServicePolicy.plan("obd", 33, hasNearbyDevicesPermission = false, hasLocationPermission = false),
        )
    }

    @Test
    fun belowAndroidQTheForegroundStartIsUntyped() {
        assertEquals(
            ForegroundPlan.Foreground(null),
            ForegroundServicePolicy.plan("obd", 28, hasNearbyDevicesPermission = true, hasLocationPermission = true),
        )
    }

    @Test
    fun locationTypeIsAddedOnlyWithLocationPermission() {
        assertEquals(
            ForegroundPlan.Foreground(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            ),
            ForegroundServicePolicy.plan("obd", 36, hasNearbyDevicesPermission = true, hasLocationPermission = true),
        )
        assertEquals(
            ForegroundPlan.Foreground(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE),
            ForegroundServicePolicy.plan("scan", 36, hasNearbyDevicesPermission = true, hasLocationPermission = false),
        )
    }

    @Test
    fun localTypeConstantsMatchThePlatform() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            ForegroundServicePolicy.TYPE_CONNECTED_DEVICE,
        )
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, ForegroundServicePolicy.TYPE_LOCATION)
    }

    private val realActions =
        listOf(
            ObdService.ACTION_CONNECT,
            ObdService.ACTION_SCAN,
            ObdService.ACTION_TPMS_SCAN,
            ObdService.ACTION_CLEAR_DTC,
        )

    /**
     * Android crashes an app whose foreground-launched service stops before calling startForeground,
     * so only a session that will reach startForeground may launch in the foreground: never the demo,
     * and not a real session that Android 14+ refuses for missing Nearby devices permission.
     */
    @Test
    fun onlySessionsThatWillEnterTheForegroundLaunchInIt() {
        for (sdk in listOf(26, 29, 31, 33, 34, 36)) {
            for (nearby in listOf(true, false)) {
                assertFalse(
                    "demo sdk=$sdk nearby=$nearby",
                    ForegroundServicePolicy.launchesInForeground(ObdService.ACTION_DEMO, sdk, nearby),
                )
                for (action in realActions) {
                    assertEquals(
                        "$action sdk=$sdk nearby=$nearby",
                        nearby || sdk < 34,
                        ForegroundServicePolicy.launchesInForeground(action, sdk, nearby),
                    )
                }
            }
        }
        for (action in realActions) {
            assertFalse(
                "no startForegroundService below Android 8",
                ForegroundServicePolicy.launchesInForeground(action, 25, true),
            )
        }
    }

    @Test
    fun aStartIsPlannedLikeTheSessionItBegins() {
        for (sdk in listOf(23, 29, 34)) {
            for (nearby in listOf(true, false)) {
                for (location in listOf(true, false)) {
                    val case = "sdk=$sdk nearby=$nearby location=$location"
                    assertEquals(
                        "demo $case",
                        ForegroundServicePolicy.plan("demo", sdk, nearby, location),
                        ForegroundServicePolicy.planForAction(ObdService.ACTION_DEMO, sdk, nearby, location),
                    )
                    for ((action, mode) in realActions.zip(realModes)) {
                        assertEquals(
                            "$action $case",
                            ForegroundServicePolicy.plan(mode, sdk, nearby, location),
                            ForegroundServicePolicy.planForAction(action, sdk, nearby, location),
                        )
                    }
                }
            }
        }
    }
}
