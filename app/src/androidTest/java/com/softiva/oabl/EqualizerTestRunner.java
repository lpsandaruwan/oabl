package com.softiva.oabl;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.audiofx.DynamicsProcessing;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.widget.SeekBar;
import android.widget.Switch;
import android.media.audiofx.AudioEffect;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Map;

/** Native DSP readback uses a silent session, not a claim of audible car processing. */
public final class EqualizerTestRunner extends Instrumentation {
    private int checks;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        SharedPreferences prefs = getTargetContext().getSharedPreferences("equalizer_profile", 0);
        Map<String, ?> original = prefs.getAll();
        EqualizerProfile profile = new EqualizerProfile(getTargetContext());
        AudioTrack track = null;
        EqualizerEngine engine = null;
        EqualizerActivity activity = null;
        try {
            SystemClock.sleep(7000); // Allow the post-install HOME restoration alarm to finish.
            check(EqualizerProfile.FREQUENCIES.length == 31, "31 fixed bands");
            prefs.edit().clear().putInt("format_version", 1).putFloat("band_0", 2)
                    .putFloat("band_1", 4).putFloat("band_2", 2).putFloat("band_3", 4).putFloat("band_8", -3).putFloat("preamp", -5)
                    .putBoolean("enabled", true).putString("preset", "Custom").commit();
            EqualizerProfile migrated = new EqualizerProfile(getTargetContext());
            check(migrated.gain(3) >= 2 && migrated.gain(3) <= 4, "legacy 40 Hz gain carried over");
            check(migrated.gain(5) >= 2 && migrated.gain(5) <= 4, "legacy 63 Hz gain carried over");
            check(migrated.gain(17) < 0 && migrated.gain(17) > -3, "legacy mids interpolated");
            check(migrated.preamp() == -5 && migrated.enabled() && "Custom".equals(migrated.preset()), "migration preserves control preferences");
            check(migrated.customPresets().contains(EqualizerProfile.FOURTEEN_BAND_BACKUP), "curve before 14 bands kept as a preset");
            check(migrated.customPresets().contains(EqualizerProfile.PRESETS_BACKUP), "sound before the 0.1.108 presets kept as a preset");
            check(migrated.takeFourteenBandNotice() && !migrated.takeFourteenBandNotice(), "14-band notice shows once");
            migrated.setGain(2, 6);
            check(new EqualizerProfile(getTargetContext()).gain(2) == 6, "migration runs only once");
            check("1 k".equals(EqualizerProfile.frequencyLabel(17)) && "31.5".equals(EqualizerProfile.frequencyLabel(2)), "precise frequency label");
            migrated.setGain(2, 9); check(migrated.gain(2) == 6, "range is ±6 dB");
            // Format 3 (15 bands, ±12 dB): a curve past ±6 is scaled to fit, keeping its shape.
            prefs.edit().clear().putInt("format_version", 3).putFloat("gain_hz_63.0", 12).putFloat("gain_hz_125.0", 4).commit();
            EqualizerProfile scaled = new EqualizerProfile(getTargetContext());
            check(Math.max(scaled.gain(6), scaled.gain(7)) >= 5, "loudest band scaled to the ±6 dB limit, where it played (94 Hz)");
            check(scaled.gain(9) > 0 && scaled.gain(9) < 4, "other bands scaled by the same factor");
            check(scaled.customCurve(EqualizerProfile.FOURTEEN_BAND_BACKUP)[5] == 6, "backup preset keeps the original shape");
            check(prefs.getFloat("gain_hz_125.0", 0) == 4, "older band keys retained as backup");
            // Format 4 (10 bands on a speaker layer): the speaker profile is folded into the one curve.
            prefs.edit().clear().putInt("format_version", 4).putFloat("gain_hz_1000.0", 2)
                    .putBoolean("speaker_enabled", true).putFloat("speaker_hz_1000.0", -3).putFloat("speaker_hz_630.0", -2)
                    .putString("audio_scope", "split").commit();
            EqualizerProfile folded = new EqualizerProfile(getTargetContext());
            check(folded.gain(17) == -1, "style and speaker gains combined at 1 kHz");
            check(folded.gain(15) < 0, "speaker cut at 630 Hz kept");
            check(!prefs.getBoolean("speaker_enabled", true), "speaker layer switched off after folding");
            float[] old = new float[31]; old[17] = 4;
            prefs.edit().putString("custom_presets", "{\"Old\":" + java.util.Arrays.toString(old) + "}").commit();
            check(folded.customCurve("Old").length == 31 && folded.customCurve("Old")[17] == 4, "31-band custom preset read as it is");
            float[] fourteen = new float[14]; fourteen[7] = 4;
            prefs.edit().putString("custom_presets", "{\"Fourteen\":" + java.util.Arrays.toString(fourteen) + "}").commit();
            check(folded.customCurve("Fourteen").length == 31 && folded.customCurve("Fourteen")[17] == 4, "14-band custom preset read on 31 bands");
            folded.takeFourteenBandNotice(); // the activity below should open without the one-time notice
            profile.setPreamp(-8); check(profile.inputGain() == -EqualizerProfile.headroom(profile.gains()), "legacy preamp no longer applied");
            profile.setExciter(.5f); profile.setExciterHz(7000); profile.saveCustom("Mine"); profile.preset(0);
            check(profile.exciter() == 0 && profile.exciterHz() == 3500, "Flat turns the exciter off");
            profile.applyCustom("Mine"); check(profile.exciter() == .5f && profile.exciterHz() == 7000, "a custom preset brings its exciter back");
            profile.deleteCustom("Mine");
            profile.setEnabled(false); check(!profile.activeEffects().active(), "no effects while the equalizer is off");
            profile.preset(3); check(profile.gain(13) < 0 && profile.gain(6) > 0 && profile.gain(23) < 0, "Heavy cuts mud and 4 kHz, lifts the kick");
            check(profile.exciter() == 0, "a preset sets its exciter");
            profile.preset(5); check(profile.gain(23) < 0 && profile.gain(25) < 0, "Smooth tames bite and sizzle");
            profile.setEnabled(true); profile.flush();
            EqualizerProfile saved = new EqualizerProfile(getTargetContext());
            check(saved.enabled() && "Smooth".equals(saved.preset())
                    && saved.gain(25) == -2f, "configured status and curve durably reload");
            profile.preset(0); profile.setPreamp(0); profile.setGain(8, 3); profile.setGain(0, -2);
            check(profile.gain(8) == 3, "saved gain");
            check(new EqualizerProfile(getTargetContext()).gain(8) == 3, "gain reloads");
            float last = 0;
            for (int i = 0; i < EqualizerProfile.FREQUENCIES.length; i++) {
                check(EqualizerProfile.cutoff(i) > last, "ordered cutoff " + i);
                last = EqualizerProfile.cutoff(i);
            }
            int size = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(size).build();
            track.play();
            track.write(new byte[size], 0, size);
            profile.setEnabled(true);
            engine = new EqualizerEngine(track.getAudioSessionId());
            engine.apply(profile);
            Field nativeField = EqualizerEngine.class.getDeclaredField("effect");
            nativeField.setAccessible(true);
            DynamicsProcessing dsp = (DynamicsProcessing) nativeField.get(engine);
            check(dsp.getConfig().getPreEqBandCount() == EqualizerProfile.FREQUENCIES.length, "native DSP runs the 31 bands");
            check(!dsp.getConfig().isPostEqInUse(), "no second EQ stage");
            check(!dsp.getConfig().isLimiterInUse() && !dsp.getConfig().isMbcInUse(), "only the EQ stage runs");
            check(Math.abs(dsp.getInputGainByChannelIndex(0) - profile.inputGain()) < .01f && profile.inputGain() == -3.5f, "input lowered by the curve's headroom");
            profile.setMasterGain(2); engine.apply(profile);
            check(Math.abs(dsp.getInputGainByChannelIndex(0)-profile.inputGain()) < .01f, "native input gain follows master gain");
            profile.setMasterGain(0); engine.apply(profile);
            check(dsp.getEnabled(), "DSP enabled");
            float[] gains = EqualizerProfile.bandGains(profile.gains());
            for (int i = 0; i < EqualizerProfile.FREQUENCIES.length; i++) {
                DynamicsProcessing.EqBand band = dsp.getPreEqBandByChannelIndex(0, i);
                check(Math.abs(band.getGain() - gains[i]) < 0.01f, "native gain readback " + i);
                check(Math.abs(band.getCutoffFrequency() - EqualizerProfile.cutoff(i)) < 1, "native boundary " + i);
            }
            profile.setEnabled(false); engine.apply(profile);
            check(!dsp.getEnabled(), "bypass disables DSP");
            engine.close(); engine = null;
            track.release(); track = null;
            profile.preset(0);
            activity = (EqualizerActivity) startActivitySync(new Intent(getTargetContext(), EqualizerActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync(); SystemClock.sleep(1000);
            EqualizerCurveView editor=activity.getWindow().getDecorView().findViewWithTag("eq_curve");
            check(editor!=null,"one real curve editor");
            Rect bounds=new Rect();int[] location=new int[2];float[] point=new float[2];
            onUi(()->{
                check(editor.getGlobalVisibleRect(bounds),"curve visible");
                editor.select(0);editor.getLocationOnScreen(location);
                point[0]=location[0]+editor.bandX(0);point[1]=location[1]+editor.bandY(0);
            });
            touch(MotionEvent.ACTION_DOWN,point[0],point[1]);SystemClock.sleep(100);
            touch(MotionEvent.ACTION_MOVE,point[0],point[1]-90);SystemClock.sleep(100);
            touch(MotionEvent.ACTION_UP,point[0],point[1]-90);
            waitForIdleSync();SystemClock.sleep(300);
            check(profile.gain(0)>0,"curve drag changes saved gain");
            check(profile.gain(1)==0,"independent drag leaves neighboring band unchanged");
            onUi(()->{
                check(location[0]+editor.bandX(30)<=bounds.right,"20 kHz point fits full-width curve");
                editor.select(30);editor.adjust(.5f);
            });
            check(profile.gain(30)==.5f,"last band has precise half-decibel control");
            // Verify the foreground owner holds/releases effects independently of this screen.
            track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(size).build();
            track.play(); track.write(new byte[size], 0, size);
            final int session = track.getAudioSessionId();
            final EqualizerActivity tuner = activity;
            Switch enabled = tuner.getWindow().getDecorView().findViewWithTag("equalizer_enabled");
            onUi(() -> {
                SoundEqualizerService.rememberSession(getTargetContext(), session, getTargetContext().getPackageName());
                enabled.setChecked(true);
            });
            waitForIdleSync(); waitFor(() -> "active".equals(SoundEqualizerService.state(getTargetContext())));
            check("active".equals(SoundEqualizerService.state(getTargetContext())) && SoundEqualizerService.status(getTargetContext()).startsWith("Active"), "service owns native effect");
            profile.preset(5);
            onUi(() -> {
                try {
                    java.lang.reflect.Method render = EqualizerActivity.class.getDeclaredMethod("renderAll");
                    render.setAccessible(true); render.invoke(tuner);
                    SoundEqualizerService.refresh(tuner);
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            waitForIdleSync(); SystemClock.sleep(500);
            java.lang.reflect.Method update = EqualizerActivity.class.getDeclaredMethod("updateStatus");
            update.setAccessible(true);
            onUi(() -> { try { update.invoke(tuner); } catch (Exception error) { throw new AssertionError(error); } });
            Bitmap image = getUiAutomation().takeScreenshot();
            if (image == null) throw new AssertionError("screenshot unavailable");
            try (FileOutputStream output = new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),
                    "equalizer-metal15-test.png"))) { image.compress(Bitmap.CompressFormat.PNG, 100, output); }
            image.recycle();
            getTargetContext().sendBroadcast(new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                    .setPackage(getTargetContext().getPackageName()).putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getTargetContext().getPackageName()));
            waitForIdleSync(); waitFor(() -> SoundEqualizerService.status(getTargetContext()).startsWith("Waiting"));
            check(SoundEqualizerService.status(getTargetContext()).startsWith("Waiting for a compatible player"), "closed session released");
            onUi(() -> new EqualizerSessionReceiver().onReceive(getTargetContext(),
                    new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                            .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, 123456)
                            .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getTargetContext().getPackageName())
                            .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_VOICE)));
            check(getTargetContext().getSharedPreferences("equalizer_runtime", android.content.Context.MODE_MULTI_PROCESS)
                    .getStringSet("sessions", java.util.Collections.emptySet()).stream().noneMatch(e -> e.startsWith("123456|")), "voice session ignored");
            onUi(() -> enabled.setChecked(false));
            waitForIdleSync(); waitFor(() -> SoundEqualizerService.status(getTargetContext()).startsWith("Off"));
            check(SoundEqualizerService.status(getTargetContext()).startsWith("Off"), "disable stops processing");
            result.putString("stream", "PASS: " + checks + " checks; 14 native DSP bands, gain readback, "
                    + "optional limiter readback, migration, saved state and curve touch. Silent emulator session only.\n");
        } catch (Throwable error) {
            result.putString("failure", error.toString()); result.putString("stream", "FAIL: " + error + "\n");
        } finally {
            if (engine != null) engine.close();
            if (track != null) track.release();
            if (activity != null) {
                final Activity closing = activity; onUi(closing::finish);
                waitForIdleSync(); SystemClock.sleep(250);
            }
            SharedPreferences.Editor restore = prefs.edit().clear();
            for (Map.Entry<String, ?> entry : original.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Boolean) restore.putBoolean(entry.getKey(), (Boolean)value);
                else if (value instanceof Float) restore.putFloat(entry.getKey(), (Float)value);
                else if (value instanceof String) restore.putString(entry.getKey(), (String)value);
                else if (value instanceof Integer) restore.putInt(entry.getKey(), (Integer)value);
            }
            restore.commit();
            SoundEqualizerService.refresh(getTargetContext());
        }
        finish(result.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
    }
    private void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
    private void onUi(Runnable action) {
        Throwable[] error = new Throwable[1];
        runOnMainSync(() -> { try { action.run(); } catch (Throwable failed) { error[0] = failed; } });
        if (error[0] != null) throw new AssertionError(error[0]);
    }
    private long gestureDown;
    private void touch(int action, float x, float y) {
        long time = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) gestureDown = time;
        MotionEvent event = MotionEvent.obtain(gestureDown, time, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        getUiAutomation().injectInputEvent(event, true);
        event.recycle();
    }

    /** The equalizer runs in its own process; give a command up to 4 s to show up in its status. */
    private static void waitFor(java.util.function.BooleanSupplier condition) {
        long end = SystemClock.uptimeMillis() + 4000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < end) SystemClock.sleep(100);
    }
}
