package com.softiva.oabl;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Build;
import android.graphics.drawable.Icon;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Notification access is the user-approved bridge that lets the launcher read
 * active MediaSessions, calls, and navigation guidance. Nothing is persisted.
 */
public final class MediaAccessService extends NotificationListenerService {
    static final String ACTION_MEDIA_CHANGED = "com.softiva.oabl.MEDIA_CHANGED";
    private static volatile CallSnapshot currentCall = CallSnapshot.NONE;
    private static volatile NavigationSnapshot currentNavigation = NavigationSnapshot.NONE;

    static final class NavigationSnapshot {
        static final NavigationSnapshot NONE = new NavigationSnapshot(
                false, null, null, null, null, null, null, 0L);
        final boolean active;
        final String packageName;
        final String appLabel;
        final NavigationHintParser.Text text;
        final Icon icon;
        final PendingIntent open;
        final String key;
        final long postedAt;
        /** The provider's own "exit navigation" action, when it offers one. */
        final PendingIntent end;

        NavigationSnapshot(boolean active, String packageName, String appLabel,
                           NavigationHintParser.Text text, Icon icon,
                           PendingIntent open, String key, long postedAt) {
            this(active, packageName, appLabel, text, icon, open, key, postedAt, null);
        }

        NavigationSnapshot(boolean active, String packageName, String appLabel,
                           NavigationHintParser.Text text, Icon icon,
                           PendingIntent open, String key, long postedAt, PendingIntent end) {
            this.end = end;
            this.active = active;
            this.packageName = packageName;
            this.appLabel = appLabel;
            this.text = text;
            this.icon = icon;
            this.open = open;
            this.key = key;
            this.postedAt = postedAt;
        }
    }

    /** In the launcher process this is the copy last received from the listener's process. */
    static NavigationSnapshot navigationSnapshot() {
        return currentNavigation;
    }

    static final class CallSnapshot {
        static final CallSnapshot NONE = new CallSnapshot(false, null, null, null, 0L);

        final boolean active;
        final String caller;
        final String state;
        final String appLabel;
        final long startedAtMillis;

        CallSnapshot(boolean active, String caller, String state, String appLabel,
                     long startedAtMillis) {
            this.active = active;
            this.caller = caller;
            this.state = state;
            this.appLabel = appLabel;
            this.startedAtMillis = startedAtMillis;
        }
    }

    static CallSnapshot callSnapshot() {
        return currentCall;
    }

    static final String ACTION_REQUEST = "com.softiva.oabl.MEDIA_REQUEST";
    private android.content.BroadcastReceiver requests;

    /** Launcher side: ask the listener (another process) to send its current snapshots. */
    static void requestUpdate(android.content.Context context) {
        context.sendBroadcast(new Intent(ACTION_REQUEST).setPackage(context.getPackageName()));
    }

    /** Launcher side: take the snapshots carried by a MEDIA_CHANGED broadcast. */
    static void accept(Intent intent) {
        if (intent == null || !intent.hasExtra("nav_active")) return;
        NavigationHintParser.Text text = intent.hasExtra("nav_title") || intent.hasExtra("nav_detail") || intent.hasExtra("nav_trip")
                ? new NavigationHintParser.Text(intent.getStringExtra("nav_title"), intent.getStringExtra("nav_detail"), intent.getStringExtra("nav_trip"))
                : null;
        currentNavigation = new NavigationSnapshot(intent.getBooleanExtra("nav_active", false),
                intent.getStringExtra("nav_package"), intent.getStringExtra("nav_label"), text,
                intent.getParcelableExtra("nav_icon"), intent.getParcelableExtra("nav_open"),
                intent.getStringExtra("nav_key"), intent.getLongExtra("nav_posted", 0L), intent.getParcelableExtra("nav_end"));
        currentCall = new CallSnapshot(intent.getBooleanExtra("call_active", false), intent.getStringExtra("call_caller"),
                intent.getStringExtra("call_state"), intent.getStringExtra("call_label"), intent.getLongExtra("call_started", 0L));
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        if (requests == null) {
            requests = new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context context, Intent intent) {
                    // Home may have missed an update while another app was in front.
                    // Read the current notifications instead of replaying an older cache.
                    updateSnapshots(null);
                    notifyLauncher();
                }
            };
            ServicesState.register(this, requests, new android.content.IntentFilter(ACTION_REQUEST));
        }
        updateSnapshots(null);
        notifyLauncher();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        updateSnapshots(null);
        notifyLauncher();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        updateSnapshots(sbn == null ? null : sbn.getKey());
        notifyLauncher();
    }

    @Override
    public void onListenerDisconnected() {
        currentCall = CallSnapshot.NONE;
        currentNavigation = NavigationSnapshot.NONE;
        mapsWatch.reset();
        tileHandler.removeCallbacksAndMessages(null);
        notifyLauncher();
        if (requests != null) { try { unregisterReceiver(requests); } catch (RuntimeException ignored) { } requests = null; }
        super.onListenerDisconnected();
    }

    private void updateSnapshots(String removedKey) {
        CallSnapshot best = CallSnapshot.NONE;
        boolean ongoingCallFound = false;
        NavigationSnapshot guidance = NavigationSnapshot.NONE;
        StatusBarNotification[] notifications;
        try {
            notifications = getActiveNotifications();
        } catch (RuntimeException error) {
            currentCall = CallSnapshot.NONE;
            currentNavigation = NavigationSnapshot.NONE;
            return;
        }
        if (notifications == null) {
            currentCall = CallSnapshot.NONE;
            currentNavigation = NavigationSnapshot.NONE;
            return;
        }
        for (StatusBarNotification sbn : notifications) {
            if (sbn == null || (removedKey != null && removedKey.equals(sbn.getKey()))) {
                continue;
            }
            NavigationSnapshot candidate = readNavigation(sbn);
            if (candidate.active && (!guidance.active || candidate.postedAt > guidance.postedAt)) {
                guidance = candidate;
            }
            Notification notification = sbn.getNotification();
            if (notification == null || !Notification.CATEGORY_CALL.equals(notification.category)) {
                continue;
            }
            Bundle extras = notification.extras;
            String caller = clean(extras == null
                    ? null : extras.getCharSequence(Notification.EXTRA_TITLE));
            String detail = clean(extras == null
                    ? null : extras.getCharSequence(Notification.EXTRA_TEXT));
            String state = callState(detail);
            if (!ongoingCallFound && (!best.active || sbn.isOngoing())) {
                best = new CallSnapshot(true, caller, state, appLabel(sbn.getPackageName()),
                        sbn.getPostTime());
                ongoingCallFound = sbn.isOngoing();
            }
        }
        currentCall = best;
        currentNavigation = guidance;
        routeChanged(mapsWatch.update(guidance.active, guidance.packageName));
    }

    private final MapsTile.Watch mapsWatch = new MapsTile.Watch();
    private final android.os.Handler tileHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable closeTile = () -> { if (!currentNavigation.active) MapsTile.close(); };
    private final Runnable openHome = () -> {
        if (!currentNavigation.active) return;
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setPackage(getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException refused) {
            // Maps simply stays in front.
        }
    };

    /** A Maps route that starts goes to its tile on Home; once it ends, the tile leaves too. */
    private void routeChanged(MapsTile.Watch.Change change) {
        if (change == MapsTile.Watch.Change.STARTED) {
            tileHandler.removeCallbacks(closeTile);
            tileHandler.postDelayed(openHome, MapsTile.HOME_AFTER_START_MS);
        } else if (change == MapsTile.Watch.Change.ENDED) {
            tileHandler.removeCallbacks(openHome);
            tileHandler.postDelayed(closeTile, MapsTile.CLOSE_AFTER_END_MS);
        }
    }

    private NavigationSnapshot readNavigation(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (notification == null) {
            return NavigationSnapshot.NONE;
        }
        ComponentName selected = new LauncherPreferences(this).focus();
        String channel = Build.VERSION.SDK_INT >= 26 ? notification.getChannelId() : null;
        if (!NavigationHintParser.accepts(sbn.getPackageName(),
                selected == null ? null : selected.getPackageName(), notification.category,
                sbn.isOngoing(), (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0,
                channel, sbn.getTag())) {
            return NavigationSnapshot.NONE;
        }
        Bundle extras = notification.extras;
        if (extras == null) {
            return NavigationSnapshot.NONE;
        }
        try {
            NavigationHintParser.Text text = NavigationHintParser.parse(
                    extras.getCharSequence(Notification.EXTRA_TITLE),
                    extras.getCharSequence(Notification.EXTRA_TEXT),
                    extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
                    extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
                    extras.getCharSequence(Notification.EXTRA_INFO_TEXT));
            if (text == null) {
                return NavigationSnapshot.NONE;
            }
            return new NavigationSnapshot(true, sbn.getPackageName(), appLabel(sbn.getPackageName()),
                    text, notification.getLargeIcon(), notification.contentIntent,
                    sbn.getKey(), sbn.getPostTime(), endAction(notification));
        } catch (RuntimeException malformedNotification) {
            return NavigationSnapshot.NONE;
        }
    }

    private static PendingIntent endAction(Notification notification) {
        if (notification.actions == null) {
            return null;
        }
        for (Notification.Action action : notification.actions) {
            if (action == null || action.title == null || action.actionIntent == null) continue;
            String title = action.title.toString().toLowerCase(java.util.Locale.ROOT);
            if (title.contains("exit") || title.contains("end") || title.contains("stop")
                    || title.contains("close")) {
                return action.actionIntent;
            }
        }
        return null;
    }

    private String appLabel(String packageName) {
        try {
            PackageManager manager = getPackageManager();
            ApplicationInfo info = manager.getApplicationInfo(packageName, 0);
            return clean(manager.getApplicationLabel(info));
        } catch (PackageManager.NameNotFoundException error) {
            return null;
        }
    }

    private static String callState(String detail) {
        if (detail == null) {
            return "Call";
        }
        String lower = detail.toLowerCase(java.util.Locale.US);
        if (lower.contains("incoming") || lower.contains("ringing")) {
            return "Incoming call";
        }
        if (lower.contains("dialing") || lower.contains("calling")) {
            return "Calling";
        }
        return "Call";
    }

    private static String clean(CharSequence value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.toString().replace('\n', ' ').trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** Sends the snapshots to the launcher process (it keeps a copy; see {@link #accept}). */
    private void notifyLauncher() {
        NavigationSnapshot nav = currentNavigation;
        CallSnapshot call = currentCall;
        Intent update = new Intent(ACTION_MEDIA_CHANGED).setPackage(getPackageName())
                .putExtra("nav_active", nav.active).putExtra("nav_package", nav.packageName)
                .putExtra("nav_label", nav.appLabel).putExtra("nav_icon", nav.icon).putExtra("nav_open", nav.open)
                .putExtra("nav_key", nav.key).putExtra("nav_posted", nav.postedAt).putExtra("nav_end", nav.end)
                .putExtra("call_active", call.active).putExtra("call_caller", call.caller).putExtra("call_state", call.state)
                .putExtra("call_label", call.appLabel).putExtra("call_started", call.startedAtMillis);
        if (nav.text != null) update.putExtra("nav_title", nav.text.title).putExtra("nav_detail", nav.text.detail).putExtra("nav_trip", nav.text.trip);
        sendBroadcast(update);
    }
}
