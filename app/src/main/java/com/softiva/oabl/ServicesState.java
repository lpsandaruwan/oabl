package com.softiva.oabl;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * Shared state of the background services (equalizer, media/navigation listener, split-screen
 * assistant). They run in their own ":services" process so the launcher UI is an ordinary app
 * that Android can trim and reclaim while YouTube or Maps are in front.
 */
final class ServicesState {
    private ServicesState() { }

    /** Re-read on each call, so the other process's latest write is seen. */
    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("services_state", Context.MODE_MULTI_PROCESS);
    }

    /** Registers for broadcasts sent by this app's other process only. */
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    static void register(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(receiver, filter);
    }
}
