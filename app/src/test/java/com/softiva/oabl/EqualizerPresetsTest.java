package com.softiva.oabl;

import static org.junit.Assert.*;

import org.junit.Test;

public class EqualizerPresetsTest {
    /** The presets as they were on the 14 bands (0.1.109), which the 31-band ones must still sound like. */
    private static final float[][] FOURTEEN = {
            new float[14],
            {1, 3.5f, 1, .5f, -1, -1.5f, -2, -2, -.5f, .5f, 1.5f, 1, 1.5f, 1.5f},
            {1.5f, 4, .5f, 0, -1.5f, -2, -2.5f, -2, -.5f, .5f, 1, 1, 1.5f, 1.5f},
            {.5f, 3.5f, 0, -.5f, -2, -2.5f, -2.5f, -2, -1, -.5f, -1, -1, 0, .5f},
            {-.5f, .5f, 0, .5f, -1, -1.5f, -2, -1.5f, 0, 1.5f, 1.5f, .5f, 0, 0},
            {1, 2.5f, 1, .5f, 0, -1, -1, -1, -.5f, -1, -1.5f, -2, -3, -3}
    };
    private static final int BANDS = EqualizerProfile.FREQUENCIES.length;
    private static final int TOP_BIN = (int) (20000 * EqualizerProfile.FFT_SIZE / EqualizerProfile.MIX_RATE);

    private static int band(float hz) {
        for (int i = 0; i < BANDS; i++) if (EqualizerProfile.FREQUENCIES[i] == hz) return i;
        throw new AssertionError("no band at " + hz);
    }

    private static float at(float[] curve, float hz) { return curve[band(hz)]; }

    @Test public void thirtyOneThirdOctaveBandsWithAShortNameEach() {
        assertEquals(31, BANDS);
        assertEquals(BANDS, EqualizerProfile.BAND_ROLES.length);
        assertEquals(20f, EqualizerProfile.FREQUENCIES[0], 0f);
        assertEquals(20000f, EqualizerProfile.FREQUENCIES[BANDS - 1], 0f);
        for (int i = 1; i < BANDS; i++) assertTrue(EqualizerProfile.FREQUENCIES[i] > EqualizerProfile.FREQUENCIES[i - 1]);
    }

    @Test public void everyPresetHasACurveOnTheThirtyOneBandsAndItsEffects() {
        assertEquals("Flat", EqualizerProfile.PRESETS[0]);
        for (int i = 0; i < EqualizerProfile.PRESETS.length; i++) {
            float[] curve = EqualizerProfile.presetCurve(i);
            assertEquals(BANDS, curve.length);
            for (float gain : curve) {
                assertTrue(Math.abs(gain) <= EqualizerProfile.STYLE_DB);
                assertEquals("0.5 dB steps", 0f, gain * 2 - Math.round(gain * 2), 0f);
            }
            float[] fx = EqualizerProfile.presetEffects(i);
            assertEquals(EqualizerProfile.EFFECTS, fx.length);
            assertTrue(fx[0] >= 0 && fx[0] <= 1);
            assertEquals(SoundEffects.nearest(SoundEffects.EXCITER_HZ, fx[1]), fx[1], 0f);
            assertEquals("no preset turns the Bass on", 0f, fx[2], 0f);
        }
        for (float gain : EqualizerProfile.presetCurve(0)) assertEquals(0f, gain, 0f);
        float[] flat = EqualizerProfile.presetEffects(0);
        assertEquals("Flat runs no exciter", 0f, flat[0], 0f);
    }

    /** Slipknot masters already run hot at 1.6–6.3 kHz: Heavy adds no exciter. */
    @Test public void heavyAddsNoExciter() {
        float[] fx = EqualizerProfile.presetEffects(java.util.Arrays.asList(EqualizerProfile.PRESETS).indexOf("Heavy"));
        assertEquals(0f, fx[0], 0f);
    }

    @Test public void presetCurvesAreCopies() {
        EqualizerProfile.presetCurve(1)[6] = 99;
        assertEquals(4f, EqualizerProfile.presetCurve(1)[6], 0f);
    }

    /** Why the engine doesn't set the 31 bands as they are: five of them would own no FFT bin. */
    @Test public void dynamicsProcessingLeavesFiveBassBandsWithoutABin() {
        int[][] owned = EqualizerProfile.ownedBins(EqualizerProfile.FREQUENCIES);
        for (float hz : new float[]{31.5f, 40, 50, 80, 100}) assertNull(hz + " Hz", owned[band(hz)]);
        assertArrayEquals(new int[]{2, 2}, owned[band(63)]); // the "63 Hz" band alone would act at 94 Hz
        for (float hz : new float[]{25, 125, 160, 200, 250, 1000, 16000}) assertNotNull(hz + " Hz", owned[band(hz)]);
    }

    /**
     * 25 to 63 Hz share the one bin at 47 Hz, so a single fader there moves it about 1 dB per 6 dB
     * (all five together move it fully); from 80 Hz up a single fader moves its bins 2.4 dB or more.
     */
    @Test public void everyBandFrom25HzUpMovesSomethingYouCanHear() {
        float[] flat = EqualizerProfile.binGains(EqualizerProfile.FREQUENCIES, EqualizerProfile.bandGains(new float[BANDS]));
        for (int i = 1; i < BANDS; i++) {
            float[] curve = new float[BANDS];
            curve[i] = 6;
            float[] bins = EqualizerProfile.binGains(EqualizerProfile.FREQUENCIES, EqualizerProfile.bandGains(curve));
            float most = 0;
            for (int bin = 1; bin <= TOP_BIN; bin++) most = Math.max(most, bins[bin] - flat[bin]);
            float floor = EqualizerProfile.FREQUENCIES[i] < 70 ? .9f : 2.4f;
            assertTrue(EqualizerProfile.FREQUENCIES[i] + " Hz moves " + most, most >= floor);
        }
    }

    @Test public void bandGainsStayWithinTheCurveSoTheHeadroomHolds() {
        for (int p = 0; p < EqualizerProfile.PRESETS.length; p++) {
            float[] curve = EqualizerProfile.presetCurve(p);
            float max = -99, min = 99;
            for (float v : curve) { max = Math.max(max, v); min = Math.min(min, v); }
            for (float g : EqualizerProfile.bandGains(curve)) assertTrue(g <= max + 1e-4f && g >= min - 1e-4f);
        }
    }

    @Test public void aBandKeepsItsOwnGainWhereItIsTheOnlyOneOnItsBins() {
        float[] curve = new float[BANDS];
        java.util.Arrays.fill(curve, 3);
        for (float g : EqualizerProfile.bandGains(curve)) assertEquals(3f, g, 1e-4f);
    }

    /** Refitted in 0.1.110 (scripts/eq-presets/thirty_one.py): bin by bin, close to what the 14 bands played. */
    @Test public void eachPresetPlaysLikeItsFourteenBandVersion() {
        for (int p = 1; p < EqualizerProfile.PRESETS.length; p++) {
            float[] was = EqualizerProfile.binGains(EqualizerProfile.FOURTEEN_FREQUENCIES, FOURTEEN[p]);
            float[] now = EqualizerProfile.binGains(EqualizerProfile.FREQUENCIES, EqualizerProfile.bandGains(EqualizerProfile.presetCurve(p)));
            double sum = 0, weight = 0, worst = 0;
            for (int bin = 1; bin <= TOP_BIN; bin++) {
                double d = now[bin] - was[bin];
                worst = Math.max(worst, Math.abs(d));
                sum += d * d / bin;     // equal weight per octave
                weight += 1.0 / bin;
            }
            String name = EqualizerProfile.PRESETS[p];
            assertTrue(name + " worst " + worst, worst <= 1.0);
            assertTrue(name + " rms " + Math.sqrt(sum / weight), Math.sqrt(sum / weight) <= .3);
        }
    }

    /** The move to format 6 recognises an untouched built-in preset and gives it the refitted curve. */
    @Test public void aFourteenBandPresetIsRecognisedAfterTheMove() {
        for (int p = 0; p < EqualizerProfile.PRESETS.length; p++) {
            assertTrue(EqualizerProfile.PRESETS[p], EqualizerProfile.soundsAlike(
                    EqualizerProfile.fromFourteen(FOURTEEN[p]), EqualizerProfile.presetCurve(p)));
        }
        assertFalse(EqualizerProfile.soundsAlike(EqualizerProfile.fromFourteen(FOURTEEN[2]), EqualizerProfile.presetCurve(0)));
    }

    @Test public void aCustomFourteenBandCurveKeepsItsValuesWhereTheBandsMeet() {
        float[] old = new float[14];
        old[7] = 4;       // 1 kHz
        old[13] = -3;     // 16 kHz
        float[] curve = EqualizerProfile.fromFourteen(old);
        assertEquals(BANDS, curve.length);
        assertEquals(4f, at(curve, 1000), 0f);
        assertEquals(-3f, at(curve, 16000), 0f);
        assertEquals(0f, at(curve, 250), 0f);
    }

    @Test public void heavyCutsTheMudAndKeepsSlipknotsHotPresenceDown() {
        int heavy = java.util.Arrays.asList(EqualizerProfile.PRESETS).indexOf("Heavy");
        float[] curve = EqualizerProfile.presetCurve(heavy);
        assertTrue(at(curve, 250) < 0 && at(curve, 400) < 0);
        assertTrue(at(curve, 2500) < 0 && at(curve, 4000) < 0);
        assertTrue(at(curve, 80) > 0);
    }

    /** 0.1.108 sounded "tubed": no preset may leave the boxy 630 Hz–1 kHz zone above the 2.5–4 kHz presence zone. */
    @Test public void theBoxyMidsSitBelowThePresenceZone() {
        for (int i = 1; i < EqualizerProfile.PRESETS.length; i++) {
            float[] curve = EqualizerProfile.presetCurve(i);
            float boxy = Math.max(at(curve, 630), Math.max(at(curve, 800), at(curve, 1000)));
            float presence = Math.max(at(curve, 2500), Math.max(at(curve, 3150), at(curve, 4000)));
            assertTrue(EqualizerProfile.PRESETS[i], boxy <= -1 && presence >= boxy);
        }
    }

    /** Measured: a flat curve still needs 1.9 dB for the inter-sample peaks the 48 kHz mixer rebuilds. */
    @Test public void aFlatCurveStillKeepsRoomForInterSamplePeaks() {
        assertEquals(2.5f, EqualizerProfile.headroom(new float[BANDS]), 1e-6f);
    }

    @Test public void headroomCoversTheLargestBoostAndCountsTrebleHigher() {
        float[] bass = new float[BANDS];
        bass[band(63)] = 4;
        assertEquals(4.5f, EqualizerProfile.headroom(bass), 1e-6f);
        float[] treble = new float[BANDS];
        treble[band(16000)] = 3;
        assertEquals(5.5f, EqualizerProfile.headroom(treble), 1e-6f);
        float[] cuts = new float[BANDS];
        java.util.Arrays.fill(cuts, -3);
        assertEquals(0.5f, EqualizerProfile.headroom(cuts), 1e-6f);
    }

    /** What the simulation on the owner's tracks found each preset needs, in dB, with the 0.5 dB safety left over. */
    @Test public void everyPresetGetsAtLeastTheHeadroomMeasuredOnTheLoudestTracks() {
        String[] names = {"Flat", "Car", "Nu Metal", "Heavy", "Vocals", "Smooth"};
        float[] needed = {1.87f, 3.55f, 3.98f, 3.38f, 2.81f, 2.40f};
        for (int i = 0; i < names.length; i++) {
            int index = java.util.Arrays.asList(EqualizerProfile.PRESETS).indexOf(names[i]);
            assertTrue(names[i], EqualizerProfile.headroom(EqualizerProfile.presetCurve(index)) >= needed[i] + 0.4f);
        }
    }

    @Test public void theInputIsTheMasterGainLessTheCurvesHeadroom() {
        float[] curve = new float[BANDS];
        curve[band(63)] = 3;
        assertEquals(-3.5f, EqualizerProfile.inputGain(0, curve), 1e-6f);
        float[] flat = new float[BANDS];
        assertEquals(1.5f, EqualizerProfile.inputGain(4, flat), 1e-6f);
        assertEquals(-8.5f, EqualizerProfile.inputGain(-6, flat), 1e-6f);
    }

    @Test public void withHeadroomOffTheInputIsJustTheMasterGain() {
        float[] curve = new float[BANDS];
        curve[band(63)] = 3;
        assertEquals(0f, EqualizerProfile.inputGain(0, curve, false), 0f);
        assertEquals(2f, EqualizerProfile.inputGain(2, curve, false), 0f);
        assertEquals(EqualizerProfile.inputGain(0, curve), EqualizerProfile.inputGain(0, curve, true), 0f);
    }
}
