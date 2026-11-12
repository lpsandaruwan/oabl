package com.softiva.oabllink;

import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/**
 * The link's request router (energy-companion.md §3.6), free of any socket so it is tested on the
 * JVM. It turns a parsed request into a response for {@code /v1/hello}, {@code /v1/now} and
 * {@code /v1/samples}. Every request must carry a valid signature from the paired box; an unsigned,
 * stale or replayed request gets 401 and no data. The router only reads the store; it has no path
 * that changes the car or the store, so a signature only ever authenticates a read.
 */
public final class LinkServer {
    public static final class Request {
        public String method = "GET";
        /** The full request target, path and query, e.g. {@code /v1/samples?after=12&limit=100}. */
        public String target = "/";
        public String body = "";
        public long timeMs;
        public String nonce;
        public String signature;
    }

    public static final class Response {
        public final int status;
        public final String body;
        public Response(int status, JSONObject body) { this.status = status; this.body = body.toString(); }
        public boolean ok() { return status == 200; }
    }

    private final CarProbe probe;
    private final SampleStore store;
    private final String appVersion, model, androidVersion, installId;
    private final Set<String> seenNonces = new HashSet<>();
    private final java.util.Map<String, Long> nonceTimes = new java.util.HashMap<>();
    /** Null until paired; set from the key the current 6-digit code derives. */
    private byte[] key;

    public LinkServer(CarProbe probe, SampleStore store, String appVersion, String model, String androidVersion, String installId) {
        this.probe = probe;
        this.store = store;
        this.appVersion = appVersion;
        this.model = model;
        this.androidVersion = androidVersion;
        this.installId = installId;
    }

    /** Sets the shared key for the current pairing code; null drops pairing, so nothing verifies. */
    public synchronized void setKey(byte[] key) { this.key = key; seenNonces.clear(); nonceTimes.clear(); }

    public boolean paired() { return key != null; }


    /** Verifies a request's signature and consumes its nonce, with no routing. */
    public synchronized boolean authorize(Request request, long nowMs) {
        java.util.Iterator<java.util.Map.Entry<String, Long>> entries = nonceTimes.entrySet().iterator();
        while (entries.hasNext()) {
            java.util.Map.Entry<String, Long> entry = entries.next();
            if (nowMs - entry.getValue() > Pairing.FRESH_WINDOW_MS) { seenNonces.remove(entry.getKey()); entries.remove(); }
        }
        if (nonceTimes.size() >= 4096) return false;
        boolean valid = key != null && Pairing.verify(key, request.method, request.target, request.body,
                request.timeMs, request.nonce, request.signature, nowMs, seenNonces);
        if (valid) nonceTimes.put(request.nonce, request.timeMs);
        return valid;
    }

    public Response handle(Request request, long nowMs) {
        if (!"GET".equalsIgnoreCase(request.method)) return error(405, "only GET");
        String path = pathOf(request.target);
        if (!path.startsWith("/v1/")) return error(404, "not found");
        if (!authorize(request, nowMs)) return error(401, "unauthorized");
        switch (path) {
            case "/v1/hello":
                return new Response(200, Protocol.hello(appVersion, model, androidVersion, installId, probe));
            case "/v1/now":
                return new Response(200, Protocol.now(store));
            case "/v1/speed": {
                // Read on request, so the box can show speed without waiting for the next 1 s sample.
                double kph = probe.speedKph();
                JSONObject body = new JSONObject().put("t", System.currentTimeMillis());
                if (!Double.isNaN(kph)) body.put("v", kph);
                // The speedometer's own figure, when the car provides one the probe trusts.
                double shown = probe.displaySpeedKph();
                if (!Double.isNaN(shown)) body.put("d", shown);
                return new Response(200, body);
            }
            case "/v1/samples":
                return new Response(200, Protocol.samples(store, longParam(request.target, "after", 0),
                        (int) longParam(request.target, "limit", 500)));
            default:
                return error(404, "not found");
        }
    }

    private static Response error(int status, String message) {
        return new Response(status, new JSONObject().put("error", message));
    }

    public static String pathOf(String target) {
        int q = target.indexOf('?');
        return q < 0 ? target : target.substring(0, q);
    }

    /** A non-negative query parameter, or the fallback when it is absent or not a number. */
    static long longParam(String target, String name, long fallback) {
        int q = target.indexOf('?');
        if (q < 0) return fallback;
        for (String pair : target.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            if (pair.substring(0, eq).equals(name)) {
                try {
                    long value = Long.parseLong(pair.substring(eq + 1));
                    return value < 0 ? fallback : value;
                } catch (NumberFormatException e) {
                    return fallback;
                }
            }
        }
        return fallback;
    }
}
