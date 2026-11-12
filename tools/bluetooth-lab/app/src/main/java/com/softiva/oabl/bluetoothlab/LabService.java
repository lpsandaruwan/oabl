package com.softiva.oabl.bluetoothlab;

import android.app.*;
import android.content.*;
import android.os.*;
import com.softiva.oabllink.*;
import com.softiva.oabllink.bluetooth.BleGattServer;

/** Runs the production BLE transport with an explicitly simulated, phone-only source. */
public final class LabService extends Service {
    public static volatile String status = "Stopped";
    private final Handler samples = new Handler(Looper.getMainLooper());
    private final SampleBuffer store = new SampleBuffer();
    private SimulatedCar car;
    private BleGattServer server;
    public static String code(Context context) {
        android.content.SharedPreferences p = context.getSharedPreferences("lab", 0);
        String value = p.getString("code", null);
        if (value == null) { value = new Pairing().newCode(); p.edit().putString("code", value).apply(); }
        return value;
    }
    private final Runnable sample = new Runnable() {
        @Override public void run() {
            synchronized (store) { store.record(car.read(System.currentTimeMillis())); }
            samples.postDelayed(this, 1000);
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("ble-lab", "Bluetooth lab", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, new Notification.Builder(this, "ble-lab").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("OABL Bluetooth Lab").setContentText("Production BLE transport · simulated data")
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE)).build());
        car = new SimulatedCar(System.currentTimeMillis());
        LinkServer reads = new LinkServer(car, store, "0.1.2-lab", Build.MODEL, Build.VERSION.RELEASE, "phone-simulator");
        reads.setKey(Pairing.sharedKey(code(this)));
        BleLinkRouter router = new BleLinkRouter(reads);
        server = new BleGattServer(this, Pairing.sharedKey(code(this)), request -> {
            synchronized (store) { return router.handle(request); }
        }, value -> status = value);
        samples.post(sample); server.start();
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) { return START_NOT_STICKY; }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        samples.removeCallbacksAndMessages(null); if (server != null) server.close(); status = "Stopped"; super.onDestroy();
    }
}
