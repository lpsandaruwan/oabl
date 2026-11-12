package com.softiva.oabl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Keeps GPS on for the energy model while a journey is active, whatever app is in front (Home
 * stops its own GPS when it is hidden). It runs only during a journey, ticks once a second and
 * saves a heartbeat every 30 s so a power cut loses at most that much. Nothing runs, and GPS stays
 * off, when there's no journey.
 */
public final class JourneyService extends Service {
    private static final String CHANNEL_ID = "oabl_journey";
    private static final int NOTIFICATION_ID = 4107;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeedTracker tracker;
    private float lastSpeedKph = Float.NaN;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            EnergyJournal journal = EnergyJournal.get(JourneyService.this);
            if (journal.runningSession() == 0) { stopSelf(); return; }
            journal.tick();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, notification());
            }
        } catch (RuntimeException notAllowed) {
            // Restarted from the background without location access: Home resumes it when shown.
            stopSelf();
            return;
        }
        EnergyJournal journal = EnergyJournal.get(this);
        journal.serviceRunning(true);
        tracker = new SpeedTracker(this, new SpeedTracker.Listener() {
            @Override public void onSpeed(int kilometersPerHour, boolean reliable) {
                lastSpeedKph = reliable ? kilometersPerHour : Float.NaN;
                EnergyJournal.get(JourneyService.this).observeSpeed(kilometersPerHour, reliable);
            }
            @Override public void onPosition(android.location.Location location, boolean reliable) {
                EnergyJournal.get(JourneyService.this).position(location, lastSpeedKph);
            }
        });
        tracker.start();
        handler.post(tick);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // A restart after the process died is handled by Home (it resumes the journey and counts
        // the gap as unknown GPS), so the system shouldn't restart this on its own.
        if (EnergyJournal.get(this).runningSession() == 0) stopSelf();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(tick);
        if (tracker != null) tracker.stop();
        EnergyJournal.get(this).serviceRunning(false);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private Notification notification() {
        Intent open = new Intent(this, EnergyActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_bar_energy)
                .setContentTitle("Drive in progress")
                .setContentText("Recording distance for the battery estimate")
                .setContentIntent(content)
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED);
        return builder.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Drive energy", NotificationManager.IMPORTANCE_MIN);
        channel.setDescription("Shown while a drive is recorded for the battery estimate");
        channel.setShowBadge(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }
}
