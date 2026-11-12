package com.softiva.oabllink.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Switch;
import com.softiva.oabllink.CarProbe;
import com.softiva.oabllink.DongfengProbe;
import com.softiva.oabllink.Reading;
import com.softiva.oabllink.Signal;
import java.util.Locale;

/**
 * A read-only probe viewer: the first installable build of OABL Link. It binds to the car, reads
 * every signal the Dongfeng probe can map, and lists the live values once a second, so the reads and
 * the still-unconfirmed scales (speed, outside temperature, the gear codes) can be checked on the
 * head unit and on a drive before anything downstream trusts them. It never writes vehicle controls. Its foreground service supplies authenticated telemetry to the AI box.
 */
public final class ProbeActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private CarPropertySource source;
    private CarProbe probe;
    private TextView header;
    private TextView body;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if ("ranchu".equals(android.os.Build.HARDWARE) || "goldfish".equals(android.os.Build.HARDWARE)) {
            probe = new com.softiva.oabllink.SimulatedCar(System.currentTimeMillis());
        } else {
            source = new CarPropertySource(this);
            probe = new DongfengProbe(source);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        header = new TextView(this);
        header.setTextColor(Color.WHITE);
        header.setTextSize(18);
        header.setTextIsSelectable(true);
        root.addView(header);

        Button startLink = new Button(this);
        startLink.setText("Start link service");
        startLink.setOnClickListener(v -> {
            if (!bluetoothPermissions()) return;
            Intent intent = new Intent(this, LinkService.class).setAction("retry_bluetooth");
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(intent);
            else startService(intent);
            Toast.makeText(this, "Starting Link · pair code " + LinkService.pairCode(this), Toast.LENGTH_LONG).show();
        });
        root.addView(startLink);

        Switch bluetooth = new Switch(this);
        bluetooth.setText("Bluetooth link"); bluetooth.setTextColor(Color.WHITE);
        bluetooth.setChecked(LinkService.bluetoothEnabled(this));
        bluetooth.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences("oabllink", 0).edit().putBoolean("bluetooth_enabled", checked).apply();
            if (!checked || bluetoothPermissions()) startForegroundService(new Intent(this, LinkService.class));
        });
        root.addView(bluetooth);
        Button btSettings = new Button(this); btSettings.setText("Bluetooth pairing help");
        btSettings.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle("Pair with OABL on the AI box")
                .setMessage("Start the link service here. On the AI box, open OABL → Vehicle data source → Link / Head unit connection, choose Bluetooth and enter the OABL code shown above.\n\nPairing happens inside OABL. Keep CarPlay connected; selecting QCOM-BTD in the car’s phone-pairing menu is not needed. Both devices need the new code-pairing update: OABL Link 0.1.3+ and OABL 0.1.10+.")
                .setPositiveButton("Done", null).show());
        root.addView(btSettings);

        ScrollView scroll = new ScrollView(this);
        body = new TextView(this);
        body.setTextColor(0xFFB9F6CA);
        body.setTextSize(16);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.setGravity(Gravity.TOP);
        scroll.addView(body);
        root.addView(scroll);

        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    private void render() {
        Reading reading = probe.read(System.currentTimeMillis());
        reading.set(Signal.HU_BOOT_ID,LinkService.bootId(this));
        header.setText("OABL Link · probe " + probe.name() + " · car " + (source == null ? "SIMULATED — emulator only" : source.status())
                + " · " + probe.available().size() + " signals"
                + "\n" + LinkService.STATUS + " · pair code " + LinkService.pairCode(this)
                + "\n" + LinkService.BLUETOOTH_STATUS);
        StringBuilder text = new StringBuilder();
        for (Signal signal : Signal.values()) {
            String value;
            if (!reading.has(signal)) {
                value = source != null && source.supported(propertyShown(signal)) ? "—" : "(not readable)";
            } else if (signal == Signal.GEAR) {
                value = Signal.Gear.of(reading.get(signal)).name();
            } else if (signal.kind == Signal.Kind.BOOL) {
                value = reading.bool(signal) ? "yes" : "no";
            } else {
                double v = reading.get(signal);
                value = (v == Math.rint(v) ? String.format(Locale.US, "%.0f", v) : String.format(Locale.US, "%.2f", v))
                        + (signal.unit.isEmpty() ? "" : " " + signal.unit);
            }
            text.append(String.format(Locale.US, "%-13s %s%n", signal.key, value));
        }
        body.setText(text.toString());
    }

    private boolean bluetoothPermissions() {
        if (!LinkService.bluetoothEnabled(this) || android.os.Build.VERSION.SDK_INT < 31) return true;
        if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) == android.content.pm.PackageManager.PERMISSION_GRANTED) return true;
        requestPermissions(new String[]{android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_ADVERTISE}, 31);
        Toast.makeText(this, "Allow Bluetooth, then tap Start link service", Toast.LENGTH_LONG).show(); return false;
    }

    /** The property id behind a signal, so the viewer can tell "not readable" from "not reported yet". */
    private static int propertyShown(Signal signal) {
        switch (signal) {
            case SOC: return DongfengProbe.SOC;
            case RANGE_EST: return DongfengProbe.RANGE_ACTUAL;
            case RANGE_STANDARD: return DongfengProbe.RANGE_STANDARD;
            case RANGE_NEDC: return DongfengProbe.RANGE_NEDC;
            case ODOMETER: return DongfengProbe.ODOMETER;
            case CHARGING: return DongfengProbe.CHARGE_STATE;
            case PLUG: return DongfengProbe.PLUG_CONNECTED;
            case READY: return DongfengProbe.DRIVE_READY;
            case GEAR: return DongfengProbe.GEAR;
            case SPEED: return DongfengProbe.SPEED;
            case SPEED_DISPLAY: return DongfengProbe.SPEED_DISPLAY;
            case TEMP_OUT: return DongfengProbe.TEMP_OUT;
            case HVAC_ON: return DongfengProbe.AC_SYSTEM_OFF;
            case POWER: return DongfengProbe.CHARGE_POWER;
            case VOLTAGE: return DongfengProbe.CHARGE_VOLTAGE;
            case CURRENT: return DongfengProbe.CHARGE_CURRENT;
            case TRIP_KM: return DongfengProbe.TRIP_A;
            case TRIP_KWH100: return DongfengProbe.TRIP_A_ENERGY;
            case TRIP_B_KM: return DongfengProbe.TRIP_B_KM;
            case TRIP_B_KWH100: return DongfengProbe.TRIP_B_KWH100;
            case TRIP_C_KM: return DongfengProbe.TRIP_C_KM;
            case TRIP_C_KWH100: return DongfengProbe.TRIP_C_KWH100;
            case TRIP_A_TIME_RAW: return DongfengProbe.TRIP_A_TIME_RAW;
            case TRIP_B_TIME_RAW: return DongfengProbe.TRIP_B_TIME_RAW;
            case TRIP_C_TIME_RAW: return DongfengProbe.TRIP_C_TIME_RAW;
            case TRIP_A_SPEED_RAW: return DongfengProbe.TRIP_A_SPEED_RAW;
            case TRIP_B_SPEED_RAW: return DongfengProbe.TRIP_B_SPEED_RAW;
            case TRIP_C_SPEED_RAW: return DongfengProbe.TRIP_C_SPEED_RAW;
            default: return 0;
        }
    }
}
