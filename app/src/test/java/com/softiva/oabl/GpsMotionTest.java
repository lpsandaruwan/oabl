package com.softiva.oabl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** GPS states with hysteresis (energy-model.md §3) and the idle periods they drive. */
public class GpsMotionTest {
    private static final double LAT = 6.9, LON = 79.86;
    private static final double METERS_PER_DEGREE_LON = 111_320 * Math.cos(Math.toRadians(LAT));

    /** Simulated car: one fix and one tick per second. */
    private static final class Car implements GpsMotion.Listener {
        final GpsMotion motion = new GpsMotion(this);
        final List<long[]> events = new ArrayList<>(); // {1 = start / 0 = end, wall}
        long second;
        double east; // metres from the start

        @Override public void standstillStarted(long since) { events.add(new long[]{1, since}); }
        @Override public void standstillEnded(long at) { events.add(new long[]{0, at}); }

        /** Drive or stand for some seconds; {@code jitter} adds GPS noise to speed and position. */
        void run(int seconds, double kph, double jitterKph, double jitterMeters, boolean accepted) {
            for (int i = 0; i < seconds; i++) {
                second++;
                double step = kph / 3.6;
                east += step;
                double noise = jitterMeters * Math.sin(second * 1.7);
                float speed = (float) Math.max(0, kph + jitterKph * Math.sin(second * 2.3));
                motion.fix(second * 1000, second * 1000, 8, speed, LAT,
                        LON + (east + noise) / METERS_PER_DEGREE_LON, accepted ? step : 0);
                motion.tick(second * 1000, second * 1000);
            }
        }

        /** No fixes at all (tunnel, or a box on the bench indoors). */
        void lost(int seconds, double kph) {
            for (int i = 0; i < seconds; i++) {
                second++;
                east += kph / 3.6;
                motion.tick(second * 1000, second * 1000);
            }
        }
    }

    @Test public void jitterAtATrafficLightIsOneStop() {
        Car car = new Car();
        car.run(60, 50, 0, 0, true);
        car.run(30, 0, 2.5, 3, false); // stopping: GPS speed wobbles under 3 km/h
        car.run(60, 0, 5.5, 5, false); // stopped: wobbling up to 5.5 km/h and ±5 m doesn't leave
        car.run(60, 40, 0, 0, true);
        assertEquals(2, car.events.size());
        assertEquals(1, car.events.get(0)[0]);
        assertEquals(0, car.events.get(1)[0]);
        // The standstill began when the car slowed, not 20 s later.
        assertEquals(61_000, car.events.get(0)[1]);
        assertEquals(90, car.motion.standstillSeconds, 6);
        assertEquals(120, car.motion.movingSeconds, 6);
        assertEquals(0, car.motion.unknownSeconds, 1e-9);
    }

    @Test public void briefSlowdownIsNotAStandstill() {
        Car car = new Car();
        car.run(30, 50, 0, 0, true);
        car.run(15, 0, 0, 0, false);
        car.run(30, 50, 0, 0, true);
        assertTrue(car.events.isEmpty());
        assertEquals(0, car.motion.standstillSeconds, 1e-9);
    }

    @Test public void leavesStandstillOnlyAfterSustainedSpeed() {
        Car car = new Car();
        car.run(30, 0, 0, 0, false);
        assertEquals(GpsMotion.State.STANDSTILL, car.motion.state());
        car.run(3, 10, 0, 0, false); // 3 s at 10 km/h: 8 m, not yet
        assertEquals(GpsMotion.State.STANDSTILL, car.motion.state());
        car.run(3, 10, 0, 0, false);
        assertEquals(GpsMotion.State.MOVING, car.motion.state());
    }

    @Test public void tunnelIsUnknownNeverStandstillAndPoorGpsWhenNotAccepted() {
        Car car = new Car();
        car.run(60, 60, 0, 0, true);
        car.lost(30, 60); // 500 m in a tunnel
        assertEquals(GpsMotion.State.UNKNOWN, car.motion.state());
        car.run(5, 60, 0, 0, true);
        assertEquals(25, car.motion.unknownSeconds, 1.5); // after the 5 s timeout
        assertTrue(car.events.isEmpty());
        assertEquals(500, car.motion.poorMeters, 20);
        assertEquals(1000 + 5 * 60 / 3.6, car.motion.meters, 1); // only what TripComputer accepted
    }

    @Test public void benchUseWithoutFixesAccruesNothing() {
        Car car = new Car();
        car.lost(600, 0);
        assertTrue(car.events.isEmpty());
        assertEquals(0, car.motion.standstillSeconds, 1e-9);
        assertEquals(0, car.motion.movingSeconds, 1e-9);
        assertEquals(599, car.motion.unknownSeconds, 1);
    }

    @Test public void gpsLostDuringStandstillEndsIdle() {
        Car car = new Car();
        car.run(40, 0, 0, 0, false);
        car.lost(60, 0);
        assertEquals(2, car.events.size());
        assertEquals(0, car.events.get(1)[0]);
        assertEquals(45_000, car.events.get(1)[1]); // last fix + 5 s
        // Back at the same spot: standstill again at once, not after another 20 s.
        car.run(10, 0, 0, 0, false);
        assertEquals(3, car.events.size());
        assertEquals(GpsMotion.State.STANDSTILL, car.motion.state());
    }

    @Test public void inaccurateFixesAreUnknown() {
        Car car = new Car();
        for (int i = 1; i <= 30; i++) {
            car.motion.fix(i * 1000, i * 1000, 45, 0, LAT, LON, 0);
            car.motion.tick(i * 1000, i * 1000);
        }
        assertEquals(GpsMotion.State.UNKNOWN, car.motion.state());
        assertTrue(car.events.isEmpty());
    }

    @Test public void haversine() {
        assertEquals(111_195, GpsMotion.distance(0, 0, 1, 0), 5);
    }
}
