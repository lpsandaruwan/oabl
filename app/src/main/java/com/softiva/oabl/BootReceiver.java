package com.softiva.oabl;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.os.UserManager;

/** Keeps OABL in front on vendor boxes that restore a stale task after boot. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent received) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            UserManager users = (UserManager) context.getSystemService(Context.USER_SERVICE);
            if (users != null && !users.isUserUnlocked()) {
                // Wallpaper URIs and launcher preferences live in credential-encrypted
                // storage. FallbackHome covers the brief locked-boot interval; the
                // USER_UNLOCKED broadcast will schedule OABL immediately afterwards.
                return;
            }
        }
        EnergyCollectorService.ensure(context);
        Intent home = new Intent(context, MainActivity.class)
                .putExtra(MainActivity.EXTRA_RELOAD_WALLPAPER, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent launch = PendingIntent.getActivity(
                context,
                4107,
                home,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) {
            // This mirrors the working launcher shim for the AutoBox ROM. The ROM
            // restores MiniLauncher first, then permits this delayed system alarm.
            alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 6000L,
                    launch);
        }
    }
}
