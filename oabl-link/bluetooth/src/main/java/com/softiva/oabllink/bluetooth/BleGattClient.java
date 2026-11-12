package com.softiva.oabllink.bluetooth;

import android.Manifest;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.util.Log;
import android.content.pm.PackageManager;
import android.os.*;
import com.softiva.oabllink.*;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.*;

/** A single-owner BLE RPC connection: prefer native LE streams, retain GATT for older peers. */
public final class BleGattClient implements AutoCloseable {
    public interface Listener { void status(String text); }
    /** A rejected pairing needs the driver to retry, rather than another automatic pairing prompt. */
    public static final class PairingException extends IOException {
        PairingException(String message, Throwable cause) { super(message, cause); }
    }
    private static final UUID RX = UUID.fromString("650f0012-b763-4f40-a619-4d88e9107310");
    private static final UUID TX = UUID.fromString("650f0013-b763-4f40-a619-4d88e9107310");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Context context;
    private final Listener listener;
    private final boolean allowNativeStream;
    private final BlockingQueue<Object> events = new LinkedBlockingQueue<>(16);
    private final BlockingQueue<Object> messages = new LinkedBlockingQueue<>(4);
    private final java.util.concurrent.atomic.AtomicBoolean acceptingScan = new java.util.concurrent.atomic.AtomicBoolean();
    private final BleFrameRecovery incoming = new BleFrameRecovery();
    private final Handler callbacks = new Handler(Looper.getMainLooper());
    private final CountDownLatch gone = new CountDownLatch(1);
    private final BlePakeSession secure;
    private volatile BluetoothGatt gatt;
    private volatile BluetoothSocket streamSocket;
    private volatile BleSocketChannel stream;
    private String connectedAddress = "";
    private volatile BluetoothLeScanner scanner;
    private volatile boolean closed;
    private volatile int payload = 20;
    private BluetoothGattCharacteristic receive, transmit;
    private ScanCallback scanning;
    private int frameId;
    private static final Object CONNECTED = new Object(), SERVICES = new Object(), WRITTEN = new Object(), SUBSCRIBED = new Object();
    private static final class StreamOffer {
        final byte[] value;
        StreamOffer(byte[] value) { this.value = value == null ? null : value.clone(); }
    }
    private static final class FramingException extends IOException {
        FramingException(IllegalArgumentException cause) { super("Bluetooth framing failed", cause); }
    }
    public BleGattClient(Context context, byte[] key, Listener listener) {
        this(context, key, listener, true);
    }
    public BleGattClient(Context context, byte[] key, Listener listener, boolean allowNativeStream) {
        this.context = context.getApplicationContext(); this.listener = listener;
        this.allowNativeStream = allowNativeStream; secure = new BlePakeSession(false, key);
    }
    private void post(Object value) {
        if (closed) return;
        if (value instanceof byte[]) { if (!messages.offer(value)) failQueue(); return; }
        if (value instanceof Exception && !messages.offer(value)) {
            messages.clear(); messages.offer(value);
        }
        if (!events.offer(value)) { events.clear(); events.offer(new IOException("Bluetooth event overflow")); }
    }
    private void postMessageFailure(IOException failure) {
        if (closed) return;
        if (!messages.offer(failure)) { messages.clear(); messages.offer(failure); }
    }
    private void failQueue() { messages.clear(); messages.offer(new IOException("Bluetooth message overflow")); }
    private Object event(long timeout) throws Exception {
        if (closed) throw new IOException("Bluetooth link closed");
        Object value = events.poll(timeout, TimeUnit.MILLISECONDS);
        if (closed) throw new IOException("Bluetooth link closed");
        if (value == null) throw new IOException("Bluetooth response timed out");
        if (value instanceof Exception) throw (Exception) value;
        return value;
    }
    private void expect(Object expected, long timeout) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        Object received;
        do { received = event(Math.max(1, deadline - SystemClock.elapsedRealtime())); }
        while (received instanceof Integer && SystemClock.elapsedRealtime() < deadline);
        if (received != expected) throw new IOException("Unexpected Bluetooth operation");
    }
    public void connect(String rememberedAddress) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Bluetooth connect must run off UI");
        if (Build.VERSION.SDK_INT >= 31 && (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED))
            throw new IOException("Bluetooth permission needed · open connection settings");
        if (Build.VERSION.SDK_INT < 31 && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            throw new IOException("Location permission needed for Bluetooth discovery");
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) throw new IOException("Bluetooth is off or unavailable");
        BluetoothDevice device;
        if (rememberedAddress != null && !rememberedAddress.isEmpty()) device = adapter.getRemoteDevice(rememberedAddress);
        else {
            listener.status("Searching for OABL Link over Bluetooth");
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) throw new IOException("Bluetooth scanning unavailable");
            scanning = new ScanCallback() {
                @Override public void onScanResult(int type, ScanResult result) { if (acceptingScan.compareAndSet(true, false)) post(result.getDevice()); }
                @Override public void onScanFailed(int error) { post(new IOException("Bluetooth scan failed · " + error)); }
            };
            try {
                acceptingScan.set(true);
                scanner.startScan(Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(BleGattServer.SERVICE)).build()),
                        new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), scanning);
                Object found = event(12000);
                if (!(found instanceof BluetoothDevice)) throw new IOException("Bluetooth discovery failed");
                device = (BluetoothDevice) found;
            } finally { stopScan(); events.clear(); }
        }
        listener.status("Connecting to OABL Link over Bluetooth");
        connectedAddress = device.getAddress();
        // Use a main-Looper callback on old Android too; the connection owner always runs elsewhere.
        gatt = Build.VERSION.SDK_INT >= 26
                ? device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, callbacks)
                : device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) throw new IOException("Bluetooth GATT connection unavailable");
        expect(CONNECTED, 15000);
        if (!gatt.discoverServices()) throw new IOException("Bluetooth service discovery refused");
        expect(SERVICES, 15000);
        BluetoothGattService service = gatt.getService(BleGattServer.SERVICE);
        if (service == null || (receive = service.getCharacteristic(RX)) == null || (transmit = service.getCharacteristic(TX)) == null)
            throw new IOException("OABL Bluetooth service missing");
        boolean nativeStream = allowNativeStream && Build.VERSION.SDK_INT >= 29 && tryStream(device, service);
        if (!nativeStream) {
            // OABL v3 authenticates the code with PAKE. Native phone/audio bonding is not involved.
            if (gatt.requestMtu(247)) { Object mtu = event(6000); if (!(mtu instanceof Integer)) throw new IOException("Bluetooth MTU negotiation failed"); }
            if (!gatt.setCharacteristicNotification(transmit, true)) throw new IOException("Bluetooth updates unavailable");
            BluetoothGattDescriptor descriptor = transmit.getDescriptor(CCCD);
            if (descriptor == null) throw new IOException("Bluetooth subscription descriptor missing");
            boolean notifications = (transmit.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
            writeDescriptor(descriptor, notifications ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
            expect(SUBSCRIBED, 45000);
            listener.status(allowNativeStream ? "Bluetooth data transport · GATT"
                    : "Bluetooth data transport · GATT after stream failure");
            listener.status(notifications ? "Bluetooth GATT notifications with app recovery"
                    : "Bluetooth GATT indications");
        }
        listener.status("Pairing securely with the OABL code");
        try {
            sendHandshake(secure.begin());
            for (int exchange = 0; !secure.authenticated() && exchange < 8; exchange++) {
                byte[] next = secure.handshake(receiveHandshake());
                if (next != null) sendHandshake(next);
            }
            if (!secure.authenticated()) throw new SecurityException("incomplete code authentication");
        } catch (Exception failure) {
            throw BleHandshakeFailure.classify(failure, closed);
        }
    }
    private byte[] lastHandshake;
    /** Pairing messages carry a checksum so damage in transit is not mistaken for a wrong code. */
    private void sendHandshake(byte[] message) throws Exception {
        lastHandshake = message;
        send(BleIntegrity.wrap(message));
    }
    private byte[] receiveHandshake() throws Exception {
        for (int repairs = 0; ; repairs++) {
            byte[] message = null;
            try { message = BleIntegrity.unwrap(message()); }
            catch (FramingException | SocketTimeoutException damaged) {
                if (stream != null || repairs >= BleSecureSession.MAX_DAMAGED_IN_A_ROW) throw damaged;
                incoming.awaitFreshMessage(); messages.clear();
            }
            if (message != null && !BleIntegrity.isNak(message)) return message;
            if (repairs >= BleSecureSession.MAX_DAMAGED_IN_A_ROW) throw new IOException("Bluetooth pairing message kept arriving damaged");
            if (message == null) {
                Log.w("OablBle", "Damaged pairing message, asking for it again (" + (repairs + 1) + ")");
                send(BleIntegrity.wrap(BleIntegrity.NAK));
            } else {
                Log.w("OablBle", "Peer asked for the last pairing message again (" + (repairs + 1) + ")");
                send(BleIntegrity.wrap(lastHandshake));
            }
        }
    }
    /** Only transport setup may fall back. A PAKE/record rejection always closes the session. */
    @android.annotation.TargetApi(29)
    private boolean tryStream(BluetoothDevice device, BluetoothGattService service) throws Exception {
        BluetoothGattCharacteristic capability = service.getCharacteristic(BleGattServer.STREAM);
        if (capability == null || !gatt.readCharacteristic(capability)) return false;
        Object result = event(6000);
        if (!(result instanceof StreamOffer)) throw new IOException("Unexpected Bluetooth stream capability response");
        int psm;
        try { psm = BleStreamFraming.psm(((StreamOffer) result).value); }
        catch (IOException unsupported) { return false; }
        BluetoothSocket socket = null;
        try {
            socket = device.createInsecureL2capChannel(psm);
            streamSocket = socket;
            if (closed) throw new IOException("Bluetooth link closed");
            BleSocketChannel.connect(socket, 12000);
            if (closed) throw new IOException("Bluetooth link closed");
            stream = new BleSocketChannel(socket);
        } catch (IOException unavailable) {
            if (socket != null) try { socket.close(); } catch (IOException ignored) { }
            streamSocket = null;
            if (closed) throw unavailable;
            listener.status("Bluetooth stream unavailable · using GATT");
            return false;
        }
        // Retain the small GATT bootstrap connection until the whole session closes. On
        // vendor stacks disconnecting GATT may also tear down the LE ACL used by CoC.
        events.clear(); messages.clear(); incoming.clear();
        listener.status("Bluetooth data transport · LE CoC");
        return true;
    }
    public String address() { return connectedAddress; }
    public boolean usingNativeStream() { return stream != null; }
    public int catchUpLimit() { return stream != null ? 25 : payload < 60 ? 5 : 25; }
    public byte[] request(byte[] cleartext) throws Exception {
        send(secure.seal(cleartext));
        for (int attempt = 0; ; attempt++) {
            byte[] encrypted;
            try { encrypted = message(); }
            catch (FramingException | SocketTimeoutException damaged) {
                if (stream != null || attempt >= BleSecureSession.MAX_DAMAGED_IN_A_ROW) throw damaged;
                // The original answer may still be transmitting. Ignore its tail; the server
                // accepts this authenticated resend request even while that tail is in flight.
                incoming.awaitFreshMessage(); messages.clear();
                Log.w("OablBle", "Incomplete GATT answer, requesting resend (" + (attempt + 1) + ")", damaged);
                send(secure.seal(BleSecureSession.RESEND_REQUEST.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                continue;
            }
            try { return BleCompression.unpack(secure.open(encrypted)); }
            catch (BleSecureSession.RecordCorrupted damaged) {
                // The record arrived with changed bytes (the radio stack, not an attacker, is the usual
                // cause on long answers). Nothing was accepted; ask for the same sealed record again.
                BleDump.record("received", encrypted);
                Log.w("OablBle", "Damaged record, asking the head unit to send it again (" + (attempt + 1) + ")");
                if (attempt >= BleSecureSession.MAX_DAMAGED_IN_A_ROW) throw damaged;
                send(secure.seal(BleSecureSession.RESEND_REQUEST.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            catch (RuntimeException failed) { BleDump.record("received", encrypted); throw failed; }
            finally { Log.d("OablBleTrace", secure.trace() + (stream != null ? " · CoC" : " · GATT")); }
        }
    }
    public boolean awaitDisconnect(long milliseconds) throws InterruptedException { return gone.await(milliseconds, TimeUnit.MILLISECONDS); }
    private byte[] message() throws Exception {
        BleSocketChannel channel = stream;
        if (channel != null) {
            try { return channel.read(18000); }
            catch (IOException failure) { gone.countDown(); throw failure; }
        }
        Object value = messages.poll(stream == null ? 5000 : 18000, TimeUnit.MILLISECONDS);
        if (closed) throw new IOException("Bluetooth link closed");
        if (value instanceof Exception) throw (Exception) value;
        if (value == null) throw new SocketTimeoutException("Bluetooth GATT response timed out");
        if (!(value instanceof byte[])) throw new IOException("Unexpected Bluetooth message"); return (byte[]) value;
    }
    @SuppressWarnings("deprecation") private void send(byte[] message) throws Exception {
        BleSocketChannel channel = stream;
        if (channel != null) {
            try { channel.write(message, 18000); }
            catch (IOException failure) { gone.countDown(); throw failure; }
            return;
        }
        for (byte[] frame : BleFrames.split(message, ++frameId, payload)) {
            if (closed) throw new IOException("Bluetooth link closed");
            BluetoothGatt active = gatt;
            if (active == null) throw new IOException("Bluetooth link closed");
            boolean accepted;
            try {
                if (Build.VERSION.SDK_INT >= 33) accepted = active.writeCharacteristic(receive, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS;
                else { receive.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); receive.setValue(frame); accepted = active.writeCharacteristic(receive); }
            } catch (SecurityException revoked) { throw new IOException("Bluetooth permission revoked · allow nearby-device access", revoked); }
            if (!accepted) throw new IOException("Bluetooth write refused");
            expect(WRITTEN, 18000);
        }
    }
    @SuppressWarnings("deprecation") private void writeDescriptor(BluetoothGattDescriptor descriptor, byte[] value) throws IOException {
        boolean accepted;
        try {
            if (Build.VERSION.SDK_INT >= 33) accepted = gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS;
            else { descriptor.setValue(value); accepted = gatt.writeDescriptor(descriptor); }
        } catch (SecurityException revoked) { throw new IOException("Bluetooth permission revoked · allow nearby-device access", revoked); }
        if (!accepted) throw new IOException("Bluetooth subscription refused");
    }
    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt connection, int status, int state) {
            // The very first callback can race connectGatt's return/field assignment.
            if (closed || stream != null || (gatt != null && connection != gatt)) return;
            if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) post(CONNECTED);
            else { gone.countDown(); post(new IOException(secure.authenticated() ? "Bluetooth disconnected · " + status : "Bluetooth pairing/connection failed · check code and system pairing (" + status + ")")); }
        }
        @Override public void onServicesDiscovered(BluetoothGatt connection, int status) {
            if (closed || stream != null || connection != gatt) return;
            post(status == BluetoothGatt.GATT_SUCCESS ? SERVICES : new IOException("Bluetooth service discovery failed · " + status));
        }
        @Override public void onMtuChanged(BluetoothGatt connection, int mtu, int status) {
            if (closed || stream != null || connection != gatt) return;
            payload = status == 0 ? Math.min(512, Math.max(20, mtu - 3)) : 20;
            listener.status("Bluetooth MTU " + mtu + " · status " + status + " · value " + payload + " bytes");
            post(payload);
        }
        @Override public void onDescriptorWrite(BluetoothGatt connection, BluetoothGattDescriptor d, int status) {
            if (closed || stream != null || connection != gatt) return;
            post(status == 0 ? SUBSCRIBED : new IOException("Bluetooth subscription failed · confirm pairing and retry (" + status + ")"));
        }
        @Override public void onCharacteristicWrite(BluetoothGatt connection, BluetoothGattCharacteristic c, int status) {
            if (closed || stream != null || connection != gatt) return;
            post(status == 0 ? WRITTEN : new IOException("Bluetooth write failed · " + status));
        }
        @Override public void onCharacteristicRead(BluetoothGatt connection, BluetoothGattCharacteristic c, byte[] value, int status) {
            if (closed || stream != null || connection != gatt || !BleGattServer.STREAM.equals(c.getUuid())) return;
            post(new StreamOffer(status == 0 ? value : null));
        }
        @SuppressWarnings("deprecation") @Override public void onCharacteristicRead(BluetoothGatt connection, BluetoothGattCharacteristic c, int status) {
            if (Build.VERSION.SDK_INT < 33) onCharacteristicRead(connection, c, c.getValue(), status);
        }
        @Override public void onCharacteristicChanged(BluetoothGatt connection, BluetoothGattCharacteristic c, byte[] value) { fragment(connection, c, value); }
        @SuppressWarnings("deprecation") @Override public void onCharacteristicChanged(BluetoothGatt connection, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33) fragment(connection, c, c.getValue());
        }
        private synchronized void fragment(BluetoothGatt connection, BluetoothGattCharacteristic c, byte[] value) {
            if (closed || stream != null || connection != gatt || !TX.equals(c.getUuid())) return;
            try { byte[] message = incoming.accept(value, SystemClock.elapsedRealtime()); if (message != null) post(message); }
            catch (IllegalArgumentException e) { postMessageFailure(new FramingException(e)); }
        }
    };
    private void stopScan() {
        acceptingScan.set(false);
        if (scanner != null && scanning != null) try { scanner.stopScan(scanning); } catch (SecurityException ignored) { } catch (RuntimeException ignored) { }
        scanner = null; scanning = null;
    }
    @Override public void close() {
        closed = true; stopScan(); gone.countDown(); events.clear(); events.offer(new IOException("Bluetooth link closed"));
        messages.clear(); messages.offer(new IOException("Bluetooth link closed"));
        BleSocketChannel channel = stream; stream = null;
        if (channel != null) channel.close();
        BluetoothSocket socket = streamSocket; streamSocket = null;
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        BluetoothGatt old = gatt; gatt = null;
        if (old != null) try { old.disconnect(); old.close(); } catch (SecurityException ignored) { } catch (RuntimeException ignored) { }
    }
}
