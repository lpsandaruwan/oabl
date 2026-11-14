package com.softiva.oabl;

/**
 * Rounding the processed sound back to 16 bit. A plain round() leaves an error that follows the music,
 * which is heard as grit on quiet passages and fades (and Even out, the Crystalizer and the bass all
 * make the signal finer than 16 bit before it gets here). The standard cure, and what this does:
 * <ul>
 *   <li><b>TPDF dither</b>: two independent uniform values of ±½ LSB added before rounding. The error
 *       becomes steady, signal-independent noise (the lowest level that removes the grit), and a
 *       signal below one LSB still comes through, under the noise.</li>
 *   <li><b>Noise shaping</b>: the error of each sample is fed back through the five-tap filter of
 *       Lipshitz, Vanderkooy and Wannamaker ("Minimally audible noise shaping", JAES 1991), so the noise
 *       moves from where the ear is most sensitive to above about 15 kHz. Against flat TPDF it is about
 *       16 dB lower at 1 kHz and 20 dB lower at 3.5 kHz, and about 17 dB higher above 15 kHz; the total
 *       is bigger, still at about -85 dBFS, and the feedback is FIR so it cannot run away.</li>
 *   <li><b>Silence stays silent</b>: after 50 ms of digital silence a channel turns the dither off, so
 *       there is no hiss between songs.</li>
 * </ul>
 * One instance per stream; it keeps each channel's error history and random state.
 */
final class Dither {
    /** The shaper's feedback coefficients: noise transfer function 1 - sum(h[k] z^-(k+1)). */
    static final double[] SHAPE = {2.033, -2.165, 1.959, -1.590, 0.6149};
    /** Largest error fed back, in LSB: clipping at full scale must not swing the shaper. */
    private static final double ERROR_LIMIT = 2;
    private static final double SILENT_LSB = .01;

    private final int channels;
    private final double[][] error;
    private final int[] seed, quiet;
    private final int silence;

    Dither(int channels, double rate) {
        this.channels = Math.max(1, channels);
        error = new double[this.channels][SHAPE.length];
        seed = new int[this.channels];
        quiet = new int[this.channels];
        silence = Math.max(1, (int) Math.round(.05 * rate));
        reset();
    }

    void reset() {
        for (int c = 0; c < channels; c++) {
            java.util.Arrays.fill(error[c], 0);
            seed[c] = 0x9E3779B9 * (c + 1) | 1;
            quiet[c] = 0;
        }
    }

    /** The next 16-bit sample for a channel from a sample in -1..1; {@code on} false is a plain round. */
    short next(int channel, float sample, boolean on) {
        double v = sample * 32768.0;
        if (!on) return clamp(Math.rint(v));
        if (Math.abs(v) < SILENT_LSB) {
            if (quiet[channel] < silence) quiet[channel]++;
        } else quiet[channel] = 0;
        double[] history = error[channel];
        if (quiet[channel] >= silence) {
            java.util.Arrays.fill(history, 0);
            return clamp(Math.rint(v));
        }
        double y = v;
        for (int k = 0; k < SHAPE.length; k++) y -= SHAPE[k] * history[k];
        double q = clamp(Math.floor(y + uniform(channel) + uniform(channel) + .5));
        double e = Math.max(-ERROR_LIMIT, Math.min(ERROR_LIMIT, q - y));
        for (int k = SHAPE.length - 1; k > 0; k--) history[k] = history[k - 1];
        history[0] = e;
        return (short) q;
    }

    /** Uniform in [-.5, .5): xorshift32, cheap and with no allocation on the audio thread. */
    private double uniform(int channel) {
        int x = seed[channel];
        x ^= x << 13; x ^= x >>> 17; x ^= x << 5;
        seed[channel] = x;
        return (x >>> 8) / 16777216.0 - .5;
    }

    private static short clamp(double value) {
        return (short) Math.max(-32768, Math.min(32767, value));
    }
}
