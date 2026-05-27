package com.volttracker.obdpoc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationCompat;

/**
 * Builds and posts the ongoing foreground-service notification for {@link ObdService}. Extracted so
 * the notification plumbing is out of the service file.
 */
final class ObdNotifications {

    static final int NOTIFICATION_ID = 4207;
    // Package-visible so MainActivity's adapter-ready notification path can post on
    // the same channel without us having to expose a builder method just for that one call site.
    static final String CHANNEL_ID = "volt_obd_connection";

    private final Context context;

    ObdNotifications(Context context) {
        this.context = context;
    }

    void createChannel() {
        ensureChannel(context);
    }

    /**
     * Idempotent channel creation usable from any {@link Context}. Both {@link ObdService} (at
     * service onCreate) and {@link MainActivity} (at activity onCreate, so adapter-ready
     * notifications can land before the foreground service has ever run) call this.
     */
    static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx == null) {
            return;
        }
        NotificationChannel channel =
                new NotificationChannel(
                        CHANNEL_ID, "OBD connection", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows while Volt Tracker is connected to an OBD adapter.");
        NotificationManager manager = ctx.getSystemService(NotificationManager.class);
        if (manager != null) {
            // createNotificationChannel is documented as a no-op when the channel already exists
            // with the same id, so repeat calls from MainActivity/ObdService cost nothing.
            manager.createNotificationChannel(channel);
        }
    }

    Notification build(String text) {
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent =
                PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID);
        return builder.setSmallIcon(R.drawable.ic_stat_obd)
                .setContentTitle("Volt Tracker OBD")
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    /** Updates the already-showing notification text. */
    void post(String text) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, build(text));
        }
    }
}
