package com.softiva.oabl;

/**
 * What the curve and slider editors work on: the one 31-band curve at ±6 dB. Both editors read and
 * write through this model.
 */
final class EqualizerModel {
    final EqualizerProfile profile;

    EqualizerModel(EqualizerProfile profile) {
        this.profile = profile;
    }

    float[] frequencies() {
        return EqualizerProfile.FREQUENCIES;
    }

    /** dB either way that the editor shows and allows. */
    float range() {
        return EqualizerProfile.STYLE_DB;
    }

    int count() {
        return frequencies().length;
    }

    float[] values() {
        return profile.gains();
    }

    void set(float[] values) {
        profile.setGains(values);
    }

    float[] linked(float[] baseline, int band, float target) {
        return EqualizerProfile.linkedGains(frequencies(), baseline, band, target, profile.linkWidth(), range());
    }

    String label(int band) {
        return EqualizerProfile.hzLabel(frequencies()[band]).replace(" k", "k");
    }

    String role(int band) {
        return EqualizerProfile.BAND_ROLES[band];
    }
}
