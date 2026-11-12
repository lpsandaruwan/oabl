package com.softiva.oabllink.bluetooth;

import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.os.*;
import android.util.Log;
import com.softiva.oabllink.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

/** One code-authenticated BLE reader. All GATT operations and RPC dispatch are serialized. */
public final class BleGattServer implements java.io.Closeable {
    public interface Rpc { String handle(String request) throws Exception; }
    public interface Listener { void status(String text); }
    private final Context context;
    private final byte[] key;
    private final Rpc router;
    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("oabl-ble-server");
    private final Handler worker;
    private BlePakeSession secure;
    private final BleFailureThrottle throttle = new BleFailureThrottle();
    private BleL2capServer stream;
    private long streamGeneration;
    private volatile boolean closed;
    private boolean registered;
    private final BroadcastReceiver bluetoothState = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            worker.post(() -> {
                if (closed) return;
                if (state == BluetoothAdapter.STATE_OFF) { shutdown(); report("Bluetooth off · waiting for it to turn on"); }
                else if (state == BluetoothAdapter.STATE_ON) { shutdown(); open(); }
            });
        }
    };
    public BleGattServer(Context context, byte[] key, Rpc router, Listener listener) {
        this.context = context.getApplicationContext(); this.key = key.clone(); this.router = router; this.listener = listener;
        thread.start(); worker = new Handler(thread.getLooper());
    }
    public void start() {
        worker.post(() -> {
            if (closed) return;
            IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
            try {
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(bluetoothState, filter, Context.RECEIVER_EXPORTED);
                else context.registerReceiver(bluetoothState, filter);
                registered = true;
            } catch (RuntimeException failure) { Log.w("OablBle", "Bluetooth state watcher unavailable", failure); }
            open();
        });
    }
    public void retry() { worker.post(() -> { if (!closed) { shutdown(); open(); } }); }

    // New service identity prevents a v2 peer silently downgrading to unprotected code proofs.
    public static final UUID SERVICE = UUID.fromString("650f0011-b763-4f40-a619-4d88e9107310");
    /** Read-only version/PSM bootstrap. It exposes no telemetry and confers no authorization. */
    public static final UUID STREAM = UUID.fromString("650f0014-b763-4f40-a619-4d88e9107310");
    private static final UUID RX = UUID.fromString("650f0012-b763-4f40-a619-4d88e9107310");
    private static final UUID TX = UUID.fromString("650f0013-b763-4f40-a619-4d88e9107310");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final BleFrames.Assembler incoming = new BleFrames.Assembler();
    private final ArrayDeque<byte[]> outgoing = new ArrayDeque<>();
    private BluetoothGattServer gatt;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattCharacteristic transmit;
    private BluetoothDevice peer;
    /** When the peer last sent anything over GATT (monotonic ms); the stream reports its own. */
    private long peerHeardAt;
    private boolean subscribed, sending, stopped, confirmedIndications = true;
    private int payload = 20, messageId, requests;
    private void report(String text) {
        listener.status(text);
        Log.i("OablBle", text);
    }
    private final Runnable stalled = () -> {
        if (peer != null && sending) { report("Indication timed out; disconnecting"); cancel(peer); resetPeer(); }
    };
    private final Runnable handshakeTimeout = () -> {
        if (peer != null && secure != null && !secure.authenticated()) {
            cancel(peer); resetPeer(); report("Bluetooth code pairing timed out · check the OABL code and retry");
        }
    };
    private void open() {
        if (closed) return;
        stopped = false;
        try {
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) throw new IllegalStateException("Turn Bluetooth on, then tap Start");
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) throw new IllegalStateException("BLE advertising is unavailable");
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    long generation = ++streamGeneration;
                    stream = new BleL2capServer(adapter, key, router, this::report, throttle,
                            device -> worker.post(() -> {
                                // The GATT link remains as a bootstrap while CoC owns the authenticated
                                // data session. Do not tear down its shared LE link after 60 seconds.
                                if (!stopped && !closed && generation == streamGeneration && device.equals(peer))
                                    worker.removeCallbacks(handshakeTimeout);
                            }));
                    report("Bluetooth stream available");
                } catch (IOException | RuntimeException unavailable) {
                    stream = null;
                    Log.i("OablBle", "Bluetooth stream unavailable; retaining GATT", unavailable);
                }
            }
            gatt = manager.openGattServer(context, callback);
            if (gatt == null) throw new IllegalStateException("GATT server is unavailable");
            BluetoothGattService service = new BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            // PAKE and encrypted records enforce application security. Requesting native bonding
            // here enters the OEM phone pairing receiver, which rejects all LE-only devices.
            service.addCharacteristic(new BluetoothGattCharacteristic(RX, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE));
            transmit = new BluetoothGattCharacteristic(TX,
                    BluetoothGattCharacteristic.PROPERTY_INDICATE | BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0);
            transmit.addDescriptor(new BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
            service.addCharacteristic(transmit);
            if (stream != null) service.addCharacteristic(new BluetoothGattCharacteristic(STREAM,
                    BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ));
            if (!gatt.addService(service)) throw new IllegalStateException("Adding GATT service failed");
            report("Registering BLE service");
        } catch (SecurityException failure) { report("Bluetooth permission needed · allow nearby-device access"); shutdown(); }
        catch (Exception failure) { report("Could not start: " + failure.getMessage()); shutdown(); }
    }
    private final AdvertiseCallback advertising = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settings) { worker.post(() -> { if (!stopped && !closed) report("Bluetooth advertising · waiting for paired AI box"); }); }
        @Override public void onStartFailure(int error) { worker.post(() -> { report("Advertising failed: " + error); shutdown(); }); }
    };
    private void resetPeer() {
        secure = null; peer = null; subscribed = sending = false; confirmedIndications = true;
        payload = 20; incoming.clear(); outgoing.clear();
        lastRecord = null; lastRecordSession = null;
        worker.removeCallbacks(stalled); worker.removeCallbacks(handshakeTimeout);
    }
    private final BluetoothGattServerCallback callback = new BluetoothGattServerCallback() {
        @Override public void onServiceAdded(int result, BluetoothGattService service) {
            worker.post(() -> {
                if (stopped || closed) return;
                if (result != BluetoothGatt.GATT_SUCCESS) { report("GATT registration failed: " + result); shutdown(); return; }
                AdvertiseSettings config = new AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                        .setConnectable(true).setTimeout(0).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build();
                // A 128-bit service UUID fits in a legacy advertisement without a device name.
                try {
                    advertiser.startAdvertising(config, new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(SERVICE)).build(), advertising);
                } catch (Exception failure) { report("Advertising failed: " + failure.getMessage()); shutdown(); }
            });
        }
        @Override public void onConnectionStateChange(BluetoothDevice device, int result, int state) {
            worker.post(() -> {
                if (stopped || closed) return;
                if (state == BluetoothProfile.STATE_CONNECTED && result == BluetoothGatt.GATT_SUCCESS) {
                    long now = SystemClock.elapsedRealtime();
                    if (!throttle.allowed(now)) { cancel(device); return; }
                    if (peer != null && !peer.equals(device)) {
                        // The box reconnects from a new random address. Its old link may still be
                        // held here; let it go unless it is a working, code-authenticated reader.
                        long streamHeard = stream == null ? 0 : stream.lastActivity(peer);
                        boolean proven = (secure != null && secure.authenticated()) || streamHeard > 0;
                        if (!BlePeerPolicy.replaceable(proven, Math.max(peerHeardAt, streamHeard), now)) { cancel(device); return; }
                        report("Bluetooth reconnected · dropping the silent connection");
                        cancel(peer);
                    }
                    resetPeer(); peer = device; peerHeardAt = now; secure = new BlePakeSession(true, key); lastHandshakeWire = null;
                    worker.postDelayed(handshakeTimeout, 60000);
                    report("Bluetooth connected · waiting for OABL code authentication");
                } else if (device.equals(peer)) { resetPeer(); report("Bluetooth disconnected · waiting to reconnect"); }
            });
        }
        @Override public void onMtuChanged(BluetoothDevice device, int mtu) {
            worker.post(() -> { if (device.equals(peer)) { payload = Math.min(512, Math.max(20, mtu - 3)); report("BLE value size " + payload + " bytes"); } });
        }
        @Override public void onCharacteristicReadRequest(BluetoothDevice device, int id, int offset, BluetoothGattCharacteristic characteristic) {
            worker.post(() -> {
                if (stopped || closed) return;
                int psm = stream == null ? 0 : stream.psm();
                boolean valid = device.equals(peer) && offset == 0 && STREAM.equals(characteristic.getUuid()) && psm != 0;
                respond(device, id, valid ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                        0, valid ? BleStreamFraming.offer(psm) : null);
            });
        }
        @Override public void onDescriptorReadRequest(BluetoothDevice device, int id, int offset, BluetoothGattDescriptor descriptor) {
            worker.post(() -> {
                if (stopped || closed) return;
                boolean valid = device.equals(peer) && offset == 0 && CCCD.equals(descriptor.getUuid());
                respond(device, id, valid ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0,
                        valid ? (subscribed ? (confirmedIndications
                                ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                                : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) : null);
            });
        }
        @Override public void onDescriptorWriteRequest(BluetoothDevice device, int id, BluetoothGattDescriptor descriptor,
                boolean prepared, boolean response, int offset, byte[] value) {
            worker.post(() -> {
                if (stopped || closed) return;
                boolean indications = Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                boolean notifications = Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                boolean valid = device.equals(peer) && CCCD.equals(descriptor.getUuid()) && !prepared && offset == 0
                        && (indications || notifications || Arrays.equals(value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE));
                if (valid) { subscribed = indications || notifications; confirmedIndications = indications; peerHeardAt = SystemClock.elapsedRealtime(); }
                if (response) respond(device, id, valid ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null);
                if (valid) report(subscribed ? "Bluetooth subscribed · " + (indications ? "indications" : "notifications")
                        + " · authenticating" : "Bluetooth updates disabled");
            });
        }
        @Override public void onCharacteristicWriteRequest(BluetoothDevice device, int id, BluetoothGattCharacteristic characteristic,
                boolean prepared, boolean response, int offset, byte[] value) {
            byte[] stable = value.clone();
            worker.post(() -> {
                if (stopped || closed) return;
                int result = BluetoothGatt.GATT_SUCCESS;
                byte[] message = null;
                try {
                    if (!device.equals(peer) || !subscribed || !RX.equals(characteristic.getUuid()) || prepared || offset != 0)
                        throw new IllegalArgumentException("not ready or busy");
                    peerHeardAt = SystemClock.elapsedRealtime();
                    message = incoming.accept(stable, SystemClock.elapsedRealtime());
                } catch (IllegalArgumentException failure) { result = BluetoothGatt.GATT_FAILURE; incoming.clear(); report("Rejected frame: " + failure.getMessage()); }
                if (response) respond(device, id, result, 0, null);
                if (message != null) request(message);
            });
        }
        @Override public void onNotificationSent(BluetoothDevice device, int result) {
            worker.post(() -> {
                if (stopped || !device.equals(peer)) return;
                worker.removeCallbacks(stalled); sending = false;
                if (result != BluetoothGatt.GATT_SUCCESS) { report("Indication failed: " + result); cancel(device); resetPeer(); return; }
                sendNext();
            });
        }
    };
    private byte[] lastRecord, lastHandshakeWire;
    private BlePakeSession lastRecordSession;
    private void request(byte[] message) {
        try {
            byte[] answer;
            if (!secure.authenticated()) {
                // Pairing messages carry a checksum (see BleIntegrity): a damaged one is asked for
                // again rather than treated as a wrong code.
                byte[] plain = BleIntegrity.unwrap(message);
                if (plain == null) {
                    Log.w("OablBle", "Damaged pairing message, asking for it again");
                    outgoing.clear();
                    outgoing.addAll(BleFrames.split(BleIntegrity.wrap(BleIntegrity.NAK), ++messageId, payload)); sendNext();
                    return;
                }
                if (BleIntegrity.isNak(plain)) {
                    if (lastHandshakeWire == null) throw new IOException("Resend requested before any pairing message");
                    Log.w("OablBle", "Peer asked for the last pairing message again");
                    outgoing.clear();
                    outgoing.addAll(BleFrames.split(lastHandshakeWire, ++messageId, payload)); sendNext();
                    return;
                }
                answer = secure.handshake(plain);
                if (answer != null) { answer = BleIntegrity.wrap(answer); lastHandshakeWire = answer; }
                if (secure.authenticated()) { throttle.authenticated(); worker.removeCallbacks(handshakeTimeout); report("Bluetooth authenticated · encrypted reads ready"); }
            } else {
                String plain = new String(secure.open(message), StandardCharsets.UTF_8);
                if (BleSecureSession.RESEND_REQUEST.equals(plain) && lastRecord != null && lastRecordSession == secure) {
                    // The box may have rejected an early fragment while the old tail is still
                    // queued. Retire that tail, then resend the same sealed answer with a new
                    // GATT message id. An indication already in flight finishes first.
                    outgoing.clear();
                    answer = lastRecord;
                    Log.i("OablBle", "Resending last encrypted answer after incomplete GATT transfer");
                } else {
                    if (!outgoing.isEmpty()) throw new IOException("New Bluetooth request while answer pending");
                    answer = secure.seal(BleCompression.pack(router.handle(plain).getBytes(StandardCharsets.UTF_8))); requests++;
                    lastRecord = answer; lastRecordSession = secure;
                }
                Log.d("OablBleTrace", secure.trace());
            }
            if (answer != null) { outgoing.addAll(BleFrames.split(answer, ++messageId, payload)); sendNext(); }
        } catch (Exception failure) {
            throttle.failed(SystemClock.elapsedRealtime());
            report("Bluetooth authentication/read failed · check code and retry");
            Log.w("OablBle", "BLE session rejected", failure);
            if (peer != null) cancel(peer); resetPeer();
        }
    }
    @SuppressWarnings("deprecation") private void sendNext() {
        if (stopped || closed || sending || peer == null || !subscribed || outgoing.isEmpty()) return;
        byte[] frame = outgoing.removeFirst(); boolean accepted;
        try {
            if (Build.VERSION.SDK_INT >= 33) accepted = gatt.notifyCharacteristicChanged(peer, transmit, confirmedIndications, frame) == BluetoothStatusCodes.SUCCESS;
            else { transmit.setValue(frame); accepted = gatt.notifyCharacteristicChanged(peer, transmit, confirmedIndications); }
        } catch (SecurityException revoked) { report("Bluetooth permission revoked"); shutdown(); return; }
        if (!accepted) { report("Could not queue BLE indication"); cancel(peer); resetPeer(); return; }
        sending = true; worker.postDelayed(stalled, 8000);
    }
    private void cancel(BluetoothDevice device) {
        if (gatt == null) return;
        try { gatt.cancelConnection(device); }
        catch (SecurityException revoked) { report("Bluetooth permission revoked"); }
        catch (RuntimeException unavailable) { Log.w("OablBle", "Could not cancel GATT connection", unavailable); }
    }
    private void respond(BluetoothDevice device, int id, int result, int offset, byte[] value) {
        if (gatt == null) return;
        try { gatt.sendResponse(device, id, result, offset, value); }
        catch (SecurityException revoked) { report("Bluetooth permission revoked"); shutdown(); }
        catch (RuntimeException unavailable) { report("Bluetooth response failed"); shutdown(); }
    }
    @Override public void close() {
        closed = true;
        worker.post(() -> {
            shutdown();
            if (registered) { context.unregisterReceiver(bluetoothState); registered = false; }
            report("Bluetooth stopped"); thread.quitSafely();
        });
    }
    private void shutdown() {
        if (stopped) return;
        stopped = true; worker.removeCallbacks(stalled);
        streamGeneration++;
        if (stream != null) { stream.close(); stream = null; }
        if (advertiser != null) try { advertiser.stopAdvertising(advertising); } catch (SecurityException ignored) { } catch (RuntimeException ignored) { }
        if (gatt != null) { try { if (peer != null) cancel(peer); gatt.close(); } catch (SecurityException ignored) { } catch (RuntimeException ignored) { } }
        resetPeer(); gatt = null; advertiser = null;
    }
}
