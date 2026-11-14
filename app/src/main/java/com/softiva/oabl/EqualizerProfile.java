package com.softiva.oabl;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Saved tone settings; live audio session IDs are deliberately never persisted.
 *
 * One curve of 31 third-octave bands, processed on each music player's own audio session. Until
 * format 5 there was a separate speaker layer and a system-mix option; the move to 14 bands folded the
 * speaker profile into the curve, and format 6 spreads those 14 bands onto 31.
 */
final class EqualizerProfile {
    /** The 31 ISO third-octave bands (owner's choice, November 2026; 14 bands from October). */
    static final float[] FREQUENCIES = {20, 25, 31.5f, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630,
            800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000};
    static final String[] BAND_ROLES = {"Sub", "Sub", "Rumble", "Depth", "Depth", "Kick", "Kick", "Bass", "Bass",
            "Punch", "Punch", "Body", "Body", "Mud", "Mud", "Boxy", "Boxy", "Riffs", "Riffs", "Vocals", "Vocals",
            "Presence", "Presence", "Bite", "Bite", "Sizzle", "Sizzle", "Cymbals", "Cymbals", "Air", "Air"};
    /** Range in dB either way. */
    static final float STYLE_DB = 6;
    /** The 14 bands of format 5 (October 2026 to launcher 0.1.109). */
    static final float[] FOURTEEN_FREQUENCIES = {40, 63, 100, 160, 250, 400, 630, 1000, 1600, 2500, 4000, 6300, 10000, 16000};
    /**
     * How DynamicsProcessing really works here (AOSP DPFrequency, android13): 1024-point FFT blocks at
     * 48 kHz, so bins 46.875 Hz apart, and every bin takes the flat gain of the band whose cutoff range
     * holds it. With the 31 bands, 31.5, 40, 50, 80 and 100 Hz own no bin at all; see {@link #bandGains}.
     */
    static final int FFT_SIZE = 1024;
    static final float MIX_RATE = 48000;
    /** The 15 bands of format 3, which the metal curves below are written on. */
    private static final float[] METAL_FREQUENCIES = {40, 63, 80, 125, 200, 315, 500, 800,
            1250, 2000, 3150, 4000, 6300, 10000, 16000};
    /** The 10 octave bands of format 4. */
    private static final float[] TEN_FREQUENCIES = {30, 60, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    static final String FOURTEEN_BAND_BACKUP = "My curve · before 14 bands";
    /** Current stored format: 6 = one 31-band curve at ±6 dB (5 was the same on 14 bands). */
    static final int FORMAT = 6;
    /** ISO third-octave centres of the old 31-band engine; older custom presets are stored on them. */
    private static final float[] THIRD_OCTAVES = {20, 25, 31.5f, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630,
            800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000};
    private static final float[] LEGACY_FREQUENCIES = {25, 40, 63, 100, 160, 250, 400, 630,
            1000, 1600, 2500, 4000, 6300, 10000, 16000};
    /**
     * Presets of November 2026 (launcher 0.1.108, mids revised in 0.1.109), designed on the 14 bands. Each was fitted so the
     * response DynamicsProcessing really gives matches a target, then run on the owner's Linkin Park
     * and Slipknot files: none goes over 0 dBFS with {@link #headroom}. See docs/metal-equalizer.md.
     * In 0.1.110 each was refitted onto the 31 bands (scripts/eq-presets/thirty_one.py) so it plays
     * within 0.62 dB of the 14-band version. Judged by ear once, on 0.1.109 (Nu Metal).
     */
    static final String[] PRESETS = {"Flat", "Car", "Nu Metal", "Heavy", "Vocals", "Smooth"};
    // 0.1.109: the owner heard vocals and guitars "tubed", like cheap boxed midrange speakers, on the
    // 0.1.108 curves, which cut 2.5–4 kHz and left 630 Hz–1.6 kHz flat. Now the boxy 400 Hz–1.3 kHz
    // zone comes down and the 2.5–6.3 kHz presence zone, where voices and guitars get their bite,
    // comes up relative to it. Bass and air are unchanged; the owner liked them.
    // 20 25 31.5 40 50 63 80 100 125 160 200 250 315 400 500 630 800 1k 1.25k 1.6k 2k 2.5k 3.15k 4k 5k 6.3k 8k 10k 12.5k 16k 20k
    private static final float[][] CURVES = {
            new float[31],
            // Car: kick and bass lift where door speakers still play (60–110 Hz), the boxy mids down,
            // presence and top up.
            {0, 0, 0, .5f, 1.5f, 3, 4, 3.5f, 1.5f, .5f, 0, -1, -1, -1.5f, -1.5f, -2, -2, -2, -1.5f, -.5f, 0, .5f, 1, 1.5f, 1.5f, 1, 1, 1.5f, 1.5f, 1.5f, 1.5f},
            // Nu Metal (Linkin Park): more kick, drop-D mud and the 630 Hz–1 kHz honk down,
            // presence up a little less than Car (those masters already run +2 dB at 1.6–4 kHz).
            {.5f, .5f, .5f, 1, 2, 3.5f, 4.5f, 4, 1, 0, -.5f, -1.5f, -1.5f, -2, -2, -2.5f, -2.5f, -2, -1.5f, -.5f, 0, .5f, 1, 1, 1, 1, 1.5f, 1.5f, 1.5f, 1.5f, 1.5f},
            // Heavy (Slipknot): those masters run 2–5 dB hotter than pop at 1.6–6.3 kHz, so presence
            // stays slightly down; the mids come down more, so it still sounds less boxy.
            {-1, -1, -.5f, 0, 1, 2.5f, 4, 3.5f, .5f, -.5f, -1, -2, -2, -2.5f, -2.5f, -2.5f, -2.5f, -2, -1.5f, -1, -1, -.5f, -.5f, -1, -1, -1, -.5f, 0, 0, .5f, .5f},
            // Vocals: the boxy zone down, 2.5–4 kHz up, so words cut through at speed.
            {-1, -1, -.5f, -.5f, -.5f, 0, .5f, .5f, 0, .5f, 0, -1, -1, -1.5f, -1.5f, -2, -2, -1.5f, -1, 0, .5f, 1.5f, 1.5f, 1.5f, 1, .5f, 0, 0, 0, 0, 0},
            // Smooth: long drives, a gentle downward tilt with a small mid dip.
            {0, 0, .5f, 1, 1.5f, 2, 2.5f, 2.5f, 1.5f, .5f, .5f, 0, 0, -1, -1, -1, -1, -1, -1, -.5f, -.5f, -1, -1.5f, -1.5f, -1.5f, -2, -2.5f, -3, -3, -3, -3}
    };
    /**
     * The exciter each preset sets: amount, and the frequency it starts from. Heavy and Smooth keep it
     * off: Slipknot masters already run hot at 1.6–6.3 kHz, and Smooth is for long drives. 0.1.111's
     * virtual bass, deep bass and boom control were heard in the car and removed in 0.1.112. The
     * third value is Xciter's psychoacoustic Bass in dB; no preset turns it on, since the car has
     * not heard it yet.
     */
    private static final float[][] PRESET_EFFECTS = {
            {0, 1500, 0},
            {.15f, 1500, 0},
            {.1f, 1500, 0},
            {0, 1500, 0},
            {.2f, 1500, 0},
            {0, 1500, 0},
    };
    /** Built-in presets before 0.1.108; a profile still naming one is shown as Custom. */
    private static final java.util.Set<String> RETIRED_PRESETS = new java.util.HashSet<>(java.util.Arrays.asList(
            "Warm", "Clarity", "Punch", "Relaxed", "Metal Tight", "Metal Smooth", "Metal Vocals Forward"));
    static final String PRESETS_BACKUP = "My sound · before 0.1.108 presets";
    /**
     * Extra headroom for bands at 4 kHz and up: the 44.1 → 48 kHz resampling in the mixer rebuilds the
     * inter-sample peaks of loud masters (measured up to +1.9 dB with a flat curve).
     */
    static final float TREBLE_HEADROOM_DB = 2f;
    /** Safety on top of what the measurements needed, for tracks louder than the ones measured. */
    static final float SAFETY_DB = .5f;
    private final SharedPreferences preferences;

    EqualizerProfile(Context context) {
        // Shared with the equalizer service's own process: re-read from disk when it changed.
        preferences = context.getSharedPreferences("equalizer_profile", Context.MODE_MULTI_PROCESS);
        if (!mayMigrate(context)) return;
        synchronized (EqualizerProfile.class) {
            if (preferences.getAll().isEmpty()) {
                // A new profile: nothing to carry over, just mark it current.
                preferences.edit().putInt("format_version", FORMAT).putBoolean("presets_backup_0_1_108", true).commit();
                return;
            }
            if (preferences.getInt("format_version", 1) < 2) {
                float[] old = new float[LEGACY_FREQUENCIES.length];
                for (int i = 0; i < old.length; i++) old[i] = clamp(preferences.getFloat("band_" + i, 0), -12, 12);
                SharedPreferences.Editor edit = preferences.edit();
                for (float hz : METAL_FREQUENCIES) edit.putFloat(hzKey("gain_hz_", hz), interpolate(LEGACY_FREQUENCIES, old, hz));
                // One atomic commit; legacy values are retained for recovery.
                edit.putInt("format_version", 3).commit();
            } else if (preferences.getInt("format_version", 1) < 3) {
                // Selected frequencies already have exact keys in the 31-band profile.
                // Keep the unused frequency keys as a recoverable backup.
                preferences.edit().putInt("format_version", 3).commit();
            }
            int format = preferences.getInt("format_version", 1);
            if (format < 5) moveToFourteenBands(format);
            // Before the 31-band presets: whatever played on 14 bands is backed up there first.
            if (!preferences.getBoolean("presets_backup_0_1_108", false)) backUpBeforeNewPresets(fourteenGains());
            if (preferences.getInt("format_version", 1) < FORMAT) moveToThirtyOneBands();
        }
    }

    /**
     * Whether this process may migrate (and so rewrite) the stored profile. Three processes read it:
     * the launcher, the equalizer service and OABL Music. Android reads a preference file caught
     * halfway through being replaced as empty; before 0.1.112 the reader then ran every migration "from
     * scratch" and saved that, wiping the owner's settings and custom presets (7 November 2026). So only
     * the launcher's own process migrates, and never on an empty read of a file that isn't empty.
     */
    private boolean mayMigrate(Context context) {
        if (Build.VERSION.SDK_INT >= 28) {
            String process = android.app.Application.getProcessName();
            if (process != null && !process.equals(context.getPackageName())) return false;
        }
        if (!preferences.getAll().isEmpty()) return true;
        java.io.File file = new java.io.File(new java.io.File(context.getApplicationInfo().dataDir, "shared_prefs"), "equalizer_profile.xml");
        return !file.exists() || file.length() < 200;
    }

    /** The presets changed in 0.1.108: whatever was playing before is kept once as a custom preset. */
    private void backUpBeforeNewPresets(float[] curve) {
        boolean shaped = false;
        for (float gain : curve) shaped |= gain != 0;
        if (shaped && customCurve(PRESETS_BACKUP) == null) putCustom(PRESETS_BACKUP, curve, null);
        preferences.edit().putBoolean("presets_backup_0_1_108", true).commit();
    }

    /**
     * Format 5: one 14-band curve. What played before (the 15-band curve of format 3 or the 10-band
     * curve of format 4, any 31-band detail, plus the speaker profile when it was on) becomes the
     * new curve, so the sound stays the same. It is saved first as a custom preset, and a curve
     * beyond ±6 dB is scaled down to keep its shape rather than clipped.
     */
    private void moveToFourteenBands(int format) {
        float[] bands = format < 4 ? METAL_FREQUENCIES : TEN_FREQUENCIES;
        float range = format < 4 ? 12 : STYLE_DB;
        float[] old = new float[bands.length];
        for (int i = 0; i < old.length; i++) old[i] = clamp(preferences.getFloat(hzKey("gain_hz_", bands[i]), 0), -range, range);
        boolean speaker = preferences.getBoolean("speaker_enabled", false);
        float[] played = new float[THIRD_OCTAVES.length];
        boolean shaped = false;
        for (int i = 0; i < played.length; i++) {
            String key = hzKey("fine_hz_", THIRD_OCTAVES[i]);
            played[i] = preferences.contains(key) ? clamp(preferences.getFloat(key, 0), -12, 12) : interpolate(bands, old, THIRD_OCTAVES[i]);
            if (speaker) played[i] += clamp(preferences.getFloat(hzKey("speaker_hz_", THIRD_OCTAVES[i]), 0), -12, 12);
            played[i] = clamp(played[i], -24, 24);
            shaped |= played[i] != 0;
        }
        float[] next = new float[FOURTEEN_FREQUENCIES.length];
        float peak = 0;
        for (int i = 0; i < next.length; i++) {
            next[i] = interpolate(THIRD_OCTAVES, played, FOURTEEN_FREQUENCIES[i], 24);
            peak = Math.max(peak, Math.abs(next[i]));
        }
        float scale = peak > STYLE_DB ? STYLE_DB / peak : 1;
        if (shaped) putCustom(FOURTEEN_BAND_BACKUP, played, null);
        SharedPreferences.Editor edit = preferences.edit();
        for (int i = 0; i < next.length; i++) edit.putFloat(hzKey("gain_hz_", FOURTEEN_FREQUENCIES[i]), Math.round(next[i] * scale * 2) / 2f);
        for (float hz : THIRD_OCTAVES) edit.remove(hzKey("fine_hz_", hz));
        // The speaker keys and the older band keys stay as a backup; nothing reads them any more.
        edit.putBoolean("speaker_enabled", false).remove("fine_mode").remove("ten_band_notice");
        edit.putBoolean("fourteen_band_notice", shaped).putInt("format_version", 5).commit();
    }

    private float[] fourteenGains() {
        if (preferences.getInt("format_version", 1) >= FORMAT) return gains();
        float[] old = new float[FOURTEEN_FREQUENCIES.length];
        for (int i = 0; i < old.length; i++) old[i] = clamp(preferences.getFloat(hzKey("gain_hz_", FOURTEEN_FREQUENCIES[i]), 0), -STYLE_DB, STYLE_DB);
        return old;
    }

    /**
     * Format 6: the 14-band curve spread onto 31 bands so it plays the same. An untouched built-in
     * preset takes its refitted 31-band curve; anything else goes through {@link #fromFourteen}.
     */
    private void moveToThirtyOneBands() {
        float[] next = fromFourteen(fourteenGains());
        int index = java.util.Arrays.asList(PRESETS).indexOf(preferences.getString("preset", "Custom"));
        // Only when the stored curve really is that preset (an old profile can name one it doesn't play).
        if (index >= 0 && soundsAlike(next, presetCurve(index))) next = presetCurve(index);
        SharedPreferences.Editor edit = preferences.edit();
        // Several of the 31 keys are also 14-band keys; every one is written here.
        for (int i = 0; i < FREQUENCIES.length; i++) edit.putFloat(key(i), next[i]);
        edit.putInt("format_version", FORMAT).commit();
    }

    /**
     * A 14-band curve on the 31 bands: what DynamicsProcessing played for it, bin by bin, read at
     * each band's frequency (bands below the first audible bin take that bin's gain), to 0.5 dB.
     * Within about 0.9 dB of the 14-band response for the built-in presets.
     */
    static float[] fromFourteen(float[] old) {
        float[] bins = binGains(FOURTEEN_FREQUENCIES, old);
        float binHz = MIX_RATE / FFT_SIZE;
        float[] curve = new float[FREQUENCIES.length];
        for (int i = 0; i < curve.length; i++) {
            double at = FREQUENCIES[i] / binHz;
            double value;
            if (at <= 1) {
                value = bins[1];
            } else {
                int below = (int) Math.floor(at);
                double fraction = Math.log(at / below) / Math.log((below + 1.0) / below);
                value = bins[below] + fraction * (bins[Math.min(below + 1, bins.length - 1)] - bins[below]);
            }
            curve[i] = clamp(Math.round(value * 2) / 2f, -STYLE_DB, STYLE_DB);
        }
        return curve;
    }

    /** True when two 31-band curves put gains within 1.5 dB of each other on every bin up to 20 kHz. */
    static boolean soundsAlike(float[] a, float[] b) {
        float[] x = binGains(FREQUENCIES, bandGains(a)), y = binGains(FREQUENCIES, bandGains(b));
        int top = (int) (20000 * FFT_SIZE / MIX_RATE);
        for (int bin = 1; bin <= top; bin++) if (Math.abs(x[bin] - y[bin]) > 1.5f) return false;
        return true;
    }

    /** The first and last FFT bin each band owns in DynamicsProcessing, or null when it owns none. */
    static int[][] ownedBins(float[] frequencies) {
        int last = FFT_SIZE / 2;
        int[][] owned = new int[frequencies.length][];
        int start = 0;
        for (int band = 0; band < frequencies.length; band++) {
            float cut = band == frequencies.length - 1 ? 22000 : (float) Math.sqrt(frequencies[band] * frequencies[band + 1]);
            int stop = (int) (0.5f + cut * FFT_SIZE / MIX_RATE);
            int end = Math.min(stop, last);
            if (end >= start) owned[band] = new int[]{start, end};
            start = stop + 1;
        }
        return owned;
    }

    /** The dB gain DynamicsProcessing puts on every bin for these band gains (0 where no band reaches). */
    static float[] binGains(float[] frequencies, float[] bandGains) {
        float[] bins = new float[FFT_SIZE / 2 + 1];
        int[][] owned = ownedBins(frequencies);
        for (int band = 0; band < owned.length; band++) {
            if (owned[band] == null) continue;
            for (int bin = owned[band][0]; bin <= owned[band][1]; bin++) bins[bin] = bandGains[band];
        }
        return bins;
    }

    /**
     * What each DynamicsProcessing band is set to for this 31-band curve: the curve averaged over the
     * frequencies its FFT bins cover. Set as it is, the 31.5, 40, 50, 80 and 100 Hz bands would do
     * nothing (they own no bin) and the 63 Hz band would act at 94 Hz; averaged, every band counts
     * where it sits, as far as 47 Hz bins allow. Never above the curve's own peak, so the headroom holds.
     */
    static float[] bandGains(float[] curve) {
        int[][] owned = ownedBins(FREQUENCIES);
        float binHz = MIX_RATE / FFT_SIZE;
        int samples = 16;
        float[] gains = new float[FREQUENCIES.length];
        for (int band = 0; band < gains.length; band++) {
            if (owned[band] == null) {
                gains[band] = curve[band];
                continue;
            }
            double low = Math.max(FREQUENCIES[0], (owned[band][0] - .5) * binHz);
            double high = Math.max(low, (owned[band][1] + .5) * binHz);
            double sum = 0;
            for (int i = 0; i < samples; i++) {
                sum += interpolate(FREQUENCIES, curve, (float) (low * Math.pow(high / low, (i + .5) / samples)), 24);
            }
            gains[band] = (float) (sum / samples);
        }
        return gains;
    }

    /** True once after the move to 14 bands when the old curve was saved as a preset. */
    boolean takeFourteenBandNotice() {
        if (!preferences.getBoolean("fourteen_band_notice", false)) return false;
        preferences.edit().putBoolean("fourteen_band_notice", false).apply();
        return true;
    }

    boolean enabled() { return preferences.getBoolean("enabled", false); }
    void setEnabled(boolean value) { preferences.edit().putBoolean("enabled", value).apply(); }
    float preamp() { return clamp(preferences.getFloat("preamp", 0), -12, 0); }
    void setPreamp(float value) { preferences.edit().putFloat("preamp", clamp(value, -12, 0)).apply(); }
    // ---- curve ----

    private static String key(int band) { return hzKey("gain_hz_", FREQUENCIES[band]); }
    private static String hzKey(String prefix, float hz) { return prefix + Float.toString(hz); }
    float gain(int band) { return clamp(preferences.getFloat(key(band), 0), -STYLE_DB, STYLE_DB); }
    void setGain(int band, float value) {
        preferences.edit().putFloat(key(band), clamp(value, -STYLE_DB, STYLE_DB))
                .putString("preset", "Custom").apply();
    }
    String preset() {
        String name = preferences.getString("preset", "Flat");
        return RETIRED_PRESETS.contains(name) ? "Custom" : name;
    }
    /** A built-in preset: its curve and its effects. */
    void preset(int index) {
        setEffects(PRESET_EFFECTS[index]);
        writeStandard(presetCurve(index), PRESETS[index]);
    }
    static float[] presetEffects(int index) { return PRESET_EFFECTS[index].clone(); }
    static float[] presetCurve(int index) {
        return CURVES[index].clone();
    }
    float[] gains() {
        float[] gains = new float[FREQUENCIES.length];
        for (int i = 0; i < gains.length; i++) gains[i] = gain(i);
        return gains;
    }
    void setGains(float[] values) { writeStandard(values, "Custom"); }

    private void writeStandard(float[] values, String name) {
        SharedPreferences.Editor edit = preferences.edit().putString("preset", name);
        for (int i = 0; i < FREQUENCIES.length; i++) edit.putFloat(key(i), clamp(values[i], -STYLE_DB, STYLE_DB));
        edit.apply();
    }

    // ---- custom presets ----

    List<String> customPresets() {
        List<String> names = new ArrayList<>();
        try {
            org.json.JSONObject all = new org.json.JSONObject(preferences.getString("custom_presets", "{}"));
            java.util.Iterator<String> keys = all.keys();
            while (keys.hasNext()) names.add(keys.next());
        } catch (org.json.JSONException ignored) { }
        java.util.Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    float[] customCurve(String name) {
        try {
            org.json.JSONArray values = new org.json.JSONObject(preferences.getString("custom_presets", "{}")).getJSONArray(name);
            float[] stored = new float[values.length()];
            for (int i = 0; i < stored.length; i++) stored[i] = (float) values.getDouble(i);
            // Saved on 14 bands (format 5, until 0.1.109).
            if (stored.length == FOURTEEN_FREQUENCIES.length) return fromFourteen(stored);
            // The 31 bands: saved now, or before format 5 on the old ±12 dB third-octave engine.
            if (stored.length != FREQUENCIES.length) return null;
            for (int i = 0; i < stored.length; i++) stored[i] = clamp(stored[i], -STYLE_DB, STYLE_DB);
            return stored;
        } catch (org.json.JSONException error) {
            return null;
        }
    }

    void saveCustom(String name) {
        putCustom(name, gains(), effects());
        preferences.edit().putString("preset", name).apply();
    }

    /** {@code effects} null keeps none with the preset; applying it then leaves the effects alone. */
    private void putCustom(String name, float[] curve, float[] effects) {
        try {
            org.json.JSONObject all = new org.json.JSONObject(preferences.getString("custom_presets", "{}"));
            org.json.JSONArray values = new org.json.JSONArray();
            for (float v : curve) values.put(v);
            all.put(name, values);
            org.json.JSONObject saved = new org.json.JSONObject(preferences.getString("custom_effects", "{}"));
            if (effects == null) saved.remove(name);
            else {
                org.json.JSONArray levels = new org.json.JSONArray();
                for (float v : effects) levels.put(v);
                saved.put(name, levels);
            }
            preferences.edit().putString("custom_presets", all.toString())
                    .putString("custom_effects", saved.toString()).commit();
        } catch (org.json.JSONException ignored) { }
    }

    /**
     * The effects saved with a custom preset, or null when it has none (saved before 0.1.111). Saved
     * by 0.1.111 there were six (bass, deep, bass Hz, exciter, exciter Hz, boom); only the exciter's stay.
     */
    float[] customEffects(String name) {
        try {
            org.json.JSONObject saved = new org.json.JSONObject(preferences.getString("custom_effects", "{}"));
            if (!saved.has(name)) return null;
            org.json.JSONArray levels = saved.getJSONArray(name);
            if (levels.length() == 6) return new float[]{(float) levels.getDouble(3), (float) levels.getDouble(4), 0};
            // Saved before the Bass: two values, so the Bass stays off.
            if (levels.length() == 2) return new float[]{(float) levels.getDouble(0), (float) levels.getDouble(1), 0};
            if (levels.length() != EFFECTS) return null;
            float[] effects = new float[EFFECTS];
            for (int i = 0; i < EFFECTS; i++) effects[i] = (float) levels.getDouble(i);
            return effects;
        } catch (org.json.JSONException error) {
            return null;
        }
    }

    void applyCustom(String name) {
        float[] curve = customCurve(name);
        if (curve == null) return;
        float[] effects = customEffects(name);
        if (effects != null) setEffects(effects);
        writeStandard(curve, name);
    }

    void deleteCustom(String name) {
        try {
            org.json.JSONObject all = new org.json.JSONObject(preferences.getString("custom_presets", "{}"));
            all.remove(name);
            org.json.JSONObject saved = new org.json.JSONObject(preferences.getString("custom_effects", "{}"));
            saved.remove(name);
            preferences.edit().putString("custom_presets", all.toString())
                    .putString("custom_effects", saved.toString()).apply();
        } catch (org.json.JSONException ignored) { }
    }

    // ---- the exciter (0.1.111: vocal focus, de-harsh, seat balance, speed lift and the Crystalizer
    // went; 0.1.112: virtual bass, deep bass, boom control and peak protection went too) ----

    /** Exciter amount, exciter Hz, bass dB, in that order. */
    static final int EFFECTS = 3;
    float exciter() { return clamp(preferences.getFloat("fx_exciter", 0), 0, 1); }
    void setExciter(float value) { preferences.edit().putFloat("fx_exciter", clamp(value, 0, 1)).apply(); }
    /**
     * Where the exciter's harmonics are made from. 0.1.125 changed what the setting means (the harmonics now
     * always land at 10 kHz and up), so an earlier choice is replaced once by 1.5 kHz, the new default.
     */
    float exciterHz() {
        if (!preferences.getBoolean("fx_air", false)) {
            preferences.edit().putFloat("fx_exciter_hz", 1500).putBoolean("fx_air", true).apply();
            return 1500;
        }
        return SoundEffects.nearest(SoundEffects.EXCITER_HZ, preferences.getFloat("fx_exciter_hz", 1500));
    }
    void setExciterHz(float value) { preferences.edit().putFloat("fx_exciter_hz", SoundEffects.nearest(SoundEffects.EXCITER_HZ, value)).apply(); }
    /** Xciter's Bass: the 40-90 Hz band's overtones plus a gentle bell, in dB (0 is off). */
    float bass() { return clamp(preferences.getFloat("fx_bass_db", 0), 0, SoundEffects.BASS_MAX_DB); }
    void setBass(float value) { preferences.edit().putFloat("fx_bass_db", clamp(value, 0, SoundEffects.BASS_MAX_DB)).apply(); }

    /**
     * Even out, 0..1: pulls quiet passages up and loud ones down toward one level. Like Headroom it is
     * a global switch of the music chain, not part of a preset.
     */
    float even() { return clamp(preferences.getFloat("fx_even", 0), 0, 1); }
    void setEven(float value) { preferences.edit().putFloat("fx_even", clamp(value, 0, 1)).apply(); }

    /**
     * Crystalizer, 0..1: a clean treble lift (each sample plus its change since the last one), the crispness
     * without the harmonics the exciter makes. Global like Even out, not part of a preset.
     */
    float crystal() { return clamp(preferences.getFloat("fx_crystal", 0), 0, 1); }
    void setCrystal(float value) { preferences.edit().putFloat("fx_crystal", clamp(value, 0, 1)).apply(); }

    /** Where the exciter's harmonics start to be played, in Hz (6, 8, 10 or 12 kHz; 8 kHz default). Global, not part of a preset. */
    float airHz() { return SoundEffects.nearest(SoundEffects.AIR_HZ, preferences.getFloat("fx_air_hz", SoundEffects.AIR_DEFAULT_HZ)); }
    void setAirHz(float value) { preferences.edit().putFloat("fx_air_hz", SoundEffects.nearest(SoundEffects.AIR_HZ, value)).apply(); }

    /** Bass: the low edge of the band its overtones are made from (30, 40, 50 or 60 Hz) and where they start to be played (80, 100, 120 or 150 Hz). Global. */
    float bassBandHz() { return SoundEffects.nearest(SoundEffects.BASS_BAND_HZ, preferences.getFloat("fx_bass_band_hz", SoundEffects.BASS_BAND_DEFAULT_HZ)); }
    void setBassBandHz(float value) { preferences.edit().putFloat("fx_bass_band_hz", SoundEffects.nearest(SoundEffects.BASS_BAND_HZ, value)).apply(); }
    float bassAirHz() { return SoundEffects.nearest(SoundEffects.BASS_AIR_HZ, preferences.getFloat("fx_bass_air_hz", SoundEffects.BASS_AIR_DEFAULT_HZ)); }
    void setBassAirHz(float value) { preferences.edit().putFloat("fx_bass_air_hz", SoundEffects.nearest(SoundEffects.BASS_AIR_HZ, value)).apply(); }

    float[] effects() { return new float[]{exciter(), exciterHz(), bass()}; }
    void setEffects(float[] values) {
        if (values == null || values.length != EFFECTS) return;
        setExciter(values[0]);
        setExciterHz(values[1]);
        setBass(values[2]);
    }

    /**
     * Hold to compare: OABL Music plays the song as it was, at a matching loudness. It has to cross
     * to the music process, so it is written while the button is held and cleared when the equalizer
     * screen is left or the music service starts.
     */
    boolean comparing() { return preferences.getBoolean("fx_comparing", false); }
    void setComparing(boolean on) { preferences.edit().putBoolean("fx_comparing", on).commit(); }

    /** What OABL Music should run: the exciter and bass while the equalizer is on, nothing while it is off. */
    SoundEffects.Settings activeEffects() {
        return enabled() ? new SoundEffects.Settings(exciter(), exciterHz(), bass(), headroom(), even(), airHz(), bassBandHz(), bassAirHz(), crystal(), dither()) : SoundEffects.Settings.OFF;
    }

    // ---- output ----

    float masterGain() { return clamp(preferences.getFloat("master_gain_db", 0), -18, 12); }
    void setMasterGain(float value) { preferences.edit().putFloat("master_gain_db", clamp(value, -18, 12)).apply(); }
    String normalization() {
        String value = preferences.getString("replay_gain", "none");
        return "track".equals(value) || "album".equals(value) ? value : "none";
    }
    void setNormalization(String value) { preferences.edit().putString("replay_gain", value).apply(); }
    float linkWidth() { return clamp(preferences.getFloat("link_octaves", 0), 0, 2); }
    void setLinkWidth(float value) { preferences.edit().putFloat("link_octaves", clamp(value, 0, 2)).apply(); }

    /**
     * How far the input is lowered so the curve can't clip a loud, limited master: the largest boost,
     * counting bands from 4 kHz up 2 dB higher (inter-sample peaks), plus a 0.5 dB safety. Always
     * applied. Measured on the owner's loudest files, not a proof.
     */
    static float headroom(float[] curve) {
        float maximum = 0;
        for (int band = 0; band < curve.length; band++) {
            float extra = FREQUENCIES[Math.min(band, FREQUENCIES.length - 1)] >= 4000 ? TREBLE_HEADROOM_DB : 0;
            maximum = Math.max(maximum, curve[band] + extra);
        }
        return maximum + SAFETY_DB;
    }

    /**
     * The DynamicsProcessing input gain: the master gain less the curve's headroom. A positive master
     * gain can clip loud tracks; the Peak protection switch that capped it at 0 dB was removed in
     * 0.1.112 (at the usual 0 dB it changed nothing, and DynamicsProcessing's limiter reacts to block
     * energy, not peaks).
     */
    static float inputGain(float master, float[] curve) {
        return inputGain(master, curve, true);
    }

    /** With the Headroom switch off nothing is turned down for the curve, so loud tracks can clip. */
    static float inputGain(float master, float[] curve, boolean headroomOn) {
        return headroomOn ? master - headroom(curve) : master;
    }

    float inputGain() { return inputGain(masterGain(), gains(), headroom()); }

    /**
     * The Dither switch (on by default): the effects' 16-bit output is rounded with TPDF dither and
     * Lipshitz noise shaping instead of a plain round, which keeps quiet passages and fades smooth.
     * Only matters while an effect is on and the player works in 16 bit. Global, not part of a preset.
     */
    boolean dither() { return preferences.getBoolean("dither_on", true); }
    void setDither(boolean on) { preferences.edit().putBoolean("dither_on", on).apply(); }

    /** The Headroom switch: take room for the curve's boost and the effects (on), or leave the level alone (off). */
    boolean headroom() { return preferences.getBoolean("headroom_on", true); }
    void setHeadroom(boolean on) { preferences.edit().putBoolean("headroom_on", on).apply(); }

    static float[] linkedGains(float[] baseline, int band, float target, float width) {
        return linkedGains(FREQUENCIES, baseline, band, target, width, STYLE_DB);
    }
    static float[] linkedGains(float[] frequencies, float[] baseline, int band, float target, float width, float range) {
        float[] values = baseline.clone();
        float delta = clamp(target, -range, range) - baseline[band];
        for (int i = 0; i < values.length; i++) {
            double octaves = Math.log(frequencies[i]/frequencies[band])/Math.log(2);
            double weight = width <= 0 ? (i == band ? 1 : 0) : Math.exp(-.5 * Math.pow(octaves / (width/2), 2));
            values[i] = clamp(baseline[i] + delta*(float)weight, -range, range);
        }
        return values;
    }
    void flush() { preferences.edit().putInt("format_version", FORMAT).commit(); }
    // DynamicsProcessing takes upper band boundaries, not center frequencies.
    static float cutoff(int band) {
        return band == FREQUENCIES.length - 1 ? 22000
                : (float) Math.sqrt(FREQUENCIES[band] * FREQUENCIES[band + 1]);
    }
    static String frequencyLabel(int band) { return hzLabel(FREQUENCIES[band]); }
    static String hzLabel(float hz) {
        java.text.DecimalFormat number = new java.text.DecimalFormat("0.##", java.text.DecimalFormatSymbols.getInstance(Locale.US));
        return number.format(hz < 1000 ? hz : hz / 1000) + (hz < 1000 ? "" : " k");
    }
    static float interpolate(float[] frequencies, float[] gains, float hz) { return interpolate(frequencies, gains, hz, 12); }
    private static float interpolate(float[] frequencies, float[] gains, float hz, float limit) {
        if (hz <= frequencies[0]) return gains[0];
        for (int i = 1; i < frequencies.length; i++) {
            if (hz == frequencies[i]) return clamp(gains[i], -limit, limit);
            if (hz < frequencies[i]) {
                double fraction = Math.log(hz/frequencies[i-1]) / Math.log(frequencies[i]/frequencies[i-1]);
                return clamp((float)(gains[i-1] + fraction*(gains[i]-gains[i-1])), -limit, limit);
            }
        }
        return gains[gains.length-1];
    }
    static float clamp(float value, float min, float max) {
        return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : 0;
    }
}
