package dev.myvu.sample;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import dev.myvu.sdk.ConnectionState;
import dev.myvu.sdk.MyvuClient;
import dev.myvu.sdk.MyvuConfig;

/**
 * Reference foreground-service pattern for keeping a glasses connection alive.
 *
 * The SDK deliberately does not ship a service: foreground-service types,
 * notification channels and Doze policy are app-level decisions. This shows one
 * correct way to host a long-lived {@link MyvuClient} -- copy and adapt it.
 *
 * The single client is exposed statically so a UI (and the notification mirror)
 * can reach it without binding.
 */
public class MyvuService extends Service {

    public static final String ACTION_START = "dev.myvu.sample.START";
    public static final String ACTION_STOP = "dev.myvu.sample.STOP";
    public static final String EXTRA_MAC = "mac";

    private static final String CHANNEL_ID = "myvu-connection";
    private static final int NOTIF_ID = 1;

    private static volatile MyvuClient client;

    /** The active client, or null when the service is not running. */
    public static MyvuClient client() {
        return client;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (client == null) {
            client = new MyvuClient(this, MyvuConfig.builder().build());
            client.addListener(new MyvuClient.Listener() {
                @Override
                public void onConnectionStateChanged(ConnectionState state) {
                    updateNotification(state.name());
                }
            });
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            if (client != null) client.disconnect();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIF_ID, buildNotification("Connecting..."));
        String mac = intent != null ? intent.getStringExtra(EXTRA_MAC) : null;
        if (mac != null) client.connect(mac); else client.connectAutoSearch();
        // START_STICKY: the OS restarts us after being killed, and onCreate
        // rebuilds the client. The connection re-establishes on next start.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (client != null) {
            client.shutdown();
            client = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // --------------------------------------------------------- notification

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        ensureChannel();
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("MYVU glasses")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Glasses connection", NotificationManager.IMPORTANCE_LOW));
    }

    // ------------------------------------------------------------- helpers

    public static void start(Context ctx, String mac) {
        Intent i = new Intent(ctx, MyvuService.class).setAction(ACTION_START);
        if (mac != null) i.putExtra(EXTRA_MAC, mac);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        ctx.startService(new Intent(ctx, MyvuService.class).setAction(ACTION_STOP));
    }
}
