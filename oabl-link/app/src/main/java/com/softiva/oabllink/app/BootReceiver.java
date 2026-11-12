package com.softiva.oabllink.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Starts the link service when the head unit boots, so the car's data keeps flowing to the box after
 * a restart without anyone reopening the app. Because the app reads the car over Binder (not a shell),
 * it survives a reboot on its own; this only re-launches the foreground service.
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        Intent service = new Intent(context, LinkService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) context.startForegroundService(service);
        else context.startService(service);
    }
}
