package com.softiva.oabl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DitherTest {
    private static final int RATE = 48000;

    /** Magnitude of one frequency in a signal (Goertzel by direct correlation), in the signal's own units. */
    private static double component(double[] x, double hz) {
        double s = 0, c = 0;
        for (int i = 0; i < x.length; i++) {
            double w = 2 * Math.PI * hz * i / RATE;
            s += x[i] * Math.sin(w); c += x[i] * Math.cos(w);
        }
        return 2 * Math.hypot(s, c) / x.length;
    }

    private static double db(double ratio) { return 20 * Math.log10(ratio); }

    /** A sine of the given size in LSB through the ditherer; returns the output in LSB. */
    private static double[] run(double lsb, double hz, boolean on, int seconds) {
        Dither dither = new Dither(1, RATE);
        double[] out = new double[RATE * seconds];
        for (int i = 0; i < out.length; i++) {
            double v = lsb * Math.sin(2 * Math.PI * hz * i / RATE);
            out[i] = dither.next(0, (float) (v / 32768.0), on);
        }
        return out;
    }

    @Test public void aSignalBelowOneStepVanishesWithoutDitherAndSurvivesWithIt() {
        assertEquals(0, component(run(.3, 1000, false, 1), 1000), 1e-9);
        double kept = component(run(.3, 1000, true, 2), 1000);
        assertEquals("1 kHz kept at .3 LSB", .3, kept, .06);
    }

    @Test public void theNoiseMovesOutOfTheEarsBestRangeIntoTheHighTreble() {
        // Music far enough above silence to keep the dither on: a 2 LSB 200 Hz tone; read the error.
        Dither dither = new Dither(1, RATE);
        double[] error = new double[RATE * 2];
        for (int i = 0; i < error.length; i++) {
            double v = 2 * Math.sin(2 * Math.PI * 200 * i / RATE);
            error[i] = dither.next(0, (float) (v / 32768.0), true) - v;
        }
        double mid = (component(error, 2500) + component(error, 3500) + component(error, 4500)) / 3;
        double top = (component(error, 18000) + component(error, 19000) + component(error, 20000)) / 3;
        assertTrue("noise at 3.5 kHz " + db(mid) + " dB vs 19 kHz " + db(top) + " dB", db(top / mid) > 25);
    }

    @Test public void plainRoundingIsUnchangedWhenDitherIsOff() {
        Dither dither = new Dither(2, RATE);
        assertEquals(0, dither.next(0, 0f, false));
        assertEquals(16384, dither.next(1, .5f, false));
        assertEquals(32767, dither.next(0, 1f, false));
        assertEquals(-32768, dither.next(0, -1f, false));
    }

    @Test public void silenceStaysSilentAfterFiftyMilliseconds() {
        Dither dither = new Dither(1, RATE);
        short last = 1;
        for (int i = 0; i < RATE / 4; i++) last = dither.next(0, 0f, true);
        assertEquals(0, last);
        // A real signal brings it straight back.
        double sum = 0;
        for (int i = 0; i < 4800; i++) sum += Math.abs(dither.next(0, (float) (3 * Math.sin(i * .05) / 32768.0), true));
        assertTrue(sum > 0);
    }

    @Test public void fullScaleAndClippedInputStayBounded() {
        Dither dither = new Dither(1, RATE);
        for (int i = 0; i < RATE; i++) {
            float x = (i / 3000) % 2 == 0 ? 1.5f : -1.5f;     // far over full scale
            short q = dither.next(0, x, true);
            assertTrue(q >= -32768 && q <= 32767);
        }
        // After that it still works normally.
        double[] out = run(1000, 440, true, 1);
        assertEquals(1000, component(out, 440), 10);
    }
}
