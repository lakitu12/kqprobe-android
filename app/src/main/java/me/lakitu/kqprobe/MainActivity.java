package me.lakitu.kqprobe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.CompoundButton;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    static {
        System.loadLibrary("kqprobe");
        System.loadLibrary("kqserver");
    }

    private static final String PREFS = "kq";
    private static final String KEY_DAEMON = "daemon_on";

    private native String probeVulkan();
    private native String probeSocket(String path);
    static native int nativeStart(String socketPath, String statusPath);

    private TextView info;
    private Switch daemonSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // targetSdk>=35 forces edge-to-edge: without this the top bar (our Switch)
        // is drawn under the 152px status bar and is invisible/untouchable.
        root.setFitsSystemWindows(true);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        daemonSwitch = new Switch(this);
        daemonSwitch.setText("GPU bridge daemon (foreground service)");
        daemonSwitch.setTextSize(15);
        root.addView(daemonSwitch);

        info = new TextView(this);
        info.setTextIsSelectable(true);
        info.setTextSize(11);
        ScrollView sv = new ScrollView(this);
        sv.addView(info);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        boolean on = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_DAEMON, false);
        daemonSwitch.setChecked(on);
        if (on) startServiceIfNeeded();
        daemonSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean(KEY_DAEMON, checked).apply();
                if (checked) startServiceIfNeeded();
                else stopService(new Intent(MainActivity.this, KumquatService.class));
                refresh();
            }
        });

        refresh();
    }

    @Override
    protected void onResume() { super.onResume(); refresh(); }

    private void startServiceIfNeeded() {
        Intent i = new Intent(this, KumquatService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    private void refresh() {
        StringBuilder sb = new StringBuilder();
        boolean on = daemonSwitch.isChecked();
        sb.append("daemon: ").append(on ? "ON" : "OFF").append("\n");
        String dir = getApplicationInfo().dataDir;
        boolean useAbstract = new java.io.File("/data/local/tmp/kq-abstract").exists();
        String sock = useAbstract ? "@kumquat-gpu-0" : dir + "/kumquat-gpu-0";
        java.io.File st = new java.io.File(dir + "/kqserver.status");
        try {
            if (st.exists()) {
                String s = new String(java.nio.file.Files.readAllBytes(st.toPath()));
                String[] lines = s.split("\n");
                sb.append("server: ").append(lines[lines.length - 1]).append("\n");
            } else {
                sb.append("server: not started\n");
            }
        } catch (Exception e) { sb.append("status read err: ").append(e).append("\n"); }
        if (on) sb.append(probeSocket(sock));
        sb.append("\n").append(probeVulkan());
        info.setText(sb);
    }
}
