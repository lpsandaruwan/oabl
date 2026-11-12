package com.softiva.wifidebugstartup;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

public final class SetupActivity extends Activity {
    private final Handler handler = new Handler();
    private TextView status;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            status.setText(EnableService.prefs(SetupActivity.this).getString("last_status", "Not run yet"));
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this); title.setTextSize(24); title.setText("Wireless debugging startup"); root.addView(title);
        TextView detail = new TextView(this); detail.setTextSize(18);
        detail.setText("Keeps Wireless debugging on when Wi-Fi is connected, including after restarts and later Wi-Fi reconnects. USB debugging and Wi-Fi/hotspot power are not changed. Turn this switch off before disabling Wireless debugging manually."); root.addView(detail);
        Switch enabled = new Switch(this); enabled.setText("Keep wireless debugging on over Wi-Fi");
        enabled.setChecked(EnableService.enabled(this));
        enabled.setOnCheckedChangeListener((button, checked) -> {
            EnableService.prefs(this).edit().putBoolean("enabled", checked).apply();
            if (checked) EnableService.start(this);
            else stopService(new android.content.Intent(this, EnableService.class));
        }); root.addView(enabled);
        status = new TextView(this); status.setTextSize(18); root.addView(status);
        Button retry = new Button(this); retry.setText("Run now"); retry.setOnClickListener(v -> EnableService.start(this)); root.addView(retry);
        setContentView(root);
        EnableService.start(this);
    }
    @Override public void onResume() { super.onResume(); handler.post(refresh); }
    @Override public void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
