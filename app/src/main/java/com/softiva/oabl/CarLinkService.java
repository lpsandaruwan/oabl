package com.softiva.oabl;

import android.content.Context;
import android.util.Log;
import java.io.IOException;
import android.os.Build;

import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import com.softiva.oabllink.bluetooth.BleGattClient;

/**
 * The box's consumer of OABL Link (energy-companion.md §3.8), over Bluetooth only. It finds the head
 * unit with a BLE scan, proves the typed code, seeds from {@code /v1/now}, then reads speed, snapshots
 * and history on a schedule, folding everything into {@link CarLink} and handing each sample to a sink
 * (the car_sample rows in energy.db). It only ever reads from the head unit.
 *
 * <p>The Bluetooth plumbing lives here; the decisions (what to request, what a gap means, what the
 * latest values are) live in the JVM-tested {@link CarLink} and {@link CarLinkProtocol}. It runs on
 * one background thread.
 */
final class CarLinkService {
    private static final String TAG = "CarLink";

    /** Live updates for the Home card and the Energy page; called on the network thread. */
    interface Listener {
        void onState(String state);
        void onLive(CarLink link);
    }

    /**
     * Where confirmed samples are persisted, keyed by the head unit's install link so a reinstall
     * (whose seq restarts at 1) never collides with old rows. {@link #lastSeq} lets the box resume
     * catch-up from what it already stored for this link.
     */
    interface CarSampleSink {
        void store(String link, CarLinkProtocol.CarSample sample);
        long lastSeq(String link);
        default void observe(String source, CarLink live) { }
        CarSampleSink NONE = new CarSampleSink() {
            @Override public void store(String link, CarLinkProtocol.CarSample sample) { }
            @Override public long lastSeq(String link) { return 0; }
        };
    }

    private final Context context;
    private final CarLink link = new CarLink();
    private Listener listener;
    private CarSampleSink sink = CarSampleSink.NONE;

    private volatile boolean running;
    private volatile String currentLink = "";
    private final CarLinkConnection connection=new CarLinkConnection();
    boolean connected(){return running&&connection.connected(android.os.SystemClock.elapsedRealtime());}
    String currentSource(){return currentLink;}
    private volatile Thread worker;
    private volatile BleGattClient bluetooth;
    /** A verified code cannot become wrong mid-drive. Prefer GATT after a damaged native stream. */
    private volatile boolean authenticatedBluetoothOnce;
    /** Until when (monotonic ms) GATT carries the link after a stream failure; 0 = try the stream. */
    private volatile long gattOnlyUntil;
    private byte[] bluetoothKey;
    private boolean allowSimulation;

    CarLinkService(Context context) { this.context = context.getApplicationContext(); }

    void setListener(Listener listener) { this.listener = listener; }
    void setSink(CarSampleSink sink) { this.sink = sink == null ? CarSampleSink.NONE : sink; }

    /** Pairs with the 6-digit code shown on the head unit. */
    void pair(String code) { link.clearReadings(); link.pair(code); bluetoothKey = CarLinkPairing.sharedKey(code);
        authenticatedBluetoothOnce = false; gattOnlyUntil = 0; }
    void allowSimulatedForTests() { allowSimulation = true; }

    void forget() { link.forget(); bluetoothKey = null; currentLink = "";
        context.getSharedPreferences("car_link", 0).edit().remove("bluetooth_address").apply(); }

    boolean paired() { return link.paired(); }

    CarLink link() { return link; }

    /** Starts the Bluetooth link; it keeps reconnecting until stopped. */
    void start() {
        if (running) return;
        running = true;
        startWorker();
    }

    synchronized void stop() {
        running = false;
        connection.disconnected();
        if (worker != null) worker.interrupt();
        worker = null;
        BleGattClient ble = bluetooth; bluetooth = null;
        if (ble != null) ble.close();
    }

    private void state(String state) {
        Log.i(TAG, state);
        if (listener != null) listener.onState(state);
    }

    private synchronized void startWorker() {
        if (!running || (worker != null && worker.isAlive())) return;
        worker = new Thread(this::run, "car-link");
        worker.setDaemon(true);
        worker.start();
    }

    // ---- the network loop ----

    private void run() {
        Thread owner = Thread.currentThread();
        int consecutiveProofFailures = 0;
        while (running && worker == owner && !owner.isInterrupted()) {
            String failure = "";
            try { runBluetooth(owner); consecutiveProofFailures = 0; }
            catch (InterruptedException e) { return; }
            catch (BleGattClient.PairingException e) {
                if (!running || worker != owner) return;
                consecutiveProofFailures++;
                failure = authenticatedBluetoothOnce ? "Bluetooth handshake damaged · retrying"
                        : consecutiveProofFailures >= 3
                        ? "Bluetooth code not verified · check code · retrying in 30s"
                        : "Bluetooth handshake interrupted · retrying";
                Log.w(TAG, "Bluetooth handshake failed; keeping automatic retry available", e);
            }
            catch (Exception e) {
                if (!running || worker != owner) return;
                failure = e.getMessage() == null ? "Bluetooth connection lost" : e.getMessage();
                Log.w(TAG, "Bluetooth link failed", e);
            }
            if (!running || worker != owner) return;
            state(failure);
            if (!pause(BleReconnectPolicy.retryDelayMs(authenticatedBluetoothOnce, consecutiveProofFailures))) return;
        }
    }

    private void acceptHello(CarLinkProtocol.Hello hello) throws IOException {
        if (hello == null || !hello.compatible() || hello.link.isEmpty()) throw new IOException("Incompatible OABL Link version");
        boolean simulation = allowSimulation || "ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE);
        if (!hello.supportedSource(simulation)) throw new IOException("Test or unknown car data refused · connect the real head unit");
        if (!currentLink.equals(hello.link)) link.clearReadings();
        currentLink = hello.link; link.seedLastSeq(sink.lastSeq(currentLink));
        fastSpeed = hello.revision >= FAST_SPEED_REVISION;
    }

    /** Link revision 5 answers {@code /v1/speed}: one getter read on request. */
    static final int FAST_SPEED_REVISION = 5;
    private volatile boolean fastSpeed;

    private boolean moving() {
        long time = link.latestTime(CarSignal.SPEED);
        return link.latest(CarSignal.SPEED) > 1 && Math.abs(System.currentTimeMillis() - time) < 5_000;
    }

    private boolean activeBluetooth() {
        long readyAt = link.latestTime(CarSignal.READY);
        return (link.latest(CarSignal.READY) == 1 && readyAt > 0
                && Math.abs(System.currentTimeMillis() - readyAt) < 60_000) || moving();
    }

    private void refreshBluetoothSpeed(BleGattClient connection) throws Exception {
        try {
            JSONObject body = bleJson(connection, "/v1/speed");
            if (body.has("v")) {
                CarLinkProtocol.Now now = new CarLinkProtocol.Now(); now.time = body.optLong("t");
                now.values.put(CarSignal.SPEED, body.getDouble("v")); now.times.put(CarSignal.SPEED, now.time);
                if (body.has("d")) { now.values.put(CarSignal.SPEED_DISPLAY, body.getDouble("d")); now.times.put(CarSignal.SPEED_DISPLAY, now.time); }
                link.ingestNow(now);
            }
            live(); // An unavailable speed is still a successful authenticated transport read.
        } catch (IOException refused) {
            if (refused.getMessage() != null && refused.getMessage().startsWith("Bluetooth read refused")) fastSpeed = false;
            else throw refused;
        }
    }

    private void runBluetooth(Thread owner) throws Exception {
        android.content.SharedPreferences prefs = context.getSharedPreferences("car_link", 0);
        String address = prefs.getString("bluetooth_address", "");
        BleGattClient connection = new BleGattClient(context, bluetoothKey, this::state,
                BleReconnectPolicy.tryNativeStream(gattOnlyUntil, android.os.SystemClock.elapsedRealtime()));
        synchronized (this) {
            if (!running || worker != owner) { connection.close(); return; }
            bluetooth = connection;
        }
        try {
            connection.connect(address);
            authenticatedBluetoothOnce = true;
                if (!running || worker != owner) return;
            acceptHello(CarLinkProtocol.parseHello(bleJson(connection, "/v1/hello")));
            prefs.edit().putString("bluetooth_address", connection.address()).apply();
            long began = android.os.SystemClock.elapsedRealtime();
            link.ingestNow(CarLinkProtocol.parseNow(bleJson(connection, "/v1/now"))); live();
            CarLinkBleSchedule schedule = new CarLinkBleSchedule(began, android.os.SystemClock.elapsedRealtime());
            // Assume a backlog until the first page says otherwise: after the box was off there usually is one.
            schedule.behind(true);
            while (running && worker == owner) {
                long now = android.os.SystemClock.elapsedRealtime();
                boolean active = activeBluetooth();
                CarLinkBleSchedule.Task task = schedule.next(now, fastSpeed, active);
                if (task == CarLinkBleSchedule.Task.WAIT) {
                    long delay = schedule.waitMs(now, fastSpeed, active);
                    if (delay > 0 && connection.awaitDisconnect(delay)) throw new IOException("Bluetooth disconnected");
                    continue;
                }
                began = android.os.SystemClock.elapsedRealtime();
                if (task == CarLinkBleSchedule.Task.SPEED) refreshBluetoothSpeed(connection);
                else if (task == CarLinkBleSchedule.Task.SNAPSHOT) refreshBluetoothLive(connection);
                else {
                    int limit = CarLinkBleSchedule.historyLimit(active, connection.catchUpLimit(), schedule.behind());
                    CarLinkProtocol.SamplesPage page = CarLinkProtocol.parseSamples(bleJson(connection, link.catchUpTarget(limit)));
                    long before = link.lastSeq();
                    String source = currentLink;
                    for (CarLinkProtocol.CarSample sample : page.samples) if (sample.seq > before) {
                        if (!running || worker != owner) return;
                        sink.store(source, sample);
                    }
                    if (!running || worker != owner) return;
                    link.ingestSamples(page);
                    boolean syncing = link.lastSeq() < page.latestSeq;
                    schedule.behind(syncing);
                    if (syncing && link.lastSeq() <= before) throw new IOException("Bluetooth catch-up made no progress");
                    live();
                    state("Connected · Bluetooth · encrypted" + (syncing ? " · syncing history" : ""));
                }
                schedule.completed(task, began, android.os.SystemClock.elapsedRealtime());
                // One serial request per turn: overdue speed wins before/after every bulk read,
                // including catch-up. An in-flight transfer cannot be preempted by this scheduler.
            }
        } catch (Exception failure) {
            if (BleReconnectPolicy.fallBackToGatt(connection.usingNativeStream(),
                    authenticatedBluetoothOnce, failure instanceof BleGattClient.PairingException)) {
                // Not for good: a stale link on the head unit can fail one stream attempt.
                gattOnlyUntil = android.os.SystemClock.elapsedRealtime() + BleReconnectPolicy.GATT_SPELL_MS;
                Log.w(TAG, "Native Bluetooth stream failed; using GATT for the next two minutes", failure);
            }
            // A changed BLE address must be discoverable again on the next attempt.
            if (!address.isEmpty()) prefs.edit().remove("bluetooth_address").apply();
            throw failure;
        } finally { if(worker==owner)this.connection.disconnected();connection.close(); synchronized (this) { if (bluetooth == connection) bluetooth = null; } }
    }

    private void refreshBluetoothLive(BleGattClient connection) throws Exception {
        link.ingestNow(CarLinkProtocol.parseNow(bleJson(connection, "/v1/now")));
        live();
    }

    private JSONObject bleJson(BleGattClient connection, String target) throws Exception {
        if (!running || worker != Thread.currentThread()) throw new InterruptedException();
        CarLink.Headers headers = link.sign("GET", target, System.currentTimeMillis());
        String id = java.util.UUID.randomUUID().toString();
        JSONObject request = new JSONObject().put("id", id).put("target", target).put("time", headers.time)
                .put("nonce", headers.nonce).put("signature", headers.signature);
        long began = android.os.SystemClock.elapsedRealtime();
        byte[] answer = connection.request(request.toString().getBytes(StandardCharsets.UTF_8));
        Log.d(TAG, "BLE read " + target.split("\\?", 2)[0] + " · "
                + (android.os.SystemClock.elapsedRealtime() - began) + " ms · " + answer.length + " bytes");
        if (!running || worker != Thread.currentThread()) throw new InterruptedException();
        JSONObject response = new JSONObject(new String(answer, StandardCharsets.UTF_8));
        if (!id.equals(response.optString("id"))) throw new IOException("Bluetooth response did not match request");
        int status = response.optInt("status");
        if (status != 200) throw new IOException(status == 401 ? "Code rejected · check pairing code and both clocks" : "Bluetooth read refused · " + status);
        return response.getJSONObject("body");
    }

    private static boolean pause(long ms) {
        try { Thread.sleep(ms); return true; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }

    private void live() {
        if(!running||worker!=Thread.currentThread())return;
        connection.message(android.os.SystemClock.elapsedRealtime());
        sink.observe(currentLink, link);
        if (listener != null) listener.onLive(link);
    }
}
