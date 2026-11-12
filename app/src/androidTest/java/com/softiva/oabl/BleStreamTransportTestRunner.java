package com.softiva.oabl;

import android.app.Activity;
import android.app.Instrumentation;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import com.softiva.oabllink.bluetooth.BleGattClient;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Real Android CoC sockets between two emulators; never run on the owner's car devices. */
public final class BleStreamTransportTestRunner extends Instrumentation {
    private String code;
    private int checks, largestReply;
    private final StringBuilder report = new StringBuilder();
    private final Set<String> statuses = java.util.Collections.synchronizedSet(new HashSet<>());
    private BleGattClient client;
    private CarLink signing;
    @Override public void onCreate(Bundle args) { super.onCreate(args); code = args == null ? null : args.getString("code"); start(); }
    private void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++; report.append("PASS · ").append(label).append('\n');
        Bundle status = new Bundle(); status.putString("stream", "PASS · " + label + "\n"); sendStatus(0, status);
    }
    private Set<String> bonds() {
        Set<String> result = new HashSet<>();
        for (BluetoothDevice device : getTargetContext().getSystemService(BluetoothManager.class).getAdapter().getBondedDevices())
            result.add(device.getAddress());
        return result;
    }
    private JSONObject request(String target) throws Exception {
        CarLink.Headers headers = signing.sign("GET", target, System.currentTimeMillis()); String id = UUID.randomUUID().toString();
        JSONObject request = new JSONObject().put("id", id).put("target", target).put("time", headers.time)
                .put("nonce", headers.nonce).put("signature", headers.signature);
        byte[] answer = client.request(request.toString().getBytes(StandardCharsets.UTF_8));
        largestReply = Math.max(largestReply, answer.length);
        JSONObject response = new JSONObject(new String(answer, StandardCharsets.UTF_8));
        if (!id.equals(response.optString("id")) || response.optInt("status") != 200)
            throw new AssertionError("Unexpected RPC reply for " + target);
        return response.getJSONObject("body");
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE), "emulator-only guard");
            check(Build.VERSION.SDK_INT >= 29 && code != null && code.matches("\\d{6}"), "stream API and fixture code available");
            Set<String> before = bonds();
            signing = new CarLink(); signing.pair(code);
            client = new BleGattClient(getTargetContext(), CarLinkPairing.sharedKey(code), statuses::add);
            client.connect("");
            check(statuses.contains("Bluetooth data transport · LE CoC"), "native LE CoC selected instead of GATT indications");
            JSONObject hello = request("/v1/hello");
            check(hello.toString().contains("simulated"), "authenticated real Link APK uses the simulated car");
            request("/v1/now"); request("/v1/speed"); request("/v1/samples?after=0&limit=500");
            check(largestReply > 3233, "encrypted history exceeds the size that failed on the car (" + largestReply + " bytes)");
            long began = SystemClock.elapsedRealtime(); int reads = 0;
            while (SystemClock.elapsedRealtime() - began < 67000) {
                request("/v1/speed"); reads++;
                if (reads % 7 == 0) request("/v1/samples?after=0&limit=500");
                SystemClock.sleep(1200);
            }
            check(reads >= 30, "stream stays live beyond the 60-second GATT bootstrap deadline (" + reads + " speed reads)");
            check(before.equals(bonds()), "no Android Bluetooth bond was created");

            Method message = BleGattClient.class.getDeclaredMethod("message"); message.setAccessible(true);
            AtomicReference<Throwable> stopped = new AtomicReference<>();
            Thread blocked = new Thread(() -> { try { message.invoke(client); } catch (Throwable failure) { stopped.set(failure); } }, "test-stream-cancel");
            blocked.start(); SystemClock.sleep(200);
            check(blocked.isAlive(), "fixture is blocked waiting for an absent response");
            client.close(); blocked.join(3000);
            check(!blocked.isAlive() && stopped.get() != null, "close promptly cancels the blocked stream read");
            report.append("PASS · ").append(checks).append(" native stream checks; simulated radios only.\n");
            result.putString("stream", report.toString()); finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", report + "FAIL · " + android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        } finally { if (client != null) client.close(); }
    }
}
