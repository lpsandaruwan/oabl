package com.softiva.oabl;

import static org.junit.Assert.*;

import org.junit.Test;

public class SoundEffectsTest {
    private static final int RATE = 48000;

    /** Two seconds of a stereo sine through the exciter; returns the left channel. */
    private static float[] run(SoundEffects.Settings settings, double hz, double amplitude) {
        SoundEffects.Chain chain = new SoundEffects.Chain(2, RATE);
        chain.configure(settings);
        float[] out = new float[RATE * 2];
        float[] frame = new float[2];
        for (int i = 0; i < out.length; i++) {
            frame[0] = frame[1] = (float) (amplitude * Math.sin(2 * Math.PI * hz * i / RATE));
            chain.process(frame, true);
            out[i] = frame[0];
        }
        return out;
    }

    /** Amplitude of one frequency over the last second (Goertzel), after the filters have settled. */
    private static double level(float[] x, double hz) {
        int start = x.length / 2, n = x.length - start;
        double w = 2 * Math.PI * hz / RATE, k = 2 * Math.cos(w), s1 = 0, s2 = 0;
        for (int i = start; i < x.length; i++) {
            double s0 = x[i] + k * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        return 2 * Math.sqrt(s1 * s1 + s2 * s2 - k * s1 * s2) / n;
    }

    @Test public void offPassesTheSoundUntouched() {
        assertFalse(SoundEffects.Settings.OFF.active());
        assertEquals(1f, SoundEffects.Settings.OFF.preGain(), 0f);
        assertEquals(.5, level(run(SoundEffects.Settings.OFF, 1000, .5), 1000), .002);
    }

    /** The first waveshaper (Chebyshev T3) turned into an inverted copy on real music and cancelled the top. */
    @Test public void theExciterAddsHarmonicsWithoutCancellingTheTrebleItStartsFrom() {
        SoundEffects.Settings s = new SoundEffects.Settings(1, 1500);
        float[] out = run(s, 4000, .5);
        double dry = .5 * s.preGain();
        assertTrue("4 kHz kept: " + level(out, 4000) + " vs " + dry, level(out, 4000) > dry * .9);
        assertTrue("12 kHz added: " + level(out, 12000), level(out, 12000) > dry * .1);
        assertTrue("20 kHz added: " + level(out, 20000), level(out, 20000) > dry * .01);
    }

    /** The harmonics are air: nothing is added below 10 kHz, so a 2 kHz note's overtones at 4, 6 and 8 kHz are left out. */
    @Test public void theExciterAddsOnlyWhatLandsAtTenKilohertzAndAbove() {
        SoundEffects.Settings s = new SoundEffects.Settings(1, 1500);
        float[] out = run(s, 2000, .5);
        double dry = .5 * s.preGain();
        assertTrue("2 kHz kept: " + level(out, 2000), level(out, 2000) > dry * .95);
        for (double below : new double[]{4000, 6000, 8000}) {
            assertTrue(below + " Hz not added: " + level(out, below), level(out, below) < dry * .05);
        }
        // 2 kHz makes overtones at 10, 14, 18 kHz (odd) and 12, 16, 20 kHz (even): the air is there.
        double air = level(out, 10000) + level(out, 12000) + level(out, 14000) + level(out, 16000);
        assertTrue("air " + air, air > dry * .08);
    }

    /** Squaring content above a quarter of the sample rate folds its 2nd harmonic back down as off-pitch fizz. */
    @Test public void theExciterDoesNotAlias() {
        int rate = 44100;
        SoundEffects.Settings s = new SoundEffects.Settings(1, 2500);
        SoundEffects.Chain chain = new SoundEffects.Chain(2, rate);
        chain.configure(s);
        float[] out = new float[rate * 2];
        float[] frame = new float[2];
        for (int i = 0; i < out.length; i++) {
            frame[0] = frame[1] = (float) (.5 * Math.sin(2 * Math.PI * 15000 * i / rate));
            chain.process(frame, true);
            out[i] = frame[0];
        }
        double alias = levelAt(out, 44100 - 30000, rate), dry = .5 * s.preGain();
        assertTrue("alias at 14.1 kHz: " + alias, alias < dry * .02);
    }

    /** Two notes must not leave a difference tone in the mids (2 + 3 kHz would give 1 kHz). */
    @Test public void theExciterAddsNoDifferenceTonesBelowItsBand() {
        SoundEffects.Settings s = new SoundEffects.Settings(1, 1500);
        SoundEffects.Chain chain = new SoundEffects.Chain(2, RATE);
        chain.configure(s);
        float[] out = new float[RATE * 2];
        float[] frame = new float[2];
        for (int i = 0; i < out.length; i++) {
            frame[0] = frame[1] = (float) (.3 * Math.sin(2 * Math.PI * 2000 * i / RATE) + .3 * Math.sin(2 * Math.PI * 3000 * i / RATE));
            chain.process(frame, true);
            out[i] = frame[0];
        }
        double dry = .3 * s.preGain();
        assertTrue("1 kHz difference tone: " + level(out, 1000), level(out, 1000) < dry * .02);
        assertTrue("5 kHz sum tone not added: " + level(out, 5000), level(out, 5000) < dry * .05);
        double air = level(out, 11000) + level(out, 13000) + level(out, 12000) + level(out, 14000) + level(out, 15000);
        assertTrue("air from the pair " + air, air > dry * .05);
    }

    private static double levelAt(float[] x, double hz, int rate) {
        int start = x.length / 2, n = x.length - start;
        double w = 2 * Math.PI * hz / rate, k = 2 * Math.cos(w), s1 = 0, s2 = 0;
        for (int i = start; i < x.length; i++) {
            double s0 = x[i] + k * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        return 2 * Math.sqrt(s1 * s1 + s2 * s2 - k * s1 * s2) / n;
    }

    @Test public void theExciterLeavesTheMidsAndBassAlone() {
        SoundEffects.Settings s = new SoundEffects.Settings(1, 5000);
        float[] out = run(s, 500, .5);
        assertEquals(.5 * s.preGain(), level(out, 500), .01);
        assertTrue(level(out, 1000) < .002);
        assertEquals(.5 * s.preGain(), level(run(s, 60, .5), 60), .01);
    }

    @Test public void nothingGoesPastFullScaleWhateverTheInput() {
        SoundEffects.Chain chain = new SoundEffects.Chain(2, RATE);
        chain.configure(new SoundEffects.Settings(1, 2500));
        float[] frame = new float[2];
        for (int i = 0; i < RATE; i++) {
            float square = (i / 7) % 2 == 0 ? 1f : -1f;
            frame[0] = square;
            frame[1] = -square;
            chain.process(frame, true);
            assertTrue(Math.abs(frame[0]) <= 1f && Math.abs(frame[1]) <= 1f);
        }
    }

    @Test public void moreExciterMeansMoreHeadroom() {
        assertTrue(new SoundEffects.Settings(.4f, 3500).preGain() < new SoundEffects.Settings(.2f, 3500).preGain());
        assertEquals(1f / (1f + SoundEffects.EXCITER_PEAK[2] * .2f), new SoundEffects.Settings(.2f, 3500).preGain(), 1e-6f);
    }

    @Test public void amountAndFrequencySnapToWhatIsOffered() {
        SoundEffects.Settings s = new SoundEffects.Settings(2, 4400);
        assertEquals(1f, s.exciter, 0f);
        assertEquals(5000f, s.exciterHz, 0f);
        assertEquals(new SoundEffects.Settings(1, 5000), s);
        assertEquals(0f, new SoundEffects.Settings(Float.NaN, 3500).exciter, 0f);
    }

    @Test public void bassLiftsItsBandByWhatTheSliderSays() {
        SoundEffects.Settings s = new SoundEffects.Settings(0, 2500, 6);
        double in = .05, dry = in * s.preGain();
        assertEquals(dry * Math.pow(10, 6 * SoundEffects.BASS_BELL / 20.0), level(run(s, 80, in), 80), dry * .03);
    }

    @Test public void bassLeavesTheMidsAndTrebleAlone() {
        SoundEffects.Settings s = new SoundEffects.Settings(0, 2500, 10);
        double dry = .05 * s.preGain();
        assertEquals(dry, level(run(s, 3000, .05), 3000), dry * .02);
        assertTrue("little below the speakers' range", level(run(s, 25, .05), 25) < dry * 1.5);
    }

    @Test public void onlyPartOfTheBassBoostComesOffTheSong() {
        assertEquals(Math.pow(10, -4 / 20.0), new SoundEffects.Settings(0, 2500, 10).preGain(), 1e-4);
    }

    @Test public void bassEasesOffRatherThanClipping() {
        float[] out = run(new SoundEffects.Settings(0, 2500, 10), 80, 1);
        float peak = 0;
        for (int i = out.length / 2; i < out.length; i++) peak = Math.max(peak, Math.abs(out[i]));
        assertTrue("under the limiter's knee: " + peak, peak <= SoundEffects.KNEE + 1e-3f);
    }

    @Test public void bassComesBackAfterAPeak() {
        SoundEffects.Chain chain = new SoundEffects.Chain(2, RATE);
        SoundEffects.Settings s = new SoundEffects.Settings(0, 2500, 10);
        chain.configure(s);
        float[] frame = new float[2], tail = new float[RATE * 2];
        for (int i = 0; i < RATE; i++) {
            frame[0] = frame[1] = (float) Math.sin(2 * Math.PI * 80 * i / RATE);
            chain.process(frame, true);
        }
        double in = .05, dry = in * s.preGain();
        for (int i = 0; i < tail.length; i++) {
            frame[0] = frame[1] = (float) (in * Math.sin(2 * Math.PI * 80 * i / RATE));
            chain.process(frame, true);
            tail[i] = frame[0];
        }
        assertEquals(dry * Math.pow(10, 10 * SoundEffects.BASS_BELL / 20.0), level(tail, 80), dry * .1);
    }

    @Test public void bassSettingsAreClampedAndCompared() {
        assertEquals(SoundEffects.BASS_MAX_DB, new SoundEffects.Settings(0, 2500, 99).bassDb, 0f);
        assertEquals(0f, new SoundEffects.Settings(0, 2500, Float.NaN).bassDb, 0f);
        assertTrue(new SoundEffects.Settings(0, 2500, 3).active());
        assertNotEquals(new SoundEffects.Settings(.3f, 2500, 3), new SoundEffects.Settings(.3f, 2500, 4));
    }

    @Test public void theMeterSeesTheMusicLinedUpWithTheOutput() {
        SoundEffects.Chain chain = new SoundEffects.Chain(2, RATE);
        chain.configure(new SoundEffects.Settings(.0f, 2500, 0));
        float[] frame = new float[2];
        for (int i = 0; i < RATE / 10; i++) {
            frame[0] = frame[1] = (float) (.3 * Math.sin(2 * Math.PI * 1000 * i / RATE));
            chain.process(frame, false);
            assertEquals(chain.music(), frame[0], 1e-6);
        }
    }

    @Test public void bassAddsOvertonesOfNotesTooLowForSmallSpeakers() {
        SoundEffects.Settings s = new SoundEffects.Settings(0, 2500, 10);
        float[] out = run(s, 55, .1);
        double dry = .1 * s.preGain();
        assertTrue("110 Hz: " + level(out, 110), level(out, 110) > dry * .15);
        assertTrue("165 Hz: " + level(out, 165), level(out, 165) > dry * .15);
        assertTrue("220 Hz: " + level(out, 220), level(out, 220) > dry * .08);
        assertTrue("no buzz at 2.2 kHz", level(out, 2200) < dry * .01);
    }

    @Test public void bassLeavesTheMidsWithoutOvertonesOfTheirOwn() {
        SoundEffects.Settings s = new SoundEffects.Settings(0, 2500, 10);
        float[] out = run(s, 1000, .1);
        assertTrue(level(out, 2000) < .1 * s.preGain() * .002);
    }

    @Test public void headroomOffTurnsNothingDown() {
        assertEquals(1f, new SoundEffects.Settings(1, 2500, 10, false).preGain(), 0f);
        assertNotEquals(new SoundEffects.Settings(1, 2500, 10, true), new SoundEffects.Settings(1, 2500, 10, false));
    }

    @Test public void compareOriginalIsAlignedWithTheProcessedLookAhead() {
        int delay = RATE / 200;
        SoundEffects.Match match = new SoundEffects.Match(RATE, .005);
        float[] dry = new float[2], wet = new float[2], out = new float[delay + 2];
        for (int i = 0; i < out.length; i++) {
            dry[0] = dry[1] = i == 0 ? 1 : 0;
            wet[0] = wet[1] = i == delay ? 1 : 0;
            match.frame(dry, wet, true);
            out[i] = wet[0];
        }
        assertEquals(0, out[0], 0);
        assertTrue(out[delay] > .5f);
    }

    // ---- Even out ----

    private static double rms(float[] x, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) sum += (double) x[i] * x[i];
        return Math.sqrt(sum / (to - from));
    }

    private static double db(double ratio) { return 20 * Math.log10(ratio); }

    /** A quiet passage (first 4 s) then a loud one (next 4 s), both stereo 220 Hz sines. */
    private static float[] quietThenLoud(SoundEffects.Settings settings, double quiet, double loud) {
        SoundEffects.Chain chain = new SoundEffects.Chain(2, RATE);
        chain.configure(settings);
        float[] out = new float[RATE * 8];
        float[] frame = new float[2];
        for (int i = 0; i < out.length; i++) {
            double a = i < RATE * 4 ? quiet : loud;
            frame[0] = frame[1] = (float) (a * Math.sin(2 * Math.PI * 220 * i / RATE));
            chain.process(frame, true);
            out[i] = frame[0];
        }
        return out;
    }

    @Test public void evenOutOffChangesNothing() {
        float[] flat = quietThenLoud(new SoundEffects.Settings(0, 3500, 0, false, 0), .03, .5);
        double quiet = db(rms(flat, RATE * 2, RATE * 4) / (.03 / Math.sqrt(2)));
        assertEquals(0, quiet, 0.05);
    }

    @Test public void evenOutLiftsAQuietPassageAndEasesALoudOne() {
        float[] out = quietThenLoud(new SoundEffects.Settings(0, 3500, 0, false, .7f), .03, .5);
        double lift = db(rms(out, RATE * 3, RATE * 4) / (.03 / Math.sqrt(2)));
        double drop = db(rms(out, RATE * 7, RATE * 8) / (.5 / Math.sqrt(2)));
        assertTrue("quiet part lifted " + lift, lift > 3);
        assertTrue("loud part eased " + drop, drop < -1);
        // The gap between the two shrinks, never reverses.
        double before = db(.5 / .03), after = db(rms(out, RATE * 7, RATE * 8) / rms(out, RATE * 3, RATE * 4));
        assertTrue("gap " + before + " -> " + after, after > 3 && after < before - 3);
    }

    @Test public void evenOutKeepsItsLimitsAndFadesOutNearSilence() {
        float s = .7f;
        double limit = SoundEffects.EVEN_LIMIT_DB * s;
        assertEquals(0, SoundEffects.evenGainDb(SoundEffects.EVEN_TARGET_DB, s), 1e-6);   // at the target: nothing
        assertEquals((SoundEffects.EVEN_TARGET_DB + 17) * SoundEffects.EVEN_UP * s, SoundEffects.evenGainDb(-17, s), 1e-4); // quiet: up, by its share
        assertEquals(limit, SoundEffects.evenGainDb(-40, s), 1e-4);                        // far below: the limit
        assertEquals((SoundEffects.EVEN_TARGET_DB + 5) * SoundEffects.EVEN_DOWN * s, SoundEffects.evenGainDb(-5, s), 1e-4); // loud: down, less
        assertTrue(Math.abs(SoundEffects.evenGainDb(-9, s)) < SoundEffects.evenGainDb(-17, s));       // easing is gentler than lifting
        assertEquals(0, SoundEffects.evenGainDb(-60, 1f), 1e-9);                           // near silence: nothing
        assertEquals(0, SoundEffects.evenGainDb(-80, 1f), 1e-9);
        assertEquals(0, SoundEffects.evenGainDb(-30, 0f), 1e-9);                           // off
    }

    @Test public void evenOutStaysInsideFullScaleAndKeepsTheStereoImage() {
        SoundEffects.Chain chain = new SoundEffects.Chain(2, RATE);
        chain.configure(new SoundEffects.Settings(0, 3500, 0, false, 1f));
        float[] frame = new float[2];
        double peak = 0, left = 0, right = 0;
        for (int i = 0; i < RATE * 3; i++) {
            double a = i < RATE ? .02 : .95;
            frame[0] = (float) (a * Math.sin(2 * Math.PI * 330 * i / RATE));
            frame[1] = frame[0] * .5f;
            chain.process(frame, true);
            peak = Math.max(peak, Math.max(Math.abs(frame[0]), Math.abs(frame[1])));
            if (i > RATE * 2) { left += frame[0] * frame[0]; right += frame[1] * frame[1]; }
        }
        assertTrue("peak " + peak, peak <= 1.0);
        assertEquals("one gain for both channels", .5, Math.sqrt(right / left), 0.01);
    }

    @Test public void evenOutIsPartOfTheSettings() {
        SoundEffects.Settings on = new SoundEffects.Settings(0, 3500, 0, true, .35f);
        assertTrue(on.active());
        assertFalse(on.equals(new SoundEffects.Settings(0, 3500, 0, true, 0)));
        assertFalse(new SoundEffects.Settings(0, 3500, 0, true, 0).active());
    }
}
