package com.softiva.oabl;

import android.app.Activity;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Activity lifetime checks that keep working on vendor ROMs whose framework is missing
 * {@link Activity#isDestroyed()} even though the reported API level says it should exist.
 */
final class ActivityState {
    private static volatile boolean platformDestroyedAvailable = true;
    private static final Set<Activity> destroyed = Collections.newSetFromMap(new WeakHashMap<>());

    private ActivityState() { }

    static boolean isGone(Activity activity) {
        if (activity == null || activity.isFinishing()) return true;
        if (platformDestroyedAvailable) {
            try {
                return activity.isDestroyed();
            } catch (NoSuchMethodError | AbstractMethodError brokenFramework) {
                platformDestroyedAvailable = false;
                android.util.Log.w("OABL", "Activity.isDestroyed unavailable; using lifecycle callbacks");
            }
        }
        synchronized (destroyed) {
            return destroyed.contains(activity);
        }
    }

    static void created(Activity activity) {
        synchronized (destroyed) {
            destroyed.remove(activity);
        }
    }

    static void destroyed(Activity activity) {
        synchronized (destroyed) {
            destroyed.add(activity);
        }
    }
}
