package com.softiva.oablenc;

import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Surface;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * OABL's own screen encoder for the AI box (private use). Runs as root through app_process, mirrors the
 * box's display into a MediaCodec input surface and delivers Annex-B video, with every setting the stock
 * encoder hard-codes under our control. Two outputs:
 *
 *   out=PATH            raw Annex-B file (default /data/local/tmp/oabl-enc.h264), runs for secs
 *   sock=NAME           abstract unix socket the shim listens on; each encoder buffer is sent as
 *                       [4-byte big-endian length][bytes] (the codec config first, as stock does).
 *                       Runs until the socket closes (secs=0 by default in this mode).
 *
 *   w=1920 h=1080       encoded size (the display is scaled to it)
 *   fps=60              nominal frame rate
 *   mbps=8              bitrate in Mbit/s
 *   mode=vbr|cbr|cq|none  bitrate mode (default vbr; none leaves the codec default)
 *   gop=2               seconds between keyframes at the nominal rate (it counts frames, see sync)
 *   refresh=0           intra-refresh period in frames (0 = off)
 *   repeat=1000         repeat the last frame after this many ms of silence (0 = off)
 *   rt=1                real-time priority and operating rate
 *   profile=main        baseline|main (default: not set; the Qualcomm encoder rejects High here)
 *   bframes=0           do not set max B-frames
 *   mime=avc|hevc       codec (default avc)
 *   qp=NN              fixed quantiser (initial value and min/max for I, P and B)
 *   qpimin=NN qpimax=NN qppmin=NN qppmax=NN   Qualcomm QP limits (omit to leave the codec default)
 *   secs=15             run time (file mode)
 *   sync=0              ask for a keyframe every N seconds from here (0 = off)
 *
 * A keyframe is also produced whenever sys.suding.force.key.frame is set to 1 (checked every 100 ms and
 * reset to 0), so the box's existing keyframe nudge works with this encoder unchanged.
 */
public final class Main {

    private static final String TAG = "OABLENC";
    private static final Map<String, String> A = new HashMap<>();

    private static int i(String k, int d) {
        String v = A.get(k);
        return v == null ? d : Integer.parseInt(v);
    }

    /** Framed buffers to a socket, or raw bytes to a file. */
    private interface Sink {
        void put(byte[] data, int len) throws IOException;

        void close() throws IOException;
    }

    public static void main(String[] args) throws Exception {
        for (String a : args) {
            int e = a.indexOf('=');
            if (e > 0) A.put(a.substring(0, e), a.substring(e + 1));
        }
        final boolean sockMode = A.containsKey("sock");
        int w = i("w", 1920), h = i("h", 1080), fps = i("fps", 60), mbps = i("mbps", 8);
        int gop = i("gop", sockMode ? 60 : 2), refresh = i("refresh", 0), repeat = i("repeat", 1000);
        int secs = i("secs", sockMode ? 0 : 15), syncEvery = i("sync", 0);
        boolean hevc = "hevc".equals(A.get("mime"));
        String mime = hevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;

        MediaFormat f = MediaFormat.createVideoFormat(mime, w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, mbps * 1_000_000);
        f.setFloat(MediaFormat.KEY_FRAME_RATE, fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, gop);
        if (i("bframes", 1) == 1) f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        String mode = A.containsKey("mode") ? A.get("mode") : "vbr";
        if (!"none".equals(mode)) f.setInteger(MediaFormat.KEY_BITRATE_MODE,
                "cbr".equals(mode) ? MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                        : "cq".equals(mode) ? MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
                        : MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        if (!hevc && A.containsKey("profile") && !"none".equals(A.get("profile"))) {
            String p = A.get("profile");
            f.setInteger(MediaFormat.KEY_PROFILE, "baseline".equals(p)
                    ? MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                    : "main".equals(p) ? MediaCodecInfo.CodecProfileLevel.AVCProfileMain
                    : MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
        }
        if (refresh > 0) f.setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD, refresh);
        if (repeat > 0) f.setLong("repeat-previous-frame-after", repeat * 1000L);
        if (i("rt", 0) == 1) {
            f.setInteger(MediaFormat.KEY_PRIORITY, 0);
            f.setInteger(MediaFormat.KEY_OPERATING_RATE, fps);
        }
        if (A.containsKey("qp")) { // fixed quantiser for I and P (and B): initial value and range pinned to it
            int qp = i("qp", 30);
            for (String t : new String[]{"i", "p", "b"}) {
                f.setInteger("vendor.qti-ext-enc-initial-qp.qp-" + t, qp);
                f.setInteger("vendor.qti-ext-enc-initial-qp.qp-" + t + "-enable", 1);
                f.setInteger("vendor.qti-ext-enc-qp-range.qp-" + t + "-min", qp);
                f.setInteger("vendor.qti-ext-enc-qp-range.qp-" + t + "-max", qp);
            }
        }
        for (String[] q : new String[][]{{"qpimin", "i-min"}, {"qpimax", "i-max"}, {"qppmin", "p-min"}, {"qppmax", "p-max"}}) {
            if (A.containsKey(q[0])) f.setInteger("vendor.qti-ext-enc-qp-range.qp-" + q[1], i(q[0], 0));
        }

        MediaCodec codec = MediaCodec.createEncoderByType(mime);
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface surface = codec.createInputSurface();
        codec.start();
        log("codec " + codec.getName() + " " + mime + " " + w + "x" + h + " fps=" + fps + " mbps=" + mbps
                + " mode=" + mode + " gop=" + gop + " refresh=" + refresh + " repeat=" + repeat);

        Mirror mirror = new Mirror(surface, w, h);
        final Sink sink;
        if (sockMode) {
            LocalSocket s = new LocalSocket();
            s.connect(new LocalSocketAddress(A.get("sock"), LocalSocketAddress.Namespace.ABSTRACT));
            final DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 1 << 16));
            sink = new Sink() {
                public void put(byte[] d, int n) throws IOException {
                    dos.writeInt(n);
                    dos.write(d, 0, n);
                    dos.flush();
                }

                public void close() throws IOException {
                    dos.close();
                }
            };
        } else {
            final OutputStream fo = new FileOutputStream(A.containsKey("out") ? A.get("out") : "/data/local/tmp/oabl-enc.h264");
            sink = new Sink() {
                public void put(byte[] d, int n) throws IOException {
                    fo.write(d, 0, n);
                }

                public void close() throws IOException {
                    fo.close();
                }
            };
        }

        long start = System.nanoTime();
        long winStart = start, lastFrame = 0, nextSync = 0, nextProp = 0;
        int winFrames = 0, winKeys = 0;
        long winBytes = 0, winMaxGap = 0, winMaxLat = 0, totalFrames = 0, totalBytes = 0;
        int winSecs = sockMode ? 5 : 1; // socket mode logs to logcat every 5 s, file mode prints every second
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        long end = secs > 0 ? start + secs * 1_000_000_000L : Long.MAX_VALUE;
        byte[] buf = new byte[1 << 20];
        try {
            while (System.nanoTime() < end) {
                long now = System.nanoTime();
                boolean wantSync = false;
                if (syncEvery > 0 && now >= nextSync) {
                    wantSync = true;
                    nextSync = now + syncEvery * 1_000_000_000L;
                }
                if (now >= nextProp) {
                    nextProp = now + 100_000_000L;
                    if ("1".equals(sysprop("sys.suding.force.key.frame", ""))) {
                        sysprop("sys.suding.force.key.frame", "0", true);
                        wantSync = true;
                    }
                }
                if (wantSync) {
                    Bundle b = new Bundle();
                    b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                    codec.setParameters(b);
                }
                int idx = codec.dequeueOutputBuffer(bi, 30_000);
                now = System.nanoTime();
                if (idx >= 0) {
                    if (bi.size > 0) {
                        if (bi.size > buf.length) buf = new byte[bi.size];
                        ByteBuffer bb = codec.getOutputBuffer(idx);
                        bb.position(bi.offset);
                        bb.get(buf, 0, bi.size);
                        sink.put(buf, bi.size);
                        if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            log("CONFIG " + bi.size + " bytes");
                        } else {
                            winFrames++;
                            totalFrames++;
                            winBytes += bi.size;
                            totalBytes += bi.size;
                            if ((bi.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                                winKeys++;
                                if (!sockMode) log("KEY size=" + bi.size + " at " + (now - start) / 1_000_000 + "ms");
                            }
                            if (lastFrame != 0) winMaxGap = Math.max(winMaxGap, now - lastFrame);
                            lastFrame = now;
                            long latUs = now / 1000 - bi.presentationTimeUs; // capture to encoded, same clock
                            if (latUs > winMaxLat) winMaxLat = latUs;
                        }
                    }
                    codec.releaseOutputBuffer(idx, false);
                }
                if (now - winStart >= winSecs * 1_000_000_000L) {
                    log(String.format("sum %ds frames=%d keys=%d kbytes=%d maxgap=%dms enclat=%dms", winSecs, winFrames,
                            winKeys, winBytes / 1000, winMaxGap / 1_000_000, winMaxLat / 1000));
                    winStart = now;
                    winFrames = winKeys = 0;
                    winBytes = winMaxGap = winMaxLat = 0;
                }
            }
        } catch (IOException e) {
            log("sink closed: " + e.getMessage());
        }
        log("done frames=" + totalFrames + " bytes=" + totalBytes);
        try {
            mirror.close();
            codec.stop();
            codec.release();
            sink.close();
        } catch (Exception ignored) {
        }
        System.exit(0);
    }

    private static void log(String s) {
        System.out.println(s);
        Log.i(TAG, s);
    }

    private static Method sysGet, sysSet;

    private static String sysprop(String key, String def) {
        return sysprop(key, def, false);
    }

    /** android.os.SystemProperties through reflection; with set=true writes val as the value. */
    private static String sysprop(String key, String val, boolean set) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            if (set) {
                if (sysSet == null) sysSet = sp.getMethod("set", String.class, String.class);
                sysSet.invoke(null, key, val);
                return val;
            }
            if (sysGet == null) sysGet = sp.getMethod("get", String.class, String.class);
            return (String) sysGet.invoke(null, key, val);
        } catch (Exception e) {
            return val;
        }
    }

    /**
     * Mirrors the built-in display into a surface through SurfaceControl, as screenrecord and scrcpy do.
     * Not created secure: asking for a secure display makes createDisplay throw on this box, so layers flagged
     * FLAG_SECURE (some video apps) may show black. Untested with Netflix.
     */
    static final class Mirror {
        private final IBinder display;

        Mirror(Surface surface, int w, int h) throws Exception {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            Method createDisplay = sc.getMethod("createDisplay", String.class, boolean.class);
            display = (IBinder) createDisplay.invoke(null, "oabl-enc", false);

            Class<?> dmg = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object g = dmg.getMethod("getInstance").invoke(null);
            Object info = dmg.getMethod("getDisplayInfo", int.class).invoke(g, 0);
            Class<?> di = info.getClass();
            int layerStack = di.getField("layerStack").getInt(info);
            int lw = di.getField("logicalWidth").getInt(info);
            int lh = di.getField("logicalHeight").getInt(info);
            log("display " + lw + "x" + lh + " layerStack=" + layerStack);

            Class<?> txc = Class.forName("android.view.SurfaceControl$Transaction");
            Constructor<?> ctor = txc.getConstructor();
            Object t = ctor.newInstance();
            txc.getMethod("setDisplaySurface", IBinder.class, Surface.class).invoke(t, display, surface);
            txc.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class)
                    .invoke(t, display, 0, new Rect(0, 0, lw, lh), new Rect(0, 0, w, h));
            txc.getMethod("setDisplayLayerStack", IBinder.class, int.class).invoke(t, display, layerStack);
            txc.getMethod("apply").invoke(t);
        }

        void close() {
            try {
                Class.forName("android.view.SurfaceControl").getMethod("destroyDisplay", IBinder.class)
                        .invoke(null, display);
            } catch (Exception ignored) {
            }
        }
    }
}
