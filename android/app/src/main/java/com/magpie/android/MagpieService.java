package com.magpie.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * Keeps the gateway running in the foreground, so Android does not freeze the
 * app (and with it the child process) once the screen is off.
 */
public final class MagpieService extends Service {

    public static final String ACTION_START = "com.magpie.android.action.START";
    public static final String ACTION_STOP = "com.magpie.android.action.STOP";

    private static final String CHANNEL = "magpie";
    private static final int NOTIFICATION_ID = 1;

    public static void start(Context ctx) {
        Intent intent = new Intent(ctx, MagpieService.class).setAction(ACTION_START);
        ctx.startForegroundService(intent);
    }

    public static void stop(Context ctx) {
        Intent intent = new Intent(ctx, MagpieService.class).setAction(ACTION_STOP);
        try {
            ctx.startService(intent);
        } catch (IllegalStateException e) {
            MagpieGateway.stop();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        channel(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            MagpieGateway.stop();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification(this));
        MagpieGateway.start(this);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        MagpieGateway.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static void channel(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL,
                ctx.getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(ctx.getString(R.string.notification_channel_desc));
        nm.createNotificationChannel(channel);
    }

    private Notification notification(Context ctx) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(
                ctx, 0, new Intent(ctx, MainActivity.class), flags);
        PendingIntent stop = PendingIntent.getService(
                ctx, 1, new Intent(ctx, MagpieService.class).setAction(ACTION_STOP), flags);
        String where = (MagpiePrefs.lan(ctx) ? "0.0.0.0:" : "127.0.0.1:")
                + MagpiePrefs.gatewayPort(ctx);
        String page = "127.0.0.1:" + MagpiePrefs.webPort(ctx);
        return new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_magpie)
                .setContentTitle(ctx.getString(R.string.notification_title))
                .setContentText(ctx.getString(R.string.notification_text, where, page))
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        null, ctx.getString(R.string.notification_open), open).build())
                .addAction(new Notification.Action.Builder(
                        null, ctx.getString(R.string.notification_stop), stop).build())
                .build();
    }
}
