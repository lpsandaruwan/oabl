package com.softiva.oabl;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * Applies playlists prepared off the box (scripts/playlists/build_mood_playlists.py). The file
 * goes to OABL's own folder, {@code Android/data/com.softiva.oabl/files/oabl-playlists.json}. It's
 * applied once through {@link MusicLibrary#importPlaylists}, then renamed so it isn't re-applied.
 */
final class PlaylistImport {
    static final String FILE = "oabl-playlists.json";
    private static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Off the UI thread; nothing happens when no file is waiting. */
    static void applyPending(Context context) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            File dir = app.getExternalFilesDir(null);
            File file = dir == null ? null : new File(dir, FILE);
            if (file == null || !file.isFile()) return;
            try {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream in = new java.io.FileInputStream(file)) {
                    byte[] block = new byte[65536]; int n;
                    while ((n = in.read(block)) > 0) bytes.write(block, 0, n);
                }
                String json = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                int written = MusicLibrary.get(app).importPlaylists(new org.json.JSONObject(json));
                File done = new File(dir, "oabl-playlists.applied-" + System.currentTimeMillis() + ".json");
                if (!file.renameTo(done)) file.delete();
                android.util.Log.i("OablPlaylists", "Imported " + written + " playlists");
            } catch (Exception failed) {
                android.util.Log.w("OablPlaylists", "Playlist file not applied", failed);
                file.renameTo(new File(dir, "oabl-playlists.failed-" + System.currentTimeMillis() + ".json"));
            }
        });
    }
}
