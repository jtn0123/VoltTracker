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
    fun startsLastAdapterWhenEnabledReadyAndIdle() {
        remember("AA:BB:CC:DD:EE:FF", "Volt OBD")
        val controller = AutoConnectController(prefs, catalog) { now }
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
    fun observedDifferentAddressDoesNotStart() {
        remember("AA:BB:CC:DD:EE:FF", "Volt OBD")
        val controller = AutoConnectController(prefs, catalog) { now }

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
    fun cooldownPreventsRepeatedAttempts() {
        remember("AA:BB:CC:DD:EE:FF", "Volt OBD")
        val controller = AutoConnectController(prefs, catalog) { now }
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
