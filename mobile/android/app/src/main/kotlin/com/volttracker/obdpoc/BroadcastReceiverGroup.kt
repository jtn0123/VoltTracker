package com.volttracker.obdpoc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Tracks a small set of [BroadcastReceiver]s registered against a [Context] and unregisters them as
 * a group.
 */
class BroadcastReceiverGroup {
    private val receivers = ArrayList<BroadcastReceiver>()

    /**
     * Registers [receiver] with [ctx] and tracks it so [unregisterAll] can tear it down later.
     */
    fun register(
        ctx: Context,
        receiver: BroadcastReceiver,
        filter: IntentFilter,
        flags: Int,
    ) {
        ContextCompat.registerReceiver(ctx, receiver, filter, flags)
        receivers.add(receiver)
    }

    /**
     * Unregisters every receiver previously passed to [register]. Swallows
     * [IllegalArgumentException] so a receiver Android already tore down for us does not crash the
     * teardown path.
     */
    fun unregisterAll(ctx: Context) {
        for (receiver in receivers) {
            try {
                ctx.unregisterReceiver(receiver)
            } catch (ignored: IllegalArgumentException) {
                // Receiver can already be unregistered if Android tears down the host quickly.
            }
        }
        receivers.clear()
    }
}
