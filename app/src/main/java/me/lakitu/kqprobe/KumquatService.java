package me.lakitu.kqprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * KumquatService — foreground daemon keeping the kumquat gfxstream server alive
 * so background limits / Miui power killing don't drop the container GPU bridge.
 * Modeled on DroidSpaces TerminalSessionService (foregroundServiceType=specialUse).
 */
public class KumquatService extends Service {
    private static final String TAG = "kqprobe-svc";
    private static final String CHANNEL_ID = "kumquat_server";
    private static final int NOTIF_ID = 1;

    private static volatile boolean serverStarted = false;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundCompat();
        ensureServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureServer();
        return START_STICKY;
    }

    private synchronized void ensureServer() {
        if (serverStarted) return;
        String dir = getApplicationInfo().dataDir;
        boolean useAbstract = new java.io.File("/data/local/tmp/kq-abstract").exists();
        String sock = useAbstract ? "@kumquat-gpu-0" : dir + "/kumquat-gpu-0";
        String status = dir + "/kqserver.status";
        int rc = MainActivity.nativeStart(sock, status);
        Log.i(TAG, "server start rc=" + rc + " sock=" + sock);
        serverStarted = (rc == 0);
        updateNotification();
    }

    private void startForegroundCompat() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Kumquat GPU Server", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        nm.notify(NOTIF_ID, buildNotif());
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, buildNotif(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, buildNotif());
        }
    }

    private Notification buildNotif() {
        String txt = serverStarted ? "running" : "starting server...";
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Kumquat GPU server")
                .setContentText(txt + " (Mali-G925 bridge)")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotif());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
