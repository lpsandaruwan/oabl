package com.softiva.oabl;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

/** Suding's Recents broadcasts task removal to Carsyso, which force-stops the whole package. */
final class VendorRecentsProtection {
    private Boolean vendorTaskKiller;

    void update(Activity activity) {
        PackageManager pm = activity.getPackageManager();
        if (vendorTaskKiller == null) {
            try {
                pm.getApplicationInfo("com.carsyso.main", 0);
                pm.getApplicationInfo("com.android.launcher3", 0);
                vendorTaskKiller = true;
            } catch (PackageManager.NameNotFoundException absent) {
                vendorTaskKiller = false;
            }
        }
        if (!vendorTaskKiller) return;

        ResolveInfo home = pm.resolveActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        boolean protectHome = home != null && home.activityInfo != null
                && activity.getPackageName().equals(home.activityInfo.packageName);
        ActivityManager manager = (ActivityManager) activity.getSystemService(Activity.ACTIVITY_SERVICE);
        if (manager == null) return;
        // Cover existing Music/tools tasks too when the user switches the default Home to OABL.
        for (ActivityManager.AppTask task : manager.getAppTasks()) {
            try {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                ComponentName root = info.baseActivity;
                if (root == null || !activity.getPackageName().equals(root.getPackageName())) continue;
                ActivityInfo declared = pm.getActivityInfo(root, 0);
                boolean excludedByManifest = (declared.flags & ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS) != 0;
                task.setExcludeFromRecents(protectHome || excludedByManifest);
            } catch (PackageManager.NameNotFoundException | IllegalArgumentException | SecurityException staleTask) {
                Log.w("OablRecents", "Could not update an OABL task", staleTask);
            }
        }
    }
}
