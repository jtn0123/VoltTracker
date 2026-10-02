package com.volttracker.obdpoc.service

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun onlyTheDemoLaunchesAsAPlainStartedService() {
        assertFalse(ForegroundServicePolicy.launchesInForeground(ObdService.ACTION_DEMO))
        assertTrue(ForegroundServicePolicy.launchesInForeground(ObdService.ACTION_CONNECT))
        assertTrue(ForegroundServicePolicy.launchesInForeground(ObdService.ACTION_SCAN))
        assertTrue(ForegroundServicePolicy.launchesInForeground(ObdService.ACTION_TPMS_SCAN))
        assertTrue(ForegroundServicePolicy.launchesInForeground(ObdService.ACTION_CLEAR_DTC))
    }
}
