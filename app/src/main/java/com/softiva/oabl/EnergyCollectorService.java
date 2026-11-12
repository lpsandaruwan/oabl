package com.softiva.oabl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Keeps the paired, read-only telemetry worker alive when no launcher screen is visible. */
public final class EnergyCollectorService extends Service {
    private static final String CHANNEL = "car-energy-collection";
    private static final int ID = 4211;
    static boolean wanted(Context context) {
        if(!EnergyClient.owner(context))return (boolean)EnergyClient.call(context,"link.collectionWanted()");
        android.content.SharedPreferences p=context.getSharedPreferences(CarLinkController.PREFS,0);
        return !CarLinkController.MODE_MANUAL.equals(p.getString("mode",CarLinkController.MODE_AUTO)) && p.contains("pair_code");
    }
    static void ensure(Context context) {
        if (!wanted(context)) return;
        try {
            Intent command=new Intent(context,EnergyCollectorService.class);
            if(Build.VERSION.SDK_INT>=26)context.startForegroundService(command);else context.startService(command);
        } catch(RuntimeException unavailable) { android.util.Log.w("CarEnergy","Background collection start deferred until Home opens",unavailable); }
    }
    @Override public void onCreate() {
        super.onCreate();
        if(Build.VERSION.SDK_INT>=26)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(
                new NotificationChannel(CHANNEL,"Vehicle energy collection",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,ID,new Intent(this,EnergyActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        Notification n=b.setSmallIcon(R.drawable.ic_bar_energy).setContentTitle("OABL vehicle energy collection")
                .setContentText("Saves car data while connected · reconnects automatically").setContentIntent(open).setOngoing(true).build();
        if(Build.VERSION.SDK_INT>=29)startForeground(ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);else startForeground(ID,n);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(!wanted(this)){stopSelf();return START_NOT_STICKY;}
        EnergyClient.link(this).startWorkerOnly();
        return START_STICKY;
    }
    @Override public void onDestroy(){CarLinkController.get(this).stopWorkerOnly();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
