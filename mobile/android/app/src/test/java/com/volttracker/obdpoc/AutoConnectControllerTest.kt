package com.volttracker.obdpoc

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoConnectControllerTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var catalog: DeviceCatalog
    private var now = 10_000L

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("auto-connect-test", Context.MODE_PRIVATE)
        prefs.edit {
            clear()
        }
        catalog = DeviceCatalog(context, prefs)
    }

    @Test
    fun autoConnectDefaultsOffSoOfflineViewingDoesNotStartAConnection() {
        remember("AA:BB:CC:DD:EE:FF", "Volt OBD")
        val controller = AutoConnectController(prefs, catalog) { now }

        val didStart =
            controller.maybeConnect(
                AutoConnectController.TRIGGER_APP_RESUME,
                null,
                bluetoothReady = true,
                loggingActive = false,
                startConnect = { _, _ -> throw AssertionError("should not start by default") },
                publishStatus = { _, _, _ -> throw AssertionError("should not publish by default") },
            )

        assertFalse(didStart)
    }

    @Test
    fun startsLastAdapterWhenEnabledReadyAndIdle() {
        remember("AA:BB:CC:DD:EE:FF", "Volt OBD")
        val controller = AutoConnectController(prefs, catalog) { now }
        controller.setEnabled(true)
        val started = ArrayList<String>()
        var status = ""

        val didStart =
            controller.maybeConnect(
                AutoConnectController.TRIGGER_BLUETOOTH_CONNECTED,
                "AA:BB:CC:DD:EE:FF",
                bluetoothReady = true,
                loggingActive = false,
                startConnect = { address, name -> started.add("$address|$name") },
                publishStatus = { state, detail, _ -> status = "$state|$detail" },
            )

        assertTrue(didStart)
        assertEquals(listOf("AA:BB:CC:DD:EE:FF|Volt OBD"), started)
        assertTrue(status.contains("connecting|OBD adapter detected"))
    }

    @Test
    fun stateJsonReportsAvailabilityAndCooldown() {
        remember("AA:BB:CC:DD:EE:FF", "Volt OBD")
        val controller = AutoConnectController(prefs, catalog) { now }
        controller.setEnabled(true)

        assertTrue(
            controller.maybeConnect(
                AutoConnectController.TRIGGER_APP_RESUME,
                null,
                bluetoothReady = true,
                loggingActive = false,
                startConnect = { _, _ -> },
                publishStatus = { _, _, _ -> },
            ),
        )
        now += 1_000L

        val payload = org.json.JSONObject(controller.stateJson())
        assertTrue(payload.getBoolean("enabled"))
        assertEquals("AA:BB:CC:DD:EE:FF", payload.getString("lastAddress"))
        assertEquals("Volt OBD", payload.getString("lastName"))
        assertTrue(payload.getBoolean("available"))
        assertEquals(89_000L, payload.getLong("cooldownRemainingMs"))
    }

    @Test
    fun disabledAutoConnectDoesNotStart() {
        remember("AA:BB:CC:DD:EE:FF", "Volt OBD")
        val controller = AutoConnectController(prefs, catalog) { now }
        controller.setEnabled(false)

        val didStart =
            controller.maybeConnect(
                AutoConnectController.TRIGGER_APP_RESUME,
                null,
                bluetoothReady = true,
                loggingActive = false,
                startConnect = { _, _ -> throw AssertionError("should not start") },
                publishStatus = { _, _, _ -> throw AssertionError("should not publish") },
            )

        assertFalse(didStart)
    }

    @Test
    fun loggingActiveBluetoothNotReadyAndInvalidLastAddressDoNotStart() {
        remember("AA:BB:CC:DD:EE:FF", "Volt OBD")
        val controller = AutoConnectController(prefs, catalog) { now }
        controller.setEnabled(true)

        assertFalse(
            controller.maybeConnect(
                AutoConnectController.TRIGGER_APP_RESUME,
                null,
                bluetoothReady = true,
                loggingActive = true,
                startConnect = { _, _ -> throw AssertionError("should not start while logging") },
                publishStatus = { _, _, _ -> throw AssertionError("should not publish while logging") },
            ),
        )
        assertFalse(
            controller.maybeConnect(
                AutoConnectController.TRIGGER_APP_RESUME,
                null,
                bluetoothReady = false,
                loggingActive = false,
                startConnect = { _, _ -> throw AssertionError("should not start when bluetooth is off") },
                publishStatus = { _, _, _ -> throw AssertionError("should not publish when bluetooth is off") },
            ),
        )

        remember("not-a-mac", "Volt OBD")
        assertFalse(
            controller.maybeConnect(
                AutoConnectController.TRIGGER_APP_RESUME,
                null,
                bluetoothReady = true,
                loggingActive = false,
                startConnect = { _, _ -> throw AssertionError("should not start for invalid address") },
                publishStatus = { _, _, _ -> throw AssertionError("should not publish for invalid address") },
            ),
        )
    }

    @Test
    fun observedDifferentAddressDoesNotStart() {
        remember("AA:BB:CC:DD:EE:FF", "Volt OBD")
        val controller = AutoConnectController(prefs, catalog) { now }
        controller.setEnabled(true)

        val didStart =
            controller.maybeConnect(
                AutoConnectController.TRIGGER_BLUETOOTH_CONNECTED,
                "11:22:33:44:55:66",
                bluetoothReady = true,
                loggingActive = false,
                startConnect = { _, _ -> throw AssertionError("should not start") },
                publishStatus = { _, _, _ -> throw AssertionError("should not publish") },
            )

        assertFalse(didStart)
    }

    @Test
    fun observedAddressMatchIsCaseInsensitiveAndStatusFallsBackToRedactedAddress() {
        remember("AA:BB:CC:DD:EE:FF", "")
        val controller = AutoConnectController(prefs, catalog) { now }
        controller.setEnabled(true)
        var statusDetail = ""

        val didStart =
            controller.maybeConnect(
                AutoConnectController.TRIGGER_BLUETOOTH_ON,
                "aa:bb:cc:dd:ee:ff",
                bluetoothReady = true,
                loggingActive = false,
                startConnect = { _, _ -> },
                publishStatus = { _, detail, _ -> statusDetail = detail },
            )

        assertTrue(didStart)
        assertTrue(statusDetail.startsWith("Bluetooth is on - auto-connecting"))
        assertTrue(statusDetail.contains("...EE:FF"))
        assertFalse(statusDetail.contains("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun cooldownPreventsRepeatedAttempts() {
        remember("AA:BB:CC:DD:EE:FF", "Volt OBD")
        val controller = AutoConnectController(prefs, catalog) { now }
        controller.setEnabled(true)
        var attempts = 0

        assertTrue(
            controller.maybeConnect(
                AutoConnectController.TRIGGER_APP_RESUME,
                null,
                bluetoothReady = true,
                loggingActive = false,
                startConnect = { _, _ -> attempts += 1 },
                publishStatus = { _, _, _ -> },
            ),
        )
        now += 1_000L
        assertFalse(
            controller.maybeConnect(
                AutoConnectController.TRIGGER_APP_RESUME,
                null,
                bluetoothReady = true,
                loggingActive = false,
                startConnect = { _, _ -> attempts += 1 },
                publishStatus = { _, _, _ -> },
            ),
        )

        assertEquals(1, attempts)
    }

    @Test
    fun cooldownExpiryAllowsAnotherAttemptAndUserEnabledMessageIsSpecific() {
        remember("AA:BB:CC:DD:EE:FF", "Volt OBD")
        val controller = AutoConnectController(prefs, catalog) { now }
        controller.setEnabled(true)
        var attempts = 0
        var detail = ""

        assertTrue(
            controller.maybeConnect(
                AutoConnectController.TRIGGER_APP_RESUME,
                null,
                bluetoothReady = true,
                loggingActive = false,
                startConnect = { _, _ -> attempts += 1 },
                publishStatus = { _, _, _ -> },
            ),
        )
        now += 90_000L
        assertTrue(
            controller.maybeConnect(
                AutoConnectController.TRIGGER_USER_ENABLED,
                null,
                bluetoothReady = true,
                loggingActive = false,
                startConnect = { _, _ -> attempts += 1 },
                publishStatus = { _, statusDetail, _ -> detail = statusDetail },
            ),
        )

        assertEquals(2, attempts)
        assertTrue(detail.startsWith("Auto-connect enabled - connecting"))
    }

    private fun remember(
        address: String,
        name: String,
    ) {
        prefs.edit {
            putString(DeviceCatalog.PREF_LAST_ADDRESS, address)
            putString(DeviceCatalog.PREF_LAST_NAME, name)
        }
    }
}
