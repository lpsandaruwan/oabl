package com.softiva.oabl;

/**
 * OABL Music's own effects, run on its decoded audio after ReplayGain and before the equalizer. The
 * <b>bass</b> is psychoacoustic: the 40–90 Hz band is turned into its 2nd, 3rd and 4th harmonics, which
 * small speakers can play and the ear hears as the missing low note, on top of a gentler bell around
 * 80 Hz. Only a share of the boost is taken off the whole song, and what it adds eases off for a peak.
 *
 * The <b>exciter</b> is OABL Music's original effect. Each channel's top end above the chosen
 * frequency gets new even harmonics mixed back in, for bite and air.
 *
 * The layout is the classic Aphex Aural Exciter one: the dry signal passes untouched, and a side
 * chain (a tunable high-pass, then a harmonic generator) is mixed back in at a low level.
 *
 * The harmonics come from a level-independent waveshaper: the band is divided by its own smoothed
 * average level (u, held within ±2), squared, and multiplied by that level again. So the harmonics
 * follow the band's level, and a transient, which arrives before the 10 ms average catches up, briefly
 * gets more, much as later Aphex units do on purpose. u² has no linear part, so it can't swell or
 * cancel the treble band it starts from. (Chebyshev T3 was tried first: on real music the band sits
 * below its peak most of the time, where T3 is mostly −3u, an inverted copy that cancelled the very
 * treble it was meant to brighten.)
 *
 * 0.1.113 closed two gaps that the textbook designs avoid (see {@link Chain}):
 * <ul>
 * <li>Aliasing: squaring content above a quarter of the sample rate folded its 2nd harmonic back
 *     down as off-pitch fizz. A 15 kHz tone came back at 14.1 kHz at half the dry level; now it's
 *     about 1 %.</li>
 * <li>Difference tones: squaring 3 and 4 kHz also gives 1 kHz, which crept into the mids and the
 *     harsh zone. Now only new harmonics above the band are kept.</li>
 * </ul>
 *
 * The input is lowered by {@link Settings#preGain} first, measured on the owner's loudest masters,
 * and anything still above {@link #KNEE} is bent smoothly towards full scale instead of clipping.
 * Ten biquads per stereo frame: still far cheaper than the equalizer's FFT.
 */
final class SoundEffects {
    /** Where the soft limiter starts, as a fraction of full scale. */
    static final float KNEE = 0.9f;
    /** Harmonic level over the band they come from at 100 %. */
    static final float EXCITER_MAX = 1f;
    static final float[] EXCITER_HZ = {2500, 3500, 5000, 7000};
    /**
     * Peak growth per unit of harmonic level at each frequency above: the worst of the owner's 39
     * tracks (Linkin Park, Slipknot, Limp Bizkit and others), measured on 0.1.113's exciter on
     * 7 November 2026. Without the aliasing and difference tones it grows peaks about half as much
     * as 0.1.112's did (1.15, 0.87, 0.76, 0.60), so it needs half the headroom.
     */
    static final float[] EXCITER_PEAK = {.62f, .50f, .45f, .25f};
    /** Envelope smoothing; u (band over its average level) is held within ±2, so a hit out of silence can't explode. */
    private static final double ENVELOPE_S = .010, U_MAX = 2;
    /** Scales a sine's 2nd harmonic to half its level (π/4 × 0.64 ≈ 0.5), as the first version gave. */
    private static final double SHAPE_GAIN = .64;

    /** The most the Bass slider adds, in dB. */
    static final float BASS_MAX_DB = 10;
    /** Centre of the lift: a bell, because door speakers and earbuds can't follow a boost much below 50 Hz. */
    static final double BASS_HZ = 80, BASS_Q = .8;
    /** How much of the slider's boost the bell gives; the harmonics do the rest. */
    static final float BASS_BELL = .6f;
    /** The band turned into harmonics, and what is kept of them: nothing near the fundamentals, nothing that sounds like buzz. */
    static final double HARMONIC_LOW_HZ = 40, HARMONIC_HIGH_HZ = 90, HARMONIC_KEEP_HZ = 100, HARMONIC_TOP_HZ = 500;
    /** Level of the harmonics against their band at full slider. */
    static final float HARMONIC_MAX = 1f;
    /** Falling 2nd to 4th, as an instrument's own overtones do. */
    private static final double HARMONIC_2 = .6, HARMONIC_3 = .45, HARMONIC_4 = .25, HARMONIC_RELEASE_S = .080;
    /** Share of the boost taken off the whole song, so kicks have room to get bigger. */
    static final float BASS_HEADROOM = .4f;
    /** The added bass eases off so the sum stays under this; the song itself is never turned down for it. */
    static final double BASS_CEILING = KNEE;
    /** How quickly the added bass comes back after a peak. */
    private static final double BASS_RELEASE_S = .150;
    /** How far ahead the bass control looks, so it can fade down before a peak instead of chopping at it. */
    private static final double BASS_LOOK_AHEAD_S = .005;

    private SoundEffects() { }

    /** What to run: the exciter's amount (0..1) and where it starts, and the bass boost in dB. */
    static final class Settings {
        static final Settings OFF = new Settings(0, 3500);
        final float exciter, exciterHz, bassDb;
        final boolean headroom;

        Settings(float exciter, float exciterHz) { this(exciter, exciterHz, 0); }

        Settings(float exciter, float exciterHz, float bassDb) { this(exciter, exciterHz, bassDb, true); }

        Settings(float exciter, float exciterHz, float bassDb, boolean headroom) {
            this.headroom = headroom;
            this.exciter = unit(exciter);
            this.exciterHz = nearest(EXCITER_HZ, exciterHz);
            this.bassDb = unit(bassDb / BASS_MAX_DB) * BASS_MAX_DB;
        }

        boolean active() { return exciter > 0 || bassDb > 0; }

        /** Input scale: room for the exciter on the worst measured master, and part of the bass boost. */
        float preGain() {
            if (!headroom) return 1f;
            float forExciter = 1f / (1f + EXCITER_PEAK[index(EXCITER_HZ, exciterHz)] * EXCITER_MAX * exciter);
            return forExciter * (float) Math.pow(10, -bassDb * BASS_HEADROOM / 20);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Settings)) return false;
            Settings o = (Settings) other;
            return exciter == o.exciter && exciterHz == o.exciterHz && bassDb == o.bassDb && headroom == o.headroom;
        }

        @Override public int hashCode() {
            return 31 * (31 * (31 * Float.hashCode(exciter) + Float.hashCode(exciterHz)) + Float.hashCode(bassDb)) + Boolean.hashCode(headroom);
        }

        @Override public String toString() { return exciter + "/" + exciterHz + "/" + bassDb + "/" + headroom; }
    }

    static int index(float[] choices, float value) {
        for (int i = 0; i < choices.length; i++) if (choices[i] == value) return i;
        return 0;
    }

    static float unit(float value) { return Float.isFinite(value) ? Math.max(0f, Math.min(1f, value)) : 0f; }

    static float nearest(float[] choices, float value) {
        float best = choices[0];
        for (float choice : choices) if (Math.abs(choice - value) < Math.abs(best - value)) best = choice;
        return best;
    }

    /** Linear below the knee, then tanh towards ±1: continuous, and never past full scale. */
    static float limit(float x) {
        float magnitude = Math.abs(x);
        if (magnitude <= KNEE) return x;
        float room = 1f - KNEE;
        float bent = KNEE + room * (float) Math.tanh((magnitude - KNEE) / room);
        return x < 0 ? -bent : bent;
    }

    /**
     * Compare: lets the listener hear the song as it was, at the loudness the exciter and virtual bass
     * give it, so louder isn't mistaken for better. It measures both and fades between them without a click.
     */
    static final class Match {
        private final double rate;
        private final float step;
        private double accDry, accOut, dry, out;
        private float mix, ratio = 1;
        private final int span;
        private float[][] delayed;
        private int at;

        Match(double rate, double delaySeconds) {
            this.rate = rate;
            step = (float) (1 / (.02 * rate));
            span = Math.max(0, (int) Math.round(delaySeconds * rate));
        }

        float ratio() { return ratio; }

        /** One frame: {@code processed} is changed in place when the original is wanted. */
        void frame(float[] original, float[] processed, boolean wantOriginal) {
            if (span > 0) {
                if (delayed == null || delayed.length != original.length) delayed = new float[original.length][span];
                for (int c = 0; c < original.length; c++) {
                    float next = original[c];
                    original[c] = delayed[c][at];
                    delayed[c][at] = next;
                }
                at = (at + 1) % span;
            }
            float target = wantOriginal ? 1 : 0;
            if (mix < target) mix = Math.min(target, mix + step); else if (mix > target) mix = Math.max(target, mix - step);
            for (int c = 0; c < processed.length; c++) {
                dry += original[c] * original[c];
                out += processed[c] * processed[c];
                if (mix > 0) processed[c] = processed[c] * (1 - mix) + Math.max(-1f, Math.min(1f, original[c] * ratio)) * mix;
            }
        }

        /** After a buffer of frames, so the loudness follows the last few seconds. */
        void endBlock(int frames) {
            if (frames <= 0) return;
            double decay = Math.exp(-frames / (3 * rate));
            accDry = accDry * decay + dry;
            accOut = accOut * decay + out;
            dry = out = 0;
            if (accDry > 1e-9) ratio = (float) Math.max(.1, Math.min(1.5, Math.sqrt(accOut / accDry)));
        }

        void reset() {
            if (delayed != null) for (float[] channel : delayed) java.util.Arrays.fill(channel, 0);
            at = 0;
            accDry = accOut = dry = out = 0;
            mix = 0;
            ratio = 1;
        }
    }

    /** A second-order section (RBJ cookbook), transposed direct form II. */
    static final class Biquad {
        private double b0, b1, b2, a1, a2, z1, z2;

        Biquad lowPass(double hz, double q, double rate) { return set(hz, q, rate, false); }
        Biquad highPass(double hz, double q, double rate) { return set(hz, q, rate, true); }

        private Biquad set(double hz, double q, double rate, boolean high) {
            double w = 2 * Math.PI * Math.min(hz, rate * .45) / rate, cos = Math.cos(w), alpha = Math.sin(w) / (2 * q);
            double a0 = 1 + alpha;
            double edge = high ? (1 + cos) / 2 : (1 - cos) / 2;
            b0 = edge / a0;
            b1 = (high ? -(1 + cos) : 1 - cos) / a0;
            b2 = edge / a0;
            a1 = -2 * cos / a0;
            a2 = (1 - alpha) / a0;
            return this;
        }

        Biquad peaking(double hz, double q, double db, double rate) {
            double a = Math.pow(10, db / 40), w = 2 * Math.PI * hz / rate, cos = Math.cos(w), alpha = Math.sin(w) / (2 * q);
            double a0 = 1 + alpha / a;
            b0 = (1 + alpha * a) / a0;
            b1 = -2 * cos / a0;
            b2 = (1 - alpha * a) / a0;
            a1 = b1;
            a2 = (1 - alpha / a) / a0;
            return this;
        }

        double next(double x) {
            double y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }

        void reset() { z1 = z2 = 0; }
    }

    /** Butterworth 4th order as two sections. */
    private static final double Q1 = .5412, Q2 = 1.3066;

    /**
     * The running exciter for one stream: one instance per decoder, used on the playback thread.
     *
     * Per channel: high-pass at the start frequency (2nd order) → low-pass at 0.2 of the sample rate
     * (4th order), so nothing reaching the shaper can have a 2nd harmonic past the top of the band
     * and fold back down as off-pitch fizz → shaper → high-pass at 1.5× the start frequency (4th
     * order), so the difference tones squaring makes (3 + 4 kHz gives 1 kHz) stay out of the mids and
     * the harsh 2.5–5 kHz zone, and only new harmonics above the band get added.
     */
    static final class Chain {
        private final int channels;
        private final double rate, smoothing, bassRelease;
        private Settings settings = Settings.OFF;
        private float pre = 1;
        private final Biquad[] start, limitA, limitB, keepA, keepB, bass;
        private final Biquad harmonicLow = new Biquad(), harmonicHighA = new Biquad(), harmonicHighB = new Biquad();
        private final Biquad harmonicKeepA = new Biquad(), harmonicKeepB = new Biquad(), harmonicTop = new Biquad();
        private final double harmonicDecay;
        private double harmonicEnvelope;
        private float harmonicWet;
        private final double[] envelope;
        // Look-ahead for the bass: the last few milliseconds of each part of the signal, oldest at `at`.
        private final int span;
        private final double[][] music, exciterAdded, bassAdded;
        private final double[] rooms, gains;
        private final double[] incoming, incomingExciter, incomingBass;
        private int at, minAge;
        private double minRoom = 1, held = 1, gainSum;
        private double musicOut;

        Chain(int channels, double rate) {
            this.channels = Math.max(1, channels);
            this.rate = rate;
            smoothing = 1 - Math.exp(-1 / (ENVELOPE_S * rate));
            bassRelease = 1 - Math.exp(-1 / (BASS_RELEASE_S * rate));
            harmonicDecay = Math.exp(-1 / (HARMONIC_RELEASE_S * rate));
            harmonicLow.highPass(HARMONIC_LOW_HZ, .7071, rate);
            harmonicHighA.lowPass(HARMONIC_HIGH_HZ, Q1, rate);
            harmonicHighB.lowPass(HARMONIC_HIGH_HZ, Q2, rate);
            harmonicKeepA.highPass(HARMONIC_KEEP_HZ, Q1, rate);
            harmonicKeepB.highPass(HARMONIC_KEEP_HZ, Q2, rate);
            harmonicTop.lowPass(HARMONIC_TOP_HZ, .7071, rate);
            bass = new Biquad[this.channels];
            span = Math.max(1, (int) Math.round(BASS_LOOK_AHEAD_S * rate));
            music = new double[this.channels][span];
            exciterAdded = new double[this.channels][span];
            bassAdded = new double[this.channels][span];
            rooms = new double[span];
            gains = new double[span];
            incoming = new double[this.channels];
            incomingExciter = new double[this.channels];
            incomingBass = new double[this.channels];
            clearLookAhead();
            start = new Biquad[this.channels];
            limitA = new Biquad[this.channels];
            limitB = new Biquad[this.channels];
            keepA = new Biquad[this.channels];
            keepB = new Biquad[this.channels];
            envelope = new double[this.channels];
            for (int c = 0; c < this.channels; c++) {
                start[c] = new Biquad(); limitA[c] = new Biquad(); limitB[c] = new Biquad();
                keepA[c] = new Biquad(); keepB[c] = new Biquad();
                bass[c] = new Biquad();
            }
            configure(Settings.OFF);
        }

        Settings settings() { return settings; }

        /** A new level or frequency; filter memories are kept so a change mid-song doesn't click. */
        void configure(Settings next) {
            settings = next;
            pre = next.preGain();
            double top = rate * .20, keep = Math.min(next.exciterHz * 1.5, rate * .4);
            for (int c = 0; c < channels; c++) {
                start[c].highPass(next.exciterHz, .7071, rate);
                limitA[c].lowPass(top, Q1, rate);
                limitB[c].lowPass(top, Q2, rate);
                keepA[c].highPass(keep, Q1, rate);
                keepB[c].highPass(keep, Q2, rate);
                bass[c].peaking(BASS_HZ, BASS_Q, next.bassDb * BASS_BELL, rate);
            }
            harmonicWet = next.bassDb / BASS_MAX_DB * HARMONIC_MAX;
        }

        void reset() {
            for (int c = 0; c < channels; c++) {
                start[c].reset(); limitA[c].reset(); limitB[c].reset(); keepA[c].reset(); keepB[c].reset();
                envelope[c] = 0;
                bass[c].reset();
            }
            harmonicLow.reset(); harmonicHighA.reset(); harmonicHighB.reset();
            harmonicKeepA.reset(); harmonicKeepB.reset(); harmonicTop.reset();
            harmonicEnvelope = 0;
            clearLookAhead();
        }

        private void clearLookAhead() {
            for (int c = 0; c < channels; c++) {
                java.util.Arrays.fill(music[c], 0);
                java.util.Arrays.fill(exciterAdded[c], 0);
                java.util.Arrays.fill(bassAdded[c], 0);
            }
            java.util.Arrays.fill(rooms, 1);
            java.util.Arrays.fill(gains, 1);
            at = 0;
            minAge = 0;
            minRoom = held = 1;
            gainSum = span;
            musicOut = 0;
        }

        /** The music in the frame {@code process} just returned, before any effect added to it. */
        double music() { return musicOut; }

        /** One frame in place, samples in -1..1; {@code limit} false is for measuring only. */
        void process(float[] frame, boolean limit) {
            float amount = settings.exciter * EXCITER_MAX;
            boolean lifting = settings.bassDb > 0;
            double room = 1;
            double harmonics = 0;
            if (lifting) {
                double mono = 0;
                for (int c = 0; c < channels; c++) mono += frame[c] * pre;
                harmonics = harmonics(mono / channels) * harmonicWet;
            }
            for (int c = 0; c < channels; c++) {
                double x = frame[c] * pre;
                double y = x;
                if (amount > 0) {
                    double band = limitB[c].next(limitA[c].next(start[c].next(x)));
                    // The band's average level, smoothed over 10 ms: steady enough not to add its own
                    // distortion, slow enough that a transient briefly gets more harmonics.
                    envelope[c] += (Math.abs(band) - envelope[c]) * smoothing;
                    double u = envelope[c] < 1e-9 ? 0 : Math.max(-U_MAX, Math.min(U_MAX, band / envelope[c]));
                    double shaped = envelope[c] * u * u * SHAPE_GAIN;
                    y += keepB[c].next(keepA[c].next(shaped)) * amount;
                }
                double lift = lifting ? bass[c].next(x) - x + harmonics : 0;
                // Only what the filter adds can be eased off, and the most that still fits under the
                // ceiling is the room this frame has.
                if (lift != 0) room = Math.min(room, (Math.copySign(BASS_CEILING, lift) - y) / lift);
                incoming[c] = x;
                incomingExciter[c] = y - x;
                incomingBass[c] = lift;
            }
            double gain = easedGain(Math.max(0, room));
            double mix = 0;
            for (int c = 0; c < channels; c++) {
                double x = music[c][at], y = x + exciterAdded[c][at] + gain * bassAdded[c][at];
                mix += x;
                music[c][at] = incoming[c];
                exciterAdded[c][at] = incomingExciter[c];
                bassAdded[c][at] = incomingBass[c];
                frame[c] = limit ? limit((float) y) : (float) y;
            }
            musicOut = mix / channels;
            at = (at + 1) % span;
        }

        /**
         * The low band's overtones. A narrow band is close to a sine, so with its peak held at 1 the
         * Chebyshev polynomials give pure harmonics; the filters after them drop what isn't one.
         */
        private double harmonics(double mono) {
            double band = harmonicHighB.next(harmonicHighA.next(harmonicLow.next(mono)));
            harmonicEnvelope = Math.max(Math.abs(band), harmonicEnvelope * harmonicDecay);
            double shaped = 0;
            if (harmonicEnvelope >= 1e-9) {
                double u = Math.max(-1, Math.min(1, band / harmonicEnvelope)), square = u * u;
                shaped = harmonicEnvelope * (HARMONIC_2 * (2 * square - 1) + HARMONIC_3 * u * (4 * square - 3)
                        + HARMONIC_4 * (8 * square * square - 8 * square + 1));
            }
            return harmonicTop.next(harmonicKeepB.next(harmonicKeepA.next(shaped)));
        }

        /**
         * The gain for the frame leaving the look-ahead now: the lowest room seen across the window,
         * recovering slowly, then averaged over the window. By the time a peak leaves, every value in
         * the average has already allowed for it, so the bass fades down instead of being chopped,
         * which is what clicked. One gain for all channels keeps the stereo image still.
         */
        private double easedGain(double room) {
            rooms[at] = room;
            minAge++;
            if (room <= minRoom) {
                minRoom = room;
                minAge = 0;
            } else if (minAge >= span) {
                minRoom = 1;
                for (int back = 0; back < span; back++) {
                    double seen = rooms[(at - back + span) % span];
                    if (seen < minRoom) { minRoom = seen; minAge = back; }
                }
            }
            held = Math.min(minRoom, held + (1 - held) * bassRelease);
            gainSum += held - gains[at];
            gains[at] = held;
            return gainSum / span;
        }
    }
}
