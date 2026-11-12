import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Looper;
import android.os.SystemClock;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;

/**
 * Temporary app_process diagnostic. No APK, app-data writes, effects, focus policy or volume-setting changes.
 *
 *   platform file.wav seconds
 *   track file.wav seconds [bufferFrames|min|vlc] [ram|stream]
 *
 * track defaults to a 48000-frame (1 s) buffer fed from RAM, the 2026-10-16 test. "vlc" asks for
 * the buffer LibVLC asks for (twice the platform minimum; OABL's track was 5772 frames on the box).
 * "stream" reads the file during playback instead of loading it first, so a WAV on the memory card
 * exercises the card.
 * See docs/audio-underrun-diagnostics.md.
 */
public final class PcmPlaybackProbe {
    private static MediaPlayer player;
    private static AudioTrack track;
    private static Source source;
    private static synchronized void release() {
        if (player != null) { try { player.stop(); } catch (RuntimeException ignored) {} player.release(); player = null; }
        if (track != null) { try { track.stop(); } catch (RuntimeException ignored) {} track.release(); track = null; }
        if (source != null) { try { source.close(); } catch (IOException ignored) {} source = null; }
    }

    /** Endless 48 kHz/stereo/16-bit PCM from the WAV's data chunk, looping at the end. */
    private abstract static class Source implements Closeable {
        private long reads, slowReads, maxReadNs, totalReadNs, slowMs = Long.MAX_VALUE / 1_000_000L;
        abstract int fill(byte[] chunk) throws IOException;
        final int read(byte[] chunk) throws IOException {
            long t = System.nanoTime(); int n = fill(chunk); t = System.nanoTime() - t;
            reads++; totalReadNs += t; maxReadNs = Math.max(maxReadNs, t); if (t > slowMs * 1_000_000L) slowReads++;
            return n;
        }
        /** Starts counting from zero; a read slower than slowMs is reported separately. */
        final void resetStats(long slowMs) { reads = slowReads = maxReadNs = totalReadNs = 0; this.slowMs = slowMs; }
        final String stats() {
            return String.format(Locale.ROOT, " reads=%d avgReadMs=%.2f maxReadMs=%.1f slowReads(>%dms)=%d",
                    reads, reads == 0 ? 0 : totalReadNs / 1e6 / reads, maxReadNs / 1e6, slowMs, slowReads);
        }
        @Override public void close() throws IOException {}
    }

    /** The whole file in RAM: no storage I/O during playback. */
    private static final class RamSource extends Source {
        private final byte[] pcm; private int offset;
        RamSource(String path) throws IOException { byte[] wav = Files.readAllBytes(Paths.get(path)); pcm = Arrays.copyOfRange(wav, 44, wav.length); }
        @Override int fill(byte[] chunk) {
            int n = Math.min(chunk.length, pcm.length - offset);
            System.arraycopy(pcm, offset, chunk, 0, n);
            offset = (offset + n) % pcm.length;
            return n;
        }
    }

    /** Synchronous reads from the file in the write loop, with no read-ahead: the worst case for slow storage. */
    private static final class StreamSource extends Source {
        private final RandomAccessFile file;
        StreamSource(String path) throws IOException { file = new RandomAccessFile(path, "r"); file.seek(44); }
        @Override int fill(byte[] chunk) throws IOException {
            int n = file.read(chunk, 0, chunk.length & ~3);
            if (n < 4) { file.seek(44); n = file.read(chunk, 0, chunk.length & ~3); }
            if (n < 4) throw new IOException("No PCM data");
            return n & ~3;
        }
        @Override public void close() throws IOException { file.close(); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > (args[0].equals("track") ? 5 : 3))
            throw new IllegalArgumentException("platform internal.wav seconds | track file.wav seconds [bufferFrames|min|vlc] [ram|stream]");
        int seconds = Integer.parseInt(args[2]);
        if (seconds < 1 || seconds > 60) throw new IllegalArgumentException("Duration must be 1–60 seconds");
        Looper.prepare();
        Runtime.getRuntime().addShutdownHook(new Thread(PcmPlaybackProbe::release));
        try {
            AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
            if (args[0].equals("platform")) {
                player = new MediaPlayer();
                player.setAudioAttributes(attributes);
                player.setDataSource(args[1]); player.setVolume(1f, 1f); player.prepare();
                System.out.println("READY platform WAV durationMs=" + player.getDuration() + " session=" + player.getAudioSessionId());
                player.start();
                long start = SystemClock.elapsedRealtime();
                while (SystemClock.elapsedRealtime() - start < seconds * 1000L) {
                    SystemClock.sleep(5000);
                    System.out.println("PLATFORM elapsedMs=" + (SystemClock.elapsedRealtime()-start)
                            + " positionMs=" + player.getCurrentPosition() + " playing=" + player.isPlaying());
                }
            } else if (args[0].equals("track")) {
                String bufferArg = args.length > 3 ? args[3] : "48000", sourceArg = args.length > 4 ? args[4] : "ram";
                if (!sourceArg.equals("ram") && !sourceArg.equals("stream")) throw new IllegalArgumentException("Source must be ram or stream");
                // Only the header: reading the whole file here would warm the page cache and hide slow storage.
                byte[] headerBytes = new byte[44];
                try (DataInputStream in = new DataInputStream(new FileInputStream(args[1]))) { in.readFully(headerBytes); }
                ByteBuffer header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN);
                long fileBytes = Files.size(Paths.get(args[1]));
                if (fileBytes < 44 + 4 * 48000 || header.getInt(0) != 0x46464952 || header.getInt(8) != 0x45564157
                        || header.getInt(36) != 0x61746164 || header.getShort(20) != 1 || header.getShort(22) != 2
                        || header.getInt(24) != 48000 || header.getShort(34) != 16)
                    throw new IllegalArgumentException("Expected generated 48kHz/stereo/16-bit PCM WAV");
                source = sourceArg.equals("ram") ? new RamSource(args[1]) : new StreamSource(args[1]);
                int min = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                // "vlc" matches LibVLC's android_audiotrack output, which asks for twice the platform minimum.
                int requestedFrames = bufferArg.equals("min") ? min / 4 : bufferArg.equals("vlc") ? 2 * min / 4 : Integer.parseInt(bufferArg);
                if (requestedFrames < 1 || requestedFrames > 10 * 48000) throw new IllegalArgumentException("bufferFrames must be min, vlc or 1–480000");
                track = new AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(min, requestedFrames * 4)).build();
                if (track.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("AudioTrack not initialized");
                int bufferFrames = track.getBufferSizeInFrames();
                // Small chunks relative to the buffer, like a decoder; 100 ms at most (the original RAM behaviour).
                byte[] chunk = new byte[4 * Math.max(256, Math.min(4800, bufferFrames / 4))];
                int pending = 0, pendingOffset = 0, prefilled = 0;
                // Pre-fill the whole buffer so initialization isn't counted as starvation.
                while (true) {
                    if (pending == 0) { pending = source.read(chunk); pendingOffset = 0; }
                    int n = track.write(chunk, pendingOffset, pending, AudioTrack.WRITE_NON_BLOCKING);
                    if (n < 0) throw new IllegalStateException("Prefill error " + n);
                    pendingOffset += n; pending -= n; prefilled += n;
                    if (pending > 0) break;
                }
                // Without read-ahead, a read longer than half the buffer leaves little margin before the track runs dry.
                source.resetStats(Math.max(1, bufferFrames / 48 / 2));
                System.out.println("READY track source=" + sourceArg + " session=" + track.getAudioSessionId() + " minFrames=" + min / 4
                        + " requestedFrames=" + requestedFrames + " bufferFrames=" + bufferFrames + " bufferMs=" + bufferFrames / 48
                        + " chunkFrames=" + chunk.length / 4 + " prefilledBytes=" + prefilled + " fileBytes=" + fileBytes);
                int before = track.getUnderrunCount();
                long start = SystemClock.elapsedRealtime(), next = start;
                track.play();
                while (SystemClock.elapsedRealtime()-start < seconds * 1000L) {
                    if (pending == 0) { pending = source.read(chunk); pendingOffset = 0; }
                    int n = track.write(chunk, pendingOffset, pending, AudioTrack.WRITE_BLOCKING);
                    if (n <= 0) throw new IllegalStateException("Streaming write error " + n);
                    pendingOffset += n; pending -= n;
                    long now = SystemClock.elapsedRealtime();
                    if (now >= next) {
                        System.out.println("TRACK elapsedMs=" + (now-start) + " headFrames=" + Integer.toUnsignedLong(track.getPlaybackHeadPosition())
                                + " clientUnderruns=" + (track.getUnderrunCount()-before) + source.stats());
                        next = now + 5000;
                    }
                }
                System.out.println("RESULT track source=" + sourceArg + " bufferFrames=" + bufferFrames
                        + " clientUnderruns=" + (track.getUnderrunCount()-before) + source.stats());
            } else throw new IllegalArgumentException("Unknown mode");
            System.out.println("PROBE complete mode=" + args[0]);
        } finally { release(); }
        // app_process can retain framework Binder threads after main returns.
        System.exit(0);
    }
}
