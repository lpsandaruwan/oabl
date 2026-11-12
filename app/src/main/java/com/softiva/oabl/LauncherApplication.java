package com.softiva.oabl;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/** Keeps the external-app Home tab out of every OABL screen, including separate tasks. */
public final class LauncherApplication extends Application {
    private final Handler main = new Handler(Looper.getMainLooper());
    private int started;
    private final VendorRecentsProtection recentsProtection = new VendorRecentsProtection();
    private final Runnable externalApp = () -> {
        if (started == 0) {
            HomeTab.show(this);
            // No OABL screen is in front any more: this is the one moment the rail is surely wanted.
            SplitScreenService.showAppControls(this);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        // The default Home process starts before this ROM finishes its slow boot broadcasts.
        // Keep one watcher: music and system-bound services run in separate processes.
        android.os.UserManager users = getSystemService(android.os.UserManager.class);
        if (getPackageName().equals(currentProcessName()) && (users == null || users.isUserUnlocked()))
            WirelessDebugKeeper.get(this).start();
        if (getPackageName().equals(currentProcessName())) new LauncherPreferences(this).mirrorNavSide();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity activity) {
                WirelessDebugKeeper.get(LauncherApplication.this).start();
                started++;
                ServicesState.prefs(LauncherApplication.this).edit().putBoolean("ours_visible", true).apply();
                main.removeCallbacks(externalApp);
                HomeTab.ownScreenVisible(true);
                HomeTab.hide(activity);
                SplitScreenService.hideAppControls(activity);
            }
            @Override public void onActivityStopped(Activity activity) {
                started = Math.max(0, started - 1);
                ServicesState.prefs(LauncherApplication.this).edit().putBoolean("ours_visible", started > 0).apply();
                HomeTab.ownScreenVisible(started > 0);
                // Avoid a flash between activities, tasks, rotations and split-window resizes.
                if (started == 0) main.postDelayed(externalApp, 250);
            }
            @Override public void onActivityCreated(Activity a, Bundle b) { ActivityState.created(a); }
            @Override public void onActivityResumed(Activity a) { recentsProtection.update(a); }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { ActivityState.destroyed(a); }
        });
    }

    private String currentProcessName() {
        if (android.os.Build.VERSION.SDK_INT >= 28) return Application.getProcessName();
        android.app.ActivityManager manager = getSystemService(android.app.ActivityManager.class);
        if (manager != null && manager.getRunningAppProcesses() != null) {
            for (android.app.ActivityManager.RunningAppProcessInfo process : manager.getRunningAppProcesses())
                if (process.pid == android.os.Process.myPid()) return process.processName;
        }
        return "";
    }
}
