package com.volttracker.obdpoc

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SdpProbeTest {
    private lateinit var controller: ServiceController<HarnessService>
    private lateinit var service: HarnessService

    @Before
    fun setUp() {
        controller = Robolectric.buildService(HarnessService::class.java).create()
        service = controller.get()
    }

    @After
    fun tearDown() {
        try {
            controller.destroy()
        } catch (ignored: RuntimeException) {
            // Service teardown is not part of these assertions.
        }
    }

    @Test
    fun triggerRejectsMissingServiceAndBlankAddress() {
        val probe = SdpProbe(null, cooldownMs = 0L)

        assertFalse(probe.trigger(null))
        assertFalse(probe.trigger("   "))
    }

    @Test
    fun triggerLogsMissingBluetoothPermission() {
        service.bluetoothConnectPermission = false
        val probe = SdpProbe(service, cooldownMs = 0L)

        assertFalse(probe.trigger(VALID_ADDRESS))

        val event = eventPayloads("sdp_refresh_skipped").last()
        assertEquals(VALID_ADDRESS, event.optString("address"))
        assertEquals("missing_bluetooth_connect", event.optString("reason"))
    }

    @Test
    fun triggerLogsInvalidAddressWithoutDispatchingSdp() {
        val probe = SdpProbe(service, cooldownMs = 0L)

        assertFalse(probe.trigger("not-a-mac"))

        val event = eventPayloads("sdp_refresh_skipped").last()
        assertEquals("not-a-mac", event.optString("address"))
        assertEquals("invalid_address", event.optString("reason"))
    }

    @Test
    fun triggerLogsRequestedSdpAndThenCooldownSkipsNextAttempt() {
        val probe = SdpProbe(service, cooldownMs = 10_000L)

        val dispatched = probe.trigger(VALID_ADDRESS)

        val requested = eventPayloads("sdp_refresh_requested").lastOrNull()
        assertNotNull("valid addresses should reach the SDP request path", requested)
        assertEquals(VALID_ADDRESS, requested!!.optString("address"))
        assertEquals(dispatched.toString(), requested.optString("dispatched"))
        assertTrue("cached UUIDs should be serialized as a string", requested.has("cachedUuids"))

        assertFalse(probe.trigger(VALID_ADDRESS))

        val cooldown = eventPayloads("sdp_refresh_skipped").last()
        assertEquals(VALID_ADDRESS, cooldown.optString("address"))
        assertEquals("cooldown", cooldown.optString("reason"))
        assertTrue(cooldown.optLong("sinceLastMs") >= 0L)
    }

    private fun eventPayloads(event: String): List<JSONObject> {
        val fileName = service.recorder.logFileName()
        assertTrue("test recorder should have an open JSONL log", fileName.isNotEmpty())
        val log = File(service.logDir, fileName)
        assertTrue("expected log file to exist: $log", log.isFile)
        return log
            .readLines()
            .mapNotNull { line ->
                val payload = JSONObject(line).optJSONObject("payload") ?: return@mapNotNull null
                if (payload.optString("event") == event) payload else null
            }
    }

    class HarnessService : ObdService() {
        lateinit var logDir: File
        var bluetoothConnectPermission = true

        override fun onCreate() {
            logDir =
                File(cacheDir, "sdp-probe-test-logs").also {
                    it.deleteRecursively()
                    it.mkdirs()
                }
            recorder =
                SessionRecorder(
                    Any(),
                    ObdSessionLog(logDir),
                    null,
                )
            recorder.openSession("sdp-test", VALID_ADDRESS, "Adapter", 1_000L)
        }

        override fun onDestroy() {
            recorder.shutdown()
        }

        override fun hasBluetoothConnectPermission(): Boolean = bluetoothConnectPermission
    }

    private companion object {
        private const val VALID_ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
