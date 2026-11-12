package com.softiva.oabl.bluetoothlab;

import android.app.*;
import android.os.*;
import com.softiva.oabllink.*;
import com.softiva.oabllink.bluetooth.BleGattClient;
import com.softiva.oabllink.bluetooth.BleGattServer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;

/** Tests the actual production Android BLE client against an independent Mac peripheral. */
public final class ClientTestRunner extends Instrumentation {
    private String code;
    private int checks;
    private BleGattClient connection;
    private byte[] key;
    private boolean lifecycleOnly;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); code = arguments.getString("code"); lifecycleOnly = "true".equals(arguments.getString("lifecycle_only")); start(); }
    private void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label); checks++;
        Bundle b = new Bundle(); b.putString("stream", "PASS " + label + "\n"); sendStatus(0, b);
    }
    private JSONObject signed(String target, long time) throws Exception {
        String nonce = UUID.randomUUID().toString();
        return new JSONObject().put("id", UUID.randomUUID().toString()).put("target", target).put("time", time)
                .put("nonce", nonce).put("signature", Pairing.sign(key, "GET", target, "", time, nonce));
    }
    private JSONObject request(JSONObject request, int expected) throws Exception {
        JSONObject response = new JSONObject(new String(connection.request(request.toString().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        check(request.getString("id").equals(response.getString("id")), "response ID matched");
        check(response.getInt("status") == expected, "read status " + expected); return response.getJSONObject("body");
    }
    private JSONObject read(String target) throws Exception { return request(signed(target, System.currentTimeMillis()), 200); }
    private BleGattClient open(String address) throws Exception {
        BleGattClient client = new BleGattClient(getTargetContext(), key, status -> {
            Bundle b = new Bundle(); b.putString("stream", status + "\n"); sendStatus(0, b);
        });
        connection = client; client.connect(address); return client;
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check(code != null && code.matches("[0-9]{6}"), "test code supplied"); key = Pairing.sharedKey(code);
            if (lifecycleOnly) {
                serverLifecycle(); result.putString("stream", "RESULT PASS · " + checks + " server lifecycle checks\n"); finish(Activity.RESULT_OK, result); return;
            }
            open(""); check(true, "Android central authenticated an encrypted BLE connection");
            String address = connection.address();
            check("simulated".equals(read("/v1/hello").getString("probe")), "Mac source explicitly marked simulated");
            JSONObject wrong = signed("/v1/now", System.currentTimeMillis()); wrong.put("signature", new String(new char[64]).replace('\0', '0'));
            request(wrong, 401);
            JSONObject replay = signed("/v1/now", System.currentTimeMillis()); request(replay, 200); request(replay, 401);
            request(signed("/v1/now", System.currentTimeMillis() - 60000), 401);
            long after = read("/v1/samples?after=0&limit=25").getLong("latest_seq");
            double first = read("/v1/now").getJSONObject("values").getJSONObject("soc").getDouble("v");
            Thread.sleep(3000);
            JSONObject now = read("/v1/now");
            check(now.getJSONObject("values").getJSONObject("soc").getDouble("v") > first, "live SOC changes over Android GATT client");
            check(Math.abs(System.currentTimeMillis() - now.getLong("t")) < 10000, "Android client receives fresh timestamps");
            connection.close(); Thread.sleep(2000); open(address);
            check(true, "Android central reconnects using remembered address and fresh session");
            JSONObject caught = read("/v1/samples?after=" + after + "&limit=25");
            check(caught.getLong("latest_seq") > after && caught.getJSONArray("samples").length() > 0, "catch-up includes changes missed while disconnected");
            long previous = after;
            for (Protocol.Incoming sample : Protocol.readSamples(caught)) { check(sample.seq > previous, "catch-up samples ordered"); previous = sample.seq; }
            result.putString("stream", "RESULT PASS · " + checks + " checks · real Android BLE central and Mac peripheral\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) { result.putString("stream", "FAIL " + android.util.Log.getStackTraceString(failure)); finish(Activity.RESULT_CANCELED, result); }
        finally { if (connection != null) connection.close(); }
    }
    private void serverLifecycle() throws Exception {
        java.util.concurrent.atomic.AtomicInteger advertisements = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> status = new java.util.concurrent.atomic.AtomicReference<>("");
        BleGattServer server = new BleGattServer(getTargetContext(), key, request -> "{}", text -> {
            status.set(text); if (text.contains("advertising")) advertisements.incrementAndGet();
        });
        try {
            server.start();
            long end = SystemClock.elapsedRealtime() + 15000;
            while (advertisements.get() < 1 && SystemClock.elapsedRealtime() < end) Thread.sleep(50);
            check(advertisements.get() >= 1, "production Android server advertises on physical phone: " + status.get());
            server.retry(); end = SystemClock.elapsedRealtime() + 15000;
            while (advertisements.get() < 2 && SystemClock.elapsedRealtime() < end) Thread.sleep(50);
            check(advertisements.get() >= 2, "server retry closes and recreates advertiser/GATT service");
        } finally { server.close(); }
        long end = SystemClock.elapsedRealtime() + 5000;
        while (!status.get().equals("Bluetooth stopped") && SystemClock.elapsedRealtime() < end) Thread.sleep(50);
        check(status.get().equals("Bluetooth stopped"), "server close completes and releases Bluetooth resources");
    }
}
