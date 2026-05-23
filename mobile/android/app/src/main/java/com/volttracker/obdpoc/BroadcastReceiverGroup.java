package com.volttracker.obdpoc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks a small set of {@link BroadcastReceiver}s registered against a {@link Context} and
 * unregisters them as a group. Extracted from {@link MainActivity} so the Activity lifecycle code
 * does not have to repeat the {@code try/catch (IllegalArgumentException)} dance for every
 * receiver.
 *
 * <p>Use one instance per host (typically per Activity). Callers register receivers in {@code
 * onResume}/{@code onStart} and call {@link #unregisterAll(Context)} in the matching teardown.
 */
final class BroadcastReceiverGroup {

    private final List<BroadcastReceiver> receivers = new ArrayList<>();

    /**
     * Registers {@code receiver} with {@code ctx} via {@link
     * ContextCompat#registerReceiver(Context, BroadcastReceiver, IntentFilter, int)} and tracks it
     * so {@link #unregisterAll(Context)} can tear it down later. {@code flags} should normally be
     * {@link ContextCompat#RECEIVER_NOT_EXPORTED} for in-process broadcasts.
     */
    void register(Context ctx, BroadcastReceiver receiver, IntentFilter filter, int flags) {
        ContextCompat.registerReceiver(ctx, receiver, filter, flags);
        receivers.add(receiver);
    }

    /**
     * Unregisters every receiver previously passed to {@link #register}. Swallows {@link
     * IllegalArgumentException} so a receiver Android already tore down for us does not crash the
     * teardown path.
     */
    void unregisterAll(Context ctx) {
        for (BroadcastReceiver receiver : receivers) {
            try {
                ctx.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
                // Receiver can already be unregistered if Android tears down the host quickly.
            }
        }
        receivers.clear();
    }
}
