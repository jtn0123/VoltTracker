package com.volttracker.obdpoc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.volttracker.obdpoc.service.ObdService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device smoke for the demo session pipeline: starting [ObdService] with [ObdService.ACTION_DEMO]
 * must produce real [ObdService.BROADCAST_TELEMETRY] broadcasts — the same telemetry stream the
 * dashboard renders. This exercises the foreground-service start path, the demo polling loop, and
 * the broadcast plumbing on a real Android runtime (no Bluetooth adapter required).
 */
@RunWith(AndroidJUnit4::class)
class ObdServiceDemoSmokeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var receiver: BroadcastReceiver? = null

    @After
    fun tearDown() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        // End the demo session so a following test starts from an idle service.
        val stop = Intent(context, ObdService::class.java)
        stop.action = ObdService.ACTION_DISCONNECT
        runCatching { context.startService(stop) }
    }

    @Test
    fun demoSessionEmitsTelemetryBroadcasts() {
        val telemetrySeen = CountDownLatch(MIN_TELEMETRY_BROADCASTS)
        val listener =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    if (intent.action == ObdService.BROADCAST_TELEMETRY &&
                        !intent.getStringExtra(ObdService.EXTRA_JSON).isNullOrEmpty()
                    ) {
                        telemetrySeen.countDown()
                    }
                }
            }
        receiver = listener
        ContextCompat.registerReceiver(
            context,
            listener,
            IntentFilter(ObdService.BROADCAST_TELEMETRY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val demo = Intent(context, ObdService::class.java)
        demo.action = ObdService.ACTION_DEMO
        ContextCompat.startForegroundService(context, demo)

        assertTrue(
            "expected >=$MIN_TELEMETRY_BROADCASTS telemetry broadcasts from the demo loop " +
                "within ${TELEMETRY_TIMEOUT_MS}ms",
            telemetrySeen.await(TELEMETRY_TIMEOUT_MS, TimeUnit.MILLISECONDS),
        )
    }

    private companion object {
        // Multiple broadcasts prove the loop is *streaming*, not a single startup blip.
        const val MIN_TELEMETRY_BROADCASTS = 3
        const val TELEMETRY_TIMEOUT_MS = 30_000L
    }
}
