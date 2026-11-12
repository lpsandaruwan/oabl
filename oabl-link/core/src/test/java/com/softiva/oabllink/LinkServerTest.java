package com.softiva.oabllink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

/** The router: signing is enforced on every endpoint, and hello/now/samples return the right data. */
public class LinkServerTest {
    private static final String CODE = "428913";
    private SampleBuffer store;
    private LinkServer server;
    private long nonce;

    @Before public void setUp() {
        store = new SampleBuffer();
        store.record(new Reading(1000).set(Signal.SOC, 62).set(Signal.ODOMETER, 3851));
        store.record(new Reading(2000).set(Signal.SOC, 61).set(Signal.SPEED, 20));
        server = new LinkServer(new SimulatedCar(0), store, "0.1.0", "BOX", "10", "install-abc");
        server.setKey(Pairing.sharedKey(CODE));
    }

    /** A request signed with the given code, fresh and with a new nonce. */
    private LinkServer.Request signed(String code, String target) {
        LinkServer.Request request = new LinkServer.Request();
        request.target = target;
        request.timeMs = 5_000_000L;
        request.nonce = "n-" + (nonce++);
        request.signature = Pairing.sign(Pairing.sharedKey(code), "GET", target, "", request.timeMs, request.nonce);
        return request;
    }

    @Test public void helloNeedsAValidSignature() {
        LinkServer.Request unsigned = new LinkServer.Request();
        unsigned.target = "/v1/hello";
        assertEquals(401, server.handle(unsigned, 5_000_000L).status);
        assertEquals(401, server.handle(signed("000000", "/v1/hello"), 5_000_000L).status); // wrong code
        LinkServer.Response ok = server.handle(signed(CODE, "/v1/hello"), 5_000_000L);
        assertEquals(200, ok.status);
        assertTrue(ok.body.contains("\"probe\":\"simulated\""));
        assertTrue("hello carries the install id", ok.body.contains("\"link\":\"install-abc\""));
    }

    @Test public void beforePairingNothingVerifies() {
        LinkServer fresh = new LinkServer(new SimulatedCar(0), store, "0.1.0", "BOX", "10", "install-abc");
        assertFalse(fresh.paired());
        assertEquals(401, fresh.handle(signed(CODE, "/v1/now"), 5_000_000L).status);
    }

    @Test public void nowReturnsTheLatestPerSignal() {
        LinkServer.Response response = server.handle(signed(CODE, "/v1/now"), 5_000_000L);
        assertEquals(200, response.status);
        JSONObject values = new JSONObject(response.body).getJSONObject("values");
        assertEquals(61, values.getJSONObject("soc").getInt("v"));
        assertEquals(2000, values.getJSONObject("soc").getLong("t"));
        assertEquals(3851, values.getJSONObject("odometer").getInt("v"));
    }

    @Test public void samplesPagesBySequence() {
        JSONObject all = new JSONObject(server.handle(signed(CODE, "/v1/samples?after=0"), 5_000_000L).body);
        assertEquals(2, all.getJSONArray("samples").length());
        assertEquals(2, all.getLong("latest_seq"));
        JSONObject after1 = new JSONObject(server.handle(signed(CODE, "/v1/samples?after=1&limit=10"), 5_000_000L).body);
        assertEquals(1, after1.getJSONArray("samples").length());
        assertEquals(2, after1.getJSONArray("samples").getJSONObject(0).getLong("seq"));
    }

    @Test public void staleReplayAndUnknownPathsAreRejected() {
        LinkServer.Request stale = signed(CODE, "/v1/now");
        assertEquals(401, server.handle(stale, stale.timeMs + Pairing.FRESH_WINDOW_MS + 1).status);
        LinkServer.Request once = signed(CODE, "/v1/now");
        assertEquals(200, server.handle(once, once.timeMs).status);
        assertEquals("same nonce replayed", 401, server.handle(once, once.timeMs).status);
        assertEquals(404, server.handle(signed(CODE, "/v1/nope"), 5_000_000L).status);
        assertEquals(404, server.handle(signed(CODE, "/elsewhere"), 5_000_000L).status);
    }
    @Test public void replayCacheExpiresOnlyAfterTheOriginalSignatureIsStale() {
        LinkServer.Request first = signed(CODE, "/v1/now");
        assertTrue(server.authorize(first, first.timeMs));
        assertFalse(server.authorize(first, first.timeMs + 1000));
        long later = first.timeMs + Pairing.FRESH_WINDOW_MS + 1;
        assertFalse(server.authorize(first, later));
        first.timeMs = later;
        first.signature = Pairing.sign(Pairing.sharedKey(CODE), "GET", first.target, "", later, first.nonce);
        assertTrue(server.authorize(first, later));
    }
}
