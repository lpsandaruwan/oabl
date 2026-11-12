package com.softiva.oabl.bluetoothlab;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler();
    private TextView status;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            status.setText("SIMULATED CAR DATA\n\nPairing code: " + LabService.code(MainActivity.this)
                    + "\n\n" + LabService.status + "\n\nNo Wi-Fi or hotspot is used."
                    + "\nKeep the app running for this test.\nThis lab is separate from OABL and contains no vehicle access."
                    + "\n\nProduction transport: confirm system Bluetooth pairing when asked. The telemetry session is encrypted.");
            handler.postDelayed(this, 1000);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density); page.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this); title.setText("OABL Bluetooth Lab"); title.setTextSize(26); page.addView(title);
        status = new TextView(this); status.setTextSize(17); page.addView(status, new LinearLayout.LayoutParams(-1, 0, 1));
        Button start = new Button(this); start.setText("Start BLE test"); start.setOnClickListener(v -> start()); page.addView(start);
        Button stop = new Button(this); stop.setText("Stop BLE test"); stop.setOnClickListener(v -> stopService(new Intent(this, LabService.class))); page.addView(stop);
        setContentView(page); start();
    }
    private void start() {
        if (Build.VERSION.SDK_INT >= 31 && (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE}, 1); return;
        }
        startForegroundService(new Intent(this, LabService.class));
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 1 && grants.length == 2 && grants[0] == PackageManager.PERMISSION_GRANTED && grants[1] == PackageManager.PERMISSION_GRANTED) start();
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
