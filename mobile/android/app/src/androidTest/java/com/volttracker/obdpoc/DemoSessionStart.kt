package com.volttracker.obdpoc

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.volttracker.obdpoc.service.ObdService
import com.volttracker.obdpoc.service.ObdServiceLauncher

/**
 * Starts the demo session the way the app's Demo buttons do, once no database operation holds
 * [DatabaseOperationLease].
 *
 * - Through [ObdServiceLauncher]: the demo runs as a plain started service and never calls
 *   startForeground, so `startForegroundService` would oblige a call that never comes, and Android
 *   crashes the app as soon as the service stops.
 * - After the lease is free: the service refuses every new session while a database operation
 *   holds it, and raw-data maintenance takes it on each Activity start. Run 42435077250 started the
 *   demo 200 ms after the previous test's MainActivity, the service refused and stopped, and that
 *   stop crashed the process.
 */
internal fun startDemoSessionWhenDatabaseIdle(context: Context) {
    val deadline = SystemClock.uptimeMillis() + DATABASE_IDLE_TIMEOUT_MS
    while (DatabaseOperationLease.isHeld()) {
        check(SystemClock.uptimeMillis() < deadline) {
            "a database operation held the lease for over ${DATABASE_IDLE_TIMEOUT_MS}ms"
        }
        Thread.sleep(DATABASE_IDLE_POLL_MS)
    }
    val demo = Intent(context, ObdService::class.java).setAction(ObdService.ACTION_DEMO)
    ObdServiceLauncher.start(context, demo)
}

private const val DATABASE_IDLE_TIMEOUT_MS = 30_000L
private const val DATABASE_IDLE_POLL_MS = 100L
