package com.softiva.oabl;

/**
 * OABL Music's own effects, run on its decoded audio after ReplayGain and before the equalizer. The
 * <b>bass</b> is psychoacoustic: the 40–90 Hz band is turned into its 2nd, 3rd and 4th harmonics, which
 * small speakers can play and the ear hears as the missing low note, on top of a gentler bell around
 * 80 Hz. Only a share of the boost is taken off the whole song, and what it adds eases off for a peak.
 *
 * The <b>exciter</b> is OABL Music's original effect. Each channel's band from the chosen frequency
 * (1.5 kHz by default) up to 6 kHz is driven through a soft clipper, which makes many harmonics of it,
 * and only what lands above the chosen "harmonics from" frequency (8 kHz by default) is mixed back in,
 * for air. The clipper runs at four times the
 * sample rate so those harmonics don't fold back down as fizz.
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
 * <b>Even out</b> is a slow leveler in front of both: it measures the song's loudness (about 150 ms to
 * rise, 400 ms to fall, both channels together so the image stays put) and moves the gain toward
 * {@link #EVEN_TARGET_DB}, turning quiet passages up and loud ones down (less) by a share of the distance, never
 * more than {@link #EVEN_LIMIT_DB}. A quiet intro comes up and the chorus eases down; near silence it
 * fades out of the way, so fades and gaps aren't lifted into hiss.
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
    static final float[] EXCITER_HZ = {1500, 2500, 3500, 5000};
    /**
     * Only what lands above the "harmonics from" frequency is added: the exciter is air, not body.
     * A 4th-order high-pass keeps the part of the generated harmonics above it (-3 dB at the corner, about
     * -12 dB at 0.8 times it). The choices, and the default.
     */
    static final float[] AIR_HZ = {6000, 8000, 10000, 12000};
    static final float AIR_DEFAULT_HZ = 8000;
    /** The band the harmonics are made from ends here: above it the 2nd harmonic alone already lands in the air. */
    static final double EXCITER_SOURCE_TOP_HZ = 6000;
    /** Harmonic level, in units of the band's own average level, at full exciter. */
    static final double AIR_GAIN = 1.6;
    /** The clipper runs at this multiple of the sample rate. */
    static final int AIR_OVERSAMPLE = 4;
    /**
     * Peak growth per unit of exciter, per start frequency. What is added sits at 10 kHz and up, at about
     * 20 dB below the music at full exciter, so it barely moves the peaks: 0.01 to 0.03 on the loud
     * Switchfoot section (0.1.125, 14 November 2026). Held at 0.15 for every start, five times that,
     * until the owner's loudest tracks are measured again (the earlier table, 0.62 to 0.25, was
     * the worst of 39 tracks for the old mid-band exciter).
     */
    static final float[] EXCITER_PEAK = {.15f, .15f, .15f, .15f};
    /** Envelope smoothing; u (band over its average level) is held within ±2, so a hit out of silence can't explode. */
    private static final double ENVELOPE_S = .010, U_MAX = 2;

    /** Even out: where the song's average level is pulled to, the share of the distance covered at full strength going up and going down (loud parts are eased less than quiet ones are lifted), and the most the gain moves (dB). */
    static final double EVEN_TARGET_DB = -13, EVEN_UP = 1.0, EVEN_DOWN = .5, EVEN_LIMIT_DB = 8;
    /** Below this level the effect fades out completely; it is at full strength from EVEN_FADE_FULL_DB up. */
    static final double EVEN_GATE_DB = -52, EVEN_FADE_FULL_DB = -42;
    /** How fast the loudness reading rises and falls, and how smoothly the gain follows it. */
    private static final double EVEN_ATTACK_S = .150, EVEN_RELEASE_S = .400, EVEN_GAIN_S = .020;
    /** The gain target is recomputed every this many frames: logs and powers per frame would be wasted. */
    private static final int EVEN_BLOCK = 32;

    /** The gain Even out applies, in dB, for a song level (mean power, dBFS) at a strength 0..1. */
    static double evenGainDb(double levelDb, float strength) {
        if (strength <= 0 || !(levelDb > -200)) return 0;
        double fade = Math.max(0, Math.min(1, (levelDb - EVEN_GATE_DB) / (EVEN_FADE_FULL_DB - EVEN_GATE_DB)));
        double limit = EVEN_LIMIT_DB * strength;
        double distance = EVEN_TARGET_DB - levelDb;
        double gain = distance * (distance > 0 ? EVEN_UP : EVEN_DOWN) * strength * fade;
        return Math.max(-limit, Math.min(limit, gain));
    }

    /** The most the Bass slider adds, in dB. */
    static final float BASS_MAX_DB = 10;
    /** Centre of the lift: a bell, because door speakers and earbuds can't follow a boost much below 50 Hz. */
    static final double BASS_HZ = 80, BASS_Q = .8;
    /** How much of the slider's boost the bell gives; the harmonics do the rest. */
    static final float BASS_BELL = .6f;
    /** The band turned into harmonics, and what is kept of them: nothing near the fundamentals, nothing that sounds like buzz. */
    static final double HARMONIC_HIGH_HZ = 90, HARMONIC_TOP_HZ = 500;
    /**
     * The same two choices the exciter has. "Bass band from": the low edge of the band the harmonics are
     * made from (it ends at 90 Hz). "Bass harmonics from": the high-pass that keeps only the overtones
     * above it (-3 dB at the corner). Defaults are what 0.1.126 and earlier had: 40 Hz and 100 Hz.
     */
    static final float[] BASS_BAND_HZ = {30, 40, 50, 60};
    static final float[] BASS_AIR_HZ = {80, 100, 120, 150};
    static final float BASS_BAND_DEFAULT_HZ = 40, BASS_AIR_DEFAULT_HZ = 100;
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
        final float exciter, exciterHz, bassDb, even, airHz, bassBandHz, bassAirHz;
        final boolean headroom;

        Settings(float exciter, float exciterHz) { this(exciter, exciterHz, 0); }

        Settings(float exciter, float exciterHz, float bassDb) { this(exciter, exciterHz, bassDb, true); }

        Settings(float exciter, float exciterHz, float bassDb, boolean headroom) { this(exciter, exciterHz, bassDb, headroom, 0); }

        Settings(float exciter, float exciterHz, float bassDb, boolean headroom, float even) {
            this(exciter, exciterHz, bassDb, headroom, even, AIR_DEFAULT_HZ);
        }

        Settings(float exciter, float exciterHz, float bassDb, boolean headroom, float even, float airHz) {
            this(exciter, exciterHz, bassDb, headroom, even, airHz, BASS_BAND_DEFAULT_HZ, BASS_AIR_DEFAULT_HZ);
        }

        Settings(float exciter, float exciterHz, float bassDb, boolean headroom, float even, float airHz,
                 float bassBandHz, float bassAirHz) {
            this.headroom = headroom;
            this.bassBandHz = nearest(BASS_BAND_HZ, bassBandHz);
            this.bassAirHz = nearest(BASS_AIR_HZ, bassAirHz);
            this.airHz = nearest(AIR_HZ, airHz);
            this.even = unit(even);
            this.exciter = unit(exciter);
            this.exciterHz = nearest(EXCITER_HZ, exciterHz);
            this.bassDb = unit(bassDb / BASS_MAX_DB) * BASS_MAX_DB;
        }

        boolean active() { return exciter > 0 || bassDb > 0 || even > 0; }

        /** Input scale: room for the exciter on the worst measured master, and part of the bass boost. */
        float preGain() {
            if (!headroom) return 1f;
            float forExciter = 1f / (1f + EXCITER_PEAK[index(EXCITER_HZ, exciterHz)] * EXCITER_MAX * exciter);
            return forExciter * (float) Math.pow(10, -bassDb * BASS_HEADROOM / 20);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Settings)) return false;
            Settings o = (Settings) other;
            return exciter == o.exciter && exciterHz == o.exciterHz && bassDb == o.bassDb && headroom == o.headroom && even == o.even && airHz == o.airHz
                    && bassBandHz == o.bassBandHz && bassAirHz == o.bassAirHz;
        }

        @Override public int hashCode() {
            return 31 * (31 * (31 * (31 * (31 * (31 * (31 * Float.hashCode(exciter) + Float.hashCode(exciterHz)) + Float.hashCode(bassDb)) + Boolean.hashCode(headroom)) + Float.hashCode(even)) + Float.hashCode(airHz)) + Float.hashCode(bassBandHz)) + Float.hashCode(bassAirHz);
        }

        @Override public String toString() { return exciter + "/" + exciterHz + "/" + bassDb + "/" + headroom + "/" + even + "/" + airHz + "/" + bassBandHz + "/" + bassAirHz; }
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

    /**
     * The harmonic generator: the band over its own level goes up to four times the sample rate, through
     * an asymmetric soft clipper (odd and even overtones, many of them), then a high-pass at 10 kHz keeps
     * only the air and a low-pass below the original Nyquist keeps the rest from folding back when the
     * rate comes down again. One per channel.
     */
    static final class Air {
        private final Biquad upA = new Biquad(), upB = new Biquad(), hpA = new Biquad(), hpB = new Biquad(),
                lpA = new Biquad(), lpB = new Biquad();

        private final double fast;

        Air(double rate) {
            fast = rate * AIR_OVERSAMPLE;
            // Hold, then smooth: the images of a hold sit at the original rate, far above the 6 kHz band.
            upA.lowPass(8000, Q1, fast);
            upB.lowPass(8000, Q2, fast);
            keepFrom(AIR_DEFAULT_HZ);
            double top = Math.min(16000, rate * .36);
            lpA.lowPass(top, Q1, fast);
            lpB.lowPass(top, Q2, fast);
        }

        /** Where the air starts: the high-pass corner. Filter memories are kept, so a change mid-song doesn't click. */
        void keepFrom(double hz) {
            hpA.highPass(hz, Q1, fast);
            hpB.highPass(hz, Q2, fast);
        }

        /** {@code u}: the band divided by its average level (held within ±2). Returns the air, in units of that level. */
        double next(double u) {
            double out = 0;
            for (int i = 0; i < AIR_OVERSAMPLE; i++) {
                double v = upB.next(upA.next(u));
                out = lpB.next(lpA.next(hpB.next(hpA.next(clip(v)))));
            }
            return out;
        }

        /** Soft clip with a slight asymmetry: x(27+x²)/(27+9x²) is tanh to within 2 % up to 3, and is held there. */
        static double clip(double v) {
            double x = 3 * (v + .3 * v * Math.abs(v));
            x = Math.max(-3, Math.min(3, x));
            return x * (27 + x * x) / (27 + 9 * x * x);
        }

        void reset() {
            upA.reset(); upB.reset(); hpA.reset(); hpB.reset(); lpA.reset(); lpB.reset();
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
        private final Biquad[] start, limitA, limitB, bass;
        private final Air[] air;
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
        private final double evenAttack, evenRelease, evenSmoothing;
        private double evenPower, evenTarget = 1, evenGain = 1;
        private int evenCount;

        Chain(int channels, double rate) {
            this.channels = Math.max(1, channels);
            this.rate = rate;
            smoothing = 1 - Math.exp(-1 / (ENVELOPE_S * rate));
            bassRelease = 1 - Math.exp(-1 / (BASS_RELEASE_S * rate));
            evenAttack = 1 - Math.exp(-1 / (EVEN_ATTACK_S * rate));
            evenRelease = 1 - Math.exp(-1 / (EVEN_RELEASE_S * rate));
            evenSmoothing = 1 - Math.exp(-1 / (EVEN_GAIN_S * rate));
            harmonicDecay = Math.exp(-1 / (HARMONIC_RELEASE_S * rate));
            harmonicLow.highPass(BASS_BAND_DEFAULT_HZ, .7071, rate);
            harmonicHighA.lowPass(HARMONIC_HIGH_HZ, Q1, rate);
            harmonicHighB.lowPass(HARMONIC_HIGH_HZ, Q2, rate);
            harmonicKeepA.highPass(BASS_AIR_DEFAULT_HZ, Q1, rate);
            harmonicKeepB.highPass(BASS_AIR_DEFAULT_HZ, Q2, rate);
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
            air = new Air[this.channels];
            envelope = new double[this.channels];
            for (int c = 0; c < this.channels; c++) {
                start[c] = new Biquad(); limitA[c] = new Biquad(); limitB[c] = new Biquad();
                air[c] = new Air(rate);
                bass[c] = new Biquad();
            }
            configure(Settings.OFF);
        }

        Settings settings() { return settings; }

        /** A new level or frequency; filter memories are kept so a change mid-song doesn't click. */
        void configure(Settings next) {
            settings = next;
            pre = next.preGain();
            double top = Math.min(rate * .20, EXCITER_SOURCE_TOP_HZ);
            for (int c = 0; c < channels; c++) {
                start[c].highPass(next.exciterHz, .7071, rate);
                air[c].keepFrom(next.airHz);
                limitA[c].lowPass(top, Q1, rate);
                limitB[c].lowPass(top, Q2, rate);
                bass[c].peaking(BASS_HZ, BASS_Q, next.bassDb * BASS_BELL, rate);
            }
            harmonicWet = next.bassDb / BASS_MAX_DB * HARMONIC_MAX;
            harmonicLow.highPass(next.bassBandHz, .7071, rate);
            harmonicKeepA.highPass(next.bassAirHz, Q1, rate);
            harmonicKeepB.highPass(next.bassAirHz, Q2, rate);
        }

        void reset() {
            evenPower = 0; evenTarget = evenGain = 1; evenCount = 0;
            for (int c = 0; c < channels; c++) {
                start[c].reset(); limitA[c].reset(); limitB[c].reset(); air[c].reset();
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
            double level = pre;
            if (settings.even > 0) level *= leveler(frame);
            double room = 1;
            double harmonics = 0;
            if (lifting) {
                double mono = 0;
                for (int c = 0; c < channels; c++) mono += frame[c] * level;
                harmonics = harmonics(mono / channels) * harmonicWet;
            }
            for (int c = 0; c < channels; c++) {
                double x = frame[c] * level;
                double y = x;
                if (amount > 0) {
                    double band = limitB[c].next(limitA[c].next(start[c].next(x)));
                    // The band's average level, smoothed over 10 ms: steady enough not to add its own
                    // distortion, slow enough that a transient briefly gets more harmonics.
                    envelope[c] += (Math.abs(band) - envelope[c]) * smoothing;
                    double u = envelope[c] < 1e-9 ? 0 : Math.max(-U_MAX, Math.min(U_MAX, band / envelope[c]));
                    y += air[c].next(u) * envelope[c] * AIR_GAIN * amount;
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

        /** Even out: one gain for all channels, from the song's loudness so far; 1 when the effect is off. */
        private double leveler(float[] frame) {
            double power = 0;
            for (int c = 0; c < channels; c++) power += (double) frame[c] * frame[c];
            power /= channels;
            evenPower += (power - evenPower) * (power > evenPower ? evenAttack : evenRelease);
            if (++evenCount >= EVEN_BLOCK) {
                evenCount = 0;
                double db = 10 * Math.log10(evenPower + 1e-12);
                evenTarget = Math.pow(10, evenGainDb(db, settings.even) / 20);
            }
            evenGain += (evenTarget - evenGain) * evenSmoothing;
            return evenGain;
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
