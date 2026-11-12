package com.softiva.oabl;

import android.app.Activity;
import android.content.Intent;
import android.location.Address;
import android.location.Geocoder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Share a place from Google Maps to OABL to save it as Home or Work, at Google's exact point.
 * Google Maps shares a short link (maps.app.goo.gl/…); it is followed to the full place address,
 * whose coordinates are read. Without coordinates, the shared name is looked up instead.
 */
public final class PlaceShareActivity extends Activity {
    private static final Pattern PLACE = Pattern.compile("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)");
    private static final Pattern AT = Pattern.compile("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)");
    private static final Pattern QUERY = Pattern.compile("[?&](?:q|ll|query|center|destination|daddr)=(-?\\d+\\.\\d+)(?:,|%2C)(-?\\d+\\.\\d+)");
    private static final Pattern GEO = Pattern.compile("geo:(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)");
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null && intent.getData() != null) text = intent.getData().toString();
        if (text == null) { finish(); return; }
        String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        String link = null, name = subject;
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            Matcher url = Pattern.compile("(https?://\\S+|geo:\\S+)").matcher(trimmed);
            if (url.find()) { if (link == null) link = url.group(1); }
            else if (name == null) name = trimmed;
        }
        final String shared = link, title = name, all = text;
        Toast.makeText(this, "Finding the place…", Toast.LENGTH_SHORT).show();
        Executors.newSingleThreadExecutor().execute(() -> {
            double[] point = coordinates(all);
            if (point == null && shared != null) point = resolve(shared);
            if (point == null && title != null) point = lookup(title);
            final double[] found = point;
            main.post(() -> ask(found, title));
        });
    }

    private void ask(double[] point, String name) {
        if (isFinishing()) return;
        if (point == null) {
            Toast.makeText(this, "Couldn't read a location from that share", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        LauncherPreferences preferences = new LauncherPreferences(this);
        LauncherTheme.Palette palette = LauncherTheme.resolve(this, preferences);
        String label = name == null ? String.format(Locale.US, "%.5f, %.5f", point[0], point[1]) : name;
        android.app.AlertDialog dialog = new ThemedDialog(this, palette).setTitle("Save " + label + " as")
                .setItems(new String[]{"Home", "Work"}, (d, which) -> {
                    preferences.setNavigationPoint(which == 0, point[0], point[1], label);
                    Toast.makeText(this, (which == 0 ? "Home" : "Work") + " saved · " + label, Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton("Cancel", (d, w) -> finish()).show();
        dialog.setOnCancelListener(d -> finish());
    }

    /** Coordinates written in the text or URL itself; the place's own point wins over the map view's. */
    static double[] coordinates(String text) {
        for (Pattern pattern : new Pattern[]{PLACE, QUERY, GEO, AT}) {
            Matcher m = pattern.matcher(text);
            if (m.find()) {
                try {
                    double lat = Double.parseDouble(m.group(1)), lon = Double.parseDouble(m.group(2));
                    if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180 && (lat != 0 || lon != 0)) return new double[]{lat, lon};
                } catch (NumberFormatException ignored) { }
            }
        }
        return null;
    }

    /** Follows the short link's redirects (up to 6) until an address carries coordinates. */
    private static double[] resolve(String link) {
        String current = link;
        for (int hop = 0; hop < 6 && current != null; hop++) {
            double[] point = coordinates(current);
            if (point != null) return point;
            if (!current.startsWith("http")) return null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(current).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) OABL");
                int code = connection.getResponseCode();
                String next = connection.getHeaderField("Location");
                if (code >= 300 && code < 400 && next != null) {
                    current = new URL(new URL(current), next).toString();
                    continue;
                }
                return null;
            } catch (Exception offline) {
                return null;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return current == null ? null : coordinates(current);
    }

    private double[] lookup(String name) {
        try {
            if (!Geocoder.isPresent()) return null;
            List<Address> found = new Geocoder(this, Locale.getDefault()).getFromLocationName(name, 1);
            if (found == null || found.isEmpty()) return null;
            return new double[]{found.get(0).getLatitude(), found.get(0).getLongitude()};
        } catch (Exception offline) {
            return null;
        }
    }
}
