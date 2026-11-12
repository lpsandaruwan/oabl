package com.softiva.oabllink.bluetooth;

import android.bluetooth.BluetoothSocket;
import com.softiva.oabllink.BleStreamFraming;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** BluetoothSocket has no public read timeout. Closing it aborts blocked connect/read/write calls. */
final class BleSocketChannel implements Closeable {
    private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "oabl-ble-timeout"); thread.setDaemon(true); return thread;
    });
    static { TIMER.setRemoveOnCancelPolicy(true); }
    private final BluetoothSocket socket;
    private final InputStream input;
    private final OutputStream output;
    private interface Work<T> { T run() throws IOException; }

    BleSocketChannel(BluetoothSocket socket) throws IOException {
        this.socket = socket; input = socket.getInputStream(); output = socket.getOutputStream();
    }
    static void connect(BluetoothSocket socket, long timeout) throws IOException {
        timed(socket, timeout, () -> { socket.connect(); return null; });
    }
    byte[] read(long timeout) throws IOException {
        return timed(socket, timeout, () -> BleStreamFraming.read(input));
    }
    void write(byte[] message, long timeout) throws IOException {
        timed(socket, timeout, () -> { BleStreamFraming.write(output, message); return null; });
    }
    private static <T> T timed(BluetoothSocket socket, long timeout, Work<T> work) throws IOException {
        if (timeout <= 0) { close(socket); throw new SocketTimeoutException("Bluetooth stream timed out"); }
        // A canceled timer may already be running. It must not close a socket after this
        // operation completed and the owner began the next request on the same channel.
        AtomicInteger state = new AtomicInteger(); // 0 pending, 1 completed, 2 expired
        ScheduledFuture<?> deadline = TIMER.schedule(() -> {
            if (state.compareAndSet(0, 2)) close(socket);
        }, timeout, TimeUnit.MILLISECONDS);
        try {
            T value = work.run();
            if (!state.compareAndSet(0, 1)) throw new SocketTimeoutException("Bluetooth stream timed out");
            return value;
        } finally { state.compareAndSet(0, 1); deadline.cancel(false); }
    }
    private static void close(BluetoothSocket socket) { try { socket.close(); } catch (IOException ignored) { } }
    @Override public void close() { close(socket); }
}
