package com.volttracker.obdpoc.service

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Starts [ObdService] the way its session will run: `startForegroundService` obliges the service to
 * call `startForeground` within seconds, so only actions whose session actually enters the
 * foreground may use it. The demo runs as a plain started service (see [ForegroundPlan.Background])
 * and is always launched from a visible screen, where `startService` is allowed.
 */
internal object ObdServiceLauncher {
    /** Throws what the platform throws (background-start restrictions); callers report it. */
    fun start(
        context: Context,
        intent: Intent,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            ForegroundServicePolicy.launchesInForeground(intent.action)
        ) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
