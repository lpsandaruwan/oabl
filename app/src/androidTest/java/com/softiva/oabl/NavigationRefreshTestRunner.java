package com.softiva.oabl;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * Exercises Home's refresh through the real notification listener in the services process.
 * A temporary call notification uses the same snapshot bridge as navigation without changing
 * the chosen navigation app or attempting to impersonate another provider's package.
 * Run on an idle device with OABL notifications and notification access enabled.
 */
public final class NavigationRefreshTestRunner extends Instrumentation {
    private static final String CHANNEL = "oabl_navigation_refresh_test";
    private static final int NOTIFICATION_ID = 2098742;
    private static final String CALLER = "OABL refresh test";
    private int assertions;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        NotificationManager manager = getTargetContext().getSystemService(NotificationManager.class);
        boolean createdChannel = false;
        boolean posted = false;
        MainActivity[] pausedHome = {null};
        int outcome = Activity.RESULT_CANCELED;
        try {
            check(manager != null && manager.areNotificationsEnabled(), "OABL notifications enabled");
            if (Build.VERSION.SDK_INT >= 33) {
                check(getTargetContext().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED, "notification permission granted");
            }
            ComponentName listener = new ComponentName(getTargetContext(), MediaAccessService.class);
            boolean access;
            if (Build.VERSION.SDK_INT >= 27) {
                access = manager.isNotificationListenerAccessGranted(listener);
            } else {
                String enabled = Settings.Secure.getString(getTargetContext().getContentResolver(),
                        "enabled_notification_listeners");
                access = enabled != null && enabled.contains(listener.flattenToString());
            }
            check(access, "notification listener access granted");
            check(!MediaAccessService.callSnapshot().active, "device has no active call");
            if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                        "OABL refresh test", NotificationManager.IMPORTANCE_LOW));
                createdChannel = true;
            }
            MainActivity home = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(getTargetContext(), CHANNEL)
                    : new Notification.Builder(getTargetContext());
            manager.notify(NOTIFICATION_ID, builder.setSmallIcon(android.R.drawable.sym_call_incoming)
                    .setContentTitle(CALLER).setContentText("Incoming call")
                    .setCategory(Notification.CATEGORY_CALL).setOngoing(true).build());
            posted = true;
            awaitCaller(true);
            check(CALLER.equals(MediaAccessService.callSnapshot().caller),
                    "real listener delivered fixture across processes");

            runOnMainSync(() -> {
                callActivityOnPause(home);
                pausedHome[0] = home;
                // Model a lost background broadcast: the foreground process has no snapshot,
                // while the actual notification remains active in Android and the listener.
                MediaAccessService.accept(new Intent(MediaAccessService.ACTION_MEDIA_CHANGED)
                        .putExtra("nav_active", false).putExtra("call_active", false));
                check(!MediaAccessService.callSnapshot().active, "Home copy is absent before resume");
            });
            runOnMainSync(() -> {
                callActivityOnResume(home);
                pausedHome[0] = null;
            });
            awaitCaller(true);
            check(CALLER.equals(MediaAccessService.callSnapshot().caller),
                    "returning Home reacquires active snapshot without a new notification");

            manager.cancel(NOTIFICATION_ID);
            posted = false;
            awaitCaller(false);
            check(!CALLER.equals(MediaAccessService.callSnapshot().caller),
                    "fixture removal clears the refreshed snapshot");
            result.putString("stream", "PASS: " + assertions
                    + " checks; real listener IPC, Home resume refresh and notification cleanup.\n");
            outcome = Activity.RESULT_OK;
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + failure + "\n");
        } finally {
            if (pausedHome[0] != null) {
                try {
                    runOnMainSync(() -> callActivityOnResume(pausedHome[0]));
                } catch (Throwable cleanupFailure) {
                    result.putString("stream", result.getString("stream", "")
                            + "FAIL: could not resume Home during cleanup: " + cleanupFailure + "\n");
                    outcome = Activity.RESULT_CANCELED;
                } finally {
                    pausedHome[0] = null;
                }
            }
            if (manager != null) {
                if (posted) manager.cancel(NOTIFICATION_ID);
                if (createdChannel && Build.VERSION.SDK_INT >= 26) manager.deleteNotificationChannel(CHANNEL);
            }
        }
        finish(outcome, result);
    }

    private void awaitCaller(boolean expected) {
        long deadline = SystemClock.elapsedRealtime() + 10_000L;
        while (SystemClock.elapsedRealtime() < deadline) {
            boolean present = CALLER.equals(MediaAccessService.callSnapshot().caller);
            if (present == expected) return;
            SystemClock.sleep(50L);
        }
        throw new AssertionError(expected ? "listener snapshot did not arrive" : "fixture snapshot did not clear");
    }

    private void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        assertions++;
    }
}
