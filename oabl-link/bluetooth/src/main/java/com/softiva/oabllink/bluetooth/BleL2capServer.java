package com.softiva.oabllink.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.SystemClock;
import android.util.Log;
import com.softiva.oabllink.BleCompression;
import com.softiva.oabllink.BleFailureThrottle;
import com.softiva.oabllink.BleIntegrity;
import com.softiva.oabllink.BlePakeSession;
import com.softiva.oabllink.BleSecureSession;
import com.softiva.oabllink.BleStreamFraming;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** API 29+ LE stream; "insecure" bypasses native bonding, never OABL's PAKE or record security. */
@android.annotation.TargetApi(29)
final class BleL2capServer implements Closeable {
    private final byte[] key;
    private final BleGattServer.Rpc router;
    private final BleGattServer.Listener listener;
    private final BleFailureThrottle throttle;
    private final java.util.function.Consumer<BluetoothDevice> authenticated;
    private final BluetoothServerSocket server;
    private final Thread worker;
    /** Every open stream. A box that reconnects is served at once, not after its dead link times out. */
    private final java.util.Set<BluetoothSocket> sockets = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final int MAX_SESSIONS = 3;
    /** The newest stream that proved the code; it replaces older ones. */
    private volatile BluetoothSocket reader;
    private volatile BluetoothDevice readerDevice;
    private volatile long readerHeardAt;
    private volatile boolean closed;
    private final int psm;

    BleL2capServer(BluetoothAdapter adapter, byte[] key, BleGattServer.Rpc router,
            BleGattServer.Listener listener, BleFailureThrottle throttle,
            java.util.function.Consumer<BluetoothDevice> authenticated) throws IOException {
        this.key = key.clone(); this.router = router; this.listener = listener; this.throttle = throttle;
        this.authenticated = authenticated;
        server = adapter.listenUsingInsecureL2capChannel();
        psm = server.getPsm();
        try { BleStreamFraming.offer(psm); }
        catch (IllegalArgumentException invalid) { server.close(); throw new IOException("Invalid LE stream PSM", invalid); }
        worker = new Thread(this::accept, "oabl-ble-stream"); worker.start();
    }
    int psm() { return !closed && worker.isAlive() ? psm : 0; }
    /** When the authenticated stream from this device last carried a request (monotonic ms); 0 if none. */
    long lastActivity(BluetoothDevice device) {
        return device != null && device.equals(readerDevice) && reader != null ? readerHeardAt : 0;
    }
    private void report(String text) { if (!closed) listener.status(text); }
    private void accept() {
        while (!closed) {
            BluetoothSocket socket;
            try { socket = server.accept(); }
            catch (IOException | RuntimeException failure) {
                if (!closed) {
                    report("Bluetooth stream listener unavailable · using GATT");
                    Log.w("OablBle", "Bluetooth stream listener stopped", failure);
                }
                close();
                return;
            }
            synchronized (this) {
                if (closed) { close(socket); return; }
                if (sockets.size() >= MAX_SESSIONS) { close(socket); continue; }
                sockets.add(socket);
            }
            new Thread(() -> {
                try {
                    if (throttle.allowed(SystemClock.elapsedRealtime())) serve(socket);
                } finally {
                    close(socket);
                    sockets.remove(socket);
                    synchronized (this) { if (reader == socket) { reader = null; readerDevice = null; } }
                }
            }, "oabl-ble-stream-session").start();
        }
    }
    private void serve(BluetoothSocket socket) {
        BlePakeSession secure = null;
        try {
            BleSocketChannel channel = new BleSocketChannel(socket);
            secure = new BlePakeSession(true, key);
            long until = SystemClock.elapsedRealtime() + 60000;
            report("Bluetooth stream connected · waiting for OABL code authentication");
            byte[] lastWire = null;
            int repairs = 0;
            for (int exchange = 0; !secure.authenticated() && exchange < 8; ) {
                byte[] message = BleIntegrity.unwrap(channel.read(Math.min(18000, until - SystemClock.elapsedRealtime())));
                if (message == null || BleIntegrity.isNak(message)) {
                    // Damaged pairing message (or the box saying so about ours): send again.
                    if (++repairs > BleSecureSession.MAX_DAMAGED_IN_A_ROW) throw new IOException("Pairing messages kept arriving damaged");
                    byte[] again = message == null ? BleIntegrity.wrap(BleIntegrity.NAK) : lastWire;
                    if (again == null) throw new IOException("Resend requested before any pairing message");
                    channel.write(again, Math.min(18000, until - SystemClock.elapsedRealtime()));
                    continue;
                }
                exchange++;
                byte[] answer = secure.handshake(message);
                if (answer != null) {
                    lastWire = BleIntegrity.wrap(answer);
                    channel.write(lastWire, Math.min(18000, until - SystemClock.elapsedRealtime()));
                }
            }
            if (!secure.authenticated()) throw new IOException("Incomplete Bluetooth stream handshake");
            throttle.authenticated();
            BluetoothSocket previous;
            synchronized (this) {
                previous = reader;
                reader = socket; readerDevice = socket.getRemoteDevice(); readerHeardAt = SystemClock.elapsedRealtime();
            }
            // Only one reader: the box that just proved the code replaces a stream left from before.
            if (previous != null && previous != socket) close(previous);
            authenticated.accept(socket.getRemoteDevice());
            report("Bluetooth stream authenticated · encrypted reads ready");
            byte[] lastRecord = null;
            while (!closed) {
                String request = new String(secure.open(channel.read(30000)), StandardCharsets.UTF_8);
                if (reader == socket) readerHeardAt = SystemClock.elapsedRealtime();
                byte[] answer;
                if (BleSecureSession.RESEND_REQUEST.equals(request) && lastRecord != null) {
                    answer = lastRecord;
                } else {
                    answer = secure.seal(BleCompression.pack(router.handle(request).getBytes(StandardCharsets.UTF_8)));
                    lastRecord = answer;
                }
                channel.write(answer, 18000);
            }
        } catch (EOFException disconnected) {
            if (!closed) report("Bluetooth stream disconnected · waiting to reconnect");
        } catch (Exception failure) {
            if (!closed && secure != null && secure.authenticated() && reader != socket) {
                // Replaced by the box's newer stream; closing this one is not a failed proof.
                Log.i("OablBle", "Older Bluetooth stream replaced");
            } else if (!closed) {
                throttle.failed(SystemClock.elapsedRealtime());
                report("Bluetooth stream authentication/read failed · retrying");
                Log.w("OablBle", "Bluetooth stream session rejected", failure);
            }
        }
    }
    private static void close(BluetoothSocket socket) { try { socket.close(); } catch (IOException ignored) { } }
    @Override public synchronized void close() {
        closed = true;
        try { server.close(); } catch (IOException ignored) { }
        for (BluetoothSocket socket : sockets) close(socket);
        worker.interrupt();
    }
}
