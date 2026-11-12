package com.softiva.oabl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.IntentFilter;
import android.media.audiofx.AudioEffect;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import java.util.LinkedHashMap;
import java.util.Map;

/** Holds enabled effects while other apps play; no recording or audio interception. */
public final class SoundEqualizerService extends Service {
    private static final Map<Integer, String> sessions = new LinkedHashMap<>();
    private static SoundEqualizerService instance;
    private static volatile String status = "Off";
    private final Map<Integer, EqualizerEngine> engines = new LinkedHashMap<>();
    private final java.util.Set<Integer> failedSessions = new java.util.HashSet<>();
    private final EqualizerSessionReceiver sessionReceiver = new EqualizerSessionReceiver();
    private boolean receiverRegistered;
    private String appliedProfile;
    private final Handler handler = new Handler(android.os.Looper.getMainLooper());
    private EqualizerProfile profile;
    private String notificationText;
    private final Runnable health = new Runnable() {
        @Override public void run() {
            applyNow();
            handler.postDelayed(this, 5000);
        }
    };

    // ---- cross-process API ----
    // The service runs in its own ":audio" process (see the manifest). The launcher screens talk
    // to it with intents and read its state from a small preference file, so the launcher UI is no
    // longer a foreground-service process: Android can trim and reclaim it while other apps play,
    // and the equalizer keeps running.
    private static final String ACTION_APPLY = "com.softiva.oabl.eq.APPLY";
    private static final String ACTION_PREVIEW = "com.softiva.oabl.eq.PREVIEW";
    private static final String ACTION_BYPASS = "com.softiva.oabl.eq.BYPASS";
    private static final String ACTION_OPEN_SESSION = "com.softiva.oabl.eq.OPEN_SESSION";
    private static final String ACTION_CLOSE_SESSION = "com.softiva.oabl.eq.CLOSE_SESSION";
    private static final String RUNTIME = "equalizer_runtime";

    // Service-process state.
    private static volatile boolean bypass;
    /** Style curve being auditioned by the Find-your-sound wizard; null when not previewing. */
    private static volatile float[] preview;
    private static volatile String state = "off";
    private String publishedState, publishedStatus;

    private static SharedPreferences runtime(Context context) {
        // MULTI_PROCESS: re-read when the service process has written a new status.
        return context.getSharedPreferences(RUNTIME, Context.MODE_MULTI_PROCESS);
    }

    /** Short state for the Equalizer status chip: "active", "waiting", "off" or "unavailable". */
    static String state(Context context) { return runtime(context).getString("state", "off"); }
    static String status(Context context) { return runtime(context).getString("status", "Off"); }

    /** Audition a style curve live without saving it; null returns to the saved sound. */
    static void setPreview(Context context, float[] style) {
        send(context, new Intent(ACTION_PREVIEW).putExtra("style", style == null ? null : style.clone()));
    }

    /** Temporarily hear the original sound (A/B compare). */
    static void setBypass(Context context, boolean value) {
        send(context, new Intent(ACTION_BYPASS).putExtra("bypass", value));
    }

    static void start(Context context) {
        send(context, new Intent(ACTION_APPLY));
    }

    static void refresh(Context context) {
        if (!new EqualizerProfile(context).enabled()) {
            context.stopService(new Intent(context, SoundEqualizerService.class));
            runtime(context).edit().putString("state", "off").putString("status", "Off").commit();
        } else send(context, new Intent(ACTION_APPLY));
    }

    /**
     * A player's audio session. Kept in the shared file too, so a session announced while the
     * equalizer was off is still known when it is switched on (the service loads them on start).
     */
    static void rememberSession(Context context, int session, String source) {
        if (session <= 0) return;
        synchronized (RUNTIME) {
            java.util.Set<String> known = new java.util.LinkedHashSet<>(runtime(context).getStringSet("sessions", java.util.Collections.emptySet()));
            known.removeIf(entry -> entry.startsWith(session + "|"));
            if (known.size() >= 8) known.remove(known.iterator().next());
            known.add(session + "|" + (source == null ? "" : source));
            runtime(context).edit().putStringSet("sessions", known).commit();
        }
        send(context, new Intent(ACTION_OPEN_SESSION).putExtra("session", session).putExtra("source", source));
    }

    static void forgetSession(Context context, int session) {
        if (session <= 0) return;
        synchronized (RUNTIME) {
            java.util.Set<String> known = new java.util.LinkedHashSet<>(runtime(context).getStringSet("sessions", java.util.Collections.emptySet()));
            if (known.removeIf(entry -> entry.startsWith(session + "|"))) runtime(context).edit().putStringSet("sessions", known).commit();
        }
        send(context, new Intent(ACTION_CLOSE_SESSION).putExtra("session", session));
    }

    /**
     * Forgets every session a player registered earlier. A player process that died never closed
     * its sessions, and they'd hold equalizer slots (at most 8) until new sessions are refused.
     */
    static void forgetSessionsFrom(Context context, String source) {
        java.util.List<Integer> stale = new java.util.ArrayList<>();
        synchronized (RUNTIME) {
            for (String entry : runtime(context).getStringSet("sessions", java.util.Collections.emptySet())) {
                String[] parts = entry.split("\\|", 2);
                if (parts.length < 2 || !parts[1].equals(source)) continue;
                try { stale.add(Integer.parseInt(parts[0])); } catch (NumberFormatException ignored) { }
            }
        }
        for (int session : stale) forgetSession(context, session);
    }

    /** Delivers a command to the service process, starting it when the equalizer is on. */
    private static void send(Context context, Intent command) {
        if (!new EqualizerProfile(context).enabled()) return;
        command.setClass(context, SoundEqualizerService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(command);
            else context.startService(command);
        } catch (RuntimeException error) {
            runtime(context).edit().putString("status", "Paused · open Equalizer to resume").apply();
        }
    }

    /** Runs in the service process: applies one command. */
    private void handle(Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PREVIEW.equals(action)) {
            float[] style = intent.getFloatArrayExtra("style");
            preview = style;
        } else if (ACTION_BYPASS.equals(action)) {
            bypass = intent.getBooleanExtra("bypass", false);
        } else if (ACTION_OPEN_SESSION.equals(action)) {
            openSession(this, intent.getIntExtra("session", -1), intent.getStringExtra("source"));
            return;
        } else if (ACTION_CLOSE_SESSION.equals(action)) {
            closeSession(intent.getIntExtra("session", -1));
            return;
        }
        applyNow();
    }

    /** In the service process. */
    private static void openSession(Context context, int session, String source) {
        if (session <= 0) return;
        String label = "Music player";
        if (source != null) {
            try {
                label = context.getPackageManager().getApplicationLabel(
                        context.getPackageManager().getApplicationInfo(source, 0)).toString();
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { return; }
        }
        if (sessions.size() >= 8 && !sessions.containsKey(session)) return;
        sessions.put(session, label);
        if (instance != null) {
            instance.failedSessions.remove(session);
            instance.applyNow();
        }
    }

    private static void closeSession(int session) {
        sessions.remove(session);
        if (instance != null) instance.applyNow();
    }

    private void publish() {
        if (state.equals(publishedState) && status.equals(publishedStatus)) return;
        publishedState = state;
        publishedStatus = status;
        runtime(this).edit().putString("state", state).putString("status", status).commit();
    }

    @Override
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    public void onCreate() {
        super.onCreate();
        instance = this;
        profile = new EqualizerProfile(this);
        IntentFilter filter = new IntentFilter(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        filter.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        // Export is intentional: sessions come from third-party music players.
        // Pre-33 Android uses the legacy exported registration API.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(sessionReceiver, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(sessionReceiver, filter);
        receiverRegistered = true;
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) notifications.createNotificationChannel(
                new NotificationChannel("equalizer", "Audio equalizer", NotificationManager.IMPORTANCE_LOW));
        Notification notification = notification("Starting…");
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(4108, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(4108, notification);
        } catch (RuntimeException error) {
            status = "Paused · open Equalizer to resume";
            stopSelf();
            return;
        }
        // Sessions announced while the equalizer was off or before this process started.
        for (String entry : runtime(this).getStringSet("sessions", java.util.Collections.emptySet())) {
            String[] parts = entry.split("\\|", 2);
            try { openSession(this, Integer.parseInt(parts[0]), parts.length > 1 && !parts[1].isEmpty() ? parts[1] : null); }
            catch (NumberFormatException ignored) { }
        }
        handler.post(health);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        profile = new EqualizerProfile(this);
        if (!profile.enabled()) { stopSelf(); return START_NOT_STICKY; }
        handle(intent);
        return START_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) unregisterReceiver(sessionReceiver);
        releaseAll();
        instance = null;
        state = "off";
        // Switched off in the launcher: read the saved choice, not this process's last copy.
        profile = new EqualizerProfile(this);
        status = profile.enabled() ? "Paused · open Equalizer to resume" : "Off";
        publish();
        super.onDestroy();
    }

    private void applyNow() {
        // Settings are edited in the launcher process; re-read them from disk.
        profile = new EqualizerProfile(this);
        if (!profile.enabled()) { stopSelf(); return; }
        if (Build.VERSION.SDK_INT < 28) {
            state = "unavailable";
            status = "Band processing requires Android 9 or newer";
            updateNotification();
            return;
        }
        String settings = java.util.Arrays.toString(profile.gains())
                + "|" + profile.masterGain() + "|" + profile.headroom() + "|" + bypass
                + "|" + java.util.Arrays.toString(preview);
        boolean changed = !settings.equals(appliedProfile);
        if (changed) { failedSessions.clear(); appliedProfile = settings; }
        // Music players only: the system output mix (session 0) is never processed.
        Map<Integer, String> targets = new LinkedHashMap<>(sessions);
        failedSessions.retainAll(targets.keySet());
        for (Integer session : new java.util.ArrayList<>(engines.keySet())) {
            if (!targets.containsKey(session) || !engines.get(session).controlled()) {
                engines.remove(session).close();
            }
        }
        for (Integer session : targets.keySet()) {
            if (failedSessions.contains(session)) continue;
            try {
                EqualizerEngine engine = engines.get(session);
                if (engine == null) {
                    engine = new EqualizerEngine(session); engines.put(session, engine);
                    engine.apply(profile, bypass, preview);
                } else if (changed) engine.apply(profile, bypass, preview);
            } catch (RuntimeException error) {
                EqualizerEngine engine = engines.remove(session);
                if (engine != null) engine.close();
                failedSessions.add(session);
            }
        }
        boolean failed = !failedSessions.isEmpty();
        if (engines.isEmpty()) {
            state = failed ? "unavailable" : "waiting";
            status = failed ? "Processing unavailable for this audio source"
                    : "Waiting for a compatible player";
        } else {
            state = "active";
            status = "Active · " + (engines.size() == 1
                    ? targets.get(engines.keySet().iterator().next()) : engines.size() + " players")
                    + (failed ? " · some sources unavailable" : "");
        }
        updateNotification();
    }
    private void releaseAll() {
        for (EqualizerEngine engine : engines.values()) engine.close();
        engines.clear();
        failedSessions.clear();
        appliedProfile = null;
    }
    private void updateNotification() {
        publish();
        if (status.equals(notificationText)) return;
        notificationText = status;
        getSystemService(NotificationManager.class).notify(4108, notification(status));
    }
    private Notification notification(String detail) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, EqualizerActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "equalizer") : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_stat_drive).setContentTitle("OABL Equalizer")
                .setContentText(detail).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build();
    }
}
