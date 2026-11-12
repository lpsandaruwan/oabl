package com.softiva.oabllink;

import org.json.JSONObject;

/** Reuses the signed read-only router, with catch-up pages bounded for BLE. */
public final class BleLinkRouter {
    private final LinkServer server;
    public BleLinkRouter(LinkServer server) { this.server = server; }
    public String handle(String request) {
        JSONObject json = new JSONObject(request);
        String id = json.getString("id"), target = json.getString("target");
        if (id.length() > 64 || target.length() > 256) throw new IllegalArgumentException("request length");
        LinkServer.Request r = new LinkServer.Request();
        r.target = target; r.timeMs = json.getLong("time"); r.nonce = json.getString("nonce"); r.signature = json.getString("signature");
        if (r.nonce.length() > 128 || r.signature.length() != 64) throw new IllegalArgumentException("signature length");
        if (!target.equals("/v1/hello") && !target.equals("/v1/now") && !target.equals("/v1/speed")
                && !target.matches("/v1/samples\\?after=[0-9]{1,18}&limit=([1-9]|1[0-9]|2[0-5])"))
            return new JSONObject().put("id", id).put("status", 400).put("body", new JSONObject().put("error", "unsupported BLE read")).toString();
        LinkServer.Response result = server.handle(r, System.currentTimeMillis());
        return new JSONObject().put("id", id).put("status", result.status).put("body", new JSONObject(result.body)).toString();
    }
}
