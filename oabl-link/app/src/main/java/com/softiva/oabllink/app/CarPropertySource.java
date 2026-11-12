package com.softiva.oabllink.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;
import com.softiva.oabllink.PropertySource;

/**
 * Reads the car's vehicle properties through Dongfeng's {@code dfcar_service} over its Binder, as
 * verified against the DFRD_S31O_MPU_V03011 firmware. The reflection path on {@code df.car.Car}
 * threw NoSuchMethodException on this build, so this uses the transactions the car service exposes.
 *
 * <p><b>Read-only.</b> It uses only {@code getCarService} (transaction 12) and {@code getProperty}
 * (transaction 4). It never calls the set-property transaction (5), never writes a vehicle property,
 * injects a frame, runs a shell command or opens the network. {@link DongfengProbe} decodes whatever
 * reads back; a property the app has no permission for (odometer, gear) simply returns null.
 */
public final class CarPropertySource implements PropertySource {
    private static final String TAG = "OablLink";
    private static final String CAR_SERVICE = "dftc.car.ICar";
    private static final ComponentName CAR_COMPONENT = new ComponentName("com.dftc.car", "com.dftc.car.CarService");
    private static final int TXN_GET_CAR_SERVICE = 12;
    private static final int TXN_GET_PROPERTY = 4;
    private static final int GLOBAL_AREA = 0;

    private volatile IBinder property;
    private volatile String status = "connecting";

    public CarPropertySource(Context context) {
        Intent intent = new Intent(CAR_SERVICE).setComponent(CAR_COMPONENT);
        try {
            boolean bound = context.getApplicationContext().bindService(intent, new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder service) {
                    Parcel data = Parcel.obtain();
                    Parcel reply = Parcel.obtain();
                    try {
                        data.writeInterfaceToken("df.car.ICar");
                        data.writeString("property");
                        if (!service.transact(TXN_GET_CAR_SERVICE, data, reply, 0)) throw new IllegalStateException("getCarService unsupported");
                        reply.readException();
                        property = reply.readStrongBinder();
                        status = property == null ? "no property binder" : "connected";
                        Log.i(TAG, "car service: " + status);
                    } catch (Exception e) {
                        status = "car bind failed: " + e.getClass().getSimpleName();
                        Log.w(TAG, "bind failed", e);
                    } finally {
                        reply.recycle();
                        data.recycle();
                    }
                }
                @Override public void onServiceDisconnected(ComponentName name) { property = null; status = "disconnected"; }
            }, Context.BIND_AUTO_CREATE);
            if (!bound) status = "bindService returned false";
        } catch (RuntimeException e) {
            status = "no car service: " + e.getClass().getSimpleName();
        }
    }

    public String status() { return status; }

    public boolean connected() { return property != null; }

    @Override public boolean supported(int propertyId) { return read(propertyId) != null; }

    @Override public Integer getInt(int propertyId) {
        Object value = read(propertyId);
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    @Override public Float getFloat(int propertyId) {
        Object value = read(propertyId);
        return value instanceof Number ? ((Number) value).floatValue() : null;
    }

    /** One {@code getProperty} (transaction 4): the only call this class makes besides binding. */
    private Object read(int propertyId) {
        IBinder binder = property;
        if (binder == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("df.car.hardware.property.ICarProperty");
            data.writeInt(propertyId);
            data.writeInt(GLOBAL_AREA);
            if (!binder.transact(TXN_GET_PROPERTY, data, reply, 0)) return null;
            reply.readException();
            if (reply.readInt() == 0) return null; // no CarPropertyValue (no permission, or unavailable)
            int returnedId = reply.readInt();
            int area = reply.readInt();
            int valueStatus = reply.readInt();
            reply.readLong(); // timestamp
            String type = reply.readString();
            if (returnedId != propertyId || area != GLOBAL_AREA || valueStatus != 0) return null;
            if (!"java.lang.Integer".equals(type) && !"java.lang.Float".equals(type)) return null;
            return reply.readValue(getClass().getClassLoader());
        } catch (Exception e) {
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
