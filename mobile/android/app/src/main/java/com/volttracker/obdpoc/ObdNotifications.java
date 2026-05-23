package com.volttracker.obdpoc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Builds and posts the ongoing foreground-service notification for {@link ObdService}. Extracted so
 * the notification plumbing is out of the service file.
 */
final class ObdNotifications {

    static final int NOTIFICATION_ID = 4207;
    private static final String CHANNEL_ID = "volt_obd_connection";

    private final Context context;

    ObdNotifications(Context context) {
        this.context = context;
    }

    void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel =
                new NotificationChannel(
                        CHANNEL_ID, "OBD connection", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows while Volt Tracker is connected to an OBD adapter.");
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    Notification build(String text) {
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent =
                PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? new Notification.Builder(context, CHANNEL_ID)
                        : new Notification.Builder(context);
        return builder.setSmallIcon(R.drawable.ic_stat_obd)
                .setContentTitle("Volt Tracker OBD")
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
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
