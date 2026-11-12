# Finding where music playback starves

Music stutter on the box can start at three places:

```
file (internal or memory card) → Media3 decode in OABL's :music process → OABL AudioTrack (0.5–0.75 s)
  → AudioFlinger mixer on AudioOut_15 (deep buffer, runs the EQ effects and Visualizer)
  → audio HAL FIFO → CarPlay bridge → car
```

Until 18 October 2026 OABL decoded with LibVLC into a 5772-frame (~120 ms) AudioTrack; the probe's `vlc` size below still reproduces that buffer for comparison. See [Playback engine](music-player-design.md#playback-engine).

Two counters in `dumpsys media.audio_flinger` tell the first two apart:

| Counter | Where in the dump | Rises when |
|---|---|---|
| Track **Underruns** | the track row under `Active Client Session …` | The app didn't write in time: storage, decoding or a too-small buffer. Counted in **frames** of silence (48 = 1 ms), not events; `AudioTrack.getUnderrunCount()` counts the same starvation as events. |
| HAL **Fifo frame underruns** `n:` | the output thread's `Hal stream dump` | The output device ran dry: the mixer was late or the device drained faster than it was fed. `last:` is the size of the latest gap in frames. |

If neither rises but the car still stutters, look past Android: the CarPlay bridge (`persist.ly.audio.mode`, see [LY box findings](ly-box-findings.md)).

`scripts/box-monitor.sh` reads `Normal mixer raw underrun counters`. Those count FastMixer underruns, and AudioOut_15 has no FastMixer, so they stay at zero during music. Use the logger below instead.

## Logger: `scripts/audio-underruns.sh`

```bash
scripts/audio-underruns.sh <device-ip> 1 20 | tee underruns.log
```

Arguments: device, sample interval in seconds (default 1) and minutes (default 20). Each sample prints a line for every output thread with an active track, using the computer's clock so lines can be matched to what you hear:

```
00:03:46 AudioOut_15 hal_underruns=2661(+34) delayed=0(+0) track=68:oabl:frames5772:ready3852:short_frames0(+0) HAL-UNDERRUN(last=30ms)
```

`grep UNDERRUN underruns.log` lists only the samples where something starved. `CLIENT-UNDERRUN` means a track ran short (`short_frames` rose); `HAL-UNDERRUN` means the HAL FIFO count rose. Tracks from other processes (such as the probe) are listed as `pid<N>`. The script only runs `dumpsys` and `pidof`.

Replaying the 16 Oct buffer test's saved dumps through it shows OABL's track with no underruns while the HAL count rose by 34 in about 12 seconds. That is one short window, not a conclusion, but it points away from the decoder and the memory card and toward the output side.

## Probe: buffer size and storage without LibVLC

`scripts/audio-probe/PcmPlaybackProbe.java` plays a generated WAV through its own AudioTrack:

```
track <file.wav> <seconds> [bufferFrames|min|vlc] [ram|stream]
```

- `bufferFrames`: a frame count (default 48000, 1 s), `min` for the platform minimum, or `vlc` for twice the minimum, which is what LibVLC asks for.
- `ram` (default) loads the file before playing. `stream` reads it in small chunks during playback with no read-ahead. That is harsher than LibVLC, which reads ahead on its own thread, so a stall here shows how slow the storage can be, not that OABL must stall.
- In `stream` mode each status line adds the read timings, including `slowReads`: reads longer than half the buffer.

Build:

```bash
DEVICE=<device-ip>; SDK=~/Library/Android/sdk; OUT=$(mktemp -d)
javac --release 11 -cp $SDK/platforms/android-36/android.jar -d $OUT scripts/audio-probe/PcmPlaybackProbe.java
$SDK/build-tools/36.1.0/d8 --min-api 24 --lib $SDK/platforms/android-36/android.jar --output $OUT/probe.jar $OUT/*.class
node scripts/audio-probe/GenerateWav.mjs $OUT/quiet-48k.wav
```

Copy both to the box:

```bash
adb -s "$DEVICE" shell mkdir -p /data/local/tmp/oabl-probe
```

```bash
adb -s "$DEVICE" push $OUT/probe.jar $OUT/quiet-48k.wav /data/local/tmp/oabl-probe/
```

Run with OABL paused, so only the probe's track is playing, and the logger running in another terminal:

```bash
adb -s "$DEVICE" shell CLASSPATH=/data/local/tmp/oabl-probe/probe.jar app_process / PcmPlaybackProbe track /data/local/tmp/oabl-probe/quiet-48k.wav 30 vlc ram
```

The WAV lasts 75 s and a run lasts at most 60 s, so a run never reads the same data twice. Android keeps a file it has just read in memory, so give each memory-card run a freshly pushed copy (`adb shell ls /storage` shows the card's folder).

| Run | Buffer | Source | File on | Answers |
|---|---|---|---|---|
| 1 | `48000` | `ram` | internal | Baseline. 16 Oct: 0 client underruns and no new HAL underruns. |
| 2 | `vlc` | `ram` | internal | Does OABL's buffer size alone cause underruns? |
| 3 | `vlc` | `stream` | card | Does the card stall long enough to starve that buffer? |
| 4 | `48000` | `stream` | card | Does a 1 s buffer hide the card's stalls? |

If run 2 shows HAL underruns that run 1 didn't, the output side needs a larger buffer from the app. If only run 3 fails, the card is at fault. OABL now reads ahead ~50 s with Media3, so a card stall has to last that long to reach the AudioTrack.
