package me.lakitu.kqprobe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Restart the GPU bridge daemon on boot if the user left the toggle ON. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        boolean on = ctx.getSharedPreferences("kq", Context.MODE_PRIVATE)
                .getBoolean("daemon_on", false);
        if (!on) return;
        try {
            Intent i = new Intent(ctx, KumquatService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception e) {
            // Android 12+ may reject FGS start from BOOT_COMPLETED; user must open app
            Log.w("kqprobe-boot", "auto-start rejected: " + e);
        }
    }
}
