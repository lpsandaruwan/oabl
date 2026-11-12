package com.softiva.oabllink.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import com.softiva.oabllink.SimulatedCar;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Test APK only: choose an existing SimulatedCar phase without altering the production APK. */
public final class SimulationScenarioRunner extends Instrumentation {
    private int elapsed;
    private int duration;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        elapsed = Integer.parseInt(args == null ? "0" : args.getString("elapsed_seconds", "0"));
        duration = Integer.parseInt(args == null ? "1800" : args.getString("duration_seconds", "1800"));
        start();
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!"ranchu".equals(Build.HARDWARE) && !"goldfish".equals(Build.HARDWARE))
                throw new IllegalStateException("REFUSED: scenario control is emulator-only");
            if (elapsed < 0 || elapsed > 1380 || duration < 1 || duration > 43200) throw new IllegalArgumentException("Invalid scenario time/duration");
            runOnMainSync(() -> getTargetContext().startForegroundService(new Intent(getTargetContext(), LinkService.class)));
            AtomicReference<LinkService> service = new AtomicReference<>();
            long end = SystemClock.elapsedRealtime() + 10000;
            while (service.get() == null && SystemClock.elapsedRealtime() < end) {
                runOnMainSync(() -> {
                    try {
                        Class<?> threadClass = Class.forName("android.app.ActivityThread");
                        Object thread = threadClass.getDeclaredMethod("currentActivityThread").invoke(null);
                        for (Object value : ((Map<?, ?>) field(thread, "mServices")).values())
                            if (value instanceof LinkService) service.set((LinkService) value);
                    } catch (Exception failure) { throw new RuntimeException(failure); }
                });
                if (service.get() == null) SystemClock.sleep(50);
            }
            if (service.get() == null) throw new IllegalStateException("LinkService did not start");
            Object probe = field(service.get(), "probe");
            if (!(probe instanceof SimulatedCar)) throw new IllegalStateException("REFUSED: probe is not simulated");
            Handler samples = (Handler) field(service.get(), "sampleHandler");
            java.io.File control = new java.io.File(getTargetContext().getFilesDir(), "simulation-phase-seconds");
            try (java.io.FileWriter writer = new java.io.FileWriter(control)) { writer.write(Integer.toString(elapsed)); }
            String applied = "";
            long until = SystemClock.elapsedRealtime() + duration * 1000L;
            while (SystemClock.elapsedRealtime() < until) {
                String value = new String(java.nio.file.Files.readAllBytes(control.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
                String[] parts=value.split("\\s+");int phase = Integer.parseInt(parts[0]);
                boolean hold=parts.length>1&&parts[1].equals("hold");
                if (phase < 0 || phase > 1380) throw new IllegalArgumentException("Phase must be 0..1380");
                if (!value.equals(applied) || hold) {
                    CountDownLatch changed = new CountDownLatch(1);
                    AtomicReference<Throwable> error = new AtomicReference<>();
                    samples.post(() -> {
                        try {
                            Field start = SimulatedCar.class.getDeclaredField("startMs");
                            start.setAccessible(true);
                            start.setLong(probe, System.currentTimeMillis() - phase * 1000L);
                        } catch (Throwable failure) { error.set(failure); }
                        finally { changed.countDown(); }
                    });
                    if (!changed.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Sample worker timed out");
                    if (error.get() != null) throw new RuntimeException(error.get());
                    if(!value.equals(applied)) {
                        applied = value;
                        SystemClock.sleep(1500);
                        Bundle status = new Bundle();
                        status.putString("stream", "READY emulator phase " + phase + "s"+(hold?" held":"")+"; production service/protocol; simulated input only\n");
                        sendStatus(0, status);
                    }
                }
                SystemClock.sleep(250);
            }
            result.putString("stream", "PASS scenario controller completed\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL " + android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
