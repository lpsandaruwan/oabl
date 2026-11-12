package com.softiva.oabl;

import android.content.Context;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Speed limit of the road the car is on, from OpenStreetMap (Overpass API). A posted maxspeed tag
 * wins; otherwise Sri Lanka's limits by road grade apply: expressways (E) 100, A roads 70,
 * B roads 60, other roads 50 km/h. On expressways in rain it shows 60, as the road authority advises.
 * Lookups are rate-limited and only made while moving; nothing else is sent.
 */
final class RoadSpeedLimit {
    interface Listener { void changed(); }

    private static RoadSpeedLimit instance;
    /** Public Overpass servers; the one that answered last is tried first. */
    private static final String[] SERVERS = {"https://overpass.private.coffee/api/interpreter",
            "https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter"};
    private int preferred;
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Location askedAt;
    private long askedTime, foundTime;
    private boolean busy;
    private int limit;
    private boolean expressway;
    private boolean estimated = true;
    private String road;
    /** Where {@code road} was last confirmed, and when; so a name from the previous street is not shown here. */
    private Location roadPoint, lastLocation;
    private int failures;
    private long retryAt;
    private final java.util.concurrent.ScheduledExecutorService hedge = java.util.concurrent.Executors.newScheduledThreadPool(3);
    Listener listener;

    static synchronized RoadSpeedLimit get() {
        if (instance == null) instance = new RoadSpeedLimit();
        return instance;
    }

    /** Current limit in km/h, or 0 when unknown, too old to trust, or confirmed too far back along the drive. */
    int limit() {
        long age = SystemClock.elapsedRealtime() - foundTime;
        double meters = roadPoint == null || lastLocation == null ? 0 : lastLocation.distanceTo(roadPoint);
        return RoadLookup.limitStillHolds(age, meters) ? limit : 0;
    }
    boolean expressway() { return expressway && limit() > 0; }
    boolean estimated() { return estimated; }
    String road() { return road; }

    /** The mapped name (or road number) of the road under {@code here}, if it was confirmed within {@code meters}. */
    String roadNear(Location here, float meters) {
        if (road == null || roadPoint == null || here == null) return null;
        if (SystemClock.elapsedRealtime() - foundTime > RoadLookup.LIMIT_FRESH_MS) return null;
        return here.distanceTo(roadPoint) <= meters ? road : null;
    }

    /** Roads already looked up, per ~55 m square, so regular routes need no network at all. */
    private static String cell(double lat, double lon) { return RoadLookup.cell(lat, lon); }

    private void apply(String saved, Location where) {
        boolean wasHidden = limit() == 0;
        roadPoint = where == null ? null : new Location(where);
        String[] parts = saved.split("\\|", -1);
        int nextLimit = Integer.parseInt(parts[0]);
        boolean nextExpressway = "1".equals(parts[1]);
        String nextRoad = parts.length > 2 && !parts[2].isEmpty() ? parts[2] : null;
        // Old cache entries have no provenance: never present them as mapped limits.
        boolean nextEstimated = parts.length < 4 || !"mapped".equals(parts[3]);
        boolean changed = nextLimit != limit || nextExpressway != expressway || nextEstimated != estimated
                || !java.util.Objects.equals(nextRoad, road)
                || wasHidden;
        limit = nextLimit; expressway = nextExpressway; road = nextRoad; estimated = nextEstimated;
        foundTime = SystemClock.elapsedRealtime();
        // Every fix on a known road lands here. Redrawing the whole dashboard each second re-encoded
        // the car screen's video over the USB link that also carries the audio, so draw only a change.
        if (changed && listener != null) listener.changed();
    }

    /** False when the cached road crosses the car's heading: it is another road through the same square. */
    private static boolean suits(String saved, Location location) {
        int direction = RoadLookup.savedDirection(saved);
        boolean heading = location.hasBearing() && location.hasSpeed() && location.getSpeed() > 3;
        return direction < 0 || !heading || RoadLookup.headingFits(direction, location.getBearing());
    }

    /**
     * Feed GPS positions. The on-device cache answers first. A square that is not cached is looked up at
     * once; otherwise, while moving, the spot about six seconds ahead is looked up, so the name and limit
     * are already known when the car gets there. One answer covers the road for several hundred metres,
     * which keeps this light for the free servers: no more than one lookup every 3 s, one at a time.
     */
    void position(Context context, Location location, boolean moving) {
        if (location == null || !location.hasAccuracy() || location.getAccuracy() > 40) return;
        lastLocation = new Location(location);
        android.content.SharedPreferences cache = context.getSharedPreferences("road_limits", 0);
        boolean haveHere = false;
        String saved = cache.getString(cell(location.getLatitude(), location.getLongitude()), null);
        if (saved != null && suits(saved, location)) {
            try {
                apply(saved, location);
                // An older entry has no direction to check, so while moving it is replaced as the car passes.
                haveHere = !moving || RoadLookup.savedDirection(saved) >= 0;
            } catch (RuntimeException corrupt) { /* look it up again */ }
        }
        if (busy) return;
        long now = SystemClock.elapsedRealtime();
        if (now < retryAt || now - askedTime < RoadLookup.MIN_GAP_MS) return;
        double lat = location.getLatitude(), lon = location.getLongitude();
        float bearing = location.hasBearing() && location.hasSpeed() && location.getSpeed() > 3 ? location.getBearing() : Float.NaN;
        if (haveHere) {
            if (!moving || Float.isNaN(bearing)) return;
            double[] ahead = RoadLookup.projectAhead(lat, lon, bearing, RoadLookup.lookAheadMeters(location.getSpeed()));
            if (cache.contains(cell(ahead[0], ahead[1]))) return;
            lat = ahead[0]; lon = ahead[1];
        }
        android.net.ConnectivityManager network = context.getSystemService(android.net.ConnectivityManager.class);
        if (network == null || network.getActiveNetwork() == null) return;
        busy = true;
        askedAt = new Location(location);
        askedTime = now;
        final double askLat = lat, askLon = lon;
        final float askBearing = bearing;
        work.execute(() -> {
            Result result = null;
            boolean failed = false;
            try { result = lookupHedged(askLat, askLon, askBearing); }
            catch (Exception allFailed) { failed = true; }
            Result found = result;
            boolean failure = failed;
            main.post(() -> {
                busy = false;
                long done = SystemClock.elapsedRealtime();
                if (failure) { failures++; retryAt = done + RoadLookup.retryDelayMs(failures); return; }
                failures = 0;
                if (found == null) { retryAt = done + 10_000; return; }
                android.content.SharedPreferences.Editor edit = cache.edit();
                if (cache.getAll().size() > 15_000) edit.clear();
                String value = encode(found);
                // The same road, with the same name and limit, for a few hundred metres either way.
                java.util.Map<String, Integer> along = RoadLookup.directionsAlong(found.shape, askLat, askLon, 250);
                String asked = cell(askLat, askLon);
                edit.putString(asked, value + (along.containsKey(asked) ? "|" + along.get(asked) : ""));
                for (java.util.Map.Entry<String, Integer> cell : along.entrySet()) edit.putString(cell.getKey(), value + "|" + cell.getValue());
                edit.commit();
                Location here = lastLocation;
                String hereCell = here == null ? null : cell(here.getLatitude(), here.getLongitude());
                String current = here == null ? null : cache.getString(hereCell, null);
                // The answer for this very square is the newest word on it, whichever way the car points.
                if (current != null && (hereCell.equals(asked) || suits(current, here))) {
                    try { apply(current, here); } catch (RuntimeException corrupt) { /* next fix */ }
                }
            });
        });
    }

    /**
     * Asks the public servers with a head start for the one that answered last: the next is tried after
     * 2.5 s and the third after 5 s, in parallel, and the first answer wins. A dead server no longer costs
     * its whole timeout before the next is asked.
     */
    private Result lookupHedged(double lat, double lon, float bearing) throws Exception {
        java.util.concurrent.CompletableFuture<Result> done = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.atomic.AtomicInteger finished = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < SERVERS.length; i++) {
            final int server = (preferred + i) % SERVERS.length;
            hedge.schedule(() -> {
                if (done.isDone()) return;
                try {
                    Result answer = lookup(SERVERS[server], lat, lon, bearing);
                    preferred = server;
                    done.complete(answer);
                } catch (Exception failed) {
                    if (finished.incrementAndGet() == SERVERS.length) done.completeExceptionally(failed);
                }
            }, i * 2500L, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        return done.get(14, java.util.concurrent.TimeUnit.SECONDS);
    }

    static final class Result {
        int limit;
        boolean expressway;
        boolean estimated = true;
        String road;
        /** The best way's shape as {lat, lon} pairs, to fill the cache along it; not stored. */
        double[][] shape;
    }

    static String encode(Result result) {
        return result.limit + "|" + (result.expressway ? 1 : 0) + "|"
                + (result.road == null ? "" : result.road.replace("|", " "))
                + "|" + (result.estimated ? "estimated" : "mapped");
    }

    private static Result lookup(String server, double lat, double lon, float bearing) throws Exception {
        String query = String.format(Locale.US, "[out:json][timeout:8];way(around:25,%.6f,%.6f)[highway~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$\"];out tags geom;", lat, lon);
        HttpURLConnection connection = (HttpURLConnection) new URL(server + "?data=" + URLEncoder.encode(query, "UTF-8")).openConnection();
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(7000);
        connection.setRequestProperty("User-Agent", "OABL-car-launcher/1.0");
        try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) { out.write(buffer, 0, n); if (out.size() > 2_000_000) break; }
            JSONArray ways = new JSONObject(out.toString("UTF-8")).optJSONArray("elements");
            if (ways == null || ways.length() == 0) return null;
            JSONObject best = null;
            double bestScore = Double.MAX_VALUE;
            for (int i = 0; i < ways.length(); i++) {
                JSONObject way = ways.getJSONObject(i);
                double score = score(way, lat, lon, bearing);
                if (score < bestScore) { bestScore = score; best = way; }
            }
            if (best == null) return null;
            Result result = limitFor(best.optJSONObject("tags"));
            if (result != null) result.shape = shapeOf(best);
            return result;
        } finally {
            connection.disconnect();
        }
    }

    private static double[][] shapeOf(JSONObject way) {
        JSONArray points = way.optJSONArray("geometry");
        if (points == null) return new double[0][];
        double[][] shape = new double[points.length()][];
        for (int i = 0; i < shape.length; i++) {
            JSONObject point = points.optJSONObject(i);
            shape[i] = point == null ? new double[]{0, 0} : new double[]{point.optDouble("lat"), point.optDouble("lon")};
        }
        return shape;
    }

    /** Distance to the road in metres, with a penalty when it runs across the car's heading. */
    private static double score(JSONObject way, double lat, double lon, float bearing) throws Exception {
        JSONArray points = way.optJSONArray("geometry");
        if (points == null || points.length() < 2) return Double.MAX_VALUE;
        double cos = Math.cos(Math.toRadians(lat)), best = Double.MAX_VALUE, bestAngle = 0;
        for (int i = 1; i < points.length(); i++) {
            JSONObject a = points.getJSONObject(i - 1), b = points.getJSONObject(i);
            double ax = (a.getDouble("lon") - lon) * cos * 111_320, ay = (a.getDouble("lat") - lat) * 110_540;
            double bx = (b.getDouble("lon") - lon) * cos * 111_320, by = (b.getDouble("lat") - lat) * 110_540;
            double dx = bx - ax, dy = by - ay, length = dx * dx + dy * dy;
            double t = length == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / length));
            double px = ax + t * dx, py = ay + t * dy, distance = Math.sqrt(px * px + py * py);
            if (distance < best) { best = distance; bestAngle = Math.toDegrees(Math.atan2(dx, dy)); }
        }
        if (!Float.isNaN(bearing)) {
            double diff = Math.abs(((bearing - bestAngle) % 180 + 180) % 180);
            diff = Math.min(diff, 180 - diff);
            if (diff > 35) best += 30;
        }
        return best;
    }

    static Result limitFor(JSONObject tags) {
        if (tags == null) return null;
        Result result = new Result();
        String highway = tags.optString("highway", "");
        String ref = tags.optString("ref", "").trim().toUpperCase(Locale.ROOT);
        result.road = tags.optString("name", ref.isEmpty() ? null : ref);
        result.expressway = highway.startsWith("motorway") || ref.startsWith("E");
        int posted = parse(tags.optString("maxspeed", ""));
        if (posted > 0) { result.limit = posted; result.estimated = false; return result; }
        if (highway.endsWith("_link")) highway = highway.substring(0, highway.length() - 5);
        if (result.expressway) result.limit = 100;
        else if (ref.startsWith("A")) result.limit = 70;
        else if (ref.startsWith("B")) result.limit = 60;
        else if (highway.equals("trunk") || highway.equals("primary")) result.limit = 70;
        else if (highway.equals("secondary")) result.limit = 60;
        else result.limit = 50;
        return result;
    }

    /** "70", "70 km/h", "45 mph"; anything else (e.g. "signals", "none") counts as not posted. */
    private static int parse(String raw) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{2,3})\\s*(mph)?").matcher(raw.toLowerCase(Locale.ROOT));
        if (!m.find()) return 0;
        int value = Integer.parseInt(m.group(1));
        return m.group(2) != null ? Math.round(value * 1.609f) : value;
    }
}
