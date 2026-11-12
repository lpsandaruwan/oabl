package com.softiva.oabl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.io.InputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * Bounded artwork decoding, never on the UI thread. Songs without embedded or folder art can
 * look it up once online (iTunes Search, no account), cached on the device; misses are remembered.
 */
final class MusicArt {
    private static final ExecutorService work = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Boolean> missing = new LruCache<>(256);
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(4*1024*1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    interface Result { void ready(Bitmap bitmap); }
    /** Frees decoded covers (Home went to the background or memory is low); they reload on demand. */
    static void trim() { cache.evictAll(); }
    static void load(Context context, MusicLibrary.Track track, Result result) {
        Context app = context.getApplicationContext();
        String key=track.uri+"|"+track.art;
        Bitmap known = cache.get(key);
        if (known != null) { main.post(() -> result.ready(known)); return; }
        if(missing.get(key)!=null){main.post(()->result.ready(null));return;}
        work.execute(() -> {
            Bitmap bitmap = null;
            try {
                if (!track.art.isEmpty()) {
                    BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                    try (InputStream in = app.getContentResolver().openInputStream(Uri.parse(track.art))) { BitmapFactory.decodeStream(in, null, bounds); }
                    bounds.inSampleSize = sample(bounds.outWidth, bounds.outHeight); bounds.inJustDecodeBounds = false;
                    try (InputStream in = app.getContentResolver().openInputStream(Uri.parse(track.art))) { bitmap = BitmapFactory.decodeStream(in, null, bounds); }
                }
                if (bitmap == null) {
                    MediaMetadataRetriever reader = new MediaMetadataRetriever();
                    try {
                        reader.setDataSource(app, Uri.parse(track.uri)); byte[] bytes = reader.getEmbeddedPicture();
                        if (bytes != null) { BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o); o.inSampleSize = sample(o.outWidth, o.outHeight); o.inJustDecodeBounds = false;
                            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o); }
                    } finally { reader.release(); }
                }
                if (bitmap == null) bitmap = online(app, track);
            } catch (Exception | OutOfMemoryError ignored) { }
            if (bitmap != null) cache.put(key, bitmap); else missing.put(key,true);
            Bitmap finished = bitmap; main.post(() -> result.ready(finished));
        });
    }
    // Multi-process: also read by the ":music" process for notification and car artwork.
    static boolean lookupEnabled(Context context) { return context.getSharedPreferences("music", Context.MODE_MULTI_PROCESS).getBoolean("online_art", true); }

    /** Cached or downloaded cover for a tagged song; null for unknown artists or when offline. */
    private static Bitmap online(Context app, MusicLibrary.Track track) {
        if (!lookupEnabled(app) || track.artist == null || track.artist.startsWith("Unknown")) return null;
        String subject = track.album != null && !track.album.isEmpty() ? track.album : track.title;
        String id = Integer.toHexString((track.artist + "|" + subject).toLowerCase(java.util.Locale.ROOT).hashCode());
        java.io.File folder = new java.io.File(app.getFilesDir(), "artwork");
        java.io.File file = new java.io.File(folder, id + ".jpg");
        if (file.exists()) return BitmapFactory.decodeFile(file.getPath());
        android.content.SharedPreferences misses = app.getSharedPreferences("artwork_misses", 0);
        // Try a missing cover again after a week; the lookup may have failed offline.
        if (System.currentTimeMillis() - misses.getLong(id, 0) < 7L * 86_400_000L) return null;
        android.net.ConnectivityManager network = app.getSystemService(android.net.ConnectivityManager.class);
        if (network == null || network.getActiveNetwork() == null) return null;
        try {
            String term = java.net.URLEncoder.encode(track.artist + " " + subject, "UTF-8");
            String entity = track.album != null && !track.album.isEmpty() ? "album" : "song";
            org.json.JSONObject answer = new org.json.JSONObject(read("https://itunes.apple.com/search?media=music&limit=1&entity=" + entity + "&term=" + term));
            org.json.JSONArray results = answer.optJSONArray("results");
            String url = results == null || results.length() == 0 ? null : results.getJSONObject(0).optString("artworkUrl100", null);
            if (url == null) { misses.edit().putLong(id, System.currentTimeMillis()).apply(); return null; }
            byte[] bytes = bytes(url.replace("100x100bb", "600x600bb"));
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) return null;
            if (folder.isDirectory() || folder.mkdirs()) {
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out); }
            }
            return bitmap;
        } catch (Exception failed) {
            return null;
        }
    }
    private static String read(String url) throws java.io.IOException { return new String(bytes(url), java.nio.charset.StandardCharsets.UTF_8); }
    private static byte[] bytes(String url) throws java.io.IOException {
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(8000);
        try (InputStream in = connection.getInputStream(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384]; int n; long total = 0;
            while ((n = in.read(buffer)) > 0) { out.write(buffer, 0, n); if ((total += n) > 4_000_000) throw new java.io.IOException("too large"); }
            return out.toByteArray();
        } finally { connection.disconnect(); }
    }
    private static int sample(int width, int height) { int sample = 1; while (Math.max(width, height) / sample > 512) sample *= 2; return sample; }
}
