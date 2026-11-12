package com.softiva.oabl;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Tells the box whether the launcher is dark, so Maps and every other app can follow it. An ordinary app
 * can't switch Android's night mode, so the launcher leaves the answer in files/night ("1" dark, "0"
 * light) and the root helper oabl-box-tune mirrors it with `cmd uimode night`. Maps must be set to
 * "Same as device theme" once (its own setting) to follow.
 */
final class SystemNightMode {
    static final String FILE = "night";
    private static String lastWritten;

    private SystemNightMode() { }

    static String contents(boolean dark) { return dark ? "1" : "0"; }

    /** Cheap to call often; writes only when the answer changed. */
    static synchronized void publish(Context context, boolean dark) {
        String value = contents(dark);
        if (value.equals(lastWritten)) return;
        File file = new File(context.getFilesDir(), FILE);
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(value.getBytes(StandardCharsets.US_ASCII));
            lastWritten = value;
        } catch (IOException failed) {
            lastWritten = null;
        }
    }
}
