package com.volttracker.obdpoc.sim

import android.content.Intent
import com.volttracker.obdpoc.service.ObdService
import com.volttracker.obdpoc.sim.VirtualVoltScorecardTest.VirtualVoltService
import org.junit.Assert.fail

/** Shared plumbing for the Robolectric virtual-Volt drive tests. */
internal object VirtualVoltTestSupport {
    fun connectIntent(
        service: VirtualVoltService,
        adapterName: String,
    ): Intent =
        Intent(service, VirtualVoltService::class.java).apply {
            action = ObdService.ACTION_CONNECT
            putExtra(ObdService.EXTRA_ADDRESS, "AA:BB:CC:DD:EE:FF")
            putExtra(ObdService.EXTRA_NAME, adapterName)
        }

    /** Polls [condition] every 20 ms until it holds, failing the test after [timeoutMs]. */
    fun waitFor(
        label: String,
        timeoutMs: Long,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail("Timed out waiting for $label")
    }
}
