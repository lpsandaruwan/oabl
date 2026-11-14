package com.softiva.oabl;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import java.nio.ByteBuffer;

/** The exciter and bass (see {@link SoundEffects}) as a step in the ExoPlayer audio chain, after ReplayGain. */
@OptIn(markerClass = UnstableApi.class)
final class SoundEffectsProcessor extends BaseAudioProcessor {
    /** Written on the main thread, read on the playback thread. */
    private volatile SoundEffects.Settings wanted = SoundEffects.Settings.OFF;
    /** Compare: play the song as it was, at a matching loudness. */
    private volatile boolean original;
    private SoundEffects.Chain chain;
    private SoundEffects.Match match;
    private Dither dither;
    private float[] frame = new float[0], dry = new float[0];

    void setSettings(SoundEffects.Settings settings) { wanted = settings == null ? SoundEffects.Settings.OFF : settings; }

    void setOriginal(boolean value) { original = value; }

    @Override
    protected AudioProcessor.AudioFormat onConfigure(AudioProcessor.AudioFormat format) throws UnhandledAudioFormatException {
        if (format.encoding != C.ENCODING_PCM_16BIT && format.encoding != C.ENCODING_PCM_FLOAT) {
            throw new UnhandledAudioFormatException(format);
        }
        chain = new SoundEffects.Chain(format.channelCount, format.sampleRate);
        frame = new float[Math.max(1, format.channelCount)];
        dry = new float[frame.length];
        // The chain looks 5 ms ahead; the original is delayed to match so Compare doesn't jump.
        match = new SoundEffects.Match(format.sampleRate, .005);
        dither = new Dither(format.channelCount, format.sampleRate);
        return format;
    }

    @Override
    protected void onFlush() {
        // A seek or a new track must not ring on with the old position's filter memories.
        if (chain != null) chain.reset();
        if (match != null) match.reset();
        if (dither != null) dither.reset();
    }

    @Override
    public void queueInput(ByteBuffer input) {
        int size = input.remaining();
        if (size == 0) return;
        ByteBuffer output = replaceOutputBuffer(size);
        SoundEffects.Settings settings = wanted;
        if (chain == null || !settings.active()) {
            output.put(input);
            output.flip();
            return;
        }
        if (!settings.equals(chain.settings())) chain.configure(settings);
        boolean pcm16 = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT;
        int bytes = pcm16 ? 2 : 4;
        int channels = frame.length;
        boolean wantOriginal = original;
        boolean dithered = settings.dither;
        int frames = 0;
        while (input.remaining() >= bytes * channels) {
            for (int c = 0; c < channels; c++) frame[c] = pcm16 ? input.getShort() / 32768f : input.getFloat();
            System.arraycopy(frame, 0, dry, 0, channels);
            chain.process(frame, true);
            match.frame(dry, frame, wantOriginal);
            frames++;
            for (int c = 0; c < channels; c++) {
                if (pcm16) output.putShort(dither.next(c, frame[c], dithered));
                else output.putFloat(frame[c]);
            }
        }
        match.endBlock(frames);
        output.flip();
    }
}
