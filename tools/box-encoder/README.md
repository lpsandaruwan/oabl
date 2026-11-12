# box-encoder

OABL's own screen encoder for the AI box: captures the box display through SurfaceControl (as
`screenrecord` and scrcpy do), encodes with MediaCodec and writes Annex-B H.264 with every setting under
our control. First stage of replacing Suding's capture and encode glue; it does not feed Android Auto yet.

```
./build.sh                                   # javac + d8 -> oabl-enc.dex
adb push oabl-enc.dex /data/local/tmp/
adb shell 'CLASSPATH=/data/local/tmp/oabl-enc.dex app_process /system/bin com.softiva.oablenc.Main \
    secs=15 w=1920 h=1080 fps=60 mbps=8 gop=2 repeat=1000 rt=1'
```

Options are listed at the top of `src/com/softiva/oablenc/Main.java`. It prints a line per second
(frames, keyframes, kilobytes, longest gap, capture-to-encoded latency) and writes
`/data/local/tmp/oabl-enc.h264`, which ffprobe/ffmpeg on the Mac decode.

Found on the box (12 November): `OMX.qcom.video.encoder.avc` rejects `profile=high` and the `low-latency`
key (configure error 0x80001001); it accepts `repeat-previous-frame-after`, priority and operating rate.
`i-frame-interval` counts frames at the nominal rate, so a screen that updates 5-20 times a second gets a
keyframe every 6-60 s whatever `gop` says: use `sync=N` for time-based keyframes.

## In the Android Auto path (12 November)

With `persist.oabl.enc.own=1` the boot shim (`tools/box-trace`, `/system/lib/liboabltrace.so`) replaces
Suding's `startRecordScreen`: it spawns this encoder with `sock=NAME w h fps` plus `persist.oabl.enc.args`
(default `mbps=12 rt=1 repeat=1000`) from the dex at `persist.oabl.enc.dex` (default
`/system/framework/oabl-enc.dex`), reads the length-prefixed buffers from the abstract socket and calls the
daemon's own video callback (`setCallBack`, `void(void *data, int size)`), so Suding's SPS/IDR handling and
`auto_reverse_video_push_frame` are untouched. If the encoder does not connect within 12 s or sends nothing,
the stock encoder runs. The encoder polls `sys.suding.force.key.frame`, so the 5-second keyframe nudge drives
it too. It logs a 5-second summary to logcat tag `OABLENC`; the shim logs `own sum` lines in the trace log.

Off: `setprop persist.oabl.enc.own 0` then `setprop ctl.restart sd-reverse`.

## H.265 works through the whole path (12 November)

`persist.oabl.enc.args="mime=hevc mbps=12 rt=1 repeat=1000"` with Open Headunit's Video Codec set to H.265:
the box daemon carries the HEVC buffers unchanged (VPS/SPS/PPS first, then frames), Open Headunit decodes with
`HW.video.hevc` on the tablet's MT8183, keyframes decode, dropped=0, concealed=0. Open Headunit also copes with
a mismatch: with H.265 selected and Suding's H.264 stream it logged "stream parameter sets identify H.264/AVC,
but this session announced H.265/HEVC; using the stream codec". The box library ignores the head unit's codec
announcement and sends whatever the encoder produces. Not yet checked on the Dongfeng head unit's decoder, and
no quality-matched size comparison yet (the screen differed between runs).

## Keyframe size against quality, H.264 vs H.265 (12 November, `rd-sweep.py`)

Hardware encoders on the box (`OMX.qcom.video.encoder.avc` / `.hevc`), 1898x1080 with no scaling, fixed
quantiser, first frame of each run compared with a screenshot of the same still screen. SSIM is the number to
trust: the screen drifted slightly between screenshots (PSNR 31.6 dB between before and after), which makes
the PSNR column jump around.

| QP | H.264 KB | H.264 SSIM | H.265 KB | H.265 SSIM |
|----|---------:|-----------:|---------:|-----------:|
| 16 | 138.8 | 0.9883 | 146.9 | 0.9881 |
| 20 | 111.4 | 0.9856 | 116.4 | 0.9858 |
| 24 | 91.1 | 0.9827 | 94.0 | 0.9810 |
| 28 | 73.5 | 0.9807 | 76.4 | 0.9757 |
| 32 | 57.7 | 0.9720 | 62.1 | 0.9645 |
| 36 | 44.4 | 0.9570 | 50.5 | 0.9400 |
| 40 | 32.7 | 0.9312 | 35.2 | 0.9076 |
| 44 | 22.3 | 0.8880 | 21.5 | 0.8752 |

At equal SSIM the Qualcomm H.265 keyframe is the same size as H.264 at high quality and about 25-30 % larger
around SSIM 0.96. This is intra only; inter frames were not compared. The stock encoder's keyframes
(QP 20 floor) measured 194-271 KB on busy screens, so the QP range is the lever that matters, not the codec.

## Frame delivery with moving video (12 November, `motion-trial.py`)

Source, encoder and head unit were measured separately (SurfaceFlinger latch times of the player layer, encoder
logcat windows, Open Headunit decoder throughput):

- 60 fps test clip (ffmpeg testsrc2, frame counter): the box's own Gallery player puts only about 37 fps on the
  display, with or without the projection (median gap 33 ms). Encoder and tablet then carry about 36-37 fps with
  dropped=0. Raising the encoder to fps=60 and 20 Mbps changed nothing: the shortfall is the box's player and
  display, not projection.
- 30 fps test clip: source 30 fps; stock encoder delivers 32-37 fps, own encoder 34-35 fps, tablet 32-35 fps,
  dropped=0 either way. Real YouTube: 35 fps, dropped=0 with both.
- Frames arrive unevenly (gaps of 60-80 ms between 33 ms ones) and capture-to-encoded delay is 165-250 ms under
  motion, 100 ms idle; neither encoder drops frames in these tests.
- Box memory: about 900 MB of swap in use with the launcher, projection and a player running.

Correction (same day): from 13:36 the encoder was built with a secure capture display, which makes
`createDisplay` throw on this box. The shim then waited 12 s and silently fell back to the stock encoder, so
the "tuned" runs after that point (the 60-70 KB keyframes with the QP range, and the real-YouTube run) were
Suding's encoder, not ours, and say nothing about our settings. The secure display is reverted (so protected-flag
layers such as some Netflix content may capture as black; untested). Our own measurements above (60 fps clip,
30 fps clip with `mbps=12`, 12 Mbps) were taken before that change and are valid. The QP-range settings
(`qpimin=26 qpimax=42 qppmin=24 qppmax=42`) are still untested on our encoder under motion. The shim logs
`own encoder failed, running the stock encoder` in the trace log when it falls back; check for it, and for
`OABLENC` summary lines in logcat, before trusting a run.

## Re-test with our encoder confirmed running (12 November, after the correction)

Args `mbps=16 rt=1 repeat=1000 qpimin=26 qpimax=42 qppmin=24 qppmax=42`, H.264, `OABLENC` summary lines present:

| Content | Frames per 5 s | Data | Keyframe | Capture-to-encoded | Tablet |
|---|---|---|---|---|---|
| 30 fps clip | 160-163 (32 fps) | about 6.2 Mbps | - | 241-262 ms | 31-32 fps, dropped 0 |
| Real YouTube (Big Buck Bunny page) | 153-186 (31-37 fps) | 2-5 Mbps | 115-213 KB | 174-540 ms (540 in the first window) | 35-36 fps, dropped 0 |

Same frame delivery as stock (stock: 32-37 fps, about 9 Mbps and 270 KB keyframes on busy content, measured
earlier), at roughly half the data. The first YouTube window is slow (540 ms) while the page and player start.
Picture looked sharp on the tablet screenshot; no side-by-side quality measurement was made.
