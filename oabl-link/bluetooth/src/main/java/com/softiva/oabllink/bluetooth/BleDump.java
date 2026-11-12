package com.softiva.oabllink.bluetooth;

import android.util.Base64;
import android.util.Log;

/** Debug aid for a transport fault: the encrypted record (never plaintext or keys) as logcat lines. */
final class BleDump {
    private static final int CHUNK = 3000;
    private BleDump() { }

    static void record(String side, byte[] record) {
        if (record == null || record.length < 10) return;
        long sequence = java.nio.ByteBuffer.wrap(record, 2, 8).getLong();
        String text = Base64.encodeToString(record, Base64.NO_WRAP);
        int parts = (text.length() + CHUNK - 1) / CHUNK;
        for (int i = 0; i < parts; i++) {
            Log.d("OablBleDump", side + " seq=" + sequence + " len=" + record.length + " part=" + (i + 1) + "/" + parts
                    + " " + text.substring(i * CHUNK, Math.min(text.length(), (i + 1) * CHUNK)));
        }
    }
}
