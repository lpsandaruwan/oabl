package com.softiva.oabl;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class InstalledAppsRepository {
    private final Context context;
    private final PackageManager packageManager;
    /** Icons drawn once at the largest size the launcher shows them, reused across reloads. */
    private static final java.util.Map<String, android.graphics.drawable.Drawable> ICONS = new java.util.HashMap<>();
    private static final int ICON_DP = 72;
    /** Written by the loader thread, read by {@link #changedSinceLoad} on the UI thread. */
    private volatile int packageSequence = -1;

    InstalledAppsRepository(Context context) {
        this.context = context.getApplicationContext();
        this.packageManager = context.getPackageManager();
    }

    List<AppEntry> load() {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = packageManager.queryIntentActivities(query, 0);
        List<AppEntry> result = new ArrayList<>();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null
                    || (context.getPackageName().equals(info.activityInfo.packageName)
                    && !MusicActivity.class.getName().equals(info.activityInfo.name))) {
                continue;
            }
            ComponentName component = new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
            result.add(new AppEntry(String.valueOf(info.loadLabel(packageManager)), component, icon(info, component)));
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            android.content.pm.ChangedPackages changed = packageManager.getChangedPackages(Math.max(0, packageSequence));
            packageSequence = changed == null ? Math.max(packageSequence, 0) : changed.getSequenceNumber();
        }
        final Collator collator = Collator.getInstance();
        Collections.sort(result, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry left, AppEntry right) {
                return collator.compare(left.label, right.label);
            }
        });
        return result;
    }

    /** True when an app was installed, updated or removed since the last load. */
    boolean changedSinceLoad() {
        if (packageSequence < 0 || android.os.Build.VERSION.SDK_INT < 26) return true;
        return packageManager.getChangedPackages(packageSequence) != null;
    }

    /**
     * App icons are full adaptive drawables (several layers, often 4× the size shown). Drawing
     * each once into a small bitmap keeps memory low and makes reloads cheap.
     */
    private android.graphics.drawable.Drawable icon(ResolveInfo info, ComponentName component) {
        long updated = 0;
        try { updated = packageManager.getPackageInfo(component.getPackageName(), 0).lastUpdateTime; }
        catch (PackageManager.NameNotFoundException gone) { /* still shown below */ }
        String key = component.flattenToString() + "|" + updated;
        synchronized (ICONS) {
            android.graphics.drawable.Drawable known = ICONS.get(key);
            if (known != null) return known.getConstantState() == null ? known : known.getConstantState().newDrawable(context.getResources());
        }
        android.graphics.drawable.Drawable original = info.loadIcon(packageManager);
        int size = Math.round(ICON_DP * context.getResources().getDisplayMetrics().density);
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        original.setBounds(0, 0, size, size);
        original.draw(canvas);
        android.graphics.drawable.BitmapDrawable small = new android.graphics.drawable.BitmapDrawable(context.getResources(), bitmap);
        synchronized (ICONS) {
            // Drop the old entry of an updated app.
            ICONS.keySet().removeIf(old -> old.startsWith(component.flattenToString() + "|"));
            ICONS.put(key, small);
        }
        return small;
    }

    AppEntry find(List<AppEntry> apps, ComponentName component) {
        if (component == null) {
            return null;
        }
        for (AppEntry app : apps) {
            if (component.equals(app.component)) {
                return app;
            }
        }
        return null;
    }

    AppEntry findFirstPackage(List<AppEntry> apps, String... packageNames) {
        for (String packageName : packageNames) {
            for (AppEntry app : apps) {
                if (packageName.equals(app.component.getPackageName())) {
                    return app;
                }
            }
        }
        return null;
    }

    /** Deep actions published by installed apps; only the active HOME app may read them. */
    List<AppEntry> loadShortcuts(List<AppEntry> apps) {
        List<AppEntry> result = new ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT < 25) return result;
        LauncherApps launcher = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        if (launcher == null || !launcher.hasShortcutHostPermission()) return result;
        try {
            LauncherApps.ShortcutQuery query = new LauncherApps.ShortcutQuery().setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                            | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                            | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
            List<ShortcutInfo> shortcuts = launcher.getShortcuts(query, android.os.Process.myUserHandle());
            if (shortcuts == null) return result;
            for (ShortcutInfo shortcut : shortcuts) {
                if (shortcut == null || !shortcut.isEnabled()) continue;
                AppEntry owner = findFirstPackage(apps, shortcut.getPackage());
                if (owner == null) continue;
                CharSequence shortLabel = shortcut.getShortLabel();
                CharSequence longLabel = shortcut.getLongLabel();
                String label = shortLabel == null ? "" : shortLabel.toString().trim();
                if (label.isEmpty() && longLabel != null) label = longLabel.toString().trim();
                if (label.isEmpty()) continue;
                android.graphics.drawable.Drawable icon = launcher.getShortcutIconDrawable(shortcut, 0);
                result.add(new AppEntry(label, owner.component, icon == null ? owner.icon : icon,
                        shortcut.getId(), owner.label));
            }
            Collections.sort(result, (left, right) -> {
                int app = Collator.getInstance().compare(left.appLabel, right.appLabel);
                return app != 0 ? app : Collator.getInstance().compare(left.label, right.label);
            });
        } catch (RuntimeException unavailable) {
            android.util.Log.w("OABL", "Launcher shortcuts unavailable", unavailable);
            result.clear();
        }
        return result;
    }

    boolean launchShortcut(AppEntry entry) {
        if (entry == null || !entry.isShortcut() || android.os.Build.VERSION.SDK_INT < 25) return false;
        LauncherApps launcher = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        if (launcher == null || !launcher.hasShortcutHostPermission()) return false;
        try {
            launcher.startShortcut(entry.component.getPackageName(), entry.shortcutId,
                    null, null, android.os.Process.myUserHandle());
            return true;
        } catch (RuntimeException stale) {
            android.util.Log.w("OABL", "Shortcut no longer available: " + entry.shortcutId, stale);
            return false;
        }
    }
}
