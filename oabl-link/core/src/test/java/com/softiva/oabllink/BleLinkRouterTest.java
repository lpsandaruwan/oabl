package com.softiva.oabllink;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class BleLinkRouterTest {
    @Test public void routesSignedReadsAndRejectsReplayWrongCodeAndOversizedPages() {
        SampleBuffer buffer = new SampleBuffer();
        buffer.record(new Reading(System.currentTimeMillis()).set(Signal.SOC, 62));
        LinkServer server = new LinkServer(new SimulatedCar(0), buffer, "0.1.2", "test", "test", "test-link");
        server.setKey(Pairing.sharedKey("123456")); BleLinkRouter router = new BleLinkRouter(server);
        JSONObject request = request("123456", "/v1/now", "nonce-1");
        JSONObject response = new JSONObject(router.handle(request.toString()));
        assertEquals("request", response.getString("id")); assertEquals(200, response.getInt("status"));
        assertEquals(62, response.getJSONObject("body").getJSONObject("values").getJSONObject("soc").getInt("v"));
        assertEquals(401, new JSONObject(router.handle(request.toString())).getInt("status"));
        assertEquals(401, new JSONObject(router.handle(request("000000", "/v1/now", "bad-code").toString())).getInt("status"));
        assertEquals(400, new JSONObject(router.handle(request("123456", "/v1/samples?after=0&limit=500", "large").toString())).getInt("status"));
        assertEquals(400, new JSONObject(router.handle(request("123456", "/v1/stream", "stream").toString())).getInt("status"));
    }
    @Test public void speedIsReadOnRequestAndSignedLikeEveryRead() {
        FakePropertySource car = new FakePropertySource().putInt(DongfengProbe.SPEED, 47).putFloat(DongfengProbe.SPEED_DISPLAY, 49f);
        LinkServer server = new LinkServer(new DongfengProbe(car), new SampleBuffer(), "0.1.9", "test", "test", "test-link");
        server.setKey(Pairing.sharedKey("123456")); BleLinkRouter router = new BleLinkRouter(server);
        JSONObject body = new JSONObject(router.handle(request("123456", "/v1/speed", "speed-1").toString())).getJSONObject("body");
        assertEquals(47, body.getDouble("v"), 0);
        assertEquals("the speedometer's figure rides along", 49, body.getDouble("d"), 0);
        assertTrue(Math.abs(System.currentTimeMillis() - body.getLong("t")) < 5000);
        car.putInt(DongfengProbe.SPEED, 52); // the next request reads the property again, not a cached sample
        assertEquals(52, new JSONObject(router.handle(request("123456", "/v1/speed", "speed-2").toString())).getJSONObject("body").getDouble("v"), 0);
        assertEquals(401, new JSONObject(router.handle(request("000000", "/v1/speed", "speed-3").toString())).getInt("status"));
        assertFalse(new JSONObject(router.handle(request("123456", "/v1/speed", "speed-4").toString())).getJSONObject("body").has("error"));
        LinkServer noSpeed = new LinkServer(new DongfengProbe(new FakePropertySource()), new SampleBuffer(), "0.1.9", "test", "test", "test-link");
        noSpeed.setKey(Pairing.sharedKey("123456"));
        assertFalse(new JSONObject(new BleLinkRouter(noSpeed).handle(request("123456", "/v1/speed", "speed-5").toString())).getJSONObject("body").has("v"));
    }
    private JSONObject request(String code, String target, String nonce) {
        long time = System.currentTimeMillis();
        return new JSONObject().put("id", "request").put("target", target).put("time", time).put("nonce", nonce)
                .put("signature", Pairing.sign(Pairing.sharedKey(code), "GET", target, "", time, nonce));
    }
}
