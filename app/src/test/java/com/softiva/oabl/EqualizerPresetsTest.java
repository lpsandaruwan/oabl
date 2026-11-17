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

    @Test public void everyPresetHasACurveOnTheThirtyOneBands() {
        assertEquals("Flat", EqualizerProfile.PRESETS[0]);
        for (int i = 0; i < EqualizerProfile.PRESETS.length; i++) {
            float[] curve = EqualizerProfile.presetCurve(i);
            assertEquals(BANDS, curve.length);
            for (float gain : curve) {
                assertTrue(Math.abs(gain) <= EqualizerProfile.STYLE_DB);
                assertEquals("0.5 dB steps", 0f, gain * 2 - Math.round(gain * 2), 0f);
            }
        }
        for (float gain : EqualizerProfile.presetCurve(0)) assertEquals(0f, gain, 0f);
    }

    @Test public void theListHasTheGenresTheVCurveAndTheOnesTunedInTheCar() {
        java.util.List<String> names = java.util.Arrays.asList(EqualizerProfile.PRESETS);
        for (String name : new String[]{"Flat", "Pop", "Rock", "Jazz", "Classical", "Dance", "EDM", "Hip-Hop", "Heavy Metal", "Acoustic", "Vocals",
                "Bass Boost", "Treble Boost", "V Curve", "Car", "Nu Metal", "Heavy", "Smooth"}) assertTrue(name, names.contains(name));
        float[] v = EqualizerProfile.presetCurve(names.indexOf("V Curve"));
        assertTrue(v[0] >= 5 && v[30] >= 5);
        assertTrue("1 kHz scooped", v[band(1000)] <= -3);
    }

    @Test public void everyPresetIsItsOwnShape() {
        for (int i = 0; i < EqualizerProfile.PRESETS.length; i++) {
            for (int k = i + 1; k < EqualizerProfile.PRESETS.length; k++) {
                assertFalse(EqualizerProfile.PRESETS[i] + " = " + EqualizerProfile.PRESETS[k],
                        java.util.Arrays.equals(EqualizerProfile.presetCurve(i), EqualizerProfile.presetCurve(k)));
            }
        }
    }

    @Test public void presetCurvesAreCopies() {
        float before = EqualizerProfile.presetCurve(1)[6];
        EqualizerProfile.presetCurve(1)[6] = 99;
        assertEquals(before, EqualizerProfile.presetCurve(1)[6], 0f);
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
    /** The presets tuned in the car keep their sound; the genre presets (0.1.131) have no fourteen-band version. */
    private static final String[] CAR_TUNED = {"Flat", "Car", "Nu Metal", "Heavy", "Smooth"};
    private static final int[] FOURTEEN_OF = {0, 1, 2, 3, 5};

    private static int presetIndex(String name) { return java.util.Arrays.asList(EqualizerProfile.PRESETS).indexOf(name); }

    @Test public void eachPresetPlaysLikeItsFourteenBandVersion() {
        for (int n = 1; n < CAR_TUNED.length; n++) {
            int p = presetIndex(CAR_TUNED[n]);
            float[] was = EqualizerProfile.binGains(EqualizerProfile.FOURTEEN_FREQUENCIES, FOURTEEN[FOURTEEN_OF[n]]);
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
        for (int n = 0; n < CAR_TUNED.length; n++) {
            assertTrue(CAR_TUNED[n], EqualizerProfile.soundsAlike(
                    EqualizerProfile.fromFourteen(FOURTEEN[FOURTEEN_OF[n]]), EqualizerProfile.presetCurve(presetIndex(CAR_TUNED[n]))));
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
        for (int n = 1; n < CAR_TUNED.length; n++) {
            int i = presetIndex(CAR_TUNED[n]);
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

    // ---- Sound profiles ----

    @Test public void theProfilesHoldTheAgreedValuesAndFrequencies() {
        float[][] v = EqualizerProfile.MODE_VALUES;
        assertArrayEquals(new float[]{.10f, 1f, .05f, 3500, 10000, 50, 120}, v[0], 1e-6f);
        assertArrayEquals(new float[]{.30f, 2f, .15f, 2500, 8000, 40, 100}, v[1], 1e-6f);
        assertArrayEquals(new float[]{.50f, 2f, .25f, 1500, 6000, 30, 80}, v[2], 1e-6f);
        assertArrayEquals("Custom starts with nothing added, at the middle frequencies", new float[]{0, 0, 0, 2500, 8000, 40, 100}, v[3], 0f);
    }

    @Test public void everyProfileFrequencyIsOneThePickerOffers() {
        for (float[] v : EqualizerProfile.MODE_VALUES) {
            assertEquals(v[3], SoundEffects.nearest(SoundEffects.EXCITER_HZ, v[3]), 0f);
            assertEquals(v[4], SoundEffects.nearest(SoundEffects.AIR_HZ, v[4]), 0f);
            assertEquals(v[5], SoundEffects.nearest(SoundEffects.BASS_BAND_HZ, v[5]), 0f);
            assertEquals(v[6], SoundEffects.nearest(SoundEffects.BASS_AIR_HZ, v[6]), 0f);
        }
    }

    @Test public void aSoundThatMatchesANamedProfileIsThatProfileAndAnyOtherChangeIsNot() {
        for (int i = 0; i < 3; i++) assertEquals(EqualizerProfile.MODES[i], EqualizerProfile.namedMode(EqualizerProfile.MODE_VALUES[i]));
        float[] signature = EqualizerProfile.MODE_VALUES[1];
        for (int i = 0; i < 7; i++) {
            float[] changed = signature.clone();
            changed[i] += i == 0 || i == 2 ? .05f : i == 1 ? .5f : 500;
            assertNull("value " + i, EqualizerProfile.namedMode(changed));
        }
        assertNull(EqualizerProfile.namedMode(EqualizerProfile.MODE_VALUES[3]));
    }
}
