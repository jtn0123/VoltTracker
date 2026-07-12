package com.volttracker.obdpoc

import android.Manifest
import android.app.Activity
import com.volttracker.obdpoc.service.ObdService
import com.volttracker.obdpoc.service.PermissionGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Unit-level coverage of the connect/permission handshake extracted from [MainActivity]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectPermissionFlowTest {
    private lateinit var activity: Activity

    private var lastState: String? = null
    private var lastDetail: String? = null
    private var lastBlocked: Boolean? = null
    private var deviceListPublishes = 0
    private val startedServices = mutableListOf<List<String?>>()
    private var canAskAgain = true
    private var openSettingsCalls = 0
    private var openSettingsReturn = true

    private lateinit var flow: ConnectPermissionFlow

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).create().get()
        flow =
            ConnectPermissionFlow(
                context = activity,
                permissionGate = { PermissionGate(activity) {} },
                publishStatus = { state, detail, blocked ->
                    lastState = state
                    lastDetail = detail
                    lastBlocked = blocked
                },
                publishDeviceList = { deviceListPublishes += 1 },
                startObdService = { action, address, name, stage ->
                    startedServices += listOf(action, address, name, stage)
                },
                canAskForBluetoothConnectAgain = { canAskAgain },
                openAppPermissionSettings = {
                    openSettingsCalls += 1
                    openSettingsReturn
                },
            )
    }

    private fun grantBluetooth() {
        shadowOf(activity).grantPermissions(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        )
    }

    private fun grantAllOptional() {
        shadowOf(activity).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    @Test
    fun parkPendingStartExplainsTheWait() {
        flow.parkPendingStart(ObdService.ACTION_CONNECT, ADDRESS, NAME, null)

        assertEquals("blocked", lastState)
        assertTrue(lastDetail!!.contains("Nearby devices"))
        assertEquals(true, lastBlocked)
    }

    @Test
    fun grantResumesTheParkedConnectWithItsName() {
        flow.parkPendingStart(ObdService.ACTION_CONNECT, ADDRESS, NAME, "stage-x")
        grantBluetooth()

        flow.onPermissionsResult()

        assertEquals(1, deviceListPublishes)
        assertEquals("connecting", lastState)
        assertTrue(lastDetail!!.contains(NAME))
        assertEquals(listOf<String?>(ObdService.ACTION_CONNECT, ADDRESS, NAME, "stage-x"), startedServices.single())
    }

    @Test
    fun partialBluetoothGrantDoesNotResumeTheParkedConnect() {
        // Android 12+ can grant CONNECT while denying SCAN; the parked connect would
        // immediately fail, so it must be treated as a denial, not resumed.
        flow.parkPendingStart(ObdService.ACTION_CONNECT, ADDRESS, NAME, null)
        shadowOf(activity).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        flow.onPermissionsResult()

        assertTrue("partial grant must not start the service", startedServices.isEmpty())
        assertEquals("blocked", lastState)
        assertEquals(true, lastBlocked)
    }

    @Test
    fun parkedStartIsConsumedBySingleResult() {
        flow.parkPendingStart(ObdService.ACTION_CONNECT, ADDRESS, NAME, null)
        grantBluetooth()
        grantAllOptional()

        flow.onPermissionsResult()
        flow.onPermissionsResult()

        assertEquals("a later result must not replay the old connect", 1, startedServices.size)
        assertEquals("ready", lastState)
    }

    @Test
    fun pendingLabelFallsBackToAddressThenGenericAdapter() {
        grantBluetooth()
        flow.parkPendingStart(ObdService.ACTION_CONNECT, ADDRESS, " ", null)
        flow.onPermissionsResult()
        assertTrue("blank name should fall back to the address: $lastDetail", lastDetail!!.contains(ADDRESS))

        flow.parkPendingStart(ObdService.ACTION_CONNECT, null, null, null)
        flow.onPermissionsResult()
        assertTrue(lastDetail!!.contains("the OBD adapter"))
    }

    @Test
    fun retriableDenialPointsBackAtConnect() {
        canAskAgain = true
        flow.parkPendingStart(ObdService.ACTION_CONNECT, ADDRESS, NAME, null)

        flow.onPermissionsResult()

        assertEquals("blocked", lastState)
        assertTrue(lastDetail!!.contains("Tap Connect"))
        assertEquals(0, openSettingsCalls)
        assertTrue(startedServices.isEmpty())
    }

    @Test
    fun permanentDenialDuringConnectOpensAppSettings() {
        canAskAgain = false
        flow.parkPendingStart(ObdService.ACTION_CONNECT, ADDRESS, NAME, null)

        flow.onPermissionsResult()

        assertEquals(1, openSettingsCalls)
        assertEquals("blocked", lastState)
        assertTrue(lastDetail!!.contains("app settings screen"))
    }

    @Test
    fun permanentDenialWithoutAConnectOnlyExplains() {
        canAskAgain = false

        flow.onPermissionsResult()

        assertEquals(0, openSettingsCalls)
        assertEquals("blocked", lastState)
        assertTrue(lastDetail!!.contains("Nearby devices"))
    }

    @Test
    fun permanentDenialFallsBackToManualStepsWhenSettingsWontOpen() {
        canAskAgain = false
        openSettingsReturn = false
        flow.parkPendingStart(ObdService.ACTION_CONNECT, ADDRESS, NAME, null)

        flow.onPermissionsResult()

        assertEquals(1, openSettingsCalls)
        assertTrue(lastDetail!!.contains("Android Settings"))
    }

    @Test
    fun grantWithoutPendingConnectMentionsMissingOptionalPermissions() {
        grantBluetooth()

        flow.onPermissionsResult()

        assertNull("no parked request, so nothing may start", startedServices.firstOrNull())
        assertEquals("ready", lastState)
        assertTrue("location is still missing: $lastDetail", lastDetail!!.contains("Location"))

        shadowOf(activity).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        flow.onPermissionsResult()
        assertTrue("notifications are still missing: $lastDetail", lastDetail!!.contains("Notifications"))

        grantAllOptional()
        flow.onPermissionsResult()
        assertTrue(lastDetail!!.contains("Pick a paired adapter"))
        assertEquals(false, lastBlocked)
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
        const val NAME = "TestOBD"
    }
}
