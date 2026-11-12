package com.softiva.oabl;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small cached dashboard bindings; no example weather, schedule or vehicle values. */
final class DashboardData {
    private static DashboardData instance;
    private final Context context;
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    volatile Location fix;
    volatile String road;
    volatile int satellitesUsed;
    volatile int satellitesSeen;
    volatile String weather = "Weather · tap to set up";
    /** Parsed forecast for the weather card; null until the first successful fetch. */
    volatile Forecast forecast;

    static final class Forecast {
        double temperature, high, low;
        int code;
        boolean day;
        long fetchedAt;
        final List<Hour> hours = new java.util.ArrayList<>();
        /** It is raining at the car now (current code or measured precipitation). */
        boolean raining;
        /** Start of the first 15-minute slot with rain in the next three hours (epoch ms), or 0. */
        long rainAt;
        /** Hour label of the first likely-rain hour when no 15-minute data exists, or null. */
        String rainHour;

        /** "Rain in ~20 min", "Rain likely around 3 pm", or null when it stays dry. */
        String rainAlert(long now) {
            if (raining) return null;
            if (rainAt > 0) {
                long minutes = (rainAt - now) / 60_000;
                if (minutes <= 5) return minutes < -30 ? null : "Rain starting";
                if (minutes <= 180) return "Rain in ~" + (minutes < 60 ? minutes + " min" : (minutes / 60) + " h " + (minutes % 60 > 0 ? (minutes % 60) + " min" : ""));
            }
            return rainHour == null ? null : "Rain likely around " + rainHour;
        }
    }

    static boolean rainCode(int code) {
        return (code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95;
    }

    static final class Hour {
        String label;
        double temperature;
        int code, rain;
        boolean day;
    }
    private long geoQuery;
    private long weatherQuery;
    private Location roadFix;
    private boolean geocoding;
    private final RoadLabelHold hold = new RoadLabelHold();
    private volatile boolean roadStale;
    private boolean roadIsStreet;
    private boolean fetchingWeather;
    private final android.content.SharedPreferences cache;

    static synchronized DashboardData get(Context context) {
        if (instance == null) instance = new DashboardData(context);
        return instance;
    }
    private DashboardData(Context context) {
        this.context = context.getApplicationContext();
        cache = this.context.getSharedPreferences("weather_cache", 0);
    }
    void position(Location position, boolean reliable) {
        if (!reliable || !position.hasAccuracy() || position.getAccuracy() > 50) return;
        fix = new Location(position);
        SunTimes.remember(context, position.getLatitude(), position.getLongitude());
        long now = SystemClock.elapsedRealtime();
        // The mapped road name (from the speed-limit lookup) wins; the geocoder only fills in when there is none.
        boolean mapped = RoadSpeedLimit.get().roadNear(position, 120) != null;
        // Ask again after 6 s while driving along; after 2 s when there is no name for here at all.
        long wait = roadFix == null || roadFix.distanceTo(position) > 500 ? 2_000 : 6_000;
        if (!mapped && !geocoding && now-geoQuery >= wait && Geocoder.isPresent()
                && (roadFix == null || roadFix.distanceTo(position) > 100)) {
            geocoding = true; geoQuery = now;
            Location request = new Location(position);
            worker.execute(() -> {
                String street = null, area = null;
                try {
                    List<Address> addresses = new Geocoder(context, Locale.getDefault())
                            .getFromLocation(request.getLatitude(), request.getLongitude(), 1);
                    if (addresses != null && !addresses.isEmpty()) {
                        Address address = addresses.get(0);
                        // A street is the answer; the area name only stands in when the address has no street.
                        street = firstNonEmpty(address.getThoroughfare());
                        // Google answers "Unnamed Road" for a road with no name; that is not a name.
                        if (street != null && street.trim().toLowerCase(Locale.ROOT).startsWith("unnamed")) street = null;
                        area = firstNonEmpty(address.getSubLocality(), address.getLocality(), address.getSubAdminArea(), address.getAdminArea());
                    }
                } catch (Exception ignored) { }
                final String foundStreet = street, foundArea = area;
                // Only a successful lookup counts; a failed one (e.g. no network yet after boot)
                // is retried after the 6 s throttle instead of waiting for the car to move.
                main.post(() -> {
                    if (foundStreet != null) { road = foundStreet; roadIsStreet = true; roadFix = request; }
                    // A passing address with no street must not replace the street the car is still on.
                    else if (foundArea != null && !(roadIsStreet && roadFix != null && roadFix.distanceTo(request) < 250)) {
                        road = foundArea; roadIsStreet = false; roadFix = request;
                    }
                    geocoding = false;
                });
            });
        }
        refreshWeather();
    }
    boolean freshFix() {
        Location position = fix;
        long age = position == null ? -1 : SystemClock.elapsedRealtimeNanos()-position.getElapsedRealtimeNanos();
        return age >= 0 && age < 10_000_000_000L;
    }
    String locationLabel() {
        if (!freshFix()) return "Waiting for GPS";
        Location position = fix;
        String heading = position.hasBearing() ? compassName(position.getBearing()) : "";
        String label = road != null && roadFix != null && roadFix.distanceTo(position) < 500
                ? road : String.format(Locale.US, "%.4f, %.4f", position.getLatitude(), position.getLongitude());
        return label + (heading.isEmpty() ? "" : "  ·  " + heading);
    }
    /** Road (or coordinates) only; the Home heading strip shows direction separately. */
    String roadLabel() {
        if (!freshFix()) return "Waiting for GPS";
        Location position = fix;
        long now = SystemClock.elapsedRealtime();
        double lat = position.getLatitude(), lon = position.getLongitude();
        // 1. The road OpenStreetMap says the car is on, and the same name while the car is still within
        // 150 m of where it was confirmed (the next lookup is on its way). 2. The geocoder's street or
        // area. 3. The last mapped name, dimmed, until a new one arrives. Never a bare "Finding…" after
        // the first name. Coordinates only when the box has no geocoder at all.
        String mapped = RoadSpeedLimit.get().roadNear(position, 120);
        if (mapped != null) { hold.remember(mapped, lat, lon, now); roadStale = false; return mapped; }
        String held = hold.current(lat, lon, now);
        if (held != null && !hold.stale(lat, lon)) { roadStale = false; return held; }
        if (road != null && roadFix != null && roadFix.distanceTo(position) < 500) {
            roadStale = roadFix.distanceTo(position) > RoadLabelHold.STALE_METERS; return road;
        }
        if (held != null) { roadStale = true; return held; }
        roadStale = false;
        return Geocoder.isPresent() ? "Finding street name…"
                : String.format(Locale.US, "%.4f, %.4f", position.getLatitude(), position.getLongitude());
    }
    /** True while the name on screen was learned a little way back, so it can be drawn quieter. */
    boolean roadLabelStale() { return roadStale; }

    private static String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value;
        return null;
    }
    /** The direction in full words, for anywhere the driver reads it. */
    static String compassName(float bearing) {
        String[] names = {"North", "North East", "East", "South East", "South", "South West", "West", "North West"};
        return names[(Math.round(((bearing % 360 + 360) % 360) / 45f)) % 8];
    }
    void refreshWeather() {
        if (!new LauncherPreferences(context).weatherEnabled()) {
            weather = "Weather · tap to set up"; return;
        }
        Location position = fix;
        if (position == null) {
            // Until the first fix, show the last forecast if it is reasonably fresh.
            long age = System.currentTimeMillis() - cache.getLong("time", 0);
            if (forecast == null && age >= 0 && age < 3 * 60 * 60 * 1000L) forecast = parse(cache.getString("json", null), cache.getLong("time", 0));
            weather = forecast != null ? cache.getString("text", "Weather") : "Weather · waiting for GPS";
            return;
        }
        // Only the approximate 0.1 degree area is sent to the weather provider.
        double lat = Math.round(position.getLatitude()*10) / 10d;
        double lon = Math.round(position.getLongitude()*10) / 10d;
        String area = lat + "," + lon;
        long age = System.currentTimeMillis()-cache.getLong("time", 0);
        boolean cached = area.equals(cache.getString("area", "")) && age >= 0 && age < 90*60*1000L;
        if (cached) {
            weather = cache.getString("text", "Weather unavailable") + (age > 45*60*1000L ? " · cached" : "");
            if (forecast == null) forecast = parse(cache.getString("json", null), cache.getLong("time", 0));
        } else weather = "Weather unavailable";
        long now = SystemClock.elapsedRealtime();
        if (fetchingWeather || (weatherQuery != 0 && now-weatherQuery < 30*60*1000L)) return;
        fetchingWeather = true; weatherQuery = now;
        worker.execute(() -> {
            HttpURLConnection connection = null;
            String result = null;
            try {
                connection = (HttpURLConnection) new URL("https://api.open-meteo.com/v1/forecast?latitude="
                        +lat+"&longitude="+lon+"&current=temperature_2m,weather_code,is_day,precipitation"
                        +"&minutely_15=precipitation&forecast_minutely_15=12"
                        +"&hourly=temperature_2m,weather_code,precipitation_probability,is_day"
                        +"&daily=temperature_2m_max,temperature_2m_min&forecast_days=2&timezone=auto").openConnection();
                connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
                if (connection.getResponseCode() != 200) throw new java.io.IOException("Weather unavailable");
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null && body.length() < 65536) body.append(line);
                }
                JSONObject current = new JSONObject(body.toString()).getJSONObject("current");
                double temperature = current.getDouble("temperature_2m");
                if (!Double.isFinite(temperature) || temperature < -80 || temperature > 70) throw new IllegalArgumentException();
                result = String.format(Locale.getDefault(), "%.0f°C · %s", temperature, weatherLabel(current.getInt("weather_code")));
                cache.edit().putString("area", area).putString("text", result).putString("json", body.toString())
                        .putLong("time", System.currentTimeMillis()).apply();
                Forecast parsed = parse(body.toString(), System.currentTimeMillis());
                if (parsed != null) forecast = parsed;
            } catch (Exception ignored) { }
            finally { if (connection != null) connection.disconnect(); }
            final String update = result;
            main.post(() -> {
                fetchingWeather = false;
                if (!new LauncherPreferences(context).weatherEnabled()) weather = "Weather · tap to set up";
                else if (update != null) weather = update;
                else if (!cached) weather = "Weather unavailable";
            });
        });
    }
    /** Current conditions, today's range and the next six hours, in the area's own time zone. */
    private static Forecast parse(String json, long fetchedAt) {
        if (json == null) return null;
        try {
            JSONObject root = new JSONObject(json);
            Forecast forecast = new Forecast();
            forecast.fetchedAt = fetchedAt;
            JSONObject current = root.getJSONObject("current");
            forecast.temperature = current.getDouble("temperature_2m");
            forecast.code = current.getInt("weather_code");
            forecast.day = current.optInt("is_day", 1) == 1;
            JSONObject daily = root.getJSONObject("daily");
            forecast.high = daily.getJSONArray("temperature_2m_max").getDouble(0);
            forecast.low = daily.getJSONArray("temperature_2m_min").getDouble(0);
            JSONObject hourly = root.getJSONObject("hourly");
            org.json.JSONArray times = hourly.getJSONArray("time");
            String nowHour = current.getString("time").substring(0, 13);
            int start = 0;
            for (int i = 0; i < times.length(); i++) if (times.getString(i).startsWith(nowHour)) { start = i + 1; break; }
            for (int i = start; i < times.length() && forecast.hours.size() < 6; i++) {
                Hour hour = new Hour();
                int clock = Integer.parseInt(times.getString(i).substring(11, 13));
                hour.label = (clock % 12 == 0 ? 12 : clock % 12) + (clock < 12 ? " am" : " pm");
                hour.temperature = hourly.getJSONArray("temperature_2m").getDouble(i);
                hour.code = hourly.getJSONArray("weather_code").getInt(i);
                hour.rain = hourly.getJSONArray("precipitation_probability").optInt(i, 0);
                hour.day = hourly.getJSONArray("is_day").optInt(i, 1) == 1;
                forecast.hours.add(hour);
                if (forecast.rainHour == null && forecast.hours.size() <= 3 && (hour.rain >= 60 || rainCode(hour.code)))
                    forecast.rainHour = hour.label;
            }
            forecast.raining = rainCode(forecast.code) || current.optDouble("precipitation", 0) >= 0.1;
            JSONObject quarter = root.optJSONObject("minutely_15");
            if (quarter != null) {
                // Times are local to the area; the offset turns them back into real instants.
                long offset = root.optLong("utc_offset_seconds", 0) * 1000L;
                org.json.JSONArray slots = quarter.getJSONArray("time");
                org.json.JSONArray amounts = quarter.getJSONArray("precipitation");
                java.text.SimpleDateFormat local = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
                local.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                for (int i = 0; i < slots.length(); i++) {
                    if (amounts.optDouble(i, 0) < 0.1) continue;
                    long at = local.parse(slots.getString(i)).getTime() - offset;
                    if (at + 15 * 60_000L >= fetchedAt) { forecast.rainAt = at; break; }
                }
            }
            return forecast;
        } catch (Exception malformed) {
            return null;
        }
    }

    static String weatherLabel(int code) {
        if (code == 0) return "Clear sky";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "Rain";
        if (code >= 71 && code <= 86) return "Snow";
        if (code >= 95) return "Thunderstorm";
        return "Weather";
    }
}
