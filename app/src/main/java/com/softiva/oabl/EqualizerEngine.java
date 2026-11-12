package com.softiva.oabl;

import android.annotation.TargetApi;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;

/**
 * One DynamicsProcessing chain per music player's audio session:
 * input gain (master, and headroom while its switch is on) → EQ (31 bands, each set to the curve's average over the FFT bins
 * it owns; see {@link EqualizerProfile#bandGains}). Nothing else: the multiband compressor (boom
 * control) and the limiter (Peak protection) went in 0.1.112, and there is no post-EQ stage.
 * ReplayGain and the exciter belong to OABL Music's own decoder.
 */
@TargetApi(28)
final class EqualizerEngine implements AutoCloseable {
    private static final int BANDS = EqualizerProfile.FREQUENCIES.length;
    private final DynamicsProcessing effect;
    private boolean closed;

    EqualizerEngine(int session) {
        DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                true, BANDS, false, 0, false, 0, false)
                .setPreferredFrameDuration(20).build();
        effect = new DynamicsProcessing(0, session, config);
        if (!effect.hasControl()) {
            effect.release();
            throw new IllegalStateException("Another equalizer controls this audio session");
        }
    }

    void apply(EqualizerProfile profile) { apply(profile, false, null); }

    void apply(EqualizerProfile profile, boolean bypass, float[] preview) {
        if (!effect.hasControl()) throw new IllegalStateException("Audio effect control was lost");
        float[] curve = preview != null && preview.length == BANDS ? preview : profile.gains();
        // With Headroom on the curve's boost is made room for; nothing after the EQ catches peaks.
        effect.setInputGainAllChannelsTo(EqualizerProfile.inputGain(profile.masterGain(), curve, profile.headroom()));
        float[] gains = EqualizerProfile.bandGains(curve);
        for (int band = 0; band < BANDS; band++) {
            effect.setPreEqBandAllChannelsTo(band, new DynamicsProcessing.EqBand(true, EqualizerProfile.cutoff(band), gains[band]));
        }
        boolean on = profile.enabled() && !bypass;
        if (effect.setEnabled(on) != AudioEffect.SUCCESS) {
            throw new IllegalStateException("Audio processing could not be enabled");
        }
    }

    boolean controlled() { return effect.hasControl(); }
    @Override public void close() {
        if (closed) return;
        closed = true;
        try {
            if (effect.hasControl()) effect.setEnabled(false);
        } catch (RuntimeException ignored) {
            // The audio session may have already disappeared.
        } finally {
            effect.release();
        }
    }
}
