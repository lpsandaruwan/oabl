package com.softiva.oabl;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.metadata.id3.InternalFrame;
import androidx.media3.extractor.metadata.id3.TextInformationFrame;
import androidx.media3.extractor.metadata.vorbis.VorbisComment;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Volume normalisation from ReplayGain tags, as libVLC's --audio-replay-gain options did: track or
 * album gain (falling back to the other), 0 dB for untagged songs, no preamp, and peak protection
 * so a boost never clips. Gain changes take effect on the audio being written, which runs up to the
 * AudioTrack buffer (~0.5 s) ahead of what is heard.
 */
@OptIn(markerClass = UnstableApi.class)
final class ReplayGainProcessor extends BaseAudioProcessor {
    /** Linear gain, written on the main thread and read on the playback thread. */
    private volatile float gain = 1f;

    void setGain(float value) { gain = value; }

    /** The linear gain for a track's tags under a normalisation mode ("none", "track", "album"). */
    static float gainFor(Format format, String mode) {
        if (format == null || format.metadata == null || "none".equals(mode)) return 1f;
        Float trackGain = null, albumGain = null, trackPeak = null, albumPeak = null;
        Metadata metadata = format.metadata;
        for (int i = 0; i < metadata.length(); i++) {
            String key = null, value = null;
            Metadata.Entry entry = metadata.get(i);
            if (entry instanceof VorbisComment) { key = ((VorbisComment) entry).key; value = ((VorbisComment) entry).value; }
            else if (entry instanceof TextInformationFrame) { key = ((TextInformationFrame) entry).description; value = ((TextInformationFrame) entry).value; }
            else if (entry instanceof InternalFrame) { key = ((InternalFrame) entry).description; value = ((InternalFrame) entry).text; }
            if (key == null || value == null) continue;
            Float number = number(value);
            if (number == null) continue;
            switch (key.toUpperCase(Locale.ROOT)) {
                case "REPLAYGAIN_TRACK_GAIN": trackGain = number; break;
                case "REPLAYGAIN_ALBUM_GAIN": albumGain = number; break;
                case "REPLAYGAIN_TRACK_PEAK": trackPeak = number; break;
                case "REPLAYGAIN_ALBUM_PEAK": albumPeak = number; break;
                default: break;
            }
        }
        boolean album = "album".equals(mode);
        Float db = album ? (albumGain != null ? albumGain : trackGain) : (trackGain != null ? trackGain : albumGain);
        Float peak = album ? (albumPeak != null ? albumPeak : trackPeak) : (trackPeak != null ? trackPeak : albumPeak);
        if (db == null) return 1f;
        float linear = (float) Math.pow(10, db / 20.0);
        if (peak != null && peak > 0) linear = Math.min(linear, 1f / peak);
        return Math.max(0.05f, Math.min(4f, linear));
    }

    /** "-6.50 dB" or "0.98765" → the number. */
    private static Float number(String value) {
        String clean = value.trim().replaceAll("(?i)\\s*db$", "");
        try { return Float.parseFloat(clean); } catch (NumberFormatException notNumber) { return null; }
    }

    @Override
    protected AudioProcessor.AudioFormat onConfigure(AudioProcessor.AudioFormat format) throws UnhandledAudioFormatException {
        if (format.encoding != C.ENCODING_PCM_16BIT && format.encoding != C.ENCODING_PCM_FLOAT) {
            throw new UnhandledAudioFormatException(format);
        }
        return format;
    }

    @Override
    public void queueInput(ByteBuffer input) {
        int size = input.remaining();
        if (size == 0) return;
        ByteBuffer output = replaceOutputBuffer(size);
        float g = gain;
        if (g == 1f) {
            output.put(input);
        } else if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            while (input.remaining() >= 2) {
                int sample = Math.round(input.getShort() * g);
                output.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample)));
            }
        } else {
            while (input.remaining() >= 4) {
                output.putFloat(Math.max(-1f, Math.min(1f, input.getFloat() * g)));
            }
        }
        output.flip();
    }
}
